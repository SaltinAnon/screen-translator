package com.saltin.screentranslator.translate

/**
 * 译文体检 —— 判断这一次模型「到底有没有在翻译」。
 *
 * ## 为什么要单独做这件事
 *
 * 用户实测里最常撞见两种翻车，而且**屏幕上看起来都很正常**，所以以前完全没人管：
 *
 * 1. **翻完还是原文**。prompt 里那句「如果某行已经是目标语言，原样返回」被过度执行，
 *    模型干脆整屏照抄；端侧还多一种情况：离线模型没加载成功，代码兜底把原文当译文画出去。
 * 2. **翻成了另一种外文**。该出简体中文，结果屏幕上是一片日文汉字、韩文或越南语。
 *    顺带解释了一个很隐蔽的诱因：手机屏幕上是日文，但源语言选了「自动检测」，
 *    端侧模式会一律按英语处理 —— 拿 en→zh 的模型去翻日文，出来的就是谁也认不出的东西。
 *
 * 这两种结果都不该被当成「翻译成功」直接画到屏幕上：那等于把错误内容盖在原文上，
 * 用户连原来那行英文都看不到了。
 *
 * ## 判据为什么用「文字系统」而不是「语言识别」
 *
 * 端侧没有语言识别器（那是额外一百多 MB 的模型），但一个便宜得多的观察就够用：
 * **译文里的字长得像不像目标语言**。该出中文却输出一片拉丁字母或假名，
 * 不需要语言模型也能看出来。
 */
object TranslationVerifier {

    /**
     * 文字系统。只用来回答「这串字看起来是不是目标语言」，不做语言判定。
     * [NONE] 表示数字、标点、空白这类**不参与判定**的字符。
     */
    private enum class Script { CJK, KANA, HANGUL, LATIN, CYRILLIC, ARABIC, THAI, DEVANAGARI, OTHER, NONE }

    /** 一次体检的统计结果 */
    data class Verdict(
        /** 参与判定的译文条数（原文太短的会被排除，见 [ELIGIBLE_MIN_CHARS]） */
        val eligible: Int,
        /** 译文与原文完全相同（忽略大小写与空白）的条数 */
        val unchanged: Int,
        /** 译文里一个目标语言的字都没有的条数 */
        val wrongScript: Int,
    ) {
        val suspicious: Int get() = unchanged + wrongScript

        /**
         * 值不值得换一种问法重试一次。
         *
         * 门槛是「可疑 ≥ 2 条」且「超过合格条目的一半」：整屏都是品牌名、代码这种
         * 本来就该保持原样的画面，零星一两条相同不该触发整帧重试 —— 那只会白花一次请求。
         */
        val needsRetry: Boolean
            get() = eligible >= 2 && suspicious >= 2 && suspicious * 2 >= eligible

        /** 给用户看的一句话；没有问题返回 null */
        val message: String?
            get() = when {
                !needsRetry -> null
                unchanged > 0 && wrongScript > 0 ->
                    "这次有 $unchanged 行译文和原文一模一样、$wrongScript 行译文不像目标语言，" +
                        "已经自动换了一种问法重试；若仍然如此，请检查「源语言」是否设成了「自动检测」"

                unchanged > 0 ->
                    "$unchanged 行译文和原文完全相同（模型很可能原样抄了原文）。" +
                        "若你选的是「自动检测」源语言，请改成手动指定；离线模式还要确认模型已下载"

                else ->
                    "$wrongScript 行译文看起来不是目标语言。" +
                        "最常见的原因是源语言设成了「自动检测」而屏幕上是另一种语言，" +
                        "请到「识别」页手动指定源语言"
            }
    }

    /** 少于这个字符数的原文不参与判定：`OK`、`Wi-Fi` 这类本来就该保持原样 */
    private const val ELIGIBLE_MIN_CHARS = 4

    /**
     * 译文里目标语言的字少到这个程度，就认为「压根没翻成目标语言」。
     * 留了余量：中英混排的译文里夹几个英文词是完全正常的。
     */
    private const val FOREIGN_SHARE = 0.8f

    /** 一条译文里至少要有这么多个「实义字符」才判得动 */
    private const val MIN_MEANINGFUL_CHARS = 3

    /**
     * 体检一批译文。
     *
     * @param translated 待检查的译文（原文 + 译文）
     * @param targetLanguage 目标语言代码，如 `zh` / `en` / `ja`
     */
    fun inspect(translated: List<TranslatedBlock>, targetLanguage: String): Verdict {
        if (translated.isEmpty()) return Verdict(0, 0, 0)
        val expected = expectedScripts(targetLanguage)

        var eligible = 0
        var unchanged = 0
        var wrongScript = 0

        for (block in translated) {
            val original = block.originalText
            if (countMeaningful(original) < ELIGIBLE_MIN_CHARS) continue
            eligible++

            val output = block.translatedText
            if (sameAsOriginal(original, output)) {
                unchanged++
                continue
            }
            if (output.isBlank()) continue
            if (!looksLikeTarget(output, expected)) wrongScript++
        }

        return Verdict(eligible = eligible, unchanged = unchanged, wrongScript = wrongScript)
    }

