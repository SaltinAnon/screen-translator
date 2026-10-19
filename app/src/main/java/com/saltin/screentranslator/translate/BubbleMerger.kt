package com.saltin.screentranslator.translate

import android.graphics.Rect
import com.saltin.screentranslator.core.BubbleMergeMode
import com.saltin.screentranslator.ocr.BlockAlign
import com.saltin.screentranslator.ocr.LineGrouper
import kotlin.math.max
import kotlin.math.min

/**
 * 把**同一个对话泡里的多块译文**合成**一块**。
 *
 * ## 它解决的是什么
 *
 * `LineGrouper` 已经在识别之后、翻译之前把「被换行拆开的行」合成了段落，
 * 但那是**按行距的严格判据**做的，管不到两件现实里常发生的事：
 *
 * 1. **竖排日文的列**：每一列在识别前被转成了「一行」，可列与列之间的间距
 *    比正常换行松得多；一旦某两列没被判成同一段，输出就是「一泡子好几个框」。
 * 2. **为了塞下横排译文而横向撑开的窄框**（`ImageUtils.widenTallRect`）：
 *    相邻列撑开之后直接互相重叠 —— 观感就是用户说的「竖排翻译后很多遮挡」。
 *
 * 于是这里加一道**翻译之后、渲染之前**的收口：作用在最终屏幕坐标上，
 * 对端侧 / 混合 / 多模态三种模式一视同仁，不管前面怎么翻，
 * 出来都是「一个泡子一个框、框里装着整段译文」。
 *
 * ## 判据：2D 紧邻，而不是只看上下
 *
 * 一条原文本该被当成「同一泡子」的前提，是两块之间**又近又对得上**：
 *
 * - **上下叠**（横排段落的一行一行）：水平方向有实质重叠 + 垂直空隙 ≤ ratio × 行高
 * - **左右并**（竖排漫画的一列一列）：垂直方向有实质重叠 + 水平空隙 ≤ ratio × 列宽
 * - **斜着挨着**：两个方向的空隙都很小
 *
 * 两条容易踩的坑，都在这里避开了：
 *
 * - **尺寸相似度要跟着方向走**。只看框高会误杀竖排：对话泡最后一列通常比别的列短，
 *   高度差一大截，可它跟旁边几列明明是同一个字号、同一个泡子。所以上下叠时比框高、
 *   左右并时比框宽 —— 那才是各自方向上「字号」的代理。
 * - **长宽上限要用绝对尺寸，不能用「比成员大 N 倍」**。竖排泡子的包围盒宽度 = 列数 × 列距，
 *   列一多就会超过「列宽 × N 倍」这种相对上限，明明是同一个泡子却被拦在门外。
 *   这里改用**屏幕尺寸**做上限（泡子不可能比屏幕还大），倍数只作为拿不到屏幕尺寸时的兜底。
 */
object BubbleMerger {

    data class Config(
        val mode: BubbleMergeMode = BubbleMergeMode.AUTO,
        /**
         * 空隙上限，相对框的厚度。0.85 是实测甜点位，见 `Defaults.BUBBLE_GAP_RATIO`。
         * 这里的「厚度」按方向取：上下叠时比行高，左右并时比列宽。
         */
        val gapRatio: Float = 0.85f,
        /** 单个泡子最多吸收多少块，防止把整屏糊成一坨 */
        val maxBlocksPerGroup: Int = 14,
        /** 单个泡子的译文总字数上限 */
        val maxCharsPerGroup: Int = 700,
        /** 尺寸容忍度：两框在某个方向上的尺寸之比低于 (1 − 该值) 视为不同层级，不合并 */
        val heightTolerance: Float = 0.55f,
        /** 拿不到屏幕尺寸时的兜底上限：包围盒最多比「最大成员」大多少倍 */
        val maxSpanFactor: Float = 20f,
        /** 合并后的包围盒最多占整屏面积的比例 */
        val maxAreaFraction: Float = 0.55f,
        /** 判定「版面是竖排」时，窄高框占比的门槛 */
        val columnarShare: Float = 0.5f,
    )

    data class Result(
        val blocks: List<TranslatedBlock>,
        /** 被合并掉的框数 = 合并前 − 合并后 */
        val mergedAway: Int,
        /** 合并后剩下的框数 */
        val groups: Int,
    ) {
        val active: Boolean get() = mergedAway > 0
    }

    /** 判定「这个框像竖排的一列」：又窄又高 */
    private fun isColumnLike(box: Rect): Boolean =
        box.width() > 0 && box.height() >= box.width() * 1.25f

