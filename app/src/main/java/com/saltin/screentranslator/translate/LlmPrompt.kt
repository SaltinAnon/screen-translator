package com.saltin.screentranslator.translate

import android.graphics.Rect
import com.saltin.screentranslator.core.SupportedLanguages
import com.saltin.screentranslator.ocr.TextBlock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 一次云端调用的全部配置。三家协议共用，避免各写一份。
 */
data class LlmConfig(
    val provider: LlmProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    /** 送云端前把图缩到该边长，平衡识别率与流量 */
    val uploadMaxSide: Int = 1024,
    val jpegQuality: Int = 72,
    val temperature: Double = 0.15,
    /** 是否带上 `response_format` 强制 JSON（少数网关不认，会自动去掉重试） */
    val forceJsonMode: Boolean = true,
    /** 是否关闭思考过程（Qwen 靠 enable_thinking / Gemini 靠 thinkingBudget） */
    val disableThinking: Boolean = true,
    /** 用户自定义的附加请求参数（JSON 对象字符串），优先级最高 */
    val extraRequestJson: String = "",
) {
    val normalizedBaseUrl: String get() = LlmProviders.normalizeBaseUrl(baseUrl)

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && normalizedBaseUrl.isNotBlank() && model.isNotBlank()

    /** 缺什么，用一句人话说明，直接展示给用户 */
    fun describeMissing(): String? = when {
        apiKey.isBlank() -> "还没有填写 ${provider.label} 的 API Key"
        normalizedBaseUrl.isBlank() -> "还没有填写接口地址（Base URL）"
        model.isBlank() -> "还没有填写模型名"
        else -> null
    }
}

/**
 * 请求体的附加字段合并。
 *
 * 两个来源：服务商自带的必需参数（如百炼的 `enable_thinking`）与用户手填的参数。
 * 用户手填的优先级更高 —— 万一服务商预设反而碍事，用户能覆盖掉。
 */
object LlmRequestExtras {

    fun apply(body: JSONObject, config: LlmConfig) {
        merge(body, config.provider.extraBodyJson)
        merge(body, config.extraRequestJson)
    }

    /** 用户附加参数写错（不是合法 JSON）时给出人话提示，而不是静默忽略 */
    fun validate(raw: String): String? {
        if (raw.isBlank()) return null
        val parsed = runCatching { JSONObject(raw.trim()) }.getOrNull()
            ?: return "附加请求参数必须是合法的 JSON 对象，例如 {\"enable_thinking\": false}"
        return if (parsed.length() == 0) "附加请求参数是空对象，等于没填" else null
    }

    private fun merge(body: JSONObject, raw: String) {
        if (raw.isBlank()) return
        val extra = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return
        val keys = extra.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            body.put(key, extra.opt(key))
        }
    }

    /** 从请求体里移除某个键，返回是否真的移除了（用于网关不认某个字段时重试） */
    fun drop(body: JSONObject, key: String): Boolean {
        if (!body.has(key)) return false
        body.remove(key)
        return true
    }
}

/**
 * 三家协议共用的提示词与响应解析。
 *
 * 提示词写成强约束 + 明确 JSON 契约，配合容错解析，兼容那些不支持 json mode 的服务商。
 */
object LlmPrompt {

