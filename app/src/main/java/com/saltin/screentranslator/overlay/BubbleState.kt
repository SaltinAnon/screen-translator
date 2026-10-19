package com.saltin.screentranslator.overlay

/**
 * 悬浮球的五种状态，直接决定它的颜色与图形，用户一眼就能知道现在在干嘛。
 */
enum class BubbleState {
    /** 待命：等着你点它 */
    IDLE,

    /** 正在识别/翻译 */
    WORKING,

    /** 刚刚翻译成功 */
    DONE,

    /** 出错（没网、Key 不对、模型报错…） */
    ERROR,

    /** 服务没在运行（理论上不会出现，兜底） */
    OFFLINE,
}

/** 悬浮球要显示的内容 */
data class BubbleUiModel(
    val state: BubbleState = BubbleState.IDLE,
    val translatedBlocks: Int = 0,
    val alpha: Float = 0.92f,
    val badgeVisible: Boolean = true,
)
