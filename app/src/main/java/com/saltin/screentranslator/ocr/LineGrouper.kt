package com.saltin.screentranslator.ocr

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 把 OCR 的**逐行**结果合并成**段落**。
 *
 * ## 为什么必须有这一步
 *
 * ML Kit 返回的最小可用单位是「行」。但现实里的文本是**被换行拆开**的：
 * 漫画对话泡、窄栏正文、手机上的多行说明 —— 一句话横跨三五行，每行只有几个词。
 * 如果逐行送去翻译，模型看到的是「不完整的短语」，只能瞎猜：
 *
 * ```
 * 原文（一个对话泡里的三行）      逐行翻译的结果
 *   I don't know what            → 我不知道什么
 *   you mean by that,            → 你的意思是这样，
 *   but I'll try.                → 但我会试试。
 * ```
 *
 * 每一句单独看都通顺，合起来语无伦次 —— 这就是用户报的「把一句话当成很多个短语翻译」。
 * 正确做法是先按段落合并成 `I don't know what you mean by that, but I'll try.`
 * 再整体翻译，模型才能给出连贯的译文。
 *
 * ## 合并判据
 *
 * 硬性条件（不满足直接不合并）：
 * 1. **垂直紧邻**：行间距 ≤ [Config.gapRatio] × 行高。
 *    换行文本的行距很小（通常 0.3~0.6 倍行高），而 UI 列表项之间有明显的留白（通常 ≥ 0.8 倍）。
 *    这是区分「同一段被换行」和「相邻两个条目」最有效的一条。
 * 2. **字号接近**：行高差异 ≤ [Config.heightTolerance]。标题和正文的字号差通常远超这个值。
 * 3. **不是同一行的错切**：上下重叠过多说明是识别碎片，不是换行。
 * 4. **足够长**：宽度不小于整帧的 [Config.minWidthFraction]，且字符数不少于
 *    [Config.minLineChars]。这两道一起挡住「确定 / 取消」这种两三字的按钮标签被凑成一句 ——
 *    被换行拆开的续行几乎不可能只有一两个字。
 *
 * 打分项（累计到 [Config.threshold] 才合并）：
 * - 来自同一个 ML Kit TextBlock（+3）：ML Kit 自己就按段落聚合，可信度最高
 * - 左对齐（+2）/ 居中（+2）/ 右对齐（+1）：换行文本必然共享一条对齐边
 * - 字号非常接近（+2）
 * - 上一行是「满行」（+2）：说明它是被容器宽度挤下来的，而不是一句独立的话
 * - 上一行以逗号/连接号/开引号结尾（+2）：明显没说完
 * - 上一行以句末标点结尾（−3）：倾向于是另一句了。
 *   ★ 这里**故意只减分不硬拆** —— 一个对话泡里本来就常有好几句话，
 *   硬按句号拆开反而更碎，用户要的是「整个泡子一起翻」。
 *
 * ## 对齐方式的副作用（好的那种）
 *
 * 合并时顺手把段落的对齐边记下来（[BlockAlign]），渲染时按同样的方式折行，
 * 译文在对话泡里就不会歪着。
 */
object LineGrouper {

    /** 句末标点：出现这些字符说明这句话说完了（只作为减分项，不做硬性拆分） */
    private val SENTENCE_END = setOf(
        '。', '．', '.', '！', '!', '？', '?', '…', '‽', '‼',
    )

    /** 表示「还没说完」的结尾：逗号、顿号、分号、冒号、破折号、开引号 */
    private val TRAILING_CONTINUE = setOf(
        '，', ',', '、', '；', ';', '：', ':', '…', '—', '–', '〜', '～',
        '「', '『', '（', '(', '“', '‘', '《', '/',
    )

    /** 拉丁文标点：这类字符后面接拉丁字母时需要补一个空格 */
    private val LATIN_PUNCT_TAIL = setOf(
        '.', ',', ';', ':', '!', '?', ')', ']', '}', '%', '"', '\'',
        '’', '”', '…', '°',
    )

    /** 合并用的硬性阈值没有命中时返回这个分数，一定低于任何 threshold */
    private const val REJECT = Int.MIN_VALUE / 2