    /**
     * @param hints 非空 = 混合模式（模型只回译文，坐标复用 OCR 结果）
     *              为空 = 纯多模态（模型自己给出归一化坐标）
     * @param strict 上一次的回答体检没过（原样抄回原文 / 翻成了另一种外文）时置 true，
     *               把「必须输出什么语言」讲得更死再问一遍 —— 见 [TranslationVerifier]
     */
    fun build(
        hints: List<TextBlock>?,
        sourceLanguage: String,
        targetLanguage: String,
        strict: Boolean = false,
    ): String {
        val targetLabel = SupportedLanguages.label(targetLanguage)
        val sourceLabel =
            if (sourceLanguage == "auto") "自动判断" else SupportedLanguages.label(sourceLanguage)

        return if (hints != null) {
            buildString {
                appendLine("你是手机屏幕实时翻译引擎。译文会**原地覆盖**在原文位置显示，所以必须简短。")
                appendLine()
                appendLine("【输入】一张当前手机屏幕截图，以及 OCR 已识别出的文本行（带编号）。")
                appendLine("【源语言】$sourceLabel")
                appendLine("【目标语言】$targetLabel")
                appendLine()
                appendLine("【硬性要求】")
                appendLine("1. 只输出 JSON，不要 markdown 代码块，不要任何解释文字。")
                appendLine("2. 输出格式严格为：{\"blocks\":[{\"i\":0,\"t\":\"译文\"},{\"i\":1,\"t\":\"译文\"}]}")
                appendLine("3. 必须覆盖输入中的**每一个编号**，一个都不能少；i 必须与输入编号严格对应，且从 0 开始。")
                appendLine("4. 结合截图语境选择译法：游戏界面用游戏术语，系统设置用系统术语，聊天用口语。")
                appendLine("5. 译文长度尽量接近原文，便于覆盖显示；不要加「翻译：」之类前缀。")
                appendLine("6. 数字、单位、品牌名、代码、变量名、快捷键、ID 保持原样不翻译。")
                appendLine("7. 如果某行已经是目标语言，原样返回。")
                appendLine()
                appendLine("【OCR 文本行】")
                hints.forEachIndexed { index, block ->
                    appendLine("$index: ${block.text}")
                }
                if (strict) append(strictSuffix(targetLanguage))
            }
        } else {
            buildString {
                appendLine("你是手机屏幕实时翻译引擎。请直接阅读这张手机屏幕截图，定位其中的文字并就地翻译。")
                appendLine()
                appendLine("【源语言】$sourceLabel")
                appendLine("【目标语言】$targetLabel")
                appendLine()
                appendLine("【硬性要求】")
                appendLine("1. 只输出 JSON，不要 markdown 代码块，不要任何解释文字。")
                appendLine("2. 输出格式严格为：{\"blocks\":[{\"o\":\"原文\",\"t\":\"译文\",\"x\":0.12,\"y\":0.34,\"w\":0.5,\"h\":0.04}]}")
                appendLine("3. 坐标 x/y/w/h 必须是**相对整张图的归一化值**，取值 0~1：(x,y) 是文本框左上角，(w,h) 是宽高。")
                appendLine("4. 只用**一个**文本框覆盖一整行原文，不要按词拆分，不要重叠。")
                appendLine("5. 译文要简短，能塞进原文本框；数字、单位、品牌名、代码、快捷键保持原样。")
                appendLine("6. 按从上到下、从左到右的顺序输出。")
                if (strict) append(strictSuffix(targetLanguage))
            }
        }
    }

    /** 纯文本模式的提示词：没有任何图片，任何模型都能用 */
    fun buildTextOnly(
        sourceLanguage: String,
        targetLanguage: String,
        strict: Boolean = false,
    ): String {
        val targetLabel = SupportedLanguages.label(targetLanguage)
        val sourceLabel =
            if (sourceLanguage == "auto") "自动判断" else SupportedLanguages.label(sourceLanguage)
        return buildString {
            appendLine("你是手机屏幕实时翻译引擎，只做翻译，不做解释。")
            appendLine("【源语言】$sourceLabel")
            appendLine("【目标语言】$targetLabel")
            appendLine()
            appendLine("【硬性要求】")
            appendLine("1. 只输出 JSON，不要 markdown 代码块，不要任何思考过程或解释文字。")
            appendLine("2. 输出格式严格为：{\"blocks\":[{\"i\":0,\"t\":\"译文\"}]}")
            appendLine("3. 必须覆盖输入中的每一个编号，i 与输入编号严格对应，从 0 开始。")
            appendLine("4. 译文要短，便于原地覆盖显示；不要加任何前缀。")
            appendLine("5. 数字、单位、品牌名、代码、快捷键保持原样。")
            appendLine("6. 若某行已经是目标语言，原样返回。")
            if (strict) append(strictSuffix(targetLanguage))
            appendLine()
            appendLine("【待翻译文本行】")
        }
    }

