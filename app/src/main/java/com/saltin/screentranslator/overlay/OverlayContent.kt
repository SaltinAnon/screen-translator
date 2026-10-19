package com.saltin.screentranslator.overlay

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import com.saltin.screentranslator.translate.TranslatedBlock
import kotlin.math.roundToInt

/**
 * 阶段四的绘制层：Jetpack Compose Canvas，按 OCR 坐标把译文**原位覆盖**在原文上方。
 *
 * 为什么用 nativeCanvas：需要精确控制字号自适应与描边（halo），
 * 直接用 Android 的 Paint 比 Compose 文本布局更可控，而且每帧只分配几个 Paint（remember 复用）。
 *
 * 两条绘制路径：
 * - **单行**（未合并的块）：随框高定字号 + 横向压缩，短句最锐利；
 * - **折行**（合并过的段落）：给定排版宽度让译文自己折行，再迭代出能塞进框里的字号。
 *
 * 这个窗口是 FLAG_NOT_TOUCHABLE 的：所有点击都穿透到下面真正的 App，
 * 用户的交互入口是旁边的悬浮球。
 */
@Composable
fun OverlayContent(state: OverlayUiState, modifier: Modifier = Modifier) {

    val textPaint = remember {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
        }
    }
    val haloPaint = remember {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }
    val scrimPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
    val bannerBgPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    }
    val bannerTextPaint = remember {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
    }

    // 排版只在「译文变了 / 字号缩放变了」时算一次，而不是每次重绘都重新测量：
    // StaticLayout 的构造成本不低，放进 onDraw 会拖慢整帧。
    val density = LocalDensity.current.density
    val prepared = remember(state.blocks, state.fontSizeScale, density) {
        prepare(state.blocks, textPaint, haloPaint, state.fontSizeScale, density)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val drawDensity = this.density

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            if (state.visible && state.blocks.isNotEmpty()) {
                drawBlocks(native, state, prepared, textPaint, haloPaint, scrimPaint, drawDensity)
            }

            state.banner?.takeIf { it.isNotBlank() }?.let { banner ->
                drawBanner(
                    native,
                    banner,
                    state.bannerKind,
                    bannerTextPaint,
                    bannerBgPaint,
                    drawDensity,
                    size.width,
                )
            }
        }
    }
}

/** 逐块算好排版。只有合并过的段落才会走 StaticLayout。 */
private fun prepare(
    blocks: List<TranslatedBlock>,
    textPaint: TextPaint,
    haloPaint: TextPaint,
    scale: Float,
    density: Float,
): List<PreparedText> = blocks.map { block ->
    val width = block.boundingBox.width().toFloat()
    val height = block.boundingBox.height().toFloat()
    val text = block.translatedText
    when {
        width <= 2f || height <= 2f || text.isBlank() ->
            PreparedText(0f, 1f, null, null, 0f)

        block.isMerged -> InPlaceTextLayout.wrapped(
            textPaint = textPaint,
            haloPaint = haloPaint,
            text = text,
            maxWidth = width,
            maxHeight = height,
            scale = scale,
            density = density,
            lineBudget = block.mergedLines,
            align = block.align,
        )

        else -> InPlaceTextLayout.single(
            textPaint = textPaint,
            haloPaint = haloPaint,
            text = text,
            maxWidth = width,
            maxHeight = height,
            scale = scale,
            density = density,
        )
    }
}

