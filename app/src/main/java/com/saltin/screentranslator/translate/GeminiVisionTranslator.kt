package com.saltin.screentranslator.translate

import android.graphics.Bitmap
import android.util.Log
import com.saltin.screentranslator.ocr.TextBlock
import com.saltin.screentranslator.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Google Gemini 协议（`generateContent`）。
 *
 * 三种用法共用同一个类：
 * - 混合模式：[translateFrame] 且 hints 非空 —— 传图 + OCR 文本，模型只回译文
 * - 纯多模态：[translateFrame] 且 hints 为空 —— 模型自己定位并返回归一化坐标
 * - 纯文本：[translateBlocks] —— 不传图，任何模型都能用，最省流量
 *
 * 2.5 系列默认会先「思考」再回答，思考 Token 从同一个额度里扣，既慢又容易把
 * JSON 截断。所以默认带 `thinkingBudget = 0` 关掉它；`gemini-2.5-pro` 这类不允许
 * 关思考的模型会返回 400，此时自动去掉该字段重试一次。
 */
class GeminiVisionTranslator(private val config: LlmConfig) : TranslationEngine {

    companion object {
        private const val TAG = "GeminiEngine"
        private const val MAX_OUTPUT_TOKENS = 4096
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override val id: String = "gemini"
    override val displayName: String = "Gemini"
    override val requiresFrameBitmap: Boolean = true

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ------------------------------------------------------------ 多模态

    override suspend fun translateFrame(
        frame: Bitmap,
        hintBlocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
        fallbackHints: List<TextBlock>,
    ): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val cfg = config
        cfg.describeMissing()?.let { throw TranslationException(it) }

        val base64 = ImageUtils.toJpegBase64(frame, cfg.uploadMaxSide, cfg.jpegQuality)
        val hints = hintBlocks.takeIf { it.isNotEmpty() }

        val text = request(cfg, partsOf(base64, LlmPrompt.build(hints, sourceLanguage, targetLanguage)))

        val direct = LlmResponseParser.parseOrNull(text, hints, frame.width, frame.height)
        if (direct.isNotEmpty()) {
            // 内容体检不合格（原样抄回 / 翻成别的语言）时，换加强版问法再问一次
            return@withContext TranslationQualityGuard.rescue(
                current = direct,
                targetLanguage = targetLanguage,
                ask = { strict ->
                    request(
                        cfg,
                        partsOf(
                            base64,
                            LlmPrompt.build(hints, sourceLanguage, targetLanguage, strict = strict),
                        ),
                    )
                },
                parse = { raw ->
                    LlmResponseParser.parseOrNull(raw, hints, frame.width, frame.height)
                },
            )
        }

        // 模型没肯给坐标时的兜底：换问法，用端侧 OCR 的行当锚点
        if (hints == null && fallbackHints.isNotEmpty()) {
            val retry = request(
                cfg,
                partsOf(base64, LlmPrompt.build(fallbackHints, sourceLanguage, targetLanguage)),
            )
            val rescued = LlmResponseParser.parseOrNull(retry, fallbackHints, frame.width, frame.height)
            if (rescued.isNotEmpty()) return@withContext rescued
            throw TranslationException(LlmResponseParser.describeFailure(retry, fallbackHints))
        }

        throw TranslationException(LlmResponseParser.describeFailure(text, hints ?: fallbackHints))
    }

    private fun partsOf(base64: String, prompt: String): JSONArray = JSONArray()
        .put(JSONObject().put("text", prompt))
        .put(
            JSONObject().put(
                "inline_data",
                JSONObject().put("mime_type", "image/jpeg").put("data", base64),
            ),
        )

    // ------------------------------------------------------------ 纯文本

    override suspend fun translateBlocks(
        blocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val cfg = config
        cfg.describeMissing()?.let { throw TranslationException(it) }
        if (blocks.isEmpty()) return@withContext emptyList()

        val parts = textParts(LlmPrompt.buildTextOnly(sourceLanguage, targetLanguage), blocks)
        val text = request(cfg, parts)
        // 没有图片可定位，坐标只能回填 OCR 的结果
        val parsed = LlmResponseParser.parse(text, blocks, 0, 0)

        // 体检：模型原样抄回、或翻成了别的语言时，换加强版问法再问一次
        TranslationQualityGuard.rescue(
            current = parsed,
            targetLanguage = targetLanguage,
            ask = { strict ->
                request(
                    cfg,
                    textParts(
                        LlmPrompt.buildTextOnly(sourceLanguage, targetLanguage, strict = strict),
                        blocks,
                    ),
                )
            },
            parse = { raw -> LlmResponseParser.parseOrNull(raw, blocks, 0, 0) },
        )
    }

