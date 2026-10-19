package com.saltin.screentranslator.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * 「保存并测试」按钮的后端。
 *
 * 两个测试：
 * - [testText] 发一次最小的纯文本请求，验证 Key / 地址 / 模型名 三件事是否都配对；
 * - [testVision] 现场画一张写着英文单词的小图发过去，请模型读出来 ——
 *   这才是真正验证「这个模型到底能不能看图」。
 *
 * ⚠️ 判成功的标准必须严格：**返回正文为空就算失败**。
 * 旧版本把空回复也显示成「连通正常」，结果用户以为配置没问题，
 * 实际是思考模型的思考过程吃掉了全部 token，一翻译就报错 —— 白折腾一轮。
 */
object LlmDiagnostics {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 探针词，画在测试图上，用来确认模型真的读到了像素而不是在瞎猜 */
    private const val VISION_WORD = "HELLO"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private const val TEXT_PROBE = "只回复两个字符：OK"
    private val VISION_PROBE =
        "这张图里有一个英文单词，请只回复那个单词本身，不要任何其他文字。"

    suspend fun testText(config: LlmConfig): String = withContext(Dispatchers.IO) {
        config.describeMissing()?.let { throw TranslationException(it) }
        LlmRequestExtras.validate(config.extraRequestJson)?.let { throw TranslationException(it) }

        val reply = when (config.provider.protocol) {
            LlmProtocol.GEMINI -> callGemini(config, TEXT_PROBE, null)
            LlmProtocol.OPENAI -> callOpenAi(config, TEXT_PROBE, null)
            LlmProtocol.ANTHROPIC -> callAnthropic(config, TEXT_PROBE, null)
        }
        assertNotBlank(reply, config)
        "连通正常 · ${config.provider.label} / ${config.model} · 模型回话：${reply.trim().take(40)}"
    }

    suspend fun testVision(config: LlmConfig): String = withContext(Dispatchers.IO) {
        config.describeMissing()?.let { throw TranslationException(it) }
        LlmRequestExtras.validate(config.extraRequestJson)?.let { throw TranslationException(it) }

        val base64 = ImageUtils.toJpegBase64(probeBitmap(), 256, 85)
        val reply = when (config.provider.protocol) {
            LlmProtocol.GEMINI -> callGemini(config, VISION_PROBE, base64)
            LlmProtocol.OPENAI -> callOpenAi(config, VISION_PROBE, base64)
            LlmProtocol.ANTHROPIC -> callAnthropic(config, VISION_PROBE, base64)
        }
        assertNotBlank(reply, config)

        if (!reply.contains(VISION_WORD, ignoreCase = true)) {
            throw TranslationException(
                "接口通了，但模型没读出测试图上的单词（它回复的是「${reply.trim().take(60)}」）。" +
                    "说明这个模型不能看图，请改用带 vision / vl 的模型，" +
                    "或在设置页关掉「混合模式下附带截图」走纯文字翻译。",
            )
        }
        "看图正常 · ${config.provider.label} / ${config.model} · 模型读出了「$VISION_WORD」"
    }