private fun drawBlocks(
    native: android.graphics.Canvas,
    state: OverlayUiState,
    prepared: List<PreparedText>,
    textPaint: TextPaint,
    haloPaint: TextPaint,
    scrimPaint: Paint,
    density: Float,
) {
    val padX = 3f * density
    val padY = 2f * density
    val radius = 5f * density

    for (index in state.blocks.indices) {
        val block = state.blocks[index]
        val box = block.boundingBox
        val left = box.left + state.offsetX
        val top = box.top + state.offsetY
        val boxWidth = box.width().toFloat()
        val boxHeight = box.height().toFloat()
        if (boxWidth <= 2f || boxHeight <= 2f) continue

        val text = block.translatedText
        if (text.isBlank()) continue
        val layout = prepared.getOrNull(index) ?: continue

        // 1) 半透明底板：把原文压暗，让译文更清晰（In-Place 覆盖的关键）
        if (state.scrimAlpha > 0.01f) {
            scrimPaint.color = withAlpha(state.scrimColor, state.scrimAlpha)
            native.drawRoundRect(
                left - padX,
                top - padY,
                left + boxWidth + padX,
                top + boxHeight + padY,
                radius,
                radius,
                scrimPaint,
            )
        }

        val haloStroke = (layout.fontSize / 8f).coerceIn(1.2f * density, 3.5f * density)

        // 2) 合并过的段落：按折行排版绘制
        val fill = layout.fillLayout
        val halo = layout.haloLayout
        if (fill != null && halo != null) {
            // layout 持有的是自己那份 Paint 克隆，颜色只能设在 layout 自己的 paint 上
            fill.paint.color = state.textColor
            halo.paint.color = state.haloColor
            halo.paint.strokeWidth = haloStroke

            val verticalOffset = ((boxHeight - layout.layoutHeight) / 2f).coerceAtLeast(0f)
            native.save()
            native.translate(left, top + verticalOffset)
            halo.draw(native)
            fill.draw(native)
            native.restore()
            continue
        }

        // 3) 单行：随框高定字号，再横向压缩，撑满文本框但绝不溢出
        textPaint.textSize = layout.fontSize
        textPaint.textScaleX = layout.textScaleX
        textPaint.color = state.textColor
        haloPaint.textSize = layout.fontSize
        haloPaint.textScaleX = layout.textScaleX
        haloPaint.color = state.haloColor
        haloPaint.strokeWidth = haloStroke

        val baseline = top + boxHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

        // 先描边后填色，得到黑边彩字的高对比效果
        native.drawText(text, left, baseline, haloPaint)
        native.drawText(text, left, baseline, textPaint)
    }
}

/** 顶部的提示条：把「缺 Key / 报错 / 降级」这类信息直接怼到用户眼前 */
private fun drawBanner(
    native: android.graphics.Canvas,
    text: String,
    kind: BannerKind,
    textPaint: TextPaint,
    bgPaint: Paint,
    density: Float,
    canvasWidth: Float,
) {
    val margin = 12f * density
    val padH = 14f * density
    val padV = 10f * density
    val radius = 12f * density

    val maxTextWidth = (canvasWidth - margin * 2 - padH * 2).coerceAtLeast(120f * density)
    textPaint.textSize = 12.5f * density
    textPaint.color = 0xFFFFFFFF.toInt()

    val layout = StaticLayout.Builder
        .obtain(text, 0, text.length, textPaint, maxTextWidth.roundToInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, 1.18f)
        .setIncludePad(false)
        .build()

    val boxWidth = layout.width.toFloat() + padH * 2
    val boxHeight = layout.height.toFloat() + padV * 2
    val left = (canvasWidth - boxWidth) / 2f
    val top = 44f * density

    bgPaint.color = when (kind) {
        BannerKind.INFO -> 0xE61B2A41.toInt()
        BannerKind.WARN -> 0xE65A4413.toInt()
        BannerKind.ERROR -> 0xE68E2A22.toInt()
    }
    native.drawRoundRect(left, top, left + boxWidth, top + boxHeight, radius, radius, bgPaint)

    // 左侧色条，强化语义
    bgPaint.color = when (kind) {
        BannerKind.INFO -> 0xFF6FA8FF.toInt()
        BannerKind.WARN -> 0xFFFFC24D.toInt()
        BannerKind.ERROR -> 0xFFFF8A7A.toInt()
    }
    native.drawRoundRect(left, top, left + 4f * density, top + boxHeight, 2f * density, 2f * density, bgPaint)

    native.save()
    native.translate(left + padH, top + padV)
    layout.draw(native)
    native.restore()
}

private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    return (a shl 24) or (color and 0x00FFFFFF)
}
