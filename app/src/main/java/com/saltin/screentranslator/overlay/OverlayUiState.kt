package com.saltin.screentranslator.overlay

import com.saltin.screentranslator.translate.TranslatedBlock

/** 顶部提示条的语义，决定配色 */
enum class BannerKind { INFO, WARN, ERROR }

/**
 * 译文层的渲染状态。
 * 每次刷新整体替换一次（不可变快照），Compose 侧靠 data class 的相等性判断是否需要重绘。
 */
data class OverlayUiState(
    val blocks: List<TranslatedBlock> = emptyList(),
    val visible: Boolean = true,
    val fontSizeScale: Float = 1f,
    val textColor: Int = 0xFFFFEB3B.toInt(),
    val haloColor: Int = 0xE6000000.toInt(),
    val scrimColor: Int = 0xFF11131A.toInt(),
    val scrimAlpha: Float = 0.72f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,

    /**
     * 顶部提示条文案。null 表示不显示。
     * 这个设计是为了让「没配 API Key」「模型报错」这类问题**在屏幕上可见**，
     * 而不是只在 App 设置页里悄悄写一行 —— 之前最大的体验问题就出在这里。
     */
    val banner: String? = null,
    val bannerKind: BannerKind = BannerKind.INFO,
)