    /**
     * [BubbleMergeMode.AUTO] 的开关判据：版面里超过 [share] 的框都是窄高条，
     * 就认为这是竖排版面（日式漫画的对话泡就是一堆窄高列）。
     *
     * 刻意保守：普通 App 界面、网页的框都是宽扁的，一眼就能排除，
     * 所以「自动」档不会去动那些场景的逐行覆盖。
     */
    fun looksColumnar(blocks: List<TranslatedBlock>, share: Float = 0.5f): Boolean {
        if (blocks.size < 2) return false
        val tall = blocks.count { isColumnLike(it.boundingBox) }
        return tall.toFloat() >= blocks.size * share
    }

    /**
     * @param frameWidth  真实屏幕宽（像素），用作包围盒上限；传 0 退回倍数兜底
     * @param frameHeight 真实屏幕高（像素）
     */
    fun merge(
        blocks: List<TranslatedBlock>,
        frameWidth: Int = 0,
        frameHeight: Int = 0,
        config: Config = Config(),
    ): Result {
        if (config.mode == BubbleMergeMode.OFF || blocks.size < 2) {
            return Result(blocks, 0, blocks.size)
        }
        if (config.mode == BubbleMergeMode.AUTO && !looksColumnar(blocks, config.columnarShare)) {
            return Result(blocks, 0, blocks.size)
        }

        val frameArea = frameWidth.toLong() * frameHeight.toLong()
        val bounds = Bounds(frameWidth, frameHeight, frameArea, config.maxAreaFraction)

        val groups = ArrayList<Group>(blocks.size)
        for (block in blocks) {
            val target = groups.firstOrNull { it.accepts(block, config, bounds) }
            if (target == null) groups += Group(block) else target.add(block)
        }

        if (groups.size == blocks.size) return Result(blocks, 0, groups.size)

        val merged = groups.map { it.toBlock() }
        return Result(merged, blocks.size - groups.size, groups.size)
    }

    /** 一屏的尺寸约束，合并后的包围盒不能越过去 */
    private class Bounds(
        private val frameWidth: Int,
        private val frameHeight: Int,
        frameArea: Long,
        areaFraction: Float,
    ) {

        private val areaLimit: Long =
            if (frameArea > 0L) (frameArea * areaFraction).toLong() else 0L

        fun ok(candidate: Rect, maxW: Int, maxH: Int, spanFactor: Float): Boolean {
            // 有屏幕尺寸就用绝对上限 —— 泡子再大也不可能超出屏幕
            if (frameWidth > 0 && candidate.width() > frameWidth) return false
            if (frameHeight > 0 && candidate.height() > frameHeight) return false
            if (areaLimit > 0L) {
                val area = candidate.width().toLong() * candidate.height().toLong()
                if (area > areaLimit) return false
            }
            if (frameWidth > 0 || frameHeight > 0) return true
            // 没有屏幕尺寸（理论上不会发生）才退回倍数上限
            if (maxW <= 0 || maxH <= 0) return false
            return candidate.width() <= maxW * spanFactor && candidate.height() <= maxH * spanFactor
        }
    }

    // ---------------------------------------------------------------- 分组

    private class Group(first: TranslatedBlock) {

        private val items = ArrayList<TranslatedBlock>(4)
        private val box = Rect(first.boundingBox)
        private var chars = first.translatedText.length
        private var maxW = first.boundingBox.width()
        private var maxH = first.boundingBox.height()

        init {
            items += first
        }

        fun accepts(block: TranslatedBlock, config: Config, bounds: Bounds): Boolean {
            if (items.size >= config.maxBlocksPerGroup) return false
            if (chars + block.translatedText.length > config.maxCharsPerGroup) return false

            // 只要跟组里**任意一块**够近就算同泡。
            // 注意「够近」这个判断必须按成员做，不能按不断长大的包围盒做 ——
            // 否则横排段落合到第三行时，包围盒已经比新来的那一行高出三倍，
            // 尺寸一致性校验就会把它自己人挡在门外。
            if (items.none { close(it.boundingBox, block.boundingBox, config) }) return false

            val candidate = Rect(box).apply { union(block.boundingBox) }
            return bounds.ok(candidate, maxW, maxH, config.maxSpanFactor)
        }

        fun add(block: TranslatedBlock) {
            items += block
            box.union(block.boundingBox)
            chars += block.translatedText.length
            maxW = max(maxW, block.boundingBox.width())
            maxH = max(maxH, block.boundingBox.height())
        }