    /** 纯文本请求的 parts：只有一段文字，没有图 */
    private fun textParts(header: String, blocks: List<TextBlock>): JSONArray {
        val prompt = buildString {
            append(header)
            blocks.forEachIndexed { index, block -> appendLine("$index: ${block.text}") }
        }
        return JSONArray().put(JSONObject().put("text", prompt))
    }

    // ------------------------------------------------------------ 网络

    private fun buildBody(cfg: LlmConfig, parts: JSONArray): JSONObject {
        val generation = JSONObject()
            .put("temperature", cfg.temperature)
            .put("topP", 0.9)
            .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            .put("responseMimeType", "application/json")

        if (cfg.disableThinking) {
            generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", generation)

        LlmRequestExtras.apply(body, cfg)
        return body
    }

    private fun request(cfg: LlmConfig, parts: JSONArray): String {
        val body = buildBody(cfg, parts)
        var note: String? = null

        var (code, payload) = execute(cfg, body.toString())

        // 2.5 Pro 之类不允许关闭思考，会报 thinking budget 相关 400 —— 去掉重试
        if (code == 400 && payload.contains("thinking", ignoreCase = true)) {
            body.optJSONObject("generationConfig")?.let { generation ->
                if (generation.has("thinkingConfig")) {
                    generation.remove("thinkingConfig")
                    note = "已自动去掉 thinkingConfig 重试（该模型不允许关闭思考过程）"
                    val retry = execute(cfg, body.toString())
                    code = retry.first
                    payload = retry.second
                }
            }
        }

        LlmExchangeLog.record(
            label = "${cfg.provider.label} / ${cfg.model}",
            url = "${cfg.normalizedBaseUrl}/models/${cfg.model}:generateContent",
            request = body.toString(),
            response = payload,
            httpCode = code,
            note = note,
            apiKey = cfg.apiKey,
        )

        if (code !in 200..299) {
            throw TranslationException(
                readableHttpError(code, sanitizeForDisplay(payload, cfg.apiKey)),
            )
        }
        return extractText(payload)
    }

    private fun execute(cfg: LlmConfig, payload: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("${cfg.normalizedBaseUrl}/models/${cfg.model}:generateContent")
            .addHeader("x-goog-api-key", cfg.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        return http.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }

    private fun extractText(payload: String): String {
        val root = JSONObject(payload)

        root.optJSONObject("promptFeedback")?.optString("blockReason")
            ?.takeIf { it.isNotBlank() }
            ?.let { throw TranslationException("模型拒绝处理该内容（$it）") }

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw TranslationException("模型未返回候选结果")
        }
        val first = candidates.optJSONObject(0)
            ?: throw TranslationException("返回结构异常")

        val finishReason = first.optString("finishReason").orEmpty()
        val parts = first.optJSONObject("content")?.optJSONArray("parts")
            ?: throw TranslationException(
                "返回内容为空（finishReason=$finishReason）。常见原因：" +
                    "① 输出被 maxOutputTokens 截断；② 该模型不支持看图；③ 内容被安全策略拦截。",
            )

        val builder = StringBuilder()
        for (i in 0 until parts.length()) {
            builder.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        if (builder.isBlank()) {
            throw TranslationException("返回空文本（finishReason=$finishReason）")
        }
        if (finishReason.equals("MAX_TOKENS", ignoreCase = true)) {
            throw TranslationException(
                "模型输出被 maxOutputTokens 截断了（finishReason=MAX_TOKENS），JSON 不完整。" +
                    "请把「单帧最多翻译行数」调小。已返回的开头：${builder.take(160)}",
            )
        }
        return builder.toString()
    }

    override fun close() {
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
        Log.d(TAG, "Gemini 引擎已释放")
    }
}