    /**
     * 「把输出语言说死」的补充约束，接在上一次不合格的回答之后再问一遍。
     *
     * 实测里两种翻车最常见：①模型把外文**原样抄回来**（上面那句
     * 「已经是目标语言就原样返回」被过度执行）；②该出简体中文，却吐了日文汉字、
     * 韩文或越南语。这时把「必须输出什么语言」讲得更狠、并明确禁止照抄，命中率明显提升。
     *
     * 注意最后一条：要主动去**抵消**上面的第 7/6 条，否则模型会拿它当借口继续照抄。
     */
    private fun strictSuffix(targetLanguage: String): String {
        val label = SupportedLanguages.label(targetLanguage)
        return buildString {
            appendLine()
            appendLine("【关于输出语言，再强调一次（上一次的结果不合格）】")
            appendLine("· 每一条译文都必须是$label。除了数字、代码、品牌名、URL，不得出现其他语言的文字。")
            appendLine("· **严禁原样照抄原文**：即使你不确定译法，也必须给出$label 的译文。")
            appendLine("· 上面「已经是$label 的行原样返回」只适用于真的已经是$label 的行，" +
                "不要拿它当整屏照抄的借口。")
        }
    }
}

/**
 * 响应文本 → [TranslatedBlock]。三家协议的模型输出都走这里。
 *
 * ## 为什么要写得这么啰嗦
 *
 * 旧版本只认 `{"blocks":[{"i":0,"t":"..."}]}` 这一种形状，`i` 一读不到就把整条丢掉，
 * 结果是模型明明返回了译文，用户却看到「没有可用的译文块」。
 *
 * 实际会遇到的各种形状（都真实存在于各家模型/网关里）：
 * - `{"blocks": [{"i":0,"t":"…"}, …]}`              标准
 * - `{"blocks": [{"index":0,"translation":"…"}]}`    换了键名
 * - `{"blocks": [{"t":"…"}, {"t":"…"}]}`             干脆没有编号
 * - `{"blocks": ["译文1", "译文2"]}`                 纯字符串数组
 * - `{"blocks": {"0":"译文1","1":"译文2"}}`          按编号做对象
 * - `{"result": "{\"blocks\":[…]}"}`                 把 JSON 当字符串塞进字段
 * - ```` ```json … ``` ```` 外面裹代码块 / 前面带客套话
 * - 直接就是 `[{...}, {...}]`
 *
 * 所以这里的策略是：**先尽量把内容捞出来，再按「编号 → 位置」两级回退定位坐标**。
 * 真的一个字都捞不到时，把模型的原始返回开头一并写进错误信息 —— 让问题可诊断，
 * 而不是给用户一句无从下手的「失败」。
 */
object LlmResponseParser {

    /** 可能的译文数组字段名 */
    private val ARRAY_KEYS = listOf(
        "blocks", "items", "result", "results", "translations", "translation",
        "lines", "list", "data", "output", "segments", "entries",
    )

    /** 形如 {"result": "…"} 的单值包裹字段名 */
    private val WRAP_KEYS = listOf(
        "result", "data", "content", "output", "response", "message", "payload", "body",
    )

    /** 编号字段的别名，按可能性排序 */
    private val INDEX_KEYS = listOf(
        "i", "index", "idx", "id", "n", "no", "num", "order", "position", "line", "seq", "k",
    )

    /** 译文字段的别名 */
    private val TEXT_KEYS = listOf(
        "t", "translated", "translated_text", "translation", "target", "target_text",
        "text", "content", "out", "output", "result", "value", "dst",
        // 国产模型偶尔直接用中文键名
        "译文", "翻译", "目标文本",
    )

    /** 原文字段的别名（纯多模态模式下模型自己给） */
    private val ORIGINAL_KEYS = listOf(
        "o", "original", "original_text", "source", "source_text", "src",
        "原文", "源文本",
    )

    fun parse(
        rawText: String,
        hints: List<TextBlock>?,
        frameWidth: Int,
        frameHeight: Int,
    ): List<TranslatedBlock> {
        val payload = extractAny(rawText)
            ?: throw TranslationException(
                "模型没有返回可解析的 JSON。原始返回开头：${rawText.trim().take(240)}",
            )

        val output = collect(payload, hints, frameWidth, frameHeight)
        if (output.isEmpty()) throw TranslationException(describeFailure(rawText, hints))
        return output
    }

    /**
     * 与 [parse] 相同，但**一个块都没解析出来时返回空列表而不是抛错**。
     *
     * 多模态路径需要它：模型可能只是「没按带坐标的格式回」，此时应该再试一次别的问法，
     * 而不是直接把这一帧判死。
     */
    fun parseOrNull(
        rawText: String,
        hints: List<TextBlock>?,
        frameWidth: Int,
        frameHeight: Int,
    ): List<TranslatedBlock> {
        val payload = extractAny(rawText) ?: return emptyList()
        return collect(payload, hints, frameWidth, frameHeight)
    }

