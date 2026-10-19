package com.saltin.screentranslator.translate

import android.util.Log
import kotlinx.coroutines.coroutineScope

/**
 * 「这一次翻得不对，就换一种问法再问一次」的统一实现。
 *
 * 三家协议（OpenAI 兼容 / Gemini / Anthropic）都走这里，避免各写一份重试逻辑。
 *
 * 触发条件是 [TranslationVerifier] 的体检结论：模型**原样把外文抄回来**，
 * 或者**该出中文却出了日文/韩文/越南语**。这两种情况下屏幕上的内容都是「看起来正常、
 * 其实完全没用」的，比直接报错更糟 —— 用户连原来那行外文都看不见了。
 *
 * 重试只在结果**确实更好**时才被采用：宁可保留原来那份，也不要越修越乱。
 * 每次重试最多多花一次请求，而体检没过本身意味着这一帧本来也不可用。
 */
object TranslationQualityGuard {

    private const val TAG = "TranslationQuality"

    /**
     * @param current 首次拿到的译文
     * @param targetLanguage 目标语言（体检用）
     * @param ask 用「加强版提示词」发起一次请求，返回原始响应文本；失败返回 null
     * @param parse 把原始响应文本解析成译文（各家的解析入口不同，由调用方给）
     * @return 最终采用的那一份（体检通过、重试失败、或重试没更好，都会返回 [current]）
     */
    suspend fun rescue(
        current: List<TranslatedBlock>,
        targetLanguage: String,
        ask: suspend (strict: Boolean) -> String?,
        parse: (String) -> List<TranslatedBlock>,
    ): List<TranslatedBlock> = coroutineScope {
        if (current.isEmpty()) return@coroutineScope current

        val verdict = TranslationVerifier.inspect(current, targetLanguage)
        if (!verdict.needsRetry) return@coroutineScope current

        Log.i(TAG, "译文体检不合格：${verdict.message}，改用加强版提示词重试一次")

        val raw = runCatching { ask(true) }.getOrNull() ?: return@coroutineScope current
        val retried = runCatching { parse(raw) }.getOrDefault(emptyList())
        if (retried.isEmpty()) return@coroutineScope current

        val after = TranslationVerifier.inspect(retried, targetLanguage)
        Log.i(TAG, "严格重试结果：可疑 ${verdict.suspicious} → ${after.suspicious}")
        if (after.suspicious < verdict.suspicious) retried else current
    }
}
