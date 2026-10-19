package com.saltin.screentranslator.ocr

/**
 * 文字的排版方向。
 *
 * ## 为什么需要这个东西
 *
 * 日本漫画的台词是**竖排、从右往左**的（縦書き / tategaki）。ML Kit 的识别器是**为横排设计**的，
 * 遇到竖排时它只会把每一「列」当成一「行」交出来：
 *
 * ```
 * 竖排原文（3 列，右→左）        ML Kit 交出来的「行」
 *   こ ん な                    →  「こんな」
 *   ん な こ                       「なこと」
 *   な と と                       「こと、」
 *   こ と 、
 * ```
 *
 * 后果有两个，用户都报过：
 * 1. **语义断裂** —— 每一列被当成一个独立句子去翻译，读起来语无伦次；
 * 2. **排版错乱** —— 每一列的框又窄又高，译文横着塞进去只能得到一条细线。
 *
 * 解决办法是**把位图逆时针转 90° 再送去识别**：竖排的「列」就变成了横排的「行」，
 * 而「右→左的列序」正好变成「上→下的行序」，列内字序也变成左→右 ——
 * 也就是 ML Kit 最擅长的那种排布。识别完之后：
 * - 交给 [LineGrouper] 的输入已经是横排的，**合并逻辑一行都不用改**；
 * - 坐标再按相反方向映射回屏幕即可。
 *
 * 映射关系（原图宽度为 W）：
 * ```
 * 正向（逆时针 90°）：(x, y) → (y, W − x)
 * 反向（映射回屏幕）：(x', y') → (W − y', x')
 * ```
 */
enum class TextOrientation(val id: String, val label: String, val detail: String) {
    AUTO(
        "auto",
        "自动（推荐）",
        "先按横排识别；如果结果看起来是「竖排被横着切」，自动转 90° 重识别一次并择优",
    ),
    HORIZONTAL(
        "horizontal",
        "横排",
        "只按横排识别。普通界面、横排漫画用这个",
    ),
    VERTICAL_RTL(
        "vertical",
        "竖排 · 日式（右→左）",
        "强制把画面转 90° 后识别，适合竖排日文漫画。横排内容用这个会认不出字",
    ),
    ;

    companion object {
        fun fromId(id: String?): TextOrientation =
            entries.firstOrNull { it.id == id } ?: AUTO

        /** 竖排只对这两种文字系统有意义（拉丁/谚文没有竖排写法） */
        fun supportsVertical(script: Script): Boolean =
            script == Script.CHINESE || script == Script.JAPANESE
    }
}
