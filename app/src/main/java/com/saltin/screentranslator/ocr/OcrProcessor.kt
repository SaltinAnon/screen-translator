package com.saltin.screentranslator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.saltin.screentranslator.util.BitmapSlot
import com.saltin.screentranslator.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.Closeable

/** 端侧 OCR 无法完成时抛出，message 是直接能给用户看的人话 */
class OcrException(message: String) : Exception(message)

/**
 * 一次识别的完整结果。
 *
 * 之所以要把「坐标系」一起带出来：竖排文字是把位图**转 90° 之后**才识别的，
 * 得到的 [blocks] 坐标在旋转后的坐标系里，上游必须知道这一点才能正确回映射
 * （见 [ImageUtils.unrotateCcwRect]），并且合并段落时要用旋转后的宽度做参照。
 */
data class OcrOutcome(
    val blocks: List<TextBlock>,
    /** 是否来自「逆时针旋转 90° 后」的位图；true 表示这批文本是竖排（日式右→左） */
    val rotated: Boolean = false,
    /** 旋转**之前**的位图尺寸；回映射与合并宽度都要用 */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
) {
    /** [blocks] 坐标所在坐标系的宽度（旋转后宽高互换） */
    val spaceWidth: Int get() = if (rotated) sourceHeight else sourceWidth

    /** [blocks] 坐标所在坐标系的高度 */
    val spaceHeight: Int get() = if (rotated) sourceWidth else sourceHeight
}

/**
 * 阶段二：Google ML Kit Text Recognition v2 封装。
 *
 * - 识别在 [Dispatchers.Default] 上执行（ML Kit 自身在内部线程池跑，await 不阻塞主线程）。
 * - 输出粒度选 **行（Text.Line）** 而不是块（Text.TextBlock）：行自带 boundingBox，
 *   是坐标最精确的一级。但行不是翻译单元 —— 一句话可能横跨好几行，
 *   所以紧接着由 [LineGrouper] 把同一段的行合并成「一段」，再送去翻译。
 * - 识别器按文字系统懒加载，用不到的模型不会被初始化。
 * - **源语言为「自动检测」时同时跑拉丁与中文两套识别器并择优** ——
 *   中文识别器能读中英混排但读英文不如拉丁识别器，反之亦然，只跑一套必然在另一类界面上吃亏。
 * - **竖排（日式漫画）**：横排识别出来的框如果普遍又窄又高，说明画面是竖排文字，
 *   此时把位图逆时针转 90° 再识别一遍（列变行、右→左的列序变上→下的行序），
 *   让 ML Kit 回到它擅长的排布上。详见 [TextOrientation]。
 */
class OcrProcessor : Closeable {

    private var latinRecognizer: TextRecognizer? = null
    private var chineseRecognizer: TextRecognizer? = null
    private var japaneseRecognizer: TextRecognizer? = null
    private var koreanRecognizer: TextRecognizer? = null

    /** 旋转 90° 的临时位图槽位，只在竖排路径上用到 */
    private val rotateSlot = BitmapSlot()

    /** 最近一次实际使用的识别器，用于在界面上解释「为什么认成这样」 */
    @Volatile
    var lastScriptLabel: String = ""
        private set

    @Synchronized
    private fun recognizerFor(script: Script): TextRecognizer? = when (script) {
        Script.LATIN -> latinRecognizer
            ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .also { latinRecognizer = it }

        Script.CHINESE -> chineseRecognizer
            ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                .also { chineseRecognizer = it }

        Script.JAPANESE -> japaneseRecognizer
            ?: TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                .also { japaneseRecognizer = it }

        Script.KOREAN -> koreanRecognizer
            ?: TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                .also { koreanRecognizer = it }

        Script.NONE -> null
    }

    /**
     * 对一帧位图做文字识别。
     *
     * @param bitmap 通常已经是缩放/放大后的帧，boundingBox 坐标系与之一致
     * @param orientation 排版方向；[TextOrientation.AUTO] 会在识别结果看起来是竖排时自动转 90° 重试
     * @return [OcrOutcome]，**务必检查 [OcrOutcome.rotated]** 再决定坐标怎么用
     * @throws OcrException 这个语言的文字系统端侧没有识别器
     */
    suspend fun recognize(
        bitmap: Bitmap,
        scriptMode: ScriptMode,
        sourceLanguage: String,
        orientation: TextOrientation = TextOrientation.AUTO,
    ): OcrOutcome = withContext(Dispatchers.Default) {
        val scripts = resolveScripts(scriptMode, sourceLanguage)

        if (scripts.isEmpty()) {
            val name = ScriptSupport.unsupportedScriptName(scriptMode, sourceLanguage) ?: "这种"
            lastScriptLabel = name
            throw OcrException(
                "端侧没有「$name」的文字识别器（ML Kit 只提供拉丁、中文、日文、韩文四套），" +
                    "离线模式读不了这种语言的界面。请改用「云端 · 整屏多模态」模式，" +
                    "让云端模型直接看图。",
            )
        }

        // 1) 横排识别。源语言为「自动检测」时两套识别器各跑一遍取更好的 —— 只多花一次 OCR
        //    时间（约 80ms），换的是英文界面不再被中文识别器拖累。
        val flat = bestOf(bitmap, scripts)
        lastScriptLabel = flat.label

        // 2) 竖排只对中日两种文字系统有意义
        val verticalWorthTrying = scripts.any { TextOrientation.supportsVertical(it) }
        val shouldRotate = verticalWorthTrying && when (orientation) {
            TextOrientation.HORIZONTAL -> false
            TextOrientation.VERTICAL_RTL -> true
            TextOrientation.AUTO -> looksVertical(flat.blocks)
        }
        if (!shouldRotate) {
            return@withContext OcrOutcome(flat.blocks, false, bitmap.width, bitmap.height)
        }

        // 3) 转 90° 再识别一次
        val turned = bestOf(ImageUtils.rotateCcwInto(bitmap, rotateSlot), scripts)

        // 自动模式下加一道保险：转过去反而认得更差，说明本来就不是竖排，退回横排结果
        if (orientation == TextOrientation.AUTO && turned.score < flat.score * 0.7) {
            return@withContext OcrOutcome(flat.blocks, false, bitmap.width, bitmap.height)
        }

        lastScriptLabel = "${turned.label} · 竖排"
        OcrOutcome(turned.blocks, true, bitmap.width, bitmap.height)
    }

