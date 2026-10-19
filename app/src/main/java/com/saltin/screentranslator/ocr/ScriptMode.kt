package com.saltin.screentranslator.ocr

/**
 * 文字系统选择。ML Kit Text Recognition v2 是按 script 分包的，
 * 不同 script 需要各自的 TextRecognizer 实例。
 */
enum class ScriptMode(val id: String, val label: String) {
    AUTO("auto", "自动（按源语言挑选）"),
    LATIN("latin", "拉丁字母（英/法/德/西…）"),
    CHINESE("chinese", "中文 / 汉字"),
    JAPANESE("japanese", "日文"),
    KOREAN("korean", "韩文"),
    ;

    companion object {
        fun fromId(id: String?): ScriptMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
