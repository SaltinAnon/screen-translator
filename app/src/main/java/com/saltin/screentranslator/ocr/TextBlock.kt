package com.saltin.screentranslator.ocr

import android.graphics.Rect

/** 段落的水平对齐方式，由 [LineGrouper] 从原始行的对齐关系推断，渲染折行时复用 */
enum class BlockAlign { LEFT, CENTER, RIGHT }

/**
 * 阶段二产物：一段文本 + 它在屏幕坐标系中的边界框。
 *
 * [boundingBox] 的坐标系与传入 [OcrProcessor.recognize] 的 Bitmap 一致；
 * 上游会把缩放系数带出来，渲染阶段再乘回去映射到真实屏幕像素。
 *
 * ★ 注意：这个「一段」未必是 OCR 的原始一行。漫画对话泡、窄栏正文里一句话会被换行
 * 拆成好几行，[LineGrouper] 会把它们先合成一段再送去翻译 —— 此时 [mergedLines]
 * 大于 1，[boundingBox] 是这几行的并集，渲染阶段必须按折行排版而不是压成一行。
 */
data class TextBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float? = null,
    val language: String? = null,
    /** ML Kit 自己的 TextBlock 序号：它本身就是按「段落」聚合的，合并时是强信号 */
    val blockIndex: Int = 0,
    /** 这一段由几行 OCR 结果合并而来；1 = 未合并 */
    val mergedLines: Int = 1,
    /** 推断出的对齐方式，仅在 [mergedLines] > 1 时有意义 */
    val align: BlockAlign = BlockAlign.LEFT,
) {
    val width: Int get() = boundingBox.width()
    val height: Int get() = boundingBox.height()
    val centerY: Int get() = boundingBox.centerY()

    /** 是否由多行合并而成 */
    val isMerged: Boolean get() = mergedLines > 1
}
