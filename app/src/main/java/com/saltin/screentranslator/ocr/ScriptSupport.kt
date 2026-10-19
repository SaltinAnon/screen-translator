package com.saltin.screentranslator.ocr

/**
 * 语言 → ML Kit 文字系统 的映射。
 *
 * ## 为什么必须有这个类
 *
 * ML Kit Text Recognition **v2 只提供了四种文字系统识别器**：
 * 拉丁字母、中文、日文、韩文。
 *
 * 也就是说：俄语（西里尔文）、阿拉伯语、泰语、希伯来文、希腊文、
 * 以及天城文/泰米尔文等印度文字，**端侧根本没有任何识别器能读**。
 *
 * 这不是「模型太小」的问题。之前 AUTO 模式把不认识的语言统一丢给中文识别器，
 * 结果就是拿汉字识别器去硬认西里尔文/阿拉伯文，输出必然是一片乱码。
 * 正确做法是：明确告诉用户端侧读不了，并引导到云端多模态模式。
 *
 * 另一个容易踩的点：**中文识别器虽然名字叫中文，但它的字符集同时覆盖拉丁字母**，
 * 所以「中英混排」界面上它反而比拉丁识别器更稳；而纯英文界面用拉丁识别器更准。
 * 这两者的取舍由 [resolve] 处理。
 */
enum class Script(val label: String, val detail: String) {
    LATIN("拉丁字母", "英文 / 法文 / 德文 / 西班牙文 / 越南文…通用"),
    CHINESE("中文", "汉字，同时能读拉丁字母，适合中英混排界面"),
    JAPANESE("日文", "假名 + 汉字 + 拉丁字母"),
    KOREAN("韩文", "谚文 + 拉丁字母"),
    NONE("无可用识别器", "ML Kit 端侧没有这种文字的识别器"),
    ;

    val available: Boolean get() = this != NONE
}

object ScriptSupport {

    private val LATIN = setOf(
        "en", "fr", "de", "es", "pt", "it", "nl", "pl", "tr", "id", "ms", "vi",
        "ro", "hu", "cs", "sk", "sl", "hr", "lt", "lv", "et", "fi", "sv", "da",
        "no", "nb", "nn", "is", "ca", "gl", "cy", "ga", "sq", "af", "sw", "tl",
        "fil", "mt", "eo", "ht", "eu", "az", "uz", "la",
    )

    private val CHINESE = setOf("zh", "zh-hans", "zh-hant", "zh-cn", "zh-tw", "zh-hk")
    private val JAPANESE = setOf("ja")
    private val KOREAN = setOf("ko")

    /**
     * ML Kit v2 没有对应识别器文字。value 是给用户看的文字系统名字。
     * 天城文其实 ML Kit 有，但本工程没集成该依赖，所以一并归到这里，
     * 文案上说的是「端侧没有可用识别器」，对用户而言结论一致。
     */
    private val UNSUPPORTED: Map<String, String> = mapOf(
        // 西里尔文
        "ru" to "西里尔文", "uk" to "西里尔文", "bg" to "西里尔文", "sr" to "西里尔文",
        "be" to "西里尔文", "mk" to "西里尔文", "kk" to "西里尔文", "mn" to "西里尔文",
        "ky" to "西里尔文", "tg" to "西里尔文",
        // 阿拉伯文
        "ar" to "阿拉伯文", "fa" to "阿拉伯文", "ur" to "阿拉伯文", "ps" to "阿拉伯文",
        "sd" to "阿拉伯文", "ug" to "阿拉伯文",
        // 其他单独文字系统
        "th" to "泰文", "he" to "希伯来文", "el" to "希腊文", "ka" to "格鲁吉亚文",
        "hy" to "亚美尼亚文", "am" to "阿姆哈拉文", "lo" to "老挝文", "km" to "高棉文",
        "my" to "缅甸文", "si" to "僧伽罗文",
        // 印度诸文字
        "hi" to "天城文", "mr" to "天城文", "ne" to "天城文", "sa" to "天城文",
        "bn" to "孟加拉文", "ta" to "泰米尔文", "te" to "泰卢固文",
        "kn" to "卡纳达文", "ml" to "马拉雅拉姆文", "gu" to "古吉拉特文",
        "pa" to "古木基文", "or" to "奥里亚文",
    )

    /** 目标语言本身就是拉丁字母的文字系统集合，用于「OCR 读得了 / 读不了」的判断 */
    private fun baseCode(language: String): String = language.substringBefore('-').lowercase()

    fun scriptOf(language: String): Script {
        val code = baseCode(language)
        return when {
            code in CHINESE -> Script.CHINESE
            code in JAPANESE -> Script.JAPANESE
            code in KOREAN -> Script.KOREAN
            code in LATIN -> Script.LATIN
            code in UNSUPPORTED -> Script.NONE
            else -> Script.LATIN
        }
    }

    /**
     * 最终用哪个识别器。
     *
     * - 用户手动指定了文字系统 → 就听他的；
     * - AUTO + 指定了源语言 → 按语言挑；
     * - AUTO + 源语言也是「自动检测」→ 交由 [OcrProcessor] 同时跑拉丁与中文两套再择优。
     */
    fun resolve(mode: ScriptMode, sourceLanguage: String): Script = when (mode) {
        ScriptMode.LATIN -> Script.LATIN
        ScriptMode.CHINESE -> Script.CHINESE
        ScriptMode.JAPANESE -> Script.JAPANESE
        ScriptMode.KOREAN -> Script.KOREAN
        ScriptMode.AUTO ->
            if (sourceLanguage == "auto") Script.CHINESE else scriptOf(sourceLanguage)
    }

    /**
     * 端侧 OCR 读不了这个源语言的文字时，返回文字系统的名字（如「西里尔文」），否则 null。
     *
     * 注意只跟**源语言**有关：把中文翻成俄语完全没问题 ——
     * 识别的是中文界面，翻译模型负责输出俄语。
     */
    fun unsupportedScriptName(mode: ScriptMode, sourceLanguage: String): String? {
        if (mode != ScriptMode.AUTO) return null
        if (sourceLanguage == "auto") return null
        return UNSUPPORTED[baseCode(sourceLanguage)]
    }

    /** 设置页里显示的「当前会用什么识别器」 */
    fun describe(mode: ScriptMode, sourceLanguage: String): String = when {
        mode == ScriptMode.AUTO && sourceLanguage == "auto" ->
            "自动择优：同时跑拉丁与中文两套识别器，取识别质量更好的那一套"

        else -> resolve(mode, sourceLanguage).let { script ->
            if (script.available) "${script.label}识别器 · ${script.detail}"
            else "没有可用识别器（${UNSUPPORTED[baseCode(sourceLanguage)] ?: "未知文字系统"}）"
        }
    }
}
