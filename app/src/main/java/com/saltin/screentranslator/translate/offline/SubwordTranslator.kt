package com.saltin.screentranslator.translate.offline

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.saltin.screentranslator.core.SupportedLanguages
import com.saltin.screentranslator.ocr.TextBlock
import com.saltin.screentranslator.translate.TranslatedBlock
import com.saltin.screentranslator.translate.TranslationEngine
import com.saltin.screentranslator.translate.TranslationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * 用 ONNX 模型做端侧离线翻译的引擎（Opus-MT 单语言对模型）。
 *
 * # 为什么会有这个引擎
 *
 * v1.4.5 之前端侧只有 ML Kit 一套模型。它的模型是**围绕英语**训练的：英语↔其他语言是
 * 直接模型，非英语之间（日→中、韩→中）要靠英语中转两次。用户实测反馈就是
 * 「离线模型太普通，翻译效果极差」。这里用的是**单语言对直译**模型，质量明显高一档。
 *
 * | | Opus-MT |
 * | --- | --- |
 * | 体积 | 约 115MB / 语言对 |
 * | 速度 | 亚秒级 |
 * | 覆盖 | 上游训过的方向 + 可经英语接力的方向 |
 *
 * 「增强」与「全能」两档用的是**同一套**模型，只是可选方向多少不同。
 * 旧版的第三档是 NLLB-200（870MB 多语言大模型，手机上要十几秒），
 * v1.6.1 已整档删除，见 [OfflineEngineKind]。
 *
 * # 多段接力（v1.5.1）
 *
 * Opus-MT 是按语言对训练的，上游**没有** `ja→zh` 这个模型（Helsinki-NLP 没训）。
 * 但 `ja→en` 和 `en→zh` 都在，所以「日译中」可以**两段接力**跑出来：
 *
 * ```
 * 日本語の文 ──[opus-mt-ja-en]──▶ English sentence ──[opus-mt-en-zh]──▶ 中文
 * ```
 *
 * 质量当然不如原生直译（误差会累积一次），但远好于「直接不让用」——
 * 而日译中恰恰是看日本漫画最需要的那条。接力与否由
 * [OfflineModelCatalog.opusRoute] 决定，本类只负责把若干段串起来跑。
 *
 * # 绝不能省的约定
 *
 * 1. **源句必须补 `</s>`**。Marian 的 `post_processor` 是 `[Sequence A, </s>]`。
 *    漏掉终止符，编码器看到的是被截断的序列 ——
 *    实测「The quick brown fox…」会翻成「快速快速的棕狐狐狐狐狐…」这种重复循环。
 * 2. **只走 Marian（Opus-MT）这一条规格**：解码起始符是 `<pad>`(65000)、EOS 是 `</s>`(0)、
 *    **不带任何语言码前缀**。历史上第三档 NLLB 用的是完全不同的一套（起始符 `</s>` +
 *    强制目标语言码 + FLORES 前缀），v1.6.1 删掉 NLLB 后这套逻辑也一并移除。
 * 3. **接力时上一段的输出就是下一段的输入**，中间不要做任何「清洗」——
 *    模型输出的英文已经是下一段期待的形式。
 *
 * # 报错原则（v1.6.1 收紧）
 *
 * 绝不静默失败。模型没下载、方向不支持、**单段推理失败**，一律**计入失败**，
 * 上层据此显示红色提示条。旧版单段失败时会 `catch` 成空串、再把原文当译文
 * 填进去（`translated.ifBlank { original }`），结果用户看到「译文和原文一模一样」，
 * 完全不知道是引擎坏了 —— 这是 v1.6.1 要修的主要问题。
 */