    /** 现场画一张写着 [VISION_WORD] 的小图 */
    private fun probeBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 56f
            isFakeBoldText = true
        }
        val width = paint.measureText(VISION_WORD)
        canvas.drawText(VISION_WORD, (320 - width) / 2f, 128 / 2f + 20f, paint)
        return bitmap
    }

    private fun assertNotBlank(reply: String, config: LlmConfig) {
        if (reply.isNotBlank()) return
        throw TranslationException(
            "接口能连通，但模型返回的正文是空的。最常见的原因：" +
                "① 这是思考模型而思考过程没关闭（打开设置页的「关闭思考过程」，" +
                "或在「附加请求参数」里填 {\"enable_thinking\": false}）；" +
                "② 服务商要求流式调用；③ 额度已用尽。" +
                "点下面的「接口诊断」可以看到完整返回。",
        )
    }

    // ------------------------------------------------------------ 三条协议

    private fun callGemini(cfg: LlmConfig, prompt: String, imageBase64: String?): String {
        val url = "${cfg.normalizedBaseUrl}/models/${cfg.model}:generateContent"

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (imageBase64 != null) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64),
                ),
            )
        }

        val generation = JSONObject()
            .put("temperature", 0.1)
            .put("maxOutputTokens", 512)
        if (cfg.disableThinking) {
            generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", generation)
        LlmRequestExtras.apply(body, cfg)

        val payload = post(
            url,
            body.toString(),
            mapOf("x-goog-api-key" to cfg.apiKey),
            cfg,
        )
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: throw TranslationException("返回不是合法 JSON：${payload.take(200)}")

        root.optJSONObject("promptFeedback")?.optString("blockReason")
            ?.takeIf { it.isNotBlank() }
            ?.let { throw TranslationException("模型拒绝处理该请求（$it）") }

        val builder = StringBuilder()
        val partsOut = root.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        if (partsOut != null) {
            for (i in 0 until partsOut.length()) {
                builder.append(partsOut.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
        return builder.toString()
    }

    private fun callOpenAi(cfg: LlmConfig, prompt: String, imageBase64: String?): String {
        val url = "${cfg.normalizedBaseUrl}/chat/completions"

        val content: Any = if (imageBase64 == null) {
            prompt
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject()
                                .put("url", "data:image/jpeg;base64,$imageBase64")
                                .put("detail", "low"),
                        ),
                )
        }

        val body = JSONObject()
            .put("model", cfg.model)
            .put("temperature", 0.1)
            // 给足额度：思考模型的思考过程也从这里扣，16 个 token 必然只能得到空正文
            .put("max_tokens", 512)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
        LlmRequestExtras.apply(body, cfg)

        val payload = post(
            url,
            body.toString(),
            mapOf("Authorization" to "Bearer ${cfg.apiKey}"),
            cfg,
        )
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: throw TranslationException("返回不是合法 JSON：${payload.take(200)}")

        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw TranslationException("返回结构里没有 choices：${payload.take(200)}")
        }
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw TranslationException("返回结构里没有 message")

        val direct = message.optString("content")
        if (direct.isNotBlank()) return direct

        val array = message.optJSONArray("content")
        if (array != null) {
            val builder = StringBuilder()
            for (i in 0 until array.length()) {
                builder.append(array.optJSONObject(i)?.optString("text").orEmpty())
            }
            if (builder.isNotBlank()) return builder.toString()
        }
        // 思考模式没关掉时的典型症状，这里直接把思考内容带出来，让用户看得见
        return message.optString("reasoning_content")
    }

    private fun callAnthropic(cfg: LlmConfig, prompt: String, imageBase64: String?): String {
        val url = "${cfg.normalizedBaseUrl}/messages"

        val content: Any = if (imageBase64 == null) {
            prompt
        } else {
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", imageBase64),
                        ),
                )
                .put(JSONObject().put("type", "text").put("text", prompt))
        }

        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", 512)
            .put("temperature", 0.1)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
        LlmRequestExtras.apply(body, cfg)

        val payload = post(
            url,
            body.toString(),
            mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01"),
            cfg,
        )
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: throw TranslationException("返回不是合法 JSON：${payload.take(200)}")

        val builder = StringBuilder()
        val parts = root.optJSONArray("content")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") builder.append(part.optString("text"))
            }
        }
        return builder.toString()
    }

    // ------------------------------------------------------------ 网络

    private fun post(
        url: String,
        payload: String,
        headers: Map<String, String>,
        cfg: LlmConfig,
    ): String {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA))
        headers.forEach { (key, value) -> builder.addHeader(key, value) }

        val response = http.newCall(builder.build()).execute()
        val body = response.use { it.body?.string().orEmpty() }
        val code = response.code

        LlmExchangeLog.record(
            label = "${cfg.provider.label} / ${cfg.model}",
            url = url,
            request = payload,
            response = body,
            httpCode = code,
            note = "连接测试",
            apiKey = cfg.apiKey,
        )

        if (code !in 200..299) {
            throw TranslationException(
                readableHttpError(code, sanitizeForDisplay(body, cfg.apiKey)),
            )
        }
        return body
    }
}