    data class Config(
        /** 总开关。关掉就退回逐行翻译（v1.2 的行为） */
        val enabled: Boolean = true,
        /**
         * 行间距上限，相对行高。
         * 0.5 保守（只合并贴得很紧的）/ 0.7 标准 / 0.95 激进（连排版松散的也合）。
         */
        val gapRatio: Float = 0.7f,
        /** 行高差异容忍度：min/max 低于 (1 - tolerance) 视为不同层级，不合并 */
        val heightTolerance: Float = 0.4f,
        /** 命中该分数才并入同一段 */
        val threshold: Int = 4,
        /** 单段的合并上限，避免把整屏文字粘成一坨 */
        val maxLines: Int = 10,
        val maxChars: Int = 800,
        /**
         * 一行至少要占整帧宽度的这个比例，才认为「值得合并」。
         *
         * 用来挡住「确定 / 取消」这种两三个字的按钮标签被凑成一句。
         * 0.045 × 1440px ≈ 65px，纯两字中文标签通常在这条线以下。
         */
        val minWidthFraction: Float = 0.045f,
        /**
         * 上一行的最少字符数。
         *
         * [minWidthFraction] 是按宽度挡，这条是按字数挡 —— 两者互补：
         * 大字号下的两字按钮宽度也能过线，但字数过不了。
         * 被换行拆开的续行几乎不可能只有一两个字。
         */
        val minLineChars: Int = 3,
    )

    data class Result(
        val blocks: List<TextBlock>,
        /** 因合并而减少的行数（原始行数 − 段落数），用于状态栏展示 */
        val mergedLines: Int,
    ) {
        val lineCount: Int get() = blocks.size
    }

    /**
     * @param lines OCR 原始行（已按阅读顺序排过或未排过都可以，内部会重排）
     * @param frameWidth OCR 位图的宽度，用于 [Config.minWidthFraction] 的相对判断
     */
    fun group(
        lines: List<TextBlock>,
        frameWidth: Int,
        config: Config = Config(),
    ): Result {
        if (!config.enabled || lines.size < 2) return Result(lines, 0)

        val ordered = lines.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val minWidth = frameWidth.coerceAtLeast(1) * config.minWidthFraction

        val groups = ArrayList<Group>(ordered.size)
        var current: Group? = null

        for (line in ordered) {
            val tail = current
            if (tail == null || !tail.accept(line, config, minWidth)) {
                current = Group(line).also { groups += it }
            } else {
                tail.add(line)
            }
        }

        if (groups.size == ordered.size) return Result(ordered, 0)

        val merged = groups.map { it.toBlock() }
        return Result(merged, ordered.size - groups.size)
    }

    // ---------------------------------------------------------------- 分组

    private class Group(first: TextBlock) {
        private val items = ArrayList<TextBlock>(4)

        var chars: Int = 0
            private set

        init {
            items += first
            chars = first.text.length
        }

        val size: Int get() = items.size
        val last: TextBlock get() = items.last()

        fun accept(line: TextBlock, config: Config, minWidth: Float): Boolean {
            if (size >= config.maxLines) return false
            if (chars + line.text.length > config.maxChars) return false

            // 上一个太窄或太短的行（按钮文字、图标旁的一个词）根本没有「被换行拆开」这回事。
            // 宽度与字数两道一起挡：大字号下的两字按钮宽度能过线，但字数过不了。
            val widest = max(items.maxOf { it.width }, line.width).toFloat()
            if (widest < minWidth) return false
            if (last.text.count { !it.isWhitespace() } < config.minLineChars) return false

            return continuationScore(last, line, config) >= config.threshold
        }

        fun add(line: TextBlock) {
            items += line
            chars += line.text.length
        }

        fun toBlock(): TextBlock {
            if (items.size == 1) return items.first()

            val box = Rect(items.first().boundingBox)
            for (i in 1 until items.size) box.union(items[i].boundingBox)

            var text = items.first().text
            for (i in 1 until items.size) text = joinAcrossLineBreak(text, items[i].text)

            val confidences = items.mapNotNull { it.confidence }
            val longest = items.maxByOrNull { it.text.length } ?: items.first()

            return TextBlock(
                text = text,
                boundingBox = box,
                confidence = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
                language = longest.language,
                blockIndex = items.first().blockIndex,
                mergedLines = items.size,
                align = resolveAlign(items),
            )
        }
    }

    // ---------------------------------------------------------------- 判据

