package com.saltin.screentranslator.translate

import android.content.Context
import com.saltin.screentranslator.core.AppSettings
import com.saltin.screentranslator.core.Defaults
import com.saltin.screentranslator.core.TranslateMode
import com.saltin.screentranslator.translate.offline.OfflineEngineKind
import com.saltin.screentranslator.translate.offline.SubwordTranslator

/**
 * 按当前设置构建翻译引擎。
 *
 * 把「模式 × 服务商协议」「端侧模式 × 离线引擎档位」这两组组合收敛到一个地方，
 * 服务层只调 [create]。
 */
object TranslatorFactory {

    fun resolveProvider(settings: AppSettings): LlmProvider =
        LlmProviders.byId(settings.llmProviderId)

    /** 设置里没填就走编译期注入的默认 Key（gradle.properties 的 llmApiKey） */
    fun effectiveApiKey(settings: AppSettings): String {
        if (settings.llmApiKey.isNotBlank()) return settings.llmApiKey.trim()
        return if (resolveProvider(settings).id == LlmProviders.GEMINI.id) {
            BuildConfigKey.value
        } else {
            ""
        }
    }

    fun buildConfig(settings: AppSettings): LlmConfig {
        val provider = resolveProvider(settings)
        return LlmConfig(
            provider = provider,
            apiKey = effectiveApiKey(settings),
            baseUrl = settings.llmBaseUrl.ifBlank { provider.defaultBaseUrl },
            model = settings.llmModel.ifBlank { provider.defaultModel },
            uploadMaxSide = settings.uploadMaxSide,
            jpegQuality = settings.jpegQuality,
            forceJsonMode = settings.forceJsonMode,
            disableThinking = settings.disableThinking,
            extraRequestJson = settings.extraRequestJson,
        )
    }

    fun create(settings: AppSettings, context: Context): TranslationEngine = when (settings.mode) {
        TranslateMode.ON_DEVICE -> createOffline(settings, context)
        else -> createCloud(buildConfig(settings))
    }

    /**
     * 端侧模式的三个档位。
     *
     * 「离线引擎」是端侧模式下的**子选项**，不是第四个模式 —— 这样「端侧 / 混合 / 云端」
     * 这张心智地图不用改，用户只需要在端侧里挑一个质量档。
     */
    fun createOffline(settings: AppSettings, context: Context): TranslationEngine =
        when (settings.offlineEngine) {
            OfflineEngineKind.ML_KIT -> MlKitOnDeviceTranslator()
            OfflineEngineKind.OPUS_MT, OfflineEngineKind.OPUS_FULL -> SubwordTranslator(
                kind = settings.offlineEngine,
                context = context.applicationContext,
                sourceLanguage = settings.sourceLanguage,
                targetLanguage = settings.targetLanguage,
            )
        }

    fun createCloud(config: LlmConfig): TranslationEngine = when (config.provider.protocol) {
        LlmProtocol.GEMINI -> GeminiVisionTranslator(config)
        LlmProtocol.OPENAI -> OpenAiVisionTranslator(config)
        LlmProtocol.ANTHROPIC -> AnthropicVisionTranslator(config)
    }

    /**
     * 引擎指纹：任何一个影响翻译结果的参数变了，都要重建引擎。
     * 服务每帧对比一次，改设置后下一帧生效。
     */
    fun signatureOf(settings: AppSettings): String = buildString {
        append(settings.mode.id).append('|')
        append(settings.sourceLanguage).append('>').append(settings.targetLanguage).append('|')
        // 端侧档位换了要重建（ML Kit 与 Opus-MT 是两套完全不同的模型）
        append(settings.offlineEngine.id).append('|')
        append(settings.llmProviderId).append('|')
        append(settings.llmBaseUrl).append('|')
        append(settings.llmModel).append('|')
        append(effectiveApiKey(settings).length).append('|')
        append(settings.uploadMaxSide).append('|')
        append(settings.jpegQuality).append('|')
        append(settings.forceJsonMode).append('|')
        append(settings.disableThinking).append('|')
        append(settings.extraRequestJson)
    }

    /**
     * 这个模式下是否必须走图片。
     * 不支持看图的模型在混合模式下会被悄悄降级到纯文本路径，而不是直接报错。
     */
    fun needsVision(settings: AppSettings): Boolean =
        settings.mode == TranslateMode.CLOUD_VISION ||
            (settings.mode == TranslateMode.HYBRID && settings.attachScreenshot)

    fun canUseVision(settings: AppSettings): Boolean =
        resolveProvider(settings).supportsVision
}

/** 把 BuildConfig 里的默认 Key 抽出来，方便测试与替换 */
internal object BuildConfigKey {
    val value: String
        get() = com.saltin.screentranslator.BuildConfig.DEFAULT_LLM_API_KEY
}
