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
 * OpenAI 兼容协议（`POST /chat/completions`）。
 *
 * 覆盖面最广的一层：OpenAI、DeepSeek、通义千问 compatible-mode、智谱、Kimi、硅基流动、
 * OpenRouter、Ollama、vLLM、LM Studio、One-API / New-API 全部走这里。
 *
 * 几个刻意的设计：
 *
 * - **服务商预设参数 + 用户自定义附加参数**都会合并进请求体（见 [LlmRequestExtras]）。
 *   典型例子是阿里云百炼：它的 OpenAI 兼容端点对 Qwen3 系列要求**非流式请求必须显式传
 *   `enable_thinking=false`**，不传就 400 或者把正文写进 `reasoning_content` 让 `content` 变空。
 * - **`response_format` 会带上但可自动回退**：不少自建/中转网关不认这个字段会直接 400，
 *   遇到时自动去掉重试一次，用户不必自己判断。
 * - **`max_tokens` 给到 4096**：思考模型会从同一个额度里扣思考 Token，
 *   给 2048 很容易出现「JSON 被截断」这种看起来很玄学的失败。
 * - 每次调用都会把脱敏后的请求/原始返回记进 [LlmExchangeLog]，方便排查。
 */
class OpenAiVisionTranslator(private val config: LlmConfig) : TranslationEngine {

    companion object {
        private const val TAG = "OpenAiEngine"
        private const val MAX_TOKENS = 4096
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override val id: String = "openai_compatible"
    override val displayName: String = config.provider.label
    override val requiresFrameBitmap: Boolean = true

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
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

        val text = request(
            cfg = cfg,
            prompt = LlmPrompt.build(hints, sourceLanguage, targetLanguage),
            imageBase64 = base64,
            imageMime = "image/jpeg",
        )

        // 首选语义：混合模式按 OCR 行对齐，纯多模态按模型自己给的坐标
        val direct = LlmResponseParser.parseOrNull(text, hints, frame.width, frame.height)
        if (direct.isNotEmpty()) {
            // 内容体检不合格（原样抄回 / 翻成别的语言）时，换加强版问法再问一次
            return@withContext TranslationQualityGuard.rescue(
                current = direct,
                targetLanguage = targetLanguage,
                ask = { strict ->
                    request(
                        cfg = cfg,
                        prompt = LlmPrompt.build(hints, sourceLanguage, targetLanguage, strict),
                        imageBase64 = base64,
                        imageMime = "image/jpeg",
                    )
                },
                parse = { raw ->
                    LlmResponseParser.parseOrNull(raw, hints, frame.width, frame.height)
                },
            )
        }

        // 兜底：纯多模态下模型没按带坐标的格式回（Qwen-VL 等很常见）。
        // 既然端侧 OCR 已经找出了文字框，就换一种问法再要一次 —— 把行带编号发过去，
        // 坐标直接复用 OCR 结果。多花一次请求，但换回的是「屏幕上至少有译文」。
        if (hints == null && fallbackHints.isNotEmpty()) {
            val retry = request(
                cfg = cfg,
                prompt = LlmPrompt.build(fallbackHints, sourceLanguage, targetLanguage),
                imageBase64 = base64,
                imageMime = "image/jpeg",
            )
            val rescued = LlmResponseParser.parseOrNull(retry, fallbackHints, frame.width, frame.height)
            if (rescued.isNotEmpty()) return@withContext rescued
            throw TranslationException(LlmResponseParser.describeFailure(retry, fallbackHints))
        }

        throw TranslationException(LlmResponseParser.describeFailure(text, hints ?: fallbackHints))
    }

    // ------------------------------------------------------------ 纯文本

    override suspend fun translateBlocks(
        blocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val cfg = config
        cfg.describeMissing()?.let { throw TranslationException(it) }
        if (blocks.isEmpty()) return@withContext emptyList()

        val prompt = buildString {
            append(LlmPrompt.buildTextOnly(sourceLanguage, targetLanguage))
            blocks.forEachIndexed { index, block -> appendLine("$index: ${block.text}") }
        }

        val text = request(cfg = cfg, prompt = prompt, imageBase64 = null, imageMime = null)
        val parsed = LlmResponseParser.parse(text, blocks, 0, 0)

        // 体检：模型原样抄回原文、或翻成了另一种外文时，换加强版问法再问一次
        TranslationQualityGuard.rescue(
            current = parsed,
            targetLanguage = targetLanguage,
            ask = { strict ->
                request(
                    cfg = cfg,
                    prompt = numberLines(
                        LlmPrompt.buildTextOnly(sourceLanguage, targetLanguage, strict = strict),
                        blocks,
                    ),
                    imageBase64 = null,
                    imageMime = null,
                )
            },
            parse = { raw -> LlmResponseParser.parseOrNull(raw, blocks, 0, 0) },
        )
    }