        fun toBlock(): TranslatedBlock {
            if (items.size == 1) return items.first()

            val ordered = readingOrder(items)
            var text = ordered.first().translatedText
            for (i in 1 until ordered.size) {
                text = LineGrouper.joinAcrossLineBreak(text, ordered[i].translatedText)
            }

            // 折行预算：让译文占满泡子，而不是缩在角落。
            // 一个泡子里原本有几行（横排）／几列（竖排），译文就大致铺几行，
            // 上限 12 行是为了挡住「一整屏被合成一块」时的极端值。
            val lines = items.sumOf { it.mergedLines.coerceIn(1, 3) }.coerceIn(2, 12)

            return TranslatedBlock(
                originalText = ordered.joinToString("\n") { it.originalText },
                translatedText = text,
                boundingBox = Rect(box),
                fromCache = items.all { it.fromCache },
                mergedLines = lines,
                // 对话泡里的译文居中最好看；竖排列共用顶边，本来也判不出左/右对齐
                align = BlockAlign.CENTER,
            )
        }
    }

    /**
     * 组内成员按**阅读顺序**排一遍再拼接 —— 顺序直接决定译文读起来通不通顺。
     *
     * - **一列一列并排**（竖排漫画）：从右往左取列，列内自上而下。
     *   识别路径在转 90° 时已经把顺序理对了，这里只是把同一套规则显式写出来，
     *   顺便让「多模态模型自己定位」那条路径也能拿到正确的语序 ——
     *   它在外面是按左上角排序的，对竖排来说是反的。
     * - **一行一行叠**（横排段落/对话泡）：自上而下、自左往右。
     */
    private fun readingOrder(items: List<TranslatedBlock>): List<TranslatedBlock> {
        val columnar = items.count { isColumnLike(it.boundingBox) } * 2 >= items.size
        return if (columnar) {
            items.sortedWith(
                compareByDescending<TranslatedBlock> { it.boundingBox.left }
                    .thenBy { it.boundingBox.top },
            )
        } else {
            items.sortedWith(
                compareBy({ it.boundingBox.top }, { it.boundingBox.left }),
            )
        }
    }

    // ---------------------------------------------------------------- 2D 紧邻判据

    private fun close(a: Rect, b: Rect, config: Config): Boolean {
        val aw = a.width().toFloat()
        val ah = a.height().toFloat()
        val bw = b.width().toFloat()
        val bh = b.height().toFloat()
        if (aw <= 0f || ah <= 0f || bw <= 0f || bh <= 0f) return false

        val avgH = (ah + bh) / 2f
        val avgW = (aw + bw) / 2f

        val xGap = gapOf(a.left, a.right, b.left, b.right)
        val yGap = gapOf(a.top, a.bottom, b.top, b.bottom)
        val xOverlap = overlapOf(a.left, a.right, b.left, b.right)
        val yOverlap = overlapOf(a.top, a.bottom, b.top, b.bottom)

        // 尺寸一致性的比对方向要跟着「它们怎么挨着」走：
        // - 上下叠 → 比**框高**（横排一行的字号就是它的高度）
        // - 左右并 → 比**框宽**（竖排一列的「字号」是它的宽度）
        // 只看框高会误杀竖排：对话泡最后一列通常比别的列短，高度差一大截，
        // 但它跟旁边几列明明是同一个字号、同一个泡子。
        val hSimilar = similar(ah, bh, config.heightTolerance)
        val wSimilar = similar(aw, bw, config.heightTolerance)

        // 上下叠：横排段落里一行接一行（也覆盖「竖排的列被转成行」之后的情形）
        val stacked = hSimilar && xOverlap >= 0.35f * min(aw, bw) && yGap <= config.gapRatio * avgH
        // 左右并：竖排漫画一列挨着一列；顺带挡住「长按钮旁边一个短标签」这种 UI 组合
        val sideBySide = wSimilar && yOverlap >= 0.35f * min(ah, bh) && xGap <= config.gapRatio * avgW
        // 斜着挨着：两个方向的空隙都很小
        val touching = (hSimilar || wSimilar) &&
            xGap <= config.gapRatio * avgW && yGap <= config.gapRatio * avgH

        return stacked || sideBySide || touching
    }

    /** 两个尺寸是不是同一个量级 */
    private fun similar(x: Float, y: Float, tolerance: Float): Boolean {
        val lo = min(x, y)
        val hi = max(x, y)
        return hi > 0f && lo / hi >= 1f - tolerance
    }

    /** 两个区间之间的空隙；重叠时返回 0 */
    private fun gapOf(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float =
        max(0, max(aStart, bStart) - min(aEnd, bEnd)).toFloat()

    /** 两个区间的重叠长度 */
    private fun overlapOf(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float =
        max(0, min(aEnd, bEnd) - max(aStart, bStart)).toFloat()
}
