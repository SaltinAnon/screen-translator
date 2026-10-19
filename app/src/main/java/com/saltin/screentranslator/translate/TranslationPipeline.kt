package com.saltin.screentranslator.translate

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.saltin.screentranslator.core.AppSettings
import com.saltin.screentranslator.core.Defaults
import com.saltin.screentranslator.core.SupportedLanguages
import com.saltin.screentranslator.core.TranslateMode
import com.saltin.screentranslator.ocr.BlockAlign
import com.saltin.screentranslator.ocr.LineGrouper
import com.saltin.screentranslator.ocr.OcrProcessor
import com.saltin.screentranslator.ocr.TextBlock
import com.saltin.screentranslator.ocr.TextCleaner
import com.saltin.screentranslator.translate.offline.OfflineEngineKind
import com.saltin.screentranslator.util.BitmapSlot
import com.saltin.screentranslator.util.ImageUtils

/** 单帧耗时与产出统计，直接展示到界面/通知栏，方便调参 */
data class PipelineStats(
    val engineId: String = "",
    val ocrMs: Long = 0,
    val translateMs: Long = 0,
    val totalMs: Long = 0,
    val ocrBlocks: Int = 0,
    val keptBlocks: Int = 0,
    val renderedBlocks: Int = 0,
    /** 段落合并掉的行数：OCR 行数 − 合并后的段数 */
    val mergedLines: Int = 0,
    /** 译文框合并掉的框数：合并前 − 合并后（一个对话泡只留一个框） */
    val bubbleMerged: Int = 0,
    val cacheHits: Int = 0,
    val requestChars: Int = 0,
    val droppedBlocks: Int = 0,
    val truncated: Boolean = false,
    /** 译文与原文完全相同的行数：端侧模式的「没翻译动」信号 */
    val unchangedBlocks: Int = 0,
    /** 本次实际使用的 OCR 识别器，出问题时能一眼看出是不是选错了 */
    val scriptLabel: String = "",
    /** 致命问题（红色提示条） */
    val note: String? = null,
    /** 不致命但必须让用户知道的提醒（黄色提示条） */
    val warning: String? = null,
)

data class PipelineResult(
    val blocks: List<TranslatedBlock>,
    val stats: PipelineStats,
)

/**
 * 阶段二 → 阶段三的编排层，也是「混合架构」的落点。
 *
 * 三种模式的差异全部收敛在这里：
 *
 * - [TranslateMode.ON_DEVICE]：放大取帧 → OCR → **合并段落** → 清洗 → 端侧逐句翻译（坐标来自 OCR）
 * - [TranslateMode.HYBRID]：放大取帧 → OCR → **合并段落** → 清洗 → **只把干净短文本 + 一张缩略图**
 *   交给云端 LLM，模型只回译文，坐标继续复用 OCR —— 这就是把「云端语境能力」和「端侧低延迟」
 *   缝在一起的关键。
 * - [TranslateMode.CLOUD_VISION]：整帧交给多模态模型，同时**先跑一次端侧 OCR 拿坐标锚点**
 *   （模型只肯回译文、不肯回坐标时用它兜底），失败还会换一种问法重试。
 *
 * 三条路径最后都会汇到同一个收口：`BubbleMerger` 把同一个对话泡里的多块译文合成一块，
 * 于是「不管怎么翻，一个泡子只有一个框」。
 *
 * 另外有两道省流量的闸门：译文 LRU 缓存 + 「已经是目标语言就跳过」。
 */
class TranslationPipeline(private val ocr: OcrProcessor) {

    companion object {
        private const val TAG = "TranslationPipeline"

        /**
         * 带图翻译被拒之后的**静默期**。
         *
         * 内容审核类的拒绝（国内网关普遍用 HTTP 402 表示内容安全拦截）在**同一页漫画里是
         * 持续性的**：被判定为成人内容的那一页，它的每一帧都会被同样拒掉。不设静默期的话，
         * 每一帧都要先白试一次带图、被拒、再降级，白白多花一次请求的时间与额度，
         * 画面也跟着慢半拍。静默期内直接走纯文本，过了再试。
         */
        private const val VISION_COOLDOWN_MS = 60_000L
    }