class SubwordTranslator(
    private val kind: OfflineEngineKind,
    private val context: Context,
    private val sourceLanguage: String,
    private val targetLanguage: String,
) : TranslationEngine {

    private companion object {
        const val TAG = "SubwordTranslator"

        /** Marian 的 `<pad>` id；词表里读不到时兜底 */
        const val PAD_ID_FALLBACK = 65000L

        /** 一次最多生成多少 token。屏幕翻译都是短句，96 够用 */
        const val MAX_NEW_TOKENS = 96
    }

    override val id: String get() = "offline-${kind.id}-$sourceLanguage-$targetLanguage"

    override val displayName: String get() = kind.label

    override val requiresFrameBitmap: Boolean = false

    @Volatile
    private var loaded: Loaded? = null

    private val lock = Any()

    // ------------------------------------------------------------ 生命周期

    override suspend fun prepare(sourceLanguage: String, targetLanguage: String) {
        withContext(Dispatchers.IO) {
            runCatching { ensureLoaded() }
                .onFailure { Log.w(TAG, "离线模型预热失败", it) }
        }
    }

    override fun close() {
        synchronized(lock) {
            loaded?.close()
            loaded = null
        }
    }

    // ------------------------------------------------------------ 翻译

    override suspend fun translateBlocks(
        blocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedBlock> = withContext(Dispatchers.Default) {
        if (blocks.isEmpty()) return@withContext emptyList()

        val engine = try {
            ensureLoaded()
        } catch (t: Throwable) {
            // 加载不了就明确报错。旧版 ML Kit 路径这里曾静默返回原文，
            // 用户看到的是「译文和原文一模一样」，完全不知道发生了什么。
            throw TranslationException(t.message ?: "离线引擎不可用", t)
        }

        val started = System.currentTimeMillis()
        val out = ArrayList<TranslatedBlock>(blocks.size)
        var failures = 0
        var lastError: Throwable? = null

        for (block in blocks) {
            val original = block.text
            // v1.6.2：这里有**两条**失败路径，旧版只堵了第一条，于是线上一直能看到「返回原文」：
            //
            //   ① 抛异常        —— 旧版已统计（catch 分支 failures++）
            //   ② 不抛异常但产出空 —— 旧版**漏了**：`engine.run()` 正常返回 "" 时 failures 仍是 0，
            //                        整批跑完不报错，再把 `ifBlank { original }` 的原文填回去，
            //                        用户看到「译文 == 原文」只会以为是翻译质量差。
            //
            // 现在把「空产出」与「等于原文」一并计入失败，且**不再注入原文**：
            // 该段直接判失败，由上层的提示条如实告诉用户引擎没工作。
            val translated = try {
                engine.run(original)
            } catch (t: Throwable) {
                Log.w(TAG, "单段离线推理失败", t)
                failures++
                lastError = t
                null
            }

            // 模型跑完却什么都没产出（空串／只有空白）＝ 同样是失败，不是「翻译结果是空」。
            // 解码器在 KV 断链、词表对不上、maxNewTokens 耗尽时都会走到这里。
            //
            // ⚠ 这里**绝不产出 TranslatedBlock**。产出一个「译文=原文」的块，渲染层会照
            //   样画到屏幕上，用户看到的正是「返回原文」；产出一个空串块，也只是被上层
            //   跳过 —— 两者都不如直接判失败、让整批报错来得诚实。
            if (translated != null && translated.isBlank()) {
                Log.w(TAG, "单段离线推理未产出内容：原文=${original.take(40)}")
                failures++
                lastError = lastError ?: TranslationException("模型未产出任何内容")
                continue
            }

            // 译文与原文逐字相同 —— Opus-MT 正常情况下绝不可能发生（它是真在翻译）。
            // 出现即意味着模型没起作用（权重错、没加载、解码跑成恒等）。同样判失败、不上屏。
            if (translated != null && translated == original) {
                Log.w(TAG, "单段离线推理产出了与原文完全相同的结果：${original.take(40)}")
                failures++
                lastError = lastError ?: TranslationException("译文与原文完全相同")
                continue
            }

            out += TranslatedBlock(
                originalText = original,
                translatedText = translated!!,
                boundingBox = Rect(block.boundingBox),
                mergedLines = block.mergedLines,
                align = block.align,
            )
        }

        Log.i(
            TAG,
            "${engine.label} ${SupportedLanguages.label(sourceLanguage)}→" +
                "${SupportedLanguages.label(targetLanguage)}：${blocks.size} 段" +
                (if (failures > 0) "（失败 $failures 段）" else "") +
                "，耗时 ${System.currentTimeMillis() - started}ms",
        )

        // 全部失败 = 引擎根本不工作（模型损坏／内存不足／ABI 不对），必须报错。
        // 部分失败 = 也报，但让用户知道是零星问题而非全盘无效。
        if (failures > 0) {
            val why = lastError?.message?.takeIf { it.isNotBlank() } ?: "推理未产出结果"
            throw TranslationException(
                if (failures == blocks.size) {
                    "离线引擎本次没有翻出任何内容（$why）。请检查模型是否下载完整，" +
                        "或改用其他离线引擎。"
                } else {
                    "离线引擎有 $failures/${blocks.size} 段没翻出来（$why）。"
                },
                lastError,
            )
        }
        out
    }

    // ------------------------------------------------------------ 加载

    private fun ensureLoaded(): Loaded {
        loaded?.let { return it }
        synchronized(lock) {
            loaded?.let { return it }
            val fresh = load()
            loaded = fresh
            return fresh
        }
    }

    private fun load(): Loaded {
        val packs = resolvePacks()
        val missing = packs.firstOrNull { !OfflineModelStore.isReady(context, it) }
        if (missing != null) {
            val present = OfflineModelStore.presentBytes(context, missing)
            val progress = if (present > 0L) {
                "（已下 ${present / 1048576}MB / ${missing.totalMb}MB）"
            } else {
                "（需要 ${missing.totalMb}MB）"
            }
            throw TranslationException(
                "「${missing.label}」的模型还没下载完整$progress。" +
                    "请到设置页 →「模型」→「端侧离线引擎」里点「下载模型」，下完会自动生效。",
            )
        }

        val stages = ArrayList<Stage>(packs.size)
        try {
            for ((index, pack) in packs.withIndex()) {
                stages += loadStage(pack, isLastStage = index == packs.lastIndex)
            }
        } catch (t: Throwable) {
            // 任何一段加载失败都要把已经加载的那段释放掉，否则就是漏内存
            stages.forEach { runCatching { it.close() } }
            throw t
        }

        val label = if (packs.size > 1) {
            "两段接力 ${packs.joinToString(" → ") { it.label.removePrefix("Opus-MT ") }}"
        } else {
            packs.first().label
        }
        Log.i(TAG, "离线引擎就绪：$label（共 ${packs.size} 段）")
        return Loaded(stages, label)
    }

    private fun loadStage(pack: ModelPack, isLastStage: Boolean): Stage {
        val dir = OfflineModelStore.packDir(context, pack.id)
        val tokenizerFile = File(dir, OfflineModelCatalog.TOKENIZER_NAME)
        val encoderFile = File(dir, OfflineModelCatalog.ENCODER_NAME)
        val decoderFile = File(dir, OfflineModelCatalog.DECODER_NAME)
        if (!encoderFile.isFile || !decoderFile.isFile || !tokenizerFile.isFile) {
            throw TranslationException("「${pack.label}」的模型文件缺失，请重新下载一次。")
        }

        val tokenizer = try {
            OfflineTokenizers.load(tokenizerFile)
        } catch (t: Throwable) {
            throw TranslationException(
                "分词器加载失败：${t.message}。建议重新下载模型，或改用其他离线引擎。", t,
            )
        }

        // Marian（Opus-MT）规格：解码起始符取词表里的 `<pad>`，没有强制起始 token，
        // 也不加任何语言码前缀。
        //
        // ⚠ 必须查**目标表**（idOfInTarget）而不是源表：这个 id 是要喂给 decoder 的，
        //   属于解码侧 id 空间。tc-big 两张表的 special 恰好一致（32000），
        //   用错也看不出来；但 sepvoc 模型两表 special 不一致时会直接翻错。
        val decoderStart = tokenizer.idOfInTarget("<pad>")?.toLong() ?: PAD_ID_FALLBACK

        if (!isLastStage) {
            // 接力中间那段的输出一定是英语，这段的源语言就是英语本身。
            // Opus-MT 没有语言码机制，这里只是为了日志说得清。
            Log.i(TAG, "接力第 1 段：${pack.label}（输出英语，交给下一段）")
        }

        val spec = OnnxSeq2Seq.Spec(
            decoderStartId = decoderStart,
            eosId = tokenizer.eosId.toLong(),
            // logits 的列**必须**按解码侧词表裁：tc-big 双表时源/目标都是 32000，
            // 但普通模型也可能存在「logits 比词表宽」的 padding，按解码侧裁才安全。
            vocabSize = tokenizer.decodeVocabSize,
            forcedBosId = null,
            maxNewTokens = MAX_NEW_TOKENS,
        )

        val model = try {
            OnnxSeq2Seq(encoderFile, decoderFile, spec)
        } catch (t: Throwable) {
            throw TranslationException(
                "ONNX 模型加载失败：${t.message}。请确认模型下载完整，或改用其他离线引擎。", t,
            )
        }

        return Stage(model = model, tokenizer = tokenizer)
    }

    /**
     * 这次翻译要用哪几个模型包。
     *
     * Opus-MT 是按语言对训练的，所以「用什么模型」这件事必须问 [OfflineModelCatalog]：
     * 有直接模型就用一个，没有但两头都有英语方向就接力两个，两头都没有才拒绝。
     *
     * **增强与全能两档都允许接力**（v1.6.2）：用户明确要求「每档都能独立翻译」，
     * 不允许出现「选了某档却连一个有匹配语言对的方向都翻不了」。两档差别在可下载方向集合。
     *
     * 拒绝时**必须明确拦住并说清替代方案**，不能假装能翻 ——
     * v1.5 的问题是反过来的：界面暗示支持中日方向，实际却连下载都不允许。
     */
    private fun resolvePacks(): List<ModelPack> = when (kind) {
        // 增强 / 全能：都用 catalog 的路由（都允许接力），差别只在档位本身的方向集合
        OfflineEngineKind.OPUS_MT, OfflineEngineKind.OPUS_FULL -> {
            if (sourceLanguage == "auto") {
                throw TranslationException(
                    "Opus-MT 是按语言对训练的模型，无法「自动检测源语言」。" +
                        "请在设置里把源语言改成具体语言。",
                )
            }
            OfflineModelCatalog.opusRoute(sourceLanguage, targetLanguage, allowPivot = true)?.packs
                ?: throw TranslationException(
                    "Opus-MT 没有「${SupportedLanguages.label(sourceLanguage)} → " +
                        "${SupportedLanguages.label(targetLanguage)}」这个方向，" +
                        "而且也没法用英语接力补出来（上游没训过这一侧的英语方向）。" +
                        "请把离线引擎换回 ML Kit（覆盖更广但经英语中转），或改用云端模式。",
                )
        }

        OfflineEngineKind.ML_KIT -> throw TranslationException(
            "ML Kit 引擎不走这条代码路径（内部逻辑错误，请反馈）",
        )
    }

    // ------------------------------------------------------------ 内部持有

    /** 接力中的一段（一个模型 + 它自己的分词器） */
    private class Stage(
        val model: OnnxSeq2Seq,
        val tokenizer: SubwordTokenizer,
    ) : Closeable {

        /**
         * 文本 → 模型输入 token。
         *
         * Opus-MT（Marian）不带语言码前缀，就是「分词结果 → `</s>`」。
         * `</s>` 绝不能省，见类注释。
         */
        fun encodeSource(text: String): IntArray = tokenizer.encode(text, appendEos = true)

        /** 跑一段推理，返回这一段产出的文本 */
        fun run(text: String): String {
            val generated = model.translate(encodeSource(text))
            return if (generated.isEmpty()) "" else tokenizer.decode(generated)
        }

        override fun close() {
            runCatching { model.close() }
        }
    }

    /** 加载好的模型组合（可能是一段，也可能是接力的两段），[close] 时统一释放 */
    private class Loaded(
        val stages: List<Stage>,
        /** 日志用：【单段】Opus-MT ja→en ／ 【两段接力】ja→en → en→zh */
        val label: String,
    ) : Closeable {

        /**
         * 完整跑一条翻译链。
         *
         * 中间任一段产出为空就整体判失败（返回空串）：接力时把空串或半截英文
         * 喂给下一段，产出的中文会莫名其妙 —— 还不如让上层如实报错、
         * 而不是把空串当成「翻译成功」。
         */
        fun run(text: String): String {
            var current = text
            for (stage in stages) {
                current = stage.run(current)
                if (current.isBlank()) return ""
            }
            return current
        }

        override fun close() {
            stages.forEach { it.close() }
        }
    }
}
