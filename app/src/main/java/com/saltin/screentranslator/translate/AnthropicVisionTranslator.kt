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
 * Anthropic Claude 协议（`POST /messages`）。
 *
 * 与 OpenAI 协议的两处差异：
 * - 鉴权走 `x-api-key` 头 + 必填 `anthropic-version`，不是 Bearer；
 * - 图片是独立的 `image` content block，用 `source.base64`，不能内联在文本里。
 */
class AnthropicVisionTranslator(private val config: LlmConfig) : TranslationEngine {

    companion object {
        private const val TAG = "ClaudeEngine"
        private const val API_VERSION = "2023-06-01"
        private const val MAX_TOKENS = 4096
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override val id: String = "anthropic"
    override val displayName: String = "Claude"
    override val requiresFrameBitmap: Boolean = true

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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

        val text = ask(cfg, base64, LlmPrompt.build(hints, sourceLanguage, targetLanguage))

        val direct = LlmResponseParser.parseOrNull(text, hints, frame.width, frame.height)
        if (direct.isNotEmpty()) {
            // 内容体检不合格（原样抄回 / 翻成别的语言）时，换加强版问法再问一次
            return@withContext TranslationQualityGuard.rescue(
                current = direct,
                targetLanguage = targetLanguage,
                ask = { strict ->
                    ask(
                        cfg,
                        base64,
                        LlmPrompt.build(hints, sourceLanguage, targetLanguage, strict = strict),
                    )
                },
                parse = { raw ->
                    LlmResponseParser.parseOrNull(raw, hints, frame.width, frame.height)
                },
            )
        }

        // 模型没肯给坐标时的兜底：换问法，用端侧 OCR 的行当锚点
        if (hints == null && fallbackHints.isNotEmpty()) {
            val retry = ask(cfg, base64, LlmPrompt.build(fallbackHints, sourceLanguage, targetLanguage))
            val rescued = LlmResponseParser.parseOrNull(retry, fallbackHints, frame.width, frame.height)
            if (rescued.isNotEmpty()) return@withContext rescued
            throw TranslationException(LlmResponseParser.describeFailure(retry, fallbackHints))
        }

        throw TranslationException(LlmResponseParser.describeFailure(text, hints ?: fallbackHints))
    }

    /** 一条「图 + 文」的消息 */
    private fun ask(cfg: LlmConfig, base64: String, prompt: String): String {
        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", base64),
                    ),
            )
            .put(JSONObject().put("type", "text").put("text", prompt))

        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", cfg.temperature)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
        LlmRequestExtras.apply(body, cfg)
        return call(cfg, body.toString())
    }

    override suspend fun translateBlocks(
        blocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val cfg = config
        cfg.describeMissing()?.let { throw TranslationException(it) }
        if (blocks.isEmpty()) return@withContext emptyList()

        val text = askTextOnly(cfg, LlmPrompt.buildTextOnly(sourceLanguage, targetLanguage), blocks)
        val parsed = LlmResponseParser.parse(text, blocks, 0, 0)

        // 体检：模型原样抄回、或翻成了别的语言时，换加强版问法再问一次
        TranslationQualityGuard.rescue(
            current = parsed,
            targetLanguage = targetLanguage,
            ask = { strict ->
                askTextOnly(
                    cfg,
                    LlmPrompt.buildTextOnly(sourceLanguage, targetLanguage, strict = strict),
                    blocks,
                )
            },
            parse = { raw -> LlmResponseParser.parseOrNull(raw, blocks, 0, 0) },
        )
    }

    /** 纯文本（无图）请求：把文本行按「编号: 内容」拼进提示词 */
    private fun askTextOnly(cfg: LlmConfig, header: String, blocks: List<TextBlock>): String {
        val prompt = buildString {
            append(header)
            blocks.forEachIndexed { index, block -> appendLine("$index: ${block.text}") }
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", MAX_TOKENS)
            .put("temperature", cfg.temperature)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", prompt)),
            )
        LlmRequestExtras.apply(body, cfg)
        return call(cfg, body.toString())
    }

    private fun call(cfg: LlmConfig, payload: String): String {
        val url = "${cfg.normalizedBaseUrl}/messages"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", cfg.apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            LlmExchangeLog.record(
                label = "${cfg.provider.label} / ${cfg.model}",
                url = url,
                request = payload,
                response = body,
                httpCode = response.code,
                apiKey = cfg.apiKey,
            )
            if (!response.isSuccessful) {
                throw TranslationException(
                    readableHttpError(response.code, sanitizeForDisplay(body, cfg.apiKey)),
                )
            }
            extractText(body, cfg.apiKey)
        }
    }

    private fun extractText(payload: String, apiKey: String): String {
        val root = JSONObject(payload)
        val content = root.optJSONArray("content")
            ?: throw TranslationException(
                "返回结构里没有 content：${sanitizeForDisplay(payload, apiKey, 200)}",
            )

        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.optString("type") == "text") builder.append(part.optString("text"))
        }
        val stop = root.optString("stop_reason").orEmpty()
        if (builder.isBlank()) {
            throw TranslationException("模型返回空文本（stop_reason=$stop）")
        }
        if (stop.equals("max_tokens", ignoreCase = true)) {
            throw TranslationException(
                "模型输出被 max_tokens 截断了（stop_reason=max_tokens），JSON 不完整。" +
                    "请把「单帧最多翻译行数」调小。已返回的开头：${builder.take(160)}",
            )
        }
        return builder.toString()
    }

    override fun close() {
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
        Log.d(TAG, "Claude 引擎已释放")
    }
}