    private val cache = TranslationCache()
    private val ocrSlot = BitmapSlot()

    /** 带图路径的静默截止时刻（[SystemClock.elapsedRealtime] 基准），见 [VISION_COOLDOWN_MS] */
    private var visionCooldownUntil = 0L

    fun reset() {
        cache.clear()
        visionCooldownUntil = 0L
    }

    fun cacheSize(): Int = cache.size()

    suspend fun process(
        engine: TranslationEngine,
        frame: Bitmap,
        settings: AppSettings,
    ): PipelineResult {
        // 纯多模态模式必须能看图；模型不支持时自动退化为「OCR + 纯文本」，
        // 宁可少一点排版还原度，也不要直接报错让用户不知所措。
        val wantsMultimodal = settings.mode == TranslateMode.CLOUD_VISION
        val visionAllowed = TranslatorFactory.canUseVision(settings)
        return if (wantsMultimodal && visionAllowed) {
            val result = processMultimodal(engine, frame, settings)
            // 整屏多模态被**整体拒掉**（内容审核、图太大、模型不认图…）时一个字都上不了屏。
            // 与其让用户对着一片空白发愣，不如退回「OCR + 纯文本」，至少把字翻出来。
            if (result.blocks.isEmpty() && result.stats.note != null) {
                Log.w(TAG, "整屏多模态未产出译文，降级为 OCR + 纯文本：${result.stats.note}")
                processOcrFirst(
                    engine = engine,
                    frame = frame,
                    settings = settings,
                    degradedFromMultimodal = true,
                    multimodalFailure = result.stats.note,
                )
            } else {
                result
            }
        } else {
            processOcrFirst(engine, frame, settings, wantsMultimodal && !visionAllowed)
        }
    }

    // ------------------------------------------------------------ 纯多模态

    private suspend fun processMultimodal(
        engine: TranslationEngine,
        frame: Bitmap,
        settings: AppSettings,
    ): PipelineResult {
        val started = SystemClock.elapsedRealtime()

        // 先做一次端侧 OCR。它在纯多模态模式下**不是**为了识别，而是为了给模型兜底：
        // 相当多的看图模型只肯回译文、不肯回坐标，此时只能把译文套回 OCR 找到的框上。
        // 顺带也让「屏幕上确实没有文字」这种情况能给出准确结论。
        val anchors = detectAnchors(frame, settings)
        val ocrFinished = SystemClock.elapsedRealtime()
        var note: String? = null

        val blocks = try {
            engine.translateFrame(
                frame = frame,
                hintBlocks = emptyList(),
                sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage,
                fallbackHints = anchors,
            )
        } catch (t: Throwable) {
            note = describe(t)
            emptyList()
        }
        val finished = SystemClock.elapsedRealtime()

        val sorted = blocks
            .filter { it.translatedText.isNotBlank() }
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

        // 一个对话泡只留一个框：多模态模型有时会给同一个泡子里的每一行/每一列各回一条，
        // 画出来就是好几块互相压着。这一步在最终屏幕坐标上收口。
        val bubble = BubbleMerger.merge(
            blocks = sorted,
            frameWidth = frame.width,
            frameHeight = frame.height,
            config = BubbleMerger.Config(
                mode = settings.bubbleMerge,
                gapRatio = settings.bubbleGapRatio,
            ),
        )
        val rendered = bubble.blocks

        // 只缓存**真的翻过**的条目。无条件写入会把「模型原样抄回原文」的结果钉死，
        // 之后每一帧都从缓存里拿到同一份错误内容，用户怎么重试都没用 ——
        // 这正是「用多模态模式翻了一次错的，之后一直错」的来源。
        rendered.forEach {
            if (it.translatedText.isNotBlank() && it.translatedText != it.originalText) {
                cache.put(
                    it.originalText,
                    settings.sourceLanguage,
                    settings.targetLanguage,
                    it.translatedText,
                )
            }
        }

        val verdict = TranslationVerifier.inspect(rendered, settings.targetLanguage)

        return PipelineResult(
            blocks = rendered,
            stats = PipelineStats(
                engineId = engine.id,
                ocrMs = ocrFinished - started,
                translateMs = finished - ocrFinished,
                totalMs = finished - started,
                ocrBlocks = anchors.size,
                keptBlocks = sorted.size,
                renderedBlocks = rendered.size,
                bubbleMerged = bubble.mergedAway,
                cacheHits = 0,
                requestChars = 0,
                droppedBlocks = 0,
                unchangedBlocks = rendered.count { it.translatedText == it.originalText },
                scriptLabel = if (anchors.isEmpty()) {
                    "整屏多模态（模型自己定位）"
                } else {
                    "整屏多模态 · 端侧锚点 ${anchors.size} 行（${ocr.lastScriptLabel}）"
                },
                note = note,
                warning = verdict.message,
            ),
        )
    }

