package com.saltin.screentranslator.translate

import android.graphics.Rect
import com.saltin.screentranslator.ocr.BlockAlign

/**
 * 阶段三产物：译文 + 它在**真实屏幕像素**坐标系中的位置。
 * 悬浮窗直接按 [boundingBox] 做 In-Place 覆盖绘制。
 *
 * [mergedLines] > 1 表示这段原文是几行 OCR 结果合并成的，
 * [boundingBox] 是那几行的并集 —— 渲染时不能再压成一行，
 * 必须按 [align] 折行排版（否则译文会横向挤成一条细线）。
 */
data class TranslatedBlock(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect,
    val fromCache: Boolean = false,
    /** 由几行原文合并而来；1 = 未合并 */
    val mergedLines: Int = 1,
    /** 段落对齐方式，决定折行排版时每一行往哪边靠 */
    val align: BlockAlign = BlockAlign.LEFT,
) {
    val isMerged: Boolean get() = mergedLines > 1
}