    // ------------------------------------------------------------ 判据

    /** 忽略大小写与所有空白后是否完全相同 */
    private fun sameAsOriginal(original: String, output: String): Boolean =
        squash(original) == squash(output)

    private fun squash(text: String): String =
        text.lowercase().filterNot { it.isWhitespace() }

    private fun countMeaningful(text: String): Int = text.count { scriptOf(it) != Script.NONE }

    /**
     * 这串译文看起来像不像目标语言。
     *
     * 两条规则：
     * 1. **一个目标语言的字都没有，却有一堆别的文字** —— 直接判定没翻（阈值 3 个字符，
     *    避免把 "OK"、"USB" 这类误伤）。
     * 2. 目标语言的字太少（占比 < 20%）、别的文字占了 80% 以上 —— 也是没翻成目标语言。
     *
     * 另有一条 CJK 专有规则：目标语言是中文时，译文里若出现成片**假名**，
     * 那多半是模型吐了日文出来 —— 中文里几乎不会自然出现假名。
     */
    private fun looksLikeTarget(text: String, expected: Set<Script>): Boolean {
        var expectedCount = 0
        var foreignCount = 0
        var kana = 0
        var cjk = 0

        for (ch in text) {
            val script = scriptOf(ch)
            when (script) {
                Script.NONE -> Unit
                Script.KANA -> {
                    kana++
                    if (script in expected) expectedCount++ else foreignCount++
                }
                Script.CJK -> {
                    cjk++
                    if (script in expected) expectedCount++ else foreignCount++
                }
                else -> if (script in expected) expectedCount++ else foreignCount++
            }
        }

        val meaningful = expectedCount + foreignCount
        if (meaningful < MIN_MEANINGFUL_CHARS) return true // 太短，判不动，不冤枉它

        // 目标语言是中文，译文却以假名为主 → 基本可以确定是日文
        if (expected == CHINESE_EXPECTED && kana >= 2 && kana * 2 > cjk) return false

        if (expectedCount == 0) return false
        val foreignShare = foreignCount.toFloat() / meaningful.toFloat()
        return !(foreignShare >= FOREIGN_SHARE && expectedCount * 5 < meaningful)
    }

    // ------------------------------------------------------------ 字符 → 文字系统

    private fun scriptOf(ch: Char): Script {
        val code = ch.code
        return when {
            code in 0x3040..0x30FF -> Script.KANA          // 平假名 / 片假名
            code in 0xAC00..0xD7AF -> Script.HANGUL        // 谚文音节
            code in 0x1100..0x11FF -> Script.HANGUL        // 谚文字母
            code in 0x4E00..0x9FFF -> Script.CJK           // 基本汉字
            code in 0x3400..0x4DBF -> Script.CJK           // 扩展 A
            code in 0xF900..0xFAFF -> Script.CJK           // 兼容汉字
            code in 0x0400..0x04FF -> Script.CYRILLIC
            code in 0x0500..0x052F -> Script.CYRILLIC      // 西里尔补充
            code in 0x0600..0x06FF -> Script.ARABIC
            code in 0x0750..0x077F -> Script.ARABIC        // 阿拉伯补充
            code in 0x0E00..0x0E7F -> Script.THAI
            code in 0x0900..0x097F -> Script.DEVANAGARI
            ch.isLetter() && (code < 0x0250 || code in 0x1E00..0x1EFF) -> Script.LATIN
            else -> Script.NONE
        }
    }

    // ------------------------------------------------------------ 目标语言 → 期望文字系统

    private val CYRILLIC_LANGUAGES = setOf(
        "ru", "uk", "bg", "sr", "be", "mk", "kk", "mn", "ky", "tg",
    )
    private val ARABIC_LANGUAGES = setOf("ar", "fa", "ur", "ps", "sd", "ug")
    private val THAI_LANGUAGES = setOf("th")
    private val DEVANAGARI_LANGUAGES = setOf("hi", "mr", "ne", "sa")
    private val CJK_LANGUAGES = setOf("zh", "zh-hans", "zh-hant", "zh-cn", "zh-tw", "zh-hk")

    private val CHINESE_EXPECTED = setOf(Script.CJK)

    /**
     * 目标语言期望看到哪些文字系统。
     *
     * 认不出来的语言一律按拉丁处理 —— 那是最常见的情况（英法德西葡…），
     * 而且判错也只是「不额外报警」，不会误伤正常译文。
     */
    private fun expectedScripts(targetLanguage: String): Set<Script> {
        val base = targetLanguage.substringBefore('-').lowercase()
        return when {
            base in CJK_LANGUAGES -> CHINESE_EXPECTED
            base == "ja" -> setOf(Script.KANA, Script.CJK)
            base == "ko" -> setOf(Script.HANGUL)
            base in CYRILLIC_LANGUAGES -> setOf(Script.CYRILLIC)
            base in ARABIC_LANGUAGES -> setOf(Script.ARABIC)
            base in THAI_LANGUAGES -> setOf(Script.THAI)
            base in DEVANAGARI_LANGUAGES -> setOf(Script.DEVANAGARI)
            else -> setOf(Script.LATIN)
        }
    }
}