    /** 这一步要用哪几套识别器（自动检测时需要两套都跑） */
    private fun resolveScripts(mode: ScriptMode, sourceLanguage: String): List<Script> {
        if (mode == ScriptMode.AUTO && sourceLanguage == "auto") {
            return listOf(Script.CHINESE, Script.LATIN)
        }
        val script = ScriptSupport.resolve(mode, sourceLanguage)
        return if (script.available) listOf(script) else emptyList()
    }

    /**
     * 横排结果像不像「竖排文字被横着切」。
     *
     * 竖排的日文漫画里 ML Kit 会把每一「列」当成一行交出来，于是得到一个又窄又高的框
     * （高 ≥ 1.5 倍宽）。横排文本永远不会这样 —— 这是判据能成立的根本原因。
     *
     * 阈值取三分之二：宁可漏判（结果还是逐列翻译，用户可手动切「竖排」），
     * 也不要误判（把横排内容硬转 90°，会直接认不出任何字）。
     */
    private fun looksVertical(blocks: List<TextBlock>): Boolean {
        if (blocks.isEmpty()) return false
        val tall = blocks.count { block ->
            block.height >= block.width * 1.5f && block.text.count { !it.isWhitespace() } >= 2
        }
        return tall * 3 >= blocks.size * 2
    }

    /** 一套识别器的产出 */
    private data class Pass(val blocks: List<TextBlock>, val score: Double, val label: String)

    /** 跑一遍（可能多套识别器），返回最好的那一份 */
    private suspend fun bestOf(bitmap: Bitmap, scripts: List<Script>): Pass {
        var best = Pass(emptyList(), 0.0, scripts.first().label)
        for (script in scripts) {
            val (blocks, score) = runOnce(bitmap, script)
            if (score > best.score) best = Pass(blocks, score, script.label)
        }
        return best
    }

    private suspend fun runOnce(bitmap: Bitmap, script: Script): Pair<List<TextBlock>, Double> {
        val recognizer = recognizerFor(script) ?: return emptyList<TextBlock>() to 0.0
        val input = InputImage.fromBitmap(bitmap, 0)
        val text: Text = recognizer.process(input).await()
        val blocks = toBlocks(text)
        return blocks to scoreOf(blocks)
    }

    /**
     * 识别质量粗评：有效字符数 × 置信度权重。
     *
     * 中文/日文/韩文识别器不返回 confidence（只有拉丁识别器返回），
     * 所以缺失时给一个中性值，避免两套之间出现系统性偏差。
     */
    private fun scoreOf(blocks: List<TextBlock>): Double {
        if (blocks.isEmpty()) return 0.0
        val chars = blocks.sumOf { block -> block.text.count { !it.isWhitespace() } }
        val confidence = blocks.mapNotNull { it.confidence }
            .takeIf { it.isNotEmpty() }?.average() ?: 0.6
        return chars * (0.4 + confidence)
    }

    private fun toBlocks(text: Text): List<TextBlock> {
        val blocks = ArrayList<TextBlock>(48)
        // ML Kit 的 TextBlock 序号要带下去：它本身就把多行按「段落」聚合过，
        // 后续 LineGrouper 判断两行是不是同一段时这是最强的一个信号。
        text.textBlocks.forEachIndexed { blockIndex, block ->
            for (line in block.lines) {
                val box: Rect = line.boundingBox ?: continue
                if (box.width() <= 1 || box.height() <= 1) continue
                val content = line.text.trim()
                if (content.isEmpty()) continue

                val confidences = line.elements.mapNotNull { it.confidence }
                val confidence = if (confidences.isEmpty()) null else confidences.average().toFloat()

                blocks += TextBlock(
                    text = content,
                    boundingBox = Rect(box),
                    confidence = confidence,
                    language = line.recognizedLanguage,
                    blockIndex = blockIndex,
                )
            }
        }
        return blocks.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    }

    override fun close() {
        listOfNotNull(latinRecognizer, chineseRecognizer, japaneseRecognizer, koreanRecognizer)
            .forEach { runCatching { it.close() } }
        latinRecognizer = null
        chineseRecognizer = null
        japaneseRecognizer = null
        koreanRecognizer = null
        rotateSlot.release()
    }
}
