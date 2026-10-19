package com.saltin.screentranslator.translate

import android.graphics.Rect
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.saltin.screentranslator.core.Defaults
import com.saltin.screentranslator.core.SupportedLanguages
import com.saltin.screentranslator.ocr.TextBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 端侧离线翻译（ML Kit On-Device Translation）。
 *
 * 特点：零流量、零上传、低延迟（几十毫秒），适合隐私敏感 / 弱网 / 纯离线场景。
 * 局限：逐句直译，缺少上下文，术语与语气不如大模型；首次使用某个语言对需要下载模型（约 30MB）。
 */
class MlKitOnDeviceTranslator : TranslationEngine {

    companion object {
        private const val TAG = "MlKitOnDevice"
    }

    override val id: String = "on_device"
    override val displayName: String = "端侧 ML Kit"
    override val requiresFrameBitmap: Boolean = false

    private val clients = ConcurrentHashMap<String, Translator>()
    private val downloaded = ConcurrentHashMap.newKeySet<String>()

    private suspend fun clientFor(source: String, target: String): Translator {
        val src = SupportedLanguages.toMlKitCode(source)
        val tgt = SupportedLanguages.toMlKitCode(target)
        val key = "$src>$tgt"
        val translator = clients.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build(),
            )
        }
        if (!downloaded.contains(key)) {
            // 已下载时立即返回；未下载则走网络拉模型
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            downloaded.add(key)
        }
        return translator
    }

    override suspend fun prepare(sourceLanguage: String, targetLanguage: String) {
        withContext(Dispatchers.Default) {
            runCatching { clientFor(sourceLanguage, targetLanguage) }
                .onFailure { Log.w(TAG, "端侧模型准备失败，先降级为原文显示", it) }
        }
    }

    override suspend fun translateBlocks(
        blocks: List<TextBlock>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<TranslatedBlock> = withContext(Dispatchers.Default) {
        if (blocks.isEmpty()) return@withContext emptyList()

        // 端侧没有「自动检测源语言」这回事：auto 以前被一律当成英语。
        // 屏幕上是日文时拿 en→zh 的模型硬翻，出来的就是用户说的
        // 「翻译完变成另一种不认识的语言」。这里按 OCR 文本自己的字符构成猜一次 ——
        // 猜错也只是用错模型，不猜则一定用错模型。
        val effectiveSource = if (sourceLanguage == "auto") guessSource(blocks) else sourceLanguage

        val src = SupportedLanguages.toMlKitCode(effectiveSource)
        val tgt = SupportedLanguages.toMlKitCode(targetLanguage)
        if (src == tgt) {
            // 源语言 == 目标语言：**这一条是合法的「不用翻」**，不是失败。
            // 返回的块里译文 == 原文，上层不会写入缓存（缓存条件要求 != originalText），
            // 渲染层会把它当「无需翻译」正常显示。这与下面「模型坏掉导致译文==原文」是两回事，
            // 所以**不能**在这里判失败，否则用户选到「中→中」时会看到无谓的报错。
            return@withContext blocks.map { TranslatedBlock(it.text, it.text, Rect(it.boundingBox)) }
        }

        val translator = try {
            clientFor(effectiveSource, targetLanguage)
        } catch (t: Throwable) {
            // 旧版本这里默默返回原文，用户看到的是「译文和原文一模一样」，
            // 完全不知道发生了什么 —— 属于典型的静默失败，现在改成明确报错。
            throw TranslationException(explainModelFailure(t, sourceLanguage, targetLanguage))
        }

        val texts = blocks.map { it.text }
        // 注意：ML Kit 的 Translator 只有单行 translate(String) 接口，没有批量重载，
        // 所以这里按行顺序调用；因为已过去重，实际行数不多（默认上限 40 行）。
        //
        // v1.6.2：和 Opus-MT 一样，这里有**两条**失败路径，旧版只堵了「抛异常」那条：
        //   ① 抛异常            —— catch 分支统计
        //   ② 不抛异常但产出空   —— 旧版漏了，`value.ifBlank { text }` 把原文填回去
        // 现在两条都计入失败，且**不再把原文当译文**。
        val translated = ArrayList<String>(texts.size)
        var failures = 0
        var lastError: Throwable? = null
        for (text in texts) {
            val value = try {
                translator.translate(text).await()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.w(TAG, "单行翻译失败", t)
                failures++
                lastError = t
                ""
            }
            if (value.isBlank()) {
                Log.w(TAG, "单行翻译未产出内容：${text.take(40)}")
                failures++
                lastError = lastError ?: TranslationException("翻译未产出内容")
            }
            // 译文与原文逐字相同同样判失败（正常情况下 ML Kit 不会这样）
            if (value.isNotBlank() && value == text) {
                Log.w(TAG, "单行翻译产出了与原文完全相同的结果：${text.take(40)}")
                failures++
                lastError = lastError ?: TranslationException("译文与原文完全相同")
            }
            translated += value
        }

        if (failures > 0) {
            val why = lastError?.message?.takeIf { it.isNotBlank() } ?: "翻译未返回结果"
            throw TranslationException(
                if (failures >= texts.size) {
                    "端侧 ML Kit 本次没有翻出任何内容（$why）。请到「离线翻译」卡片确认模型已下载完整。"
                } else {
                    "端侧 ML Kit 有 $failures/${texts.size} 行没翻出来（$why）。"
                },
                lastError,
            )
        }

        // 到这里每一行都一定是「非空且与原文不同」的译文。仍然逐行校验一次，
        // 漏网的空串直接跳过（宁可不画，也绝不把原文当译文画上屏）。
        blocks.mapIndexedNotNull { index, block ->
            val value = translated.getOrNull(index)?.takeIf { it.isNotBlank() && it != block.text }
                ?: return@mapIndexedNotNull null
            TranslatedBlock(
                originalText = block.text,
                translatedText = value,
                boundingBox = Rect(block.boundingBox),
            )
        }
    }

    /**
     * 按 OCR 文本自己的字符构成猜源语言。
     *
     * 规则刻意做得非常粗：出现假名就判日语（日语里假名必然成片出现），出现谚文判韩语，
     * 汉字不少于拉丁字母判中文，西里尔文比拉丁多判俄语，其余按英语。
     *
     * 为什么不做得更细：端侧没有语言识别模型，多出来的那点精度不值得额外一百多 MB 体积；
     * 而这个判定的作用只是「决定加载哪个离线模型」，选错的代价远小于「一定选错」。
     */
    private fun guessSource(blocks: List<TextBlock>): String {
        var cjk = 0
        var kana = 0
        var hangul = 0
        var latin = 0
        var cyrillic = 0

        for (block in blocks) {
            for (ch in block.text) {
                val code = ch.code
                when {
                    code in 0x3040..0x30FF -> kana++
                    code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> hangul++
                    code in 0x4E00..0x9FFF -> cjk++
                    code in 0x0400..0x04FF -> cyrillic++
                    ch.isLetter() -> latin++
                }
            }
        }

        val guess = when {
            kana > 0 -> "ja"
            hangul > 0 -> "ko"
            cjk > 0 && cjk >= latin -> "zh"
            cyrillic > latin -> "ru"
            else -> Defaults.ON_DEVICE_FALLBACK_SOURCE
        }
        Log.i(
            TAG,
            "端侧不支持自动检测，按识别结果判定源语言为 $guess" +
                "（汉字 $cjk / 假名 $kana / 谚文 $hangul / 拉丁 $latin）",
        )
        return guess
    }

    override fun close() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        downloaded.clear()
    }

    /** 把 ML Kit 那一堆英文异常翻译成人话，并直接告诉用户下一步该做什么 */
    private fun explainModelFailure(t: Throwable, source: String, target: String): String {
        val raw = (t.message ?: "") + " " + t::class.java.simpleName
        val pair = "${SupportedLanguages.label(source)} → ${SupportedLanguages.label(target)}"
        return when {
            raw.contains("MISSING", ignoreCase = true) ||
                raw.contains("not available", ignoreCase = true) ||
                raw.contains("not downloaded", ignoreCase = true) ->
                "「$pair」的离线模型还没下载到手机上。请到设置页的「离线翻译（端侧模式）」" +
                    "点一次「下载 / 检查离线模型」（约 30MB，需要能访问境外网络）。"

            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("NETWORK", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ->
                "下载离线模型时网络不通（模型服务器在境外）。可以连上合适的网络后重试，或改用云端模式。"

            raw.contains("ENOSPC", ignoreCase = true) ||
                raw.contains("SPACE", ignoreCase = true) ->
                "手机存储空间不足，离线模型下载失败。请清理出至少 200MB 后重试。"

            else -> "端侧离线翻译不可用：${t.message ?: t::class.java.simpleName}。" +
                "请到设置页点一次「下载 / 检查离线模型」，或改用云端模式。"
        }
    }
}
