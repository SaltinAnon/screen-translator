package com.saltin.screentranslator.translate

/**
 * 最近一次云端交互的记录，用于「接口诊断」。
 *
 * 出问题时再让用户去抓包是不现实的。这里把**已经脱敏**的请求体与**原始**返回
 * 留在内存里，设置页直接展示、可一键复制 —— 用户把这段贴出来，问题基本就能定位到。
 *
 * 只保留最近一次：目的是排障，不是审计；保留多了反而不好找。
 */
object LlmExchangeLog {

    data class Entry(
        val at: Long,
        val label: String,
        val url: String,
        val request: String,
        val response: String,
        val httpCode: Int,
        val note: String?,
    ) {
        val httpLabel: String get() = if (httpCode == 0) "网络异常" else "HTTP $httpCode"
    }

    /** 展示时单段文本的上限，防止把几十 KB 的返回塞进 TextView */
    private const val DISPLAY_LIMIT = 6000

    /** 超过这个长度的字符串会被当作图片/二进制数据抹掉 */
    private const val OPAQUE_THRESHOLD = 400

    private val LONG_STRING = Regex("\"[A-Za-z0-9+/=\\\\n\\s]{$OPAQUE_THRESHOLD,}\"")

    @Volatile
    private var lastEntry: Entry? = null

    val last: Entry? get() = lastEntry

    fun record(
        label: String,
        url: String,
        request: String,
        response: String,
        httpCode: Int,
        note: String? = null,
        apiKey: String = "",
    ) {
        lastEntry = Entry(
            at = System.currentTimeMillis(),
            label = label,
            url = redact(url, apiKey),
            request = truncate(redact(request, apiKey)),
            response = truncate(redact(response, apiKey)),
            httpCode = httpCode,
            note = note,
        )
    }

    fun clear() {
        lastEntry = null
    }

    /** 把 base64 图片和内联的 API Key 换成占位符：日志是给用户看的，不能顺手泄露 Key */
    private fun redact(text: String, apiKey: String): String {
        var result = text
        if (apiKey.isNotBlank()) result = result.replace(apiKey, "***")
        return result.replace(LONG_STRING) { match ->
            "\"<已省略约 ${match.value.length / 1024}KB 的图片数据>\""
        }
    }

    private fun truncate(text: String): String =
        if (text.length <= DISPLAY_LIMIT) text else text.take(DISPLAY_LIMIT) + "\n…（后续已省略）"

    /** 供界面直接整段展示 */
    fun describe(): String {
        val entry = lastEntry ?: return "还没有发起过云端请求。先点「保存并测试」，或启动服务翻译一次。"
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(entry.at))
        return buildString {
            appendLine("时间：$time")
            appendLine("服务商 / 模型：${entry.label}")
            appendLine("接口地址：${entry.url}")
            appendLine("结果：${entry.httpLabel}")
            entry.note?.let { appendLine("说明：$it") }
            appendLine()
            appendLine("──── 请求体（图片数据已省略）────")
            appendLine(entry.request)
            appendLine()
            appendLine("──── 模型原始返回 ────")
            appendLine(entry.response)
        }
    }
}