    /**
     * 判断 [next] 是不是 [prev] 的下一行（同一段的续行）。
     * 返回累计得分，低于配置阈值就不合并。
     */
    private fun continuationScore(prev: TextBlock, next: TextBlock, config: Config): Int {
        val prevBox = prev.boundingBox
        val nextBox = next.boundingBox

        val avgHeight = (prev.height + next.height) / 2f
        if (avgHeight <= 0f) return REJECT

        // 硬否 1：离得太远 —— 这是 UI 列表项与换行文本之间最本质的区别
        val gap = nextBox.top - prevBox.bottom
        if (gap > config.gapRatio * avgHeight) return REJECT
        // 硬否 2：上下重叠太多 —— 是同一行被识别切成了两半，不是换行
        if (gap < -0.6f * avgHeight) return REJECT

        // 硬否 3：字号差太多（标题 vs 正文）
        val minHeight = min(prev.height, next.height).toFloat()
        val maxHeight = max(prev.height, next.height).toFloat()
        if (maxHeight <= 0f || minHeight / maxHeight < 1f - config.heightTolerance) return REJECT

        val maxWidth = max(prev.width, next.width).toFloat().coerceAtLeast(1f)
        var score = 0

        // ML Kit 自己就把它们归到同一个块里 —— 最强信号
        if (prev.blockIndex == next.blockIndex) score += 3

        // 对齐关系：换行文本一定会共享一条对齐边
        val leftDelta = abs(prevBox.left - nextBox.left)
        val centerDelta = abs(prevBox.centerX() - nextBox.centerX())
        val rightDelta = abs(prevBox.right - nextBox.right)
        if (leftDelta <= 0.12f * maxWidth) score += 2
        if (centerDelta <= 0.18f * maxWidth) score += 2
        if (rightDelta <= 0.12f * maxWidth) score += 1

        // 字号越接近越像同一段
        score += if (minHeight / maxHeight >= 0.82f) 2 else 1

        // 上一行是「满行」→ 它是被容器宽度挤下来的，而不是一句独立的话
        if (prev.width >= 0.6f * maxWidth) score += 2

        val tailChar = prev.text.lastOrNull()
        if (tailChar in TRAILING_CONTINUE) score += 2
        if (tailChar in SENTENCE_END) score -= 3

        return score
    }

    /** 从一组行的对齐关系推断段落的对齐边；都不明显时按居中处理（对话泡最常见） */
    private fun resolveAlign(items: List<TextBlock>): BlockAlign {
        if (items.size < 2) return BlockAlign.LEFT
        val maxWidth = items.maxOf { it.width }.toFloat().coerceAtLeast(1f)

        val leftOk = items.all { abs(it.boundingBox.left - items.first().boundingBox.left) <= 0.12f * maxWidth }
        if (leftOk) return BlockAlign.LEFT

        val rightOk = items.all { abs(it.boundingBox.right - items.first().boundingBox.right) <= 0.12f * maxWidth }
        if (rightOk) return BlockAlign.RIGHT

        val centerOk = items.all {
            abs(it.boundingBox.centerX() - items.first().boundingBox.centerX()) <= 0.18f * maxWidth
        }
        if (centerOk) return BlockAlign.CENTER

        return BlockAlign.CENTER
    }

    // ---------------------------------------------------------------- 拼接

    /**
     * 把两行拼成一句。这里的细节直接决定译文质量：
     * - 中日韩之间**不补空格**（补了会变成 `你好 世界`）；
     * - 拉丁文之间补空格；
     * - **中英混排的交界处补空格**（`你好world` → `你好 world`），既好读，也让模型分词更稳；
     * - 英文跨行断词（`thou-` + `sand`）要把连字符去掉接起来。
     */
    fun joinAcrossLineBreak(prev: String, next: String): String {
        if (prev.isEmpty()) return next
        if (next.isEmpty()) return prev

        val tail = prev.last()
        val head = next.first()

        // 英文跨行断词：上一行以连字符结尾，且连字符前面是字母、后面是小写字母
        if (tail == '-' || tail == '\u2010' || tail == '\u00AD') {
            val before = prev.dropLast(1).lastOrNull()
            if (before != null && before.isLetter() && head.isLetter() && head.isLowerCase()) {
                return prev.dropLast(1) + next
            }
        }

        val leftLatin = isLatinWordChar(tail) || tail in LATIN_PUNCT_TAIL
        val rightLatin = isLatinWordChar(head)
        val leftCjk = isCjk(tail)
        val rightCjk = isCjk(head)

        val needsSpace = (leftLatin && rightLatin) ||
            (leftLatin && rightCjk) ||
            (leftCjk && rightLatin)
        return if (needsSpace) "$prev $next" else prev + next
    }

    private fun isLatinWordChar(c: Char): Boolean = c.isLetterOrDigit() && c.code < 0x0300

    /** 汉字、假名、谚文 —— 这些字符之间不补空格 */
    private fun isCjk(c: Char): Boolean = when (c.code) {
        in 0x3040..0x30FF -> true      // 平假名 / 片假名
        in 0x3400..0x4DBF -> true      // 扩展 A
        in 0x4E00..0x9FFF -> true      // 基本汉字
        in 0xAC00..0xD7AF -> true      // 谚文
        in 0xF900..0xFAFF -> true      // 兼容汉字
        else -> false
    }
}