    /** 一个块都拿不到时的错误文案：把「提交了几行」和「模型原样返回了什么」都带上，方便排查 */
    fun describeFailure(rawText: String, hints: List<TextBlock>?): String {
        val head = rawText.trim().take(240)
        val hintNote = if (!hints.isNullOrEmpty()) "本次共提交 ${hints.size} 行；" else ""
        return if (head.isEmpty()) {
            "模型返回的正文是空的，一个译文块都没拿到。请打开设置页最下方的「接口诊断」查看原始返回。"
        } else {
            "模型返回里没有可用的译文块。${hintNote}原始返回开头：$head"
        }
    }

    private fun collect(
        payload: Any?,
        hints: List<TextBlock>?,
        frameWidth: Int,
        frameHeight: Int,
    ): List<TranslatedBlock> {
        val items = expand(blocksOf(payload), hints)
        val output = ArrayList<TranslatedBlock>(items.size)
        // 位置回退用的计数器：只统计**真的取到译文**的元素。
        // 如果数组里混了空对象，用元素下标当位置会对不齐，用这个计数器不会。
        var consumed = 0

        for (value in items) {
            val explicitIndex = indexOf(value)
            // 位置回退：先把「如果接受这一条，它应该是第几个」算出来，再决定要不要接受。
            // consumed 只在真的取到译文后才前进，否则数组里混入空对象会让位置整体错开。
            val slot = consumed
            val hint = when {
                hints == null -> null
                explicitIndex >= 0 && explicitIndex < hints.size -> hints[explicitIndex]
                slot < hints.size -> hints[slot]
                else -> null
            }

            val translated = textOf(value) ?: guessByOriginal(value, hint) ?: continue
            consumed++

            val original = hint?.text
                ?: (value as? JSONObject)?.let { obj ->
                    ORIGINAL_KEYS.firstNotNullOfOrNull { key ->
                        obj.optString(key).takeIf { it.isNotBlank() }
                    }
                }
                ?: ""

            val box = when {
                hint != null -> Rect(hint.boundingBox)
                value is JSONObject && value.has("x") -> normalizedToRect(value, frameWidth, frameHeight)
                else -> null
            } ?: continue

            if (box.width() <= 1 || box.height() <= 1) continue
            output += TranslatedBlock(original.trim(), translated, box)
        }

        return output.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    }

