package com.saltin.screentranslator.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.saltin.screentranslator.core.SupportedLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 端侧离线翻译模型的「检查 + 预下载」。
 *
 * ML Kit 的离线翻译是**按语言对**下载的，一个语言对一个包（通常 30MB 上下）。
 * 这些模型下载完就常驻手机，之后可以完全断网使用。
 *
 * 之所以单独做一个类：如果等到正式翻译时才去下载，用户会先经历一次莫名其妙的长时间无响应，
 * 而且失败原因完全不可见。提前在设置页把这件事做完并给出明确结果，体验好得多。
 */
object OfflineModelWarmup {

    /** 离线模型的大致体积，用于给用户一个预期 */
    const val APPROX_MB = 30

    data class Status(
        val sourceReady: Boolean,
        val targetReady: Boolean,
    ) {
        val ready: Boolean get() = sourceReady && targetReady
    }

    /** 查询当前语言对的模型是否已经在本地 */
    suspend fun status(source: String, target: String): Status = withContext(Dispatchers.Default) {
        val manager = RemoteModelManager.getInstance()
        Status(
            sourceReady = isDownloaded(manager, SupportedLanguages.toMlKitCode(source)),
            targetReady = isDownloaded(manager, SupportedLanguages.toMlKitCode(target)),
        )
    }

    private suspend fun isDownloaded(manager: RemoteModelManager, code: String): Boolean =
        runCatching {
            manager.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()
        }.getOrDefault(false)

    /**
     * 检查并在必要时下载模型。
     *
     * @param onProgress 中间状态回调，用来在界面上显示「正在下载…」这类提示
     * @return 可以直接展示给用户的结果文案
     * @throws TranslationWarmupException 语言对不支持，或下载失败（拿到的是人话原因）
     */
    suspend fun warmup(
        source: String,
        target: String,
        onProgress: (String) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        val src = SupportedLanguages.toMlKitCode(source)
        val tgt = SupportedLanguages.toMlKitCode(target)

        if (!SupportedLanguages.onDeviceSupported(source, target)) {
            throw TranslationWarmupException(
                "端侧离线模型不支持「${SupportedLanguages.label(source)} → " +
                    "${SupportedLanguages.label(target)}」这个语言对，请换一个语言。",
            )
        }
        if (src == tgt) {
            throw TranslationWarmupException("源语言和目标语言是同一个，不需要下载模型。")
        }

        val current = status(source, target)
        if (current.ready) {
            return@withContext "离线模型已在本机，可以断网使用（约 ${APPROX_MB}MB / 语言对）。"
        }

        onProgress("正在下载离线模型（约 ${APPROX_MB}MB）…请保持网络通畅，不要退出本页面。")

        try {
            val client = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build(),
            )
            try {
                client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } finally {
                runCatching { client.close() }
            }
        } catch (t: Throwable) {
            throw TranslationWarmupException(explain(t), t)
        }

        if (status(source, target).ready) {
            "下载完成，现在可以断网使用离线翻译了。"
        } else {
            "下载请求已提交，但模型似乎还没落地。请稍等片刻后重新点一次「检查离线模型」。"
        }
    }

    private fun explain(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            raw.contains("MISSING", ignoreCase = true) ||
                raw.contains("not available", ignoreCase = true) ->
                "下载失败：这台设备缺少可用的模型源。多数情况是手机没有安装或未启用 Google Play 服务，" +
                    "导致 ML Kit 无法获取离线模型。可以改用云端模式。"

            raw.contains("NETWORK", ignoreCase = true) ||
                raw.contains("Unable to resolve host", ignoreCase = true) ->
                "下载失败：网络不通。请检查能否正常访问外网（ML Kit 的模型服务器在境外）。"

            raw.contains("SPACE", ignoreCase = true) || raw.contains("ENOSPC", ignoreCase = true) ->
                "下载失败：手机存储空间不足，请先清理出至少 200MB。"

            else -> "下载失败：${raw.ifBlank { t::class.java.simpleName }}"
        }
    }
}

class TranslationWarmupException(message: String, cause: Throwable? = null) : Exception(message, cause)