    /**
     * 给纯多模态模式准备**屏幕像素坐标**的文字锚点。
     *
     * 失败一律吞掉：这只是兜底材料，没有它多模态路径照样该工作，
     * 绝不能因为 OCR 读不了这个文字系统就让整个多模态模式挂掉。
     */
    private suspend fun detectAnchors(frame: Bitmap, settings: AppSettings): List<TextBlock> = try {
        val bitmap = ImageUtils.ocrFrameInto(
            src = frame,
            slot = ocrSlot,
            maxWidth = settings.maxCaptureWidth,
            upscale = settings.ocrUpscale,
            enhanceContrast = settings.ocrEnhanceContrast,
        )
        val scale = frame.width.toFloat() / bitmap.width.toFloat()
        val outcome = ocr.recognize(
            bitmap,
            settings.scriptMode,
            settings.sourceLanguage,
            settings.textOrientation,
        )
        // 这里刻意不做段落合并：模型是自己决定翻译粒度的，锚点保持「一行一个」，
        // 万一条数对得上，位置最准。
        TextCleaner.clean(
            outcome.blocks,
            TextCleaner.Config(
                minLength = settings.minTextLength,
                maxChars = settings.maxCharsPerRequest,
                maxBlocks = settings.maxBlocksPerRequest,
                targetLanguage = settings.targetLanguage,
                sourceLanguage = settings.sourceLanguage,
            ),
        ).blocks.map { block ->
            val rect = if (outcome.rotated) {
                ImageUtils.widenTallRect(
                    ImageUtils.unrotateCcwRect(block.boundingBox, bitmap.width),
                    bitmap.width,
                )
            } else {
                block.boundingBox
            }
            block.copy(boundingBox = ImageUtils.scaleRect(rect, scale))
        }
    } catch (t: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------ 端侧 OCR 优先（含混合模式）

    private suspend fun processOcrFirst(
        engine: TranslationEngine,
        frame: Bitmap,
        settings: AppSettings,
        degradedFromMultimodal: Boolean = false,
        /** [degradedFromMultimodal] 为真时，整屏多模态那次失败的具体原因 */
        multimodalFailure: String? = null,
    ): PipelineResult {
        val started = SystemClock.elapsedRealtime()
        val provider = TranslatorFactory.resolveProvider(settings)
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        // 1) 给 OCR 准备的帧：先按宽度上限缩，再按 ocrUpscale 放大。
        //    屏幕上的小字直接识别容易漏，放大一次带来的准确率提升比换模型还明显。
        //    彩色文字 / 花哨背景板再叠一层「灰度 + 对比度拉伸」，见 ImageUtils.ocrEnhancePaint。
        val ocrBitmap = ImageUtils.ocrFrameInto(
            src = frame,
            slot = ocrSlot,
            maxWidth = settings.maxCaptureWidth,
            upscale = settings.ocrUpscale,
            enhanceContrast = settings.ocrEnhanceContrast,
        )
        val coordinateScale = frame.width.toFloat() / ocrBitmap.width.toFloat()

        // 2) 识别（Dispatchers.Default，不占主线程）
        val outcome = try {
            ocr.recognize(
                bitmap = ocrBitmap,
                scriptMode = settings.scriptMode,
                sourceLanguage = settings.sourceLanguage,
                orientation = settings.textOrientation,
            )
        } catch (t: Throwable) {
            val finished = SystemClock.elapsedRealtime()
            return PipelineResult(
                blocks = emptyList(),
                stats = PipelineStats(
                    engineId = engine.id,
                    ocrMs = finished - started,
                    totalMs = finished - started,
                    scriptLabel = ocr.lastScriptLabel,
                    note = describe(t),
                ),
            )
        }
        val rawBlocks = outcome.blocks
        val ocrFinished = SystemClock.elapsedRealtime()

        // 2.5) 段落合并：ML Kit 给的是一行一行的结果，但「行」不是翻译单元。
        //      漫画对话泡、窄栏正文里一句话横跨好几行，每行只有几个词，
        //      逐行翻译必然语义断裂。先按行距/字号/对齐把同一段的行合成一句再翻。
        //
        //      ★ 竖排（日式漫画）在识别前已经把位图转过 90°，所以到这里
        //        「一列」已经变成「一行」—— 合并逻辑一行都不用改。参照宽度要用
        //        旋转后坐标系的宽度（宽高互换）。
        val grouped = LineGrouper.group(
            lines = rawBlocks,
            frameWidth = outcome.spaceWidth,
            config = LineGrouper.Config(
                enabled = settings.mergeParagraphs,
                gapRatio = settings.mergeGapRatio,
            ),
        )

        // 3) 清洗：丢掉纯符号/URL/重复/已经是目标语言的块 —— 混合架构的第一道省流量闸门
        val cleaned = TextCleaner.clean(
            grouped.blocks,
            TextCleaner.Config(
                minLength = settings.minTextLength,
                maxChars = settings.maxCharsPerRequest,
                maxBlocks = settings.maxBlocksPerRequest,
                targetLanguage = settings.targetLanguage,
                sourceLanguage = settings.sourceLanguage,
            ),
        )

        // 4) 同一个文本只翻一次
        val unique = LinkedHashMap<String, TextBlock>()
        cleaned.blocks.forEach { unique.putIfAbsent(it.text, it) }

        // 5) 缓存查表：第二道省流量闸门
        val cached = HashMap<String, String>()
        val misses = ArrayList<TextBlock>()
        for ((text, block) in unique) {
            val hit = cache.get(text, settings.sourceLanguage, settings.targetLanguage)
            if (hit != null) cached[text] = hit else misses += block
        }

        val preparedAt = SystemClock.elapsedRealtime()

        // 6) 翻译：混合模式把整帧（缩略图）当语境 + 干净短文本一起发出去，坐标仍用 OCR 的
        // 只有「混合模式 + 开了附带截图 + 该服务商确实有看图模型」才走图片路径，
        // 否则一律走纯文本 —— DeepSeek 这类纯文本模型也能正常用。
        // 刚被服务商拒过的话（见 [visionCooldownUntil]）也直接走纯文本，不再白试一次。
        val visionCooling = SystemClock.elapsedRealtime() < visionCooldownUntil
        val useVision = settings.mode == TranslateMode.HYBRID &&
            settings.attachScreenshot &&
            !visionCooling &&
            TranslatorFactory.canUseVision(settings)

        if (settings.mode == TranslateMode.HYBRID && settings.attachScreenshot &&
            !TranslatorFactory.canUseVision(settings)
        ) {
            warnings += "${provider.label} 不支持看图，已自动改用纯文本模式"
        }
        if (degradedFromMultimodal) {
            warnings += if (multimodalFailure != null) {
                "整屏多模态这次没能出译文（$multimodalFailure），已自动改用「OCR + 纯文本」模式"
            } else {
                "${provider.label} 没有可用的看图模型，已自动改用「OCR + 纯文本」模式"
            }
        }
        if (settings.mode == TranslateMode.ON_DEVICE) {
            // 下面这些提醒是 ML Kit 那套模型的固有限制，换成 Opus-MT 之后都不成立：
            //   - Opus-MT 是单语言对**直译**，不存在「经英语中转」的质量下降
            // 继续照发会让用户以为软件坏了，所以必须按引擎区分。
            if (settings.offlineEngine == OfflineEngineKind.ML_KIT) {
                if (settings.sourceLanguage == "auto") {
                    warnings += "端侧 ML Kit 不支持自动检测源语言，已按" +
                        "${Defaults.ON_DEVICE_FALLBACK_SOURCE}处理，建议在设置页手动指定源语言"
                }
                SupportedLanguages.onDeviceQualityWarning(
                    settings.sourceLanguage,
                    settings.targetLanguage,
                )?.let { warnings += it }
            } else if (settings.sourceLanguage == "auto") {
                // Opus-MT 两档（增强 / 全能）都是按语言对训练的，都不支持自动检测
                warnings += "Opus-MT 是按语言对训练的模型，无法自动检测源语言，" +
                    "请在设置页手动指定源语言"
            }
        }

        val translated = ArrayList<TranslatedBlock>()
        if (misses.isNotEmpty()) {
            // ★ 带图路径失败**必须**降级重试，绝不能直接放弃。
            //
            //   图片在这里只是「辅助语境」：译文完全来自那批干净短文本，图是锦上添花。
            //   而内容审核（网关普遍用 HTTP 402 表示安全拦截）、图太大、模型不认图……
            //   都会让**整个**请求被拒。旧版遇到这种情况直接记进 errors、translated 全空，
            //   于是一页漫画一行字都翻不出来 —— 用户只看到「翻译失败」，
            //   可明明文字本身一点问题都没有。
            var visionFailure: String? = null
            var done = false
            if (useVision) {
                try {
                    translated += engine.translateFrame(
                        frame,
                        misses,
                        settings.sourceLanguage,
                        settings.targetLanguage,
                    )
                    done = true
                } catch (t: Throwable) {
                    visionFailure = describe(t)
                    Log.w(TAG, "混合模式带图翻译失败，改用纯文本重试一次", t)
                }
            }
            if (!done) {
                try {
                    translated += engine.translateBlocks(
                        misses,
                        settings.sourceLanguage,
                        settings.targetLanguage,
                    )
                } catch (t: Throwable) {
                    val reason = visionFailure
                    errors += if (reason != null) {
                        "${describe(t)}（附带的截图同样被拒绝：$reason）"
                    } else {
                        describe(t)
                    }
                }
                val reason = visionFailure
                // 只有降级**确实翻出了东西**，才敢承诺「译文不受影响」并打开静默期；
                // 否则上面刚记进 errors 的失败才是用户该看到的结论，再补一句「不受影响」就是自相矛盾。
                if (reason != null && translated.isNotEmpty()) {
                    visionCooldownUntil = SystemClock.elapsedRealtime() + VISION_COOLDOWN_MS
                    warnings += visionFallbackNotice(reason)
                }
            }
        }
        val translatedAt = SystemClock.elapsedRealtime()

        // 7) 回填：以原文为键合并「本次译文」与「缓存译文」
        val byOriginal = HashMap<String, String>(unique.size * 2)
        translated.forEach { byOriginal[it.originalText] = it.translatedText }
        translated.forEach {
            if (it.translatedText.isNotBlank() && it.translatedText != it.originalText) {
                cache.put(it.originalText, settings.sourceLanguage, settings.targetLanguage, it.translatedText)
            }
        }
        cached.forEach { (text, value) -> byOriginal.putIfAbsent(text, value) }

        // 8) 坐标从 OCR 坐标系映射回真实屏幕像素。
        //    竖排这里**只做「反向转 90°」这一步**，撑宽留到合并之后（第 9.6 步）——
        //    撑宽会让相邻的列互相重叠，把合并判据赖以工作的「列间距」抹成 0，
        //    那样不管把合并强度调到哪一档，结果都只能是同一个（实测确认过）。
        val rendered = ArrayList<TranslatedBlock>(cleaned.blocks.size)
        for (block in cleaned.blocks) {
            val value = byOriginal[block.text]
            if (value.isNullOrBlank()) continue

            val rect = if (outcome.rotated) {
                ImageUtils.unrotateCcwRect(block.boundingBox, ocrBitmap.width)
            } else {
                block.boundingBox
            }

            rendered += TranslatedBlock(
                originalText = block.text,
                translatedText = value,
                boundingBox = ImageUtils.scaleRect(rect, coordinateScale),
                fromCache = cached.containsKey(block.text),
                mergedLines = block.mergedLines,
                // 竖排的「列」在对齐上是共用顶边的，转完 90° 就会被判成左对齐。
                // 但那只是排版的巧合 —— 对话泡里的译文该居中，否则会贴着泡子一边。
                align = if (outcome.rotated) BlockAlign.CENTER else block.align,
            )
        }

        // 9) 译文体检：模型原样抄回原文、或翻成了另一种文字系统（该出中文却出了日文/韩文/越南语）。
        //    这一类结果「看起来是成功的」，其实屏幕上是一片没用的字，所以必须告诉用户。
        //    ★ 必须在「译文框合并」**之前**做 —— 合并会把多行原文拼成一段，
        //      「译文是否与原文相同」这条判据当场失效。
        //    端侧离线模型没加载成功时也会落在这里，所以原先那条端侧专用提醒已经并入体检文案。
        val verdict = TranslationVerifier.inspect(rendered, settings.targetLanguage)
        val unchanged = rendered.count { it.translatedText == it.originalText }
        verdict.message?.let { warnings += it }

        // 9.5) 译文框合并：一个对话泡只留一个框。
        //      放在坐标映射之后、错误分级之前 —— 它作用在最终屏幕坐标上，
        //      对三种模式一视同仁，也决定了后面「屏幕上到底有没有东西」的判断。
        val bubble = BubbleMerger.merge(
            blocks = rendered,
            frameWidth = frame.width,
            frameHeight = frame.height,
            config = BubbleMerger.Config(
                mode = settings.bubbleMerge,
                gapRatio = settings.bubbleGapRatio,
            ),
        )

        // 9.6) 撑宽「合并之后仍然是单独一列」的窄高框。
        //      一列竖排原文的框又窄又高，横排译文塞进去只剩一条细线，
        //      所以按框高横向撑开并强制走折行路径。
        //      已经合成泡子的不用撑：泡子的包围盒本来就够宽（而且撑宽会溢出泡子）。
        val finalBlocks = if (!outcome.rotated) {
            bubble.blocks
        } else {
            bubble.blocks.map { block ->
                val widened = ImageUtils.widenTallRect(block.boundingBox, frame.width)
                if (widened == block.boundingBox) {
                    block
                } else {
                    block.copy(boundingBox = widened, mergedLines = maxOf(block.mergedLines, 2))
                }
            }
        }

        // 10) 错误分级：屏幕上一旦有译文，出问题就不该再顶一条**红色常驻**错误 ——
        //     用户明明看见翻译出来了，却被一条「翻译失败」红条永久挡住画面。
        //     这种情况降级成一次性提醒（说明这次有哪部分没翻出来），只有「一个字都没翻出来」
        //     才是真正的致命错误。
        val anyRendered = finalBlocks.isNotEmpty()
        val allWarnings = ArrayList(warnings)
        if (anyRendered && errors.isNotEmpty()) {
            allWarnings += "这次有部分内容没翻出来（其余已显示）：" + errors.joinToString("；")
        }

        return PipelineResult(
            blocks = finalBlocks,
            stats = PipelineStats(
                engineId = engine.id,
                ocrMs = ocrFinished - started,
                translateMs = translatedAt - preparedAt,
                totalMs = translatedAt - started,
                ocrBlocks = rawBlocks.size,
                keptBlocks = cleaned.blocks.size,
                renderedBlocks = finalBlocks.size,
                mergedLines = grouped.mergedLines,
                bubbleMerged = bubble.mergedAway,
                cacheHits = cached.size,
                requestChars = misses.sumOf { it.text.length },
                droppedBlocks = cleaned.dropped,
                truncated = cleaned.truncated,
                unchangedBlocks = unchanged,
                scriptLabel = ocr.lastScriptLabel,
                note = if (anyRendered) null else errors.takeIf { it.isNotEmpty() }?.joinToString("；"),
                warning = allWarnings.takeIf { it.isNotEmpty() }?.joinToString("；"),
            ),
        )
    }

    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName

    fun release() {
        ocrSlot.release()
        cache.clear()
    }
}