    /**
     * `{"blocks":["第一句\n第二句\n第三句"]}` 是极常见的形状：
     * 模型把整屏译文塞进**一个字符串**里，用换行分隔。此时按换行拆开，
     * 位置正好与提交的行一一对应 —— 否则整屏只会显示出一块译文。
     *
     * 只在「拆出来的条数正好等于提交行数」时才采用，避免把译文里
     * 本来就有的换行误当成行分隔符。
     */
    private fun expand(items: List<Any>, hints: List<TextBlock>?): List<Any> {
        if (hints == null || hints.size < 2 || items.size != 1) return items
        val single = textOf(items.first()) ?: return items
        if (!single.contains('\n')) return items
        val parts = single.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size == hints.size) parts else items
    }

    // ------------------------------------------------------------ JSON 抽取

    /**
     * 从模型返回里尽量抠出一个 JSON 对象或数组。
     * 顺序：剥代码块 → 直接解析 → 取第一个 `{`/`[` 到最后一个 `}`/`]`。
     */
    private fun extractAny(raw: String): Any? {
        val text = stripCodeFence(raw).trim()
        if (text.isEmpty()) return null

        parseLenient(text)?.let { return it }

        val candidates = ArrayList<Pair<Int, String>>()
        val objStart = text.indexOf('{')
        val objEnd = text.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            candidates += objStart to text.substring(objStart, objEnd + 1)
        }
        val arrStart = text.indexOf('[')
        val arrEnd = text.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) {
            candidates += arrStart to text.substring(arrStart, arrEnd + 1)
        }
        candidates.sortBy { it.first }
        for ((_, candidate) in candidates) {
            parseLenient(candidate)?.let { return it }
        }
        return null
    }

    private fun parseLenient(text: String): Any? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> null
            }
        }.getOrNull()
    }

    private fun stripCodeFence(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("```")) return text
        val body = text.removePrefix("```json").removePrefix("```").trim()
        val end = body.lastIndexOf("```")
        return if (end >= 0) body.substring(0, end).trim() else body
    }

    // ------------------------------------------------------------ 结构归一

    private fun blocksOf(payload: Any?, depth: Int = 0): List<Any> {
        if (payload == null || depth > 3) return emptyList()

        when (payload) {
            is JSONArray -> return (0 until payload.length()).mapNotNull { payload.opt(it) }

            is JSONObject -> {
                for (key in ARRAY_KEYS) {
                    when (val value = payload.opt(key)) {
                        is JSONArray ->
                            return (0 until value.length()).mapNotNull { value.opt(it) }

                        is JSONObject ->
                            numericEntries(value).takeIf { it.isNotEmpty() }?.let { return it }

                        is String -> {
                            val nested = parseLenient(value)
                            val result = blocksOf(nested, depth + 1)
                            if (result.isNotEmpty()) return result
                        }

                        else -> Unit
                    }
                }

                // 单值包裹：{"result": "{...}"} / {"data": {...}}
                for (key in WRAP_KEYS) {
                    val value = payload.opt(key) ?: continue
                    val inner = when {
                        value is JSONObject -> value
                        value is String -> parseLenient(value)
                        else -> null
                    } ?: continue
                    val result = blocksOf(inner, depth + 1)
                    if (result.isNotEmpty()) return result
                }

                // 兜底：整个对象本身就是「编号 → 译文」的映射
                return numericEntries(payload)
            }

            else -> return emptyList()
        }
    }

    /** 取对象里所有「键能解析成整数」的成员，按编号升序返回 */
    private fun numericEntries(obj: JSONObject): List<Any> {
        val pairs = ArrayList<Pair<Int, Any>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val index = key.trim().toIntOrNull() ?: continue
            val value = obj.opt(key) ?: continue
            pairs += index to value
        }
        if (pairs.isEmpty()) return emptyList()
        pairs.sortBy { it.first }
        return pairs.map { it.second }
    }

    /** 从数组元素里取译文。元素可能直接是字符串，也可能是对象。 */
    private fun textOf(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() }

        is JSONObject -> TEXT_KEYS.firstNotNullOfOrNull { key ->
            when (val raw = value.opt(key)) {
                is String -> raw.trim().takeIf { it.isNotEmpty() }
                is Number -> raw.toString()
                else -> null
            }
        }

        else -> null
    }

    /**
     * 模型自造键名时的最后一道保险。
     *
     * 真实案例：模型返回 `{"原文":"こんにちは","译文":"你好"}` —— 键名是中文，
     * 别名表里那十几个英文键一个都不命中，整条被丢弃。
     *
     * 这里的判据不需要知道键名：**对象里恰好有两个字符串、且其中一个正好等于原文**，
     * 那另一个就是译文。条件足够严（必须正好两个、必须有一个对得上原文），
     * 不会把 `{"text":"...","note":"..."}` 这种误判成译文。
     */
    private fun guessByOriginal(value: Any?, hint: TextBlock?): String? {
        val obj = value as? JSONObject ?: return null
        val original = hint?.text ?: return null

        val strings = ArrayList<String>(4)
        val keys = obj.keys()
        while (keys.hasNext()) {
            when (val raw = obj.opt(keys.next())) {
                is String -> raw.trim().takeIf { it.isNotEmpty() }?.let { strings += it }
                is Number -> strings += raw.toString()
                else -> Unit
            }
        }
        if (strings.size != 2) return null
        return when {
            strings[0] == original -> strings[1]
            strings[1] == original -> strings[0]
            else -> null
        }
    }

    private fun indexOf(value: Any?): Int {        val obj = value as? JSONObject ?: return -1
        return INDEX_KEYS.firstNotNullOfOrNull { key ->
            when (val raw = obj.opt(key)) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
        } ?: -1
    }

    private fun normalizedToRect(item: JSONObject, frameWidth: Int, frameHeight: Int): Rect? {
        val x = item.optDouble("x", Double.NaN)
        val y = item.optDouble("y", Double.NaN)
        val w = item.optDouble("w", Double.NaN)
        val h = item.optDouble("h", Double.NaN)
        if (x.isNaN() || y.isNaN() || w.isNaN() || h.isNaN()) return null

        val left = (x.coerceIn(0.0, 1.0) * frameWidth).roundToInt()
        val top = (y.coerceIn(0.0, 1.0) * frameHeight).roundToInt()
        val right = ((x + w).coerceIn(0.0, 1.0) * frameWidth).roundToInt()
        val bottom = ((y + h).coerceIn(0.0, 1.0) * frameHeight).roundToInt()
        return Rect(left, top, right, bottom)
    }
}

