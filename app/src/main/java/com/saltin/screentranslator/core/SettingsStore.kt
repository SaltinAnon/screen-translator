package com.saltin.screentranslator.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.saltin.screentranslator.ocr.ScriptMode
import com.saltin.screentranslator.ocr.TextOrientation
import com.saltin.screentranslator.translate.offline.OfflineEngineKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轻量设置仓库：SharedPreferences 落地 + StateFlow 供 Compose 直接订阅。
 * 前台服务每帧都会 [current]，所以改设置后下一帧就生效。
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun current(): AppSettings = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        if (next == _state.value) return
        write(next)
        _state.value = next
    }

    fun replace(settings: AppSettings) = update { settings }

    fun reset() = update { AppSettings() }

    /** 先读新键；没有就尝试迁移旧键 */
    private fun readStringWithLegacy(primary: String, legacy: String): String =
        prefs.getString(primary, null) ?: prefs.getString(legacy, null) ?: ""

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            mode = TranslateMode.fromId(prefs.getString(K_MODE, d.mode.id)),
            offlineEngine = OfflineEngineKind.fromId(
                prefs.getString(K_OFFLINE_ENGINE, d.offlineEngine.id),
            ),
            offlineModelHost = prefs.getString(K_MODEL_HOST, d.offlineModelHost) ?: "",
            sourceLanguage = prefs.getString(K_SRC, d.sourceLanguage) ?: d.sourceLanguage,
            targetLanguage = prefs.getString(K_TGT, d.targetLanguage) ?: d.targetLanguage,
            scriptMode = ScriptMode.fromId(prefs.getString(K_SCRIPT, d.scriptMode.id)),
            textOrientation = TextOrientation.fromId(
                prefs.getString(K_ORIENTATION, d.textOrientation.id),
            ),

            autoTranslate = prefs.getBoolean(K_AUTO, d.autoTranslate),
            autoClearSeconds = prefs.getInt(K_AUTO_CLEAR, d.autoClearSeconds),

            captureIntervalMs = prefs.getLong(K_INTERVAL, d.captureIntervalMs),
            maxCaptureWidth = prefs.getInt(K_MAX_W, d.maxCaptureWidth),
            ocrUpscale = prefs.getFloat(K_OCR_UPSCALE, d.ocrUpscale),
            ocrEnhanceContrast = prefs.getBoolean(K_OCR_ENHANCE, d.ocrEnhanceContrast),
            minTextLength = prefs.getInt(K_MIN_LEN, d.minTextLength),
            maxCharsPerRequest = prefs.getInt(K_MAX_CHARS, d.maxCharsPerRequest),
            maxBlocksPerRequest = prefs.getInt(K_MAX_BLOCKS, d.maxBlocksPerRequest),
            mergeParagraphs = prefs.getBoolean(K_MERGE_PARAGRAPHS, d.mergeParagraphs),
            mergeGapRatio = prefs.getFloat(K_MERGE_GAP, d.mergeGapRatio),
            bubbleMerge = BubbleMergeMode.fromId(prefs.getString(K_BUBBLE_MERGE, d.bubbleMerge.id)),
            bubbleGapRatio = prefs.getFloat(K_BUBBLE_GAP, d.bubbleGapRatio),

            bubbleEnabled = prefs.getBoolean(K_BUBBLE, d.bubbleEnabled),
            bubbleSizeDp = prefs.getInt(K_BUBBLE_SIZE, d.bubbleSizeDp),
            bubbleAlpha = prefs.getFloat(K_BUBBLE_ALPHA, d.bubbleAlpha),
            bubbleX = prefs.getInt(K_BUBBLE_X, d.bubbleX),
            bubbleY = prefs.getInt(K_BUBBLE_Y, d.bubbleY),
            bubbleXFrac = prefs.getFloat(K_BUBBLE_XF, d.bubbleXFrac),
            bubbleYFrac = prefs.getFloat(K_BUBBLE_YF, d.bubbleYFrac),

            overlayEnabled = prefs.getBoolean(K_OVERLAY, d.overlayEnabled),
            fontSizeScale = prefs.getFloat(K_FONT_SCALE, d.fontSizeScale),
            textColor = prefs.getInt(K_TEXT_COLOR, d.textColor),
            scrimColor = prefs.getInt(K_SCRIM_COLOR, d.scrimColor),
            scrimAlpha = prefs.getFloat(K_SCRIM_ALPHA, d.scrimAlpha),
            offsetXDp = prefs.getInt(K_OFFSET_X, d.offsetXDp),
            offsetYDp = prefs.getInt(K_OFFSET_Y, d.offsetYDp),

            llmProviderId = prefs.getString(K_PROVIDER, d.llmProviderId) ?: d.llmProviderId,
            // 新键没有时，回落到 v1.0 的 gemini_api_key / gemini_model，老用户升上来不用重填
            llmApiKey = readStringWithLegacy(K_API_KEY, K_LEGACY_API_KEY),
            llmBaseUrl = prefs.getString(K_BASE_URL, d.llmBaseUrl) ?: "",
            llmModel = readStringWithLegacy(K_MODEL, K_LEGACY_MODEL),
            attachScreenshot = prefs.getBoolean(K_ATTACH_IMAGE, d.attachScreenshot),
            forceJsonMode = prefs.getBoolean(K_FORCE_JSON, d.forceJsonMode),
            disableThinking = prefs.getBoolean(K_NO_THINKING, d.disableThinking),
            extraRequestJson = prefs.getString(K_EXTRA_JSON, d.extraRequestJson) ?: "",
            uploadMaxSide = prefs.getInt(K_UPLOAD_SIDE, d.uploadMaxSide),
            jpegQuality = prefs.getInt(K_JPEG_Q, d.jpegQuality),

            tutorialSeen = prefs.getBoolean(K_TUTORIAL_SEEN, d.tutorialSeen),
        )
    }

    @SuppressLint("ApplySharedPref")
    private fun write(s: AppSettings) {
        prefs.edit()
            .putString(K_MODE, s.mode.id)
            .putString(K_OFFLINE_ENGINE, s.offlineEngine.id)
            .putString(K_MODEL_HOST, s.offlineModelHost)
            .putString(K_SRC, s.sourceLanguage)
            .putString(K_TGT, s.targetLanguage)
            .putString(K_SCRIPT, s.scriptMode.id)
            .putString(K_ORIENTATION, s.textOrientation.id)
            .putBoolean(K_AUTO, s.autoTranslate)
            .putInt(K_AUTO_CLEAR, s.autoClearSeconds)
            .putLong(K_INTERVAL, s.captureIntervalMs)
            .putInt(K_MAX_W, s.maxCaptureWidth)
            .putFloat(K_OCR_UPSCALE, s.ocrUpscale)
            .putBoolean(K_OCR_ENHANCE, s.ocrEnhanceContrast)
            .putInt(K_MIN_LEN, s.minTextLength)
            .putInt(K_MAX_CHARS, s.maxCharsPerRequest)
            .putInt(K_MAX_BLOCKS, s.maxBlocksPerRequest)
            .putBoolean(K_MERGE_PARAGRAPHS, s.mergeParagraphs)
            .putFloat(K_MERGE_GAP, s.mergeGapRatio)
            .putString(K_BUBBLE_MERGE, s.bubbleMerge.id)
            .putFloat(K_BUBBLE_GAP, s.bubbleGapRatio)
            .putBoolean(K_BUBBLE, s.bubbleEnabled)
            .putInt(K_BUBBLE_SIZE, s.bubbleSizeDp)
            .putFloat(K_BUBBLE_ALPHA, s.bubbleAlpha)
            .putInt(K_BUBBLE_X, s.bubbleX)
            .putInt(K_BUBBLE_Y, s.bubbleY)
            .putFloat(K_BUBBLE_XF, s.bubbleXFrac)
            .putFloat(K_BUBBLE_YF, s.bubbleYFrac)
            .putBoolean(K_OVERLAY, s.overlayEnabled)
            .putFloat(K_FONT_SCALE, s.fontSizeScale)
            .putInt(K_TEXT_COLOR, s.textColor)
            .putInt(K_SCRIM_COLOR, s.scrimColor)
            .putFloat(K_SCRIM_ALPHA, s.scrimAlpha)
            .putInt(K_OFFSET_X, s.offsetXDp)
            .putInt(K_OFFSET_Y, s.offsetYDp)
            .putString(K_PROVIDER, s.llmProviderId)
            .putString(K_API_KEY, s.llmApiKey)
            .putString(K_BASE_URL, s.llmBaseUrl)
            .putString(K_MODEL, s.llmModel)
            .putBoolean(K_ATTACH_IMAGE, s.attachScreenshot)
            .putBoolean(K_FORCE_JSON, s.forceJsonMode)
            .putBoolean(K_NO_THINKING, s.disableThinking)
            .putString(K_EXTRA_JSON, s.extraRequestJson)
            .putInt(K_UPLOAD_SIDE, s.uploadMaxSide)
            .putInt(K_JPEG_Q, s.jpegQuality)
            .putBoolean(K_TUTORIAL_SEEN, s.tutorialSeen)
            .commit()
    }

    companion object {
        private const val PREFS_NAME = "screen_translator_settings"
        private const val K_MODE = "mode"
        private const val K_OFFLINE_ENGINE = "offline_engine"
        private const val K_MODEL_HOST = "offline_model_host"
        private const val K_SRC = "source_language"
        private const val K_TGT = "target_language"
        private const val K_SCRIPT = "script_mode"
        private const val K_ORIENTATION = "text_orientation"
        private const val K_AUTO = "auto_translate"
        private const val K_AUTO_CLEAR = "auto_clear_seconds"
        private const val K_INTERVAL = "capture_interval"
        private const val K_MAX_W = "max_capture_width"
        private const val K_OCR_UPSCALE = "ocr_upscale"
        private const val K_OCR_ENHANCE = "ocr_enhance_contrast"
        private const val K_MIN_LEN = "min_text_length"
        private const val K_MAX_CHARS = "max_chars"
        private const val K_MAX_BLOCKS = "max_blocks"
        private const val K_MERGE_PARAGRAPHS = "merge_paragraphs"
        private const val K_MERGE_GAP = "merge_gap_ratio"
        private const val K_BUBBLE_MERGE = "bubble_merge_mode"
        private const val K_BUBBLE_GAP = "bubble_gap_ratio"
        private const val K_BUBBLE = "bubble_enabled"
        private const val K_BUBBLE_SIZE = "bubble_size_dp"
        private const val K_BUBBLE_ALPHA = "bubble_alpha"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_XF = "bubble_x_frac"
        private const val K_BUBBLE_YF = "bubble_y_frac"
        private const val K_OVERLAY = "overlay_enabled"
        private const val K_FONT_SCALE = "font_scale"
        private const val K_TEXT_COLOR = "text_color"
        private const val K_SCRIM_COLOR = "scrim_color"
        private const val K_SCRIM_ALPHA = "scrim_alpha"
        private const val K_OFFSET_X = "offset_x"
        private const val K_OFFSET_Y = "offset_y"
        private const val K_PROVIDER = "llm_provider"
        private const val K_API_KEY = "llm_api_key"
        private const val K_BASE_URL = "llm_base_url"
        private const val K_MODEL = "llm_model"
        private const val K_ATTACH_IMAGE = "attach_screenshot"
        private const val K_FORCE_JSON = "force_json_mode"
        private const val K_NO_THINKING = "disable_thinking"
        private const val K_EXTRA_JSON = "extra_request_json"
        private const val K_UPLOAD_SIDE = "upload_max_side"
        private const val K_JPEG_Q = "jpeg_quality"
        private const val K_TUTORIAL_SEEN = "tutorial_seen"

        // v1.0 的键名，仅用于一次性的读取迁移
        private const val K_LEGACY_API_KEY = "gemini_api_key"
        private const val K_LEGACY_MODEL = "gemini_model"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