    /** 把文本行按「编号: 内容」拼到提示词后面 */
    private fun numberLines(header: String, blocks: List<TextBlock>): String = buildString {
        append(header)
        blocks.forEachIndexed { index, block -> appendLine("$index: ${block.text}") }
    }

    // ------------------------------------------------------------ 网络

    private fun buildBody(
        cfg: LlmConfig,
        prompt: String,
        imageBase64: String?,
        imageMime: String?,
    ): JSONObject {
        val content: Any = if (imageBase64 == null || imageMime == null) {
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
                                .put("url", "data:$imageMime;base64,$imageBase64")
                                .put("detail", "low"),
                        ),
                )
        }

        val body = JSONObject()
            .put("model", cfg.model)
            .put("temperature", cfg.temperature)
            .put("max_tokens", MAX_TOKENS)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )

        // 服务商必需参数 + 用户自定义参数（后者优先级更高）
        LlmRequestExtras.apply(body, cfg)

        if (cfg.forceJsonMode && !body.has("response_format")) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        return body
    }

    private fun request(
        cfg: LlmConfig,
        prompt: String,
        imageBase64: String?,
        imageMime: String?,
    ): String {
        val body = buildBody(cfg, prompt, imageBase64, imageMime)
        var note: String? = null

        var (code, payload) = execute(cfg, body.toString())

        // 少数网关不认 response_format 会直接 400，去掉它重试一次即可，不必打扰用户
        if (code == 400 && body.has("response_format") &&
            payload.contains("response_format", ignoreCase = true)
        ) {
            LlmRequestExtras.drop(body, "response_format")
            note = "已自动去掉 response_format 重试（该接口不支持强制 JSON 输出）"
            val retry = execute(cfg, body.toString())
            code = retry.first
            payload = retry.second
        }

        LlmExchangeLog.record(
            label = "${cfg.provider.label} / ${cfg.model}",
            url = "${cfg.normalizedBaseUrl}/chat/completions",
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

        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: throw TranslationException(
                "返回不是合法 JSON：${sanitizeForDisplay(payload, cfg.apiKey, 200)}",
            )
        return extractText(root, cfg, payload)
    }

    private fun execute(cfg: LlmConfig, payload: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("${cfg.normalizedBaseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .addHeader("Content-Type", "application/json")
            // OpenRouter 建议带上，其他家忽略即可
            .addHeader("HTTP-Referer", "https://localhost/screen-translator")
            .addHeader("X-Title", "ScreenTranslator")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        return http.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }

    private fun extractText(root: JSONObject, cfg: LlmConfig, payload: String): String {
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw TranslationException(
                "返回结构里没有 choices：${sanitizeForDisplay(payload, cfg.apiKey, 200)}",
            )
        }
        val choice = choices.optJSONObject(0)
            ?: throw TranslationException("choices[0] 结构异常")
        val message = choice.optJSONObject("message")
            ?: throw TranslationException("返回结构里没有 message")

        val finish = choice.optString("finish_reason").orEmpty()
        val direct = message.optString("content")

        if (finish.equals("length", ignoreCase = true)) {
            throw TranslationException(
                "模型输出被 max_tokens 截断了（finish_reason=length），JSON 不完整。" +
                    "请把「单帧最多翻译行数」调小，或在设置页打开「关闭思考过程」。" +
                    "已返回的开头：${direct.take(160)}",
            )
        }

        if (direct.isNotBlank()) return direct

        // 少数网关把内容拆成数组
        val array = message.optJSONArray("content")
        if (array != null) {
            val builder = StringBuilder()
            for (i in 0 until array.length()) {
                val part = array.optJSONObject(i) ?: continue
                builder.append(part.optString("text").orEmpty())
            }
            if (builder.isNotBlank()) return builder.toString()
        }

        // 思考模式没关掉时最常见的症状：正文空、思考过程满
        val reasoning = message.optString("reasoning_content")
        if (reasoning.isNotBlank()) {
            Log.w(TAG, "模型只返回了 reasoning_content，正文为空，尝试从中解析 JSON")
            return reasoning
        }

        throw TranslationException(
            "模型返回的正文是空的（finish_reason=$finish）。常见原因：" +
                "① 这是思考模型但思考过程没关闭 —— 打开设置页的「关闭思考过程」，" +
                "或在「附加请求参数」里填 {\"enable_thinking\": false}；" +
                "② max_tokens 被思考过程消耗完；③ 所选模型不支持看图。",
        )
    }

    override fun close() {
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
        Log.d(TAG, "OpenAI 兼容引擎已释放")
    }
}