/** 错误回显里抹掉 API Key，避免泄漏进日志/界面 */
internal fun sanitizeForDisplay(payload: String, apiKey: String, limit: Int = 280): String {
    val cleaned = if (apiKey.isNotEmpty()) payload.replace(apiKey, "***") else payload
    return cleaned.take(limit)
}

/**
 * 一条报错看起来像不像「内容被服务商拒绝」。
 *
 * 有个容易踩的坑：**HTTP 402 在字面上是「Payment Required」，但国内网关普遍拿它表示
 * 内容安全拦截**（智谱、百度、阿里等都有这种用法），而不是真的欠费。所以不能只按
 * HTTP 语义给用户提示「额度不足」，那样他会去充值，而真正的问题在图片内容上。
 *
 * 判定结果**只影响提示文案，不参与控制流**：即使这里返回 false，上游照样会降级重试，
 * 宁可多说一句「原因未知」，也绝不漏掉降级机会。
 */
internal fun looksLikeContentRejection(message: String): Boolean {
    val lower = message.lowercase()
    return CONTENT_REJECTION_MARKERS.any { lower.contains(it) }
}

private val CONTENT_REJECTION_MARKERS = listOf(
    "402",
    "content_policy", "content policy", "contentpolicy", "content_filter",
    "safety", "sensitive", "moderation", "blocked", "block_reason", "blockreason",
    "prohibited", "nsfw", "r18", "explicit",
    "审核", "敏感", "违规", "不合规", "涉黄", "内容安全", "拒绝处理", "被拒绝",
)

/**
 * 混合模式「带图那一次」失败、但纯文本已经翻出来之后的提示语。
 *
 * 关键信息有三条：① 这次译文是好的、没受影响；② 失败的原因是什么；
 * ③ 怎么彻底绕开（关掉「混合模式下附带截图」）。
 */
internal fun visionFallbackNotice(reason: String): String {
    val head = if (looksLikeContentRejection(reason)) {
        "附带的截图被服务商拒绝了（多半是内容安全审核，比如被判定为成人内容）"
    } else {
        "附带截图这条路这次没走通"
    }
    return "$head，已自动改用**纯文字**翻译，这一屏的译文不受影响。" +
        "想彻底不再尝试带图，可在设置页关掉「混合模式下附带截图」。具体报错：$reason"
}

/** 从各家不同的错误结构里尽量抠出一句人话 */
internal fun readableHttpError(code: Int, payload: String): String {
    val message = runCatching {
        val json = JSONObject(payload)
        json.optJSONObject("error")?.let { err ->
            listOf("message", "msg", "detail").firstNotNullOfOrNull { key ->
                err.optString(key).takeIf { it.isNotBlank() }
            }
        } ?: json.optString("message").takeIf { it.isNotBlank() }
            ?: json.optString("msg").takeIf { it.isNotBlank() }
    }.getOrNull()

    val hint = when (code) {
        401, 403 -> "（API Key 无效或没有该模型的权限）"
        // ★ 402 在很多国内网关上不是「欠费」，而是**内容安全拦截** —— 提示里必须两种可能都说到，
        //   否则用户会去充值，而真正的问题在发出去的图片内容上。
        402 -> "（请求被服务商拒绝：多为内容安全审核拦截，也可能是额度不足）"
        404 -> "（接口地址或模型名不对）"
        413 -> "（请求体过大，多半是附带的截图太大）"
        429 -> "（触发限流或额度用尽）"
        in 500..599 -> "（服务端暂时不可用，稍后重试）"
        else -> ""
    }
    val advice = if (code == 400 && payload.contains("enable_thinking", ignoreCase = true)) {
        "（该模型是非流式调用必须关闭思考过程，可在设置页的「附加请求参数」里填 {\"enable_thinking\": false}）"
    } else {
        ""
    }
    return "HTTP $code$hint$advice ${message.orEmpty()}".trim()
}
