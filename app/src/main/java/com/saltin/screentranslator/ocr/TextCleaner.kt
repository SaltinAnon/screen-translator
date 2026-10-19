package com.saltin.screentranslator.ocr

/**
 * OCR 结果清洗器（混合架构的第一半：只把「干净的短文本」送出去）。
 *
 * 过滤掉：纯数字/纯符号、URL、长度不足、与目标语言同语言的块、重复块。
 * 同时按阅读顺序（上→下、左→右）排序，让云端 LLM 拿到连贯上下文。
 */
object TextCleaner {

    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\uFEFF]")
    private val WHITESPACE = Regex("[\\u00A0\\u2000-\\u200A\\u2028\\u2029\\u3000\\s]+")
    private val ONLY_SYMBOLS_OR_DIGITS = Regex("^[\\p{P}\\p{S}\\d\\s]+$")
    private val ONLY_URL = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)

    data class Config(
        val minLength: Int = 2,
        val maxChars: Int = 1400,
        val maxBlocks: Int = 40,
        val targetLanguage: String = "zh",
        /** 用户声明的源语言。非 auto 且与目标语言文字系统不同时，不再做「已是目标语言」猜测 */
        val sourceLanguage: String = "auto",
    )

    data class Result(
        val blocks: List<TextBlock>,
        val dropped: Int,
        val truncated: Boolean,
    ) {
        val totalChars: Int get() = blocks.sumOf { it.text.length }
    }

    fun clean(raw: List<TextBlock>, config: Config): Result {
        val ordered = raw.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val seen = HashSet<String>(ordered.size)
        val kept = ArrayList<TextBlock>(ordered.size)
        var dropped = 0
        var chars = 0
        var truncated = false

        for (block in ordered) {
            val text = normalize(block.text)
            if (text.length < config.minLength) {
                dropped++
                continue
            }
            if (ONLY_SYMBOLS_OR_DIGITS.matches(text) || ONLY_URL.matches(text)) {
                dropped++
                continue
            }
            if (alreadyInTargetLanguage(text, config.targetLanguage, config.sourceLanguage)) {
                dropped++
                continue
            }
            if (!seen.add(text.lowercase())) {
                dropped++
                continue
            }
            if (kept.size >= config.maxBlocks || chars + text.length > config.maxChars) {
                truncated = true
                break
            }
            kept += block.copy(text = text)
            chars += text.length
        }
        return Result(kept, dropped, truncated)
    }

    /** 去掉零宽字符、控制符，把各种空白折叠成单个半角空格 */
    fun normalize(input: String): String =
        WHITESPACE.replace(CONTROL.replace(input, ""), " ").trim()

    /**
     * 判断文本是否「已经是目标语言」——是的话就没必要翻译，
     * 这是混合架构省流量的第二道闸门。
     *
     * ★ 这里曾经有个严重的误杀：目标语言是中文时，日语行只要含 2 个汉字就算「已经是中文」
     * 而被直接过滤掉。日语里汉字占比很高，结果日译中时大量行根本不进翻译、译文里一片空缺，
     * 看起来就像「识别不到文字」。
     *
     * 现在的规则：
     * 1. 用户**明确声明**了源语言（非 auto），且源语言和目标语言不是同一种文字系统，
     *    就完全不做这个猜测 —— 用户都说了这是日语，我们没资格替他判成中文；
     * 2. 只有在「源语言未知」或「源/目标文字系统相近」时才用字符统计兜底，
     *    并且中文判定必须排除假名与谚文。
     */
    fun alreadyInTargetLanguage(
        text: String,
        targetLanguage: String,
        sourceLanguage: String = "auto",
    ): Boolean {
        val target = targetLanguage.lowercase().substringBefore('-')
        val source = sourceLanguage.lowercase().substringBefore('-')

        if (source != "auto" && source != target) {
            // 用户声明了源语言且与目标不同 → 全部交给翻译，不做同语言过滤
            return false
        }

        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val kana = text.count { it.code in 0x3040..0x30FF }
        val hangul = text.count { it.code in 0xAC00..0xD7AF }
        val latin = text.count { it.isLetter() && it.code < 0x0250 }
        val cyrillic = text.count { it.code in 0x0400..0x04FF }

        return when (target) {
            // 假名 / 谚文 / 西里尔文出现就不是中文
            "zh" -> cjk >= 2 && cjk >= latin && kana == 0 && hangul == 0 && cyrillic == 0
            "ja" -> kana >= 1 || (cjk >= 2 && latin == 0 && hangul == 0)
            "ko" -> hangul >= 1
            "en" -> latin >= 2 && cjk == 0 && kana == 0 && hangul == 0 && cyrillic == 0
            "ru" -> cyrillic >= 2
            else -> false
        }
    }
}
