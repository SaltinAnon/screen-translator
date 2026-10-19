package com.saltin.screentranslator.overlay

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.saltin.screentranslator.ocr.BlockAlign
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 一个块的**排版结果**。绘制阶段只做「画」，不再做任何测量。
 *
 * [fillLayout] / [haloLayout] 非空表示这个块是合并过的段落，要按折行绘制；
 * 为空走原来的单行路径（自适应字号 + 横向压缩），短句的下场更锐利、更像原位覆盖。
 */
internal class PreparedText(
    val fontSize: Float,
    val textScaleX: Float,
    val fillLayout: StaticLayout?,
    val haloLayout: StaticLayout?,
    val layoutHeight: Float,
) {
    val wrapped: Boolean get() = fillLayout != null && haloLayout != null
}

/**
 * In-Place 覆盖的两套排版策略。
 *
 * ## 为什么要分两套
 *
 * - **单行**：OCR 给出的单个文本行，框很扁。译文通常也就一两个词，
 *   把它压进这个扁框最自然的做法是「随框高定字号，再用 textScaleX 横向挤一挤」，
 *   出来是一行干净的大字。**这条路径与以往版本完全一致，不动它。**
 *
 * - **折行**：合并过的段落，框是好几行的并集，又高又宽。
 *   如果还按框高定字号，字会大得离谱然后被横向压成一条细线。
 *   正确做法是给一个固定的排版宽度让文字自己折行，再二分/迭代出能塞进框里的字号。
 *
 * ## 一个容易踩的坑
 *
 * [StaticLayout] 会**持有**传进去的 TextPaint 引用，绘制时才从它复制属性。
 * 如果多个 layout 共用同一个 Paint 实例，后面为别的块改字号会连带改掉前面已建好的 layout。
 * 所以 [build] 里给每个 layout 都克隆一份专属的 Paint。
 */
internal object InPlaceTextLayout {

    private const val MIN_SIZE_DP = 7f

    /** 折行时最多重建几次 layout 来收敛字号 */
    private const val MAX_BUILD_ROUNDS = 4

    /** 中日韩每字约占 1.0 em²，拉丁约 0.5 em²，折中取 0.62 做面积估算 */
    private const val AREA_PER_CHAR = 0.62f

    // ---------------------------------------------------------------- 单行（与历史行为一致）

    fun single(
        textPaint: TextPaint,
        haloPaint: TextPaint,
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        scale: Float,
        density: Float,
    ): PreparedText {
        val minSize = MIN_SIZE_DP * density
        var size = (maxHeight * 0.82f * scale).coerceAtLeast(minSize)

        // 测量必须在一个干净的基准上做，否则上一块残留的 textScaleX 会污染结果
        textPaint.textScaleX = 1f
        textPaint.textSize = size
        val measuredAtFullHeight = textPaint.measureText(text)
        if (measuredAtFullHeight > maxWidth && measuredAtFullHeight > 0f) {
            size = (size * (maxWidth / measuredAtFullHeight)).coerceAtLeast(minSize)
        }

        textPaint.textSize = size
        val measured = textPaint.measureText(text)
        val squeeze = if (measured > maxWidth && measured > 0f) {
            (maxWidth / measured).coerceIn(0.55f, 1f)
        } else {
            1f
        }
        textPaint.textScaleX = squeeze
        haloPaint.textSize = size
        haloPaint.textScaleX = squeeze

        return PreparedText(size, squeeze, null, null, size)
    }

    // ---------------------------------------------------------------- 折行（合并过的段落）

    fun wrapped(
        textPaint: TextPaint,
        haloPaint: TextPaint,
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        scale: Float,
        density: Float,
        lineBudget: Int,
        align: BlockAlign,
    ): PreparedText {
        val minSize = MIN_SIZE_DP * density
        val width = maxWidth.roundToInt().coerceAtLeast(1)
        val lines = lineBudget.coerceAtLeast(1)

        // 字号上限：别比原文的行高胖太多，否则一行短译文会在泡子里撑得夸张
        val maxSize = (maxHeight / lines * 1.35f * scale).coerceAtLeast(minSize)

        // 起步用一个面积估算：字号越大占的面积越多，反推一个合理的初值，
        // 比「从最大字号一路缩」少建好几次 layout。
        val estimate = sqrt(maxWidth * maxHeight / (text.length.coerceAtLeast(1) * AREA_PER_CHAR)) * scale
        var size = estimate.coerceIn(minSize, maxSize)

        var layout = build(textPaint, text, width, size, align)
        var rounds = 0
        while (layout.height > maxHeight && rounds < MAX_BUILD_ROUNDS && size > minSize) {
            val ratio = (maxHeight / layout.height.toFloat()).coerceIn(0.5f, 0.95f)
            size = (size * ratio).coerceAtLeast(minSize)
            layout = build(textPaint, text, width, size, align)
            rounds++
        }

        // 估得太保守（比如译文只有两个字，却给了一个三行高的框）→ 放大一次，填满泡子
        if (layout.height < maxHeight * 0.55f && size < maxSize) {
            val grown = (size * (maxHeight * 0.92f / layout.height.toFloat()).coerceIn(1f, 1.6f))
                .coerceAtMost(maxSize)
            if (grown > size) {
                val bigger = build(textPaint, text, width, grown, align)
                if (bigger.height <= maxHeight) {
                    layout = bigger
                    size = grown
                }
            }
        }

        val halo = build(haloPaint, text, width, size, align)
        return PreparedText(size, 1f, layout, halo, layout.height.toFloat())
    }

    private fun build(
        base: TextPaint,
        text: String,
        width: Int,
        size: Float,
        align: BlockAlign,
    ): StaticLayout {
        // 克隆一份专属 Paint：StaticLayout 持有引用，共用实例会互相串改字号
        val paint = TextPaint(base).apply {
            textSize = size
            textScaleX = 1f
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(
                when (align) {
                    BlockAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    BlockAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    BlockAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                },
            )
            .setLineSpacing(0f, 1.08f)
            .setIncludePad(false)
            .build()
    }
}
