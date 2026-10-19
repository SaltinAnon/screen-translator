package com.saltin.screentranslator.service

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.saltin.screentranslator.R
import com.saltin.screentranslator.capture.CaptureConfig
import com.saltin.screentranslator.capture.ScreenCaptureManager
import com.saltin.screentranslator.core.AppSettings
import com.saltin.screentranslator.core.ServiceState
import com.saltin.screentranslator.core.SettingsStore
import com.saltin.screentranslator.core.SupportedLanguages
import com.saltin.screentranslator.core.TranslateMode
import com.saltin.screentranslator.ocr.OcrProcessor
import com.saltin.screentranslator.ocr.ScriptSupport
import com.saltin.screentranslator.overlay.BannerKind
import com.saltin.screentranslator.overlay.BubbleMenuView
import com.saltin.screentranslator.overlay.BubbleState
import com.saltin.screentranslator.overlay.BubbleUiModel
import com.saltin.screentranslator.overlay.OverlayRenderer
import com.saltin.screentranslator.translate.PipelineResult
import com.saltin.screentranslator.translate.TranslationEngine
import com.saltin.screentranslator.translate.TranslationPipeline
import com.saltin.screentranslator.translate.TranslatorFactory
import com.saltin.screentranslator.translate.offline.OfflineEngineKind
import com.saltin.screentranslator.translate.offline.OfflineModelCatalog
import com.saltin.screentranslator.util.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阶段一 + 阶段四的宿主：前台服务。
 *
 * 它把四阶段串成一条流水线：
 *   ScreenCaptureManager(取帧) → OcrProcessor(识别) → TranslationEngine(翻译) → OverlayRenderer(覆盖渲染)
 *
 * 两种触发方式：
 * - **点击翻译（默认）**：点悬浮球 / 通知栏「翻译一次」/ QS 磁贴 → 抓一帧、翻一次、画上去。
 * - **连续翻译**：按 [AppSettings.captureIntervalMs] 持续刷新（适合看直播字幕、刷外文网页）。
 *
 * Android 14+ 合规要点（缺一不可）：
 * 1. manifest 里声明 `android:foregroundServiceType="mediaProjection"` 且申请
 *    `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限；
 * 2. **必须先 `startForeground()`（带 mediaProjection 类型），再调用 `getMediaProjection()`**；
 * 3. 通知栏常驻通知（[NotificationHelper]），IMPORTANCE_LOW、ongoing；
 * 4. 注册 MediaProjection.Callback，用户随时撤销投屏时立即收尾（见 [captureListener]）。
 */
class TranslationForegroundService : Service() {

    companion object {
        private const val TAG = "TranslationService"

        const val ACTION_START = "com.saltin.screentranslator.action.START"
        const val ACTION_STOP = "com.saltin.screentranslator.action.STOP"
        const val ACTION_TRANSLATE_NOW = "com.saltin.screentranslator.action.TRANSLATE_NOW"
        const val ACTION_CLEAR = "com.saltin.screentranslator.action.CLEAR"
        const val ACTION_RESET_BUBBLE = "com.saltin.screentranslator.action.RESET_BUBBLE"
        const val ACTION_TOGGLE_AUTO = "com.saltin.screentranslator.action.TOGGLE_AUTO"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 1500L

        /** 手动模式下等待点击的轮询间隔：既能秒响应，又能及时感知「切到连续模式」 */
        private const val IDLE_POLL_MS = 700L

        /** 抓帧前先清掉译文层和悬浮球，给系统一个渲染周期 */
        private const val CAPTURE_SETTLE_MS = 70L

        /** 画面完全静止时可能取不到帧，短重试几次 */
        private const val CAPTURE_RETRY = 4
        private const val CAPTURE_RETRY_DELAY_MS = 45L

        /**
         * 非致命提示条停留多久后自动收起。
         *
         * 提示条是覆盖在全屏最上层的，长期挂着会挡住内容。致命错误（[BannerKind.ERROR]）
         * 不参与淡出 —— 它代表「现在根本翻不出来」，必须一直摆在用户眼前。
         */
        private const val BANNER_AUTO_HIDE_MS = 5000L

        /**
         * 同一条非致命提示的重复冷却时间。
         *
         * [BANNER_AUTO_HIDE_MS] 保证提示条会自己走，但连续翻译模式下它还会**再回来**：
         * 画面一直没文字时，「没有识别到文字」每 5 秒弹一次同样是刷屏。
         * 冷却期内同一条提示直接吞掉，把重弹频率压到可接受的范围。
         * 致命错误（[BannerKind.ERROR]）不受冷却约束 —— 它也从来不淡出。
         */
        private const val BANNER_REPEAT_COOLDOWN_MS = 20_000L

        /**
         * 悬浮球守护协程的间隔。
         *
         * 为什么必须有一条**独立于取帧循环**的通路：手动模式（没开连续翻译）下取帧只在
         * 「被点一下」时才跑，而悬浮球一旦跑到屏幕外就再也点不到 ——
         * 它自己就是唯一的入口，于是永久失联。用户实测的「横屏后小球消失、无法翻译」
         * 就是这个死锁。
         *
         * 1 秒是权衡：足够快（人对「刚转完屏就看到球」的耐心大约就是这个量级），
         * 又足够省（一次只读几个整数，位置没变就什么都不做）。
         */
        private const val OVERLAY_WATCHDOG_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var settingsStore: SettingsStore
    private val mainHandler = Handler(Looper.getMainLooper())

    private var capture: ScreenCaptureManager? = null
    private var overlay: OverlayRenderer? = null
    private var ocr: OcrProcessor? = null
    private var pipeline: TranslationPipeline? = null
    private var engine: TranslationEngine? = null
    private var engineSignature: String? = null
    private var loopJob: Job? = null
    private var autoClearJob: Job? = null
    private var maintainJob: Job? = null

    /** 手动触发信号。CONFLATED：连点多次只算一次，不会积压。 */
    private val manualTrigger = Channel<Unit>(Channel.CONFLATED)

    private val shuttingDown = AtomicBoolean(false)

    /**
     * 当前屏幕参数。旋转时会在主线程被改写，而取帧循环在后台线程读，所以标 Volatile。
     */
    @Volatile
    private var screenConfig = CaptureConfig(1080, 1920, 320)
    private var lastNotificationText = ""
    private var lastNotificationAt = 0L
    private var lastBannerText: String? = null
    private var lastBannerKind = BannerKind.INFO

    /**
     * 本次「开启翻译」期间已经提示过的提醒文案。
     *
     * 离线品质（「日语会经英语中转」）、自动降级、源语言兜底这类提醒都是**环境性**的：
     * 只要设置不变，它们每一帧都会重新产生，内容还完全一样。要是每帧都弹，
     * 提示条就变成常驻横幅一直挡着画面 —— 用户明确反馈过这个问题。
     *
     * 所以每条提醒在本次会话里只露一次脸（留够时间看完，然后自动淡出），之后静默。
     * 服务重新启动（重新点「开启翻译」）时清空，配置真的改了才会再次提示。
     */
    private val shownReminders = LinkedHashSet<String>()

    /** 非致命提示的重复冷却：文本 → 可以再次显示的绝对时刻 */
    private val bannerCooldown = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore.get(this)
        NotificationHelper.ensureChannel(this)
        ServiceState.setRunning(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(getString(R.string.status_idle))
                return START_NOT_STICKY
            }

            ACTION_TRANSLATE_NOW -> {
                if (ServiceState.running.value) {
                    requestManualTranslation()
                } else {
                    // 服务没在跑：这个 Intent 只是把服务拉起来了，别让它空转
                    Log.i(TAG, "服务未运行，忽略手动翻译请求")
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_CLEAR -> {
                if (ServiceState.running.value) {
                    runOnMain {
                        overlay?.clear()
                        setBubble(BubbleState.IDLE, 0)
                    }
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_RESET_BUBBLE -> {
                if (ServiceState.running.value) {
                    runOnMain {
                        overlay?.resetBubblePosition()
                        if (settingsStore.current().bubbleEnabled) {
                            overlay?.showBubble(settingsStore.current())
                        }
                    }
                    ServiceState.setStatus(getString(R.string.status_bubble_reset))
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_AUTO -> {
                val next = !settingsStore.current().autoTranslate
                settingsStore.update { it.copy(autoTranslate = next) }
                ServiceState.setStatus(
                    if (next) getString(R.string.status_auto_on) else getString(R.string.status_manual_on),
                )
                if (next) requestManualTranslation() // 立刻先翻一帧，不用等间隔
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (ServiceState.running.value) {
                    Log.i(TAG, "服务已在运行，忽略重复启动")
                    return START_NOT_STICKY
                }
                startPipelineFrom(intent)
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    // ------------------------------------------------------------ 启动

    private fun startPipelineFrom(intent: Intent) {
        // ★ 第一步必须是开始前台服务：Android 14 要求先有 mediaProjection 类型的 FGS，
        //   才允许调用 MediaProjectionManager.getMediaProjection()
        startForegroundCompat(
            NotificationHelper.build(this, getString(R.string.notif_text_starting), manual = true),
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            shutdown(getString(R.string.err_no_token))
            return
        }

        screenConfig = readScreenConfig()
        try {
            bootstrap(resultCode, resultData)
        } catch (t: Throwable) {
            Log.e(TAG, "启动流水线失败", t)
            shutdown("启动失败：${t.message}")
        }
    }

    private fun bootstrap(resultCode: Int, resultData: Intent) {
        val settings = settingsStore.current()
        // 每次「开启翻译」都是一次全新的会话：提醒可以再露面一次
        shownReminders.clear()
        bannerCooldown.clear()
        mainHandler.removeCallbacks(bannerHideRunnable)

        ocr = OcrProcessor()
        pipeline = TranslationPipeline(ocr!!)
        engine = TranslatorFactory.create(settings, this)
            .also { engineSignature = TranslatorFactory.signatureOf(settings) }

        val renderer = OverlayRenderer(this)
        renderer.onBubbleTap = { requestManualTranslation() }
        renderer.onMenuAction = { action -> onMenuAction(action) }
        renderer.onBubbleMoved = { anchor ->
            // 同时记住绝对像素与比例：比例是横竖屏都能复用的那一份
            settingsStore.update {
                it.copy(
                    bubbleX = anchor.x,
                    bubbleY = anchor.y,
                    bubbleXFrac = anchor.xFrac,
                    bubbleYFrac = anchor.yFrac,
                )
            }
        }
        overlay = renderer

        val canDraw = renderer.canDrawOverlays()
        if (!canDraw) {
            ServiceState.setError(getString(R.string.err_overlay_permission))
            setBanner(getString(R.string.hint_need_overlay), BannerKind.ERROR)
        } else {
            // 先加译文层再加悬浮球：后加的窗口在上层，这样悬浮球不会被译文/提示条压住
            renderer.showCanvas(settings)
            if (settings.bubbleEnabled) renderer.showBubble(settings)
            if (!settings.bubbleEnabled) {
                setBanner(getString(R.string.hint_bubble_off), BannerKind.INFO)
            }
        }

        val manager = ScreenCaptureManager(this)
        capture = manager
        manager.start(resultCode, resultData, screenConfig, captureListener)
        registerDisplayListener()

        ServiceState.setRunning(true)
        ServiceState.setStatus(
            if (settings.autoTranslate) getString(R.string.status_auto_on)
            else getString(R.string.status_manual_on),
        )
        updateNotification(getString(R.string.notif_text_running), force = true)

        loopJob = scope.launch { runLoop() }
        startOverlayWatchdog()
        Log.i(TAG, "流水线已启动，模式=${settings.mode.id}，触发=${if (settings.autoTranslate) "自动" else "手动"}")
    }

    // ------------------------------------------------------------ 悬浮窗守护

    /**
     * 起一条每秒一次的守护协程，保证「屏幕上永远有一个物理可见、可点的入口」。
     *
     * 这条不变量在 v1.0 就立过（当时是「不能依赖有译文才显示悬浮球」），
     * 但手动模式下它其实**没有被真正保证**：球的所有修复逻辑都写在 [tick] 里，
     * 而 tick 只在被点击时跑 —— 球一旦跑到屏幕外，就再也没有东西能触发修复。
     * 横屏正是最容易触发这个死锁的场景（竖屏的 y 拎到矮了一多半的横屏里就是屏幕外）。
     *
     * 所以修复逻辑必须**脱离取帧循环**：见 [maintainOverlay]。
     */
    private fun startOverlayWatchdog() {
        maintainJob?.cancel()
        maintainJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(OVERLAY_WATCHDOG_MS)
                if (shuttingDown.get()) break
                runCatching { maintainOverlay() }
                    .onFailure { Log.w(TAG, "悬浮窗守护失败", it) }
            }
        }
    }

    /**
     * 守护一次：把悬浮窗的几何状态拉回正轨。全部在主线程做，且**完全幂等**。
     *
     * 三件事，按顺序：
     * 1. [OverlayRenderer.syncGeometry]：重新读屏幕尺寸，把跑到屏幕外的球夹回来；
     *    尺寸真的变了还会按球心比例搬到新位置；
     * 2. 球**不在了**就补回来（可能是被系统清掉了，也可能是加窗口那次失败了）；
     * 3. 译文层同理，但只在用户开着它的时候补。
     *
     * 用户主动关掉悬浮球（`bubbleEnabled = false`）时**不补** —— 那是他的选择，
     * 不是故障。
     */
    private suspend fun maintainOverlay() {
        val renderer = overlay ?: return
        if (!renderer.canDrawOverlays()) return
        val raw = settingsStore.current()

        withContext(Dispatchers.Main) {
            renderer.syncGeometry()

            if (raw.bubbleEnabled && !renderer.isBubbleAttached) {
                Log.i(TAG, "守护发现悬浮球不见了，重新补上")
                renderer.showBubble(raw)
            }
            if (raw.overlayEnabled && !renderer.isCanvasAttached) {
                Log.i(TAG, "守护发现译文层不见了，重新补上")
                renderer.showCanvas(raw)
            }
        }
    }

    /**
     * Service 也能收到配置变化（清单里给 service 声明了 configChanges，系统不会重启它）。
     *
     * 这里**不能**只靠 [OverlayRenderer] 里那个 DisplayManager 监听：实测部分 ROM
     * 在 App 进入全屏横屏时并不发 `onDisplayChanged`，于是球留在竖屏坐标上就消失了。
     * 两条路都接上，哪条先到都能把球捞回来。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "配置变化：横竖屏=${newConfig.orientation}，重排悬浮窗并重建取帧链")
        runOnMain { overlay?.syncGeometry() }
        onScreenGeometryChanged()
    }

    // ------------------------------------------------------------ 主循环

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val manual = !settingsStore.current().autoTranslate

            if (manual) {
                ServiceState.setStatus(getString(R.string.status_manual_on))
                // 等到「被点一下」为止；超时也醒一次，好让切模式能及时生效
                val triggered = withTimeoutOrNull(IDLE_POLL_MS) { manualTrigger.receive() } != null
                if (settingsStore.current().autoTranslate) continue
                if (!triggered) continue
            }

            val began = SystemClock.elapsedRealtime()
            try {
                tick(manual = manual)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.w(TAG, "帧处理异常", t)
                ServiceState.setError(t.message ?: t::class.java.simpleName)
                setBubble(BubbleState.ERROR, 0)
            }

            if (settingsStore.current().autoTranslate) {
                val interval = settingsStore.current().captureIntervalMs
                val spent = SystemClock.elapsedRealtime() - began
                delay((interval - spent).coerceAtLeast(120L))
            } else {
                scheduleAutoClear()
            }
        }
    }

    private suspend fun tick(manual: Boolean) {
        val manager = capture ?: return
        val renderer = overlay
        val pipe = pipeline ?: return

        val raw = settingsStore.current()
        val (settings, preflightHint) = resolveRuntimeSettings(raw)
        // 这里只登记状态（设置页要能查到），**不直接碰提示条**：
        // 提示条的内容统一在帧末按本轮结论决定。以前这里 clear、帧末又 show，
        // 去重状态被复位，同一条提醒就变成每帧闪一下、实际常驻。
        ServiceState.setHint(preflightHint)

        ensureEngine(settings)
        val activeEngine = engine ?: return

        if (raw.overlayEnabled && renderer != null && !renderer.isCanvasAttached) {
            renderer.showCanvas(raw)
        }

        // 悬浮球的开关 / 尺寸 / 不透明度都做成运行中即时生效，不用重启服务
        if (renderer != null && renderer.canDrawOverlays()) {
            when {
                raw.bubbleEnabled && !renderer.isBubbleAttached -> renderer.showBubble(raw)
                !raw.bubbleEnabled && renderer.isBubbleAttached -> renderer.hideBubble()
                raw.bubbleEnabled -> renderer.updateBubbleAppearance(raw)
            }
        }

        setBubble(BubbleState.WORKING, ServiceState.lastBlocks.value)

        // 手动模式下先把上一轮的译文和悬浮球藏起来，保证抓到的画面是干净的
        if (manual) {
            runOnMain {
                renderer?.clear()
                renderer?.setBubbleCovered(true)
            }
            delay(CAPTURE_SETTLE_MS)
        }

        val frame = try {
            captureWithRetry(manager)
        } finally {
            if (manual) runOnMain { renderer?.setBubbleCovered(false) }
        }

        if (frame == null) {
            // 自愈：如果上一次转屏重建取帧链是失败的，这里强制重做一次再试。
            // 不做这一步的话，用户点多少次都是同一个感叹号 —— 只能重启翻译。
            if (manager.lastResizeError != null) {
                Log.w(TAG, "上次重建取帧链失败，尝试自愈一次")
                val healed = runCatching { manager.resize(screenConfig, force = true) }
                    .getOrDefault(false)
                if (healed) {
                    val retry = captureWithRetry(manager)
                    if (retry != null) {
                        return finishTick(retry, renderer, pipe, settings, raw, preflightHint)
                    }
                }
            }
            // 取不到帧有两种情况，给的提示必须不一样：
            // 1. **转屏后取帧链坏了**（resize 没成功）—— 点多少次都没用，得重新开启翻译；
            // 2. 画面静止时 VirtualDisplay 不推缓冲 —— 正常现象，再点一次通常就有。
            val resizeBroken = manager.lastResizeError != null
            if (resizeBroken) {
                ServiceState.setStatus(getString(R.string.status_no_frame_after_rotate))
                runOnMain {
                    setBanner(getString(R.string.banner_landscape_recapture_failed), BannerKind.ERROR)
                }
            } else {
                ServiceState.setStatus(getString(R.string.status_no_frame))
            }
            setBubble(BubbleState.ERROR, 0)
            return
        }

        finishTick(frame, renderer, pipe, settings, raw, preflightHint)
    }

    /** 帧到手之后的正常流程：翻译 → 渲染 → 提示条分级。抽出来是为了让自愈路径能复用。 */
    private suspend fun finishTick(
        frame: android.graphics.Bitmap,
        renderer: com.saltin.screentranslator.overlay.OverlayRenderer?,
        pipe: TranslationPipeline,
        settings: AppSettings,
        raw: AppSettings,
        preflightHint: String?,
    ) {
        val activeEngine = engine ?: return

        val result: PipelineResult = pipe.process(activeEngine, frame, settings)

        if (raw.overlayEnabled) {
            renderer?.render(raw, result.blocks)
        } else {
            renderer?.clear()
        }

        ServiceState.setStats(result.stats)
        ServiceState.setLastBlocks(result.blocks.size)
        ServiceState.setStatus(describe(result, settings))
        ServiceState.setError(result.stats.note)

        // 出错时把错误顶到屏幕上，别让它只躺在设置页里；
        // 不致命但重要的提醒（离线品质、自动降级原因）用黄色条单独呈现。
        //
        // 两者的呈现策略不同：
        // - **致命错误常驻**：用户必须知道现在为什么翻不出来；
        // - **提醒只在本次开启翻译时露一次脸**：它是环境性的，配置不变就每帧复现，
        //   每帧都弹等于把提示条变成常驻横幅（见 [shownReminders]）。
        val note = result.stats.note
        // preflightHint 是「你得去改设置」的配置问题（比如没配 Key 被临时降级到端侧），
        // 比 pipeline 的提醒更该被看到，所以排在前面；但两条**各自**都要有机会露一次脸，
        // 不能因为前一条已提示过就把后一条永久压住。
        val reminders = listOfNotNull(preflightHint, result.stats.warning)
        when {
            note != null -> {
                setBanner(getString(R.string.banner_translate_failed, note), BannerKind.ERROR)
                setBubble(BubbleState.ERROR, 0)
            }

            result.blocks.isEmpty() -> {
                setBanner(getString(R.string.banner_nothing_found), BannerKind.WARN)
                setBubble(BubbleState.DONE, 0)
            }

            reminders.isNotEmpty() -> {
                // 取第一条还没提示过的；全提示过了就不干预，让上一条自己到期淡出
                reminders.firstOrNull { shownReminders.add(it) }
                    ?.let { setBanner(it, BannerKind.WARN) }
                setBubble(BubbleState.DONE, result.blocks.size)
            }

            else -> {
                clearBanner()
                setBubble(BubbleState.DONE, result.blocks.size)
            }
        }

        refreshNotification(result)
    }

    /**
     * 取一帧。
     *
     * VirtualDisplay 只有在画面有更新时才会推送新缓冲，所以在画面完全静止时
     * `acquireLatestImage()` 有可能返回 null。手动模式下用户是「点了才翻」，
     * 这时候返回 null 会让他觉得「点了没反应」，所以这里做几次短重试。
     */
    private suspend fun captureWithRetry(manager: ScreenCaptureManager): android.graphics.Bitmap? {
        repeat(CAPTURE_RETRY) { attempt ->
            val frame = manager.captureSingleFrame()
            if (frame != null) return frame
            if (attempt < CAPTURE_RETRY - 1) delay(CAPTURE_RETRY_DELAY_MS)
        }
        return null
    }

    private fun describe(result: PipelineResult, settings: AppSettings): String {
        val s = result.stats
        val builder = StringBuilder()
        builder.append(if (settings.autoTranslate) "连续翻译中" else "已翻译")
        builder.append(" · ").append(s.renderedBlocks).append(" 条")
        builder.append(" · 总 ").append(s.totalMs).append("ms")
        if (settings.mode == TranslateMode.ON_DEVICE) {
            builder.append("（OCR ").append(s.ocrMs).append(" / 端侧译 ").append(s.translateMs).append("）")
        } else {
            builder.append("（OCR ").append(s.ocrMs).append(" / 云端 ").append(s.translateMs).append("）")
        }
        if (s.scriptLabel.isNotBlank()) builder.append(" · 识别器 ").append(s.scriptLabel)
        if (s.mergedLines > 0) builder.append(" · 合并 ").append(s.mergedLines).append(" 行换行")
        if (s.cacheHits > 0) builder.append(" · 缓存命中 ").append(s.cacheHits)
        if (s.requestChars > 0) builder.append(" · 上报 ").append(s.requestChars).append(" 字")
        if (s.unchangedBlocks > 0 && s.unchangedBlocks == s.renderedBlocks) {
            builder.append(" · 未改动 ").append(s.unchangedBlocks)
        }
        if (s.truncated) builder.append(" · 已截断")
        return builder.toString()
    }

    // ------------------------------------------------------------ 运行时降级

    /**
     * 把用户设置翻译成「这次真正能跑起来的设置」。
     *
     * 关键点是**绝不静默失败**，也**绝不越权**：
     * - 源语言的文字系统端侧根本读不了（俄语/阿拉伯语/泰语…）→ 云端模式下自动升到「整屏多模态」；
     *   但用户要是明确选了端侧离线，就只报错说明，**绝不偷偷把屏幕内容送到云端**；
     * - 云端没配 Key → 临时切到端侧离线，保证点了就有东西出来，并用提示条说明原因；
     * - 端侧语言对不支持 → 明确告诉用户换语言或换模式。
     * 用户一旦在设置页填好 Key，下一帧就自动切回云端，不用重启服务。
     */
    private fun resolveRuntimeSettings(raw: AppSettings): Pair<AppSettings, String?> {
        val provider = TranslatorFactory.resolveProvider(raw)
        val hasKey = TranslatorFactory.effectiveApiKey(raw).isNotBlank()
        val unsupported = ScriptSupport.unsupportedScriptName(raw.scriptMode, raw.sourceLanguage)

        if (unsupported != null) {
            when (raw.mode) {
                TranslateMode.ON_DEVICE ->
                    // 隐私优先：用户要的是离线，不能替他改主意
                    return raw to getString(R.string.hint_script_offline_impossible, unsupported)

                TranslateMode.HYBRID ->
                    return if (hasKey && provider.supportsVision) {
                        raw.copy(mode = TranslateMode.CLOUD_VISION) to
                            getString(R.string.hint_script_switch_vision, unsupported)
                    } else {
                        raw to getString(R.string.hint_script_need_cloud, unsupported)
                    }

                TranslateMode.CLOUD_VISION ->
                    if (!provider.supportsVision) {
                        return raw to getString(
                            R.string.hint_script_need_vision_model,
                            unsupported,
                            provider.label,
                        )
                    }
            }
        }

        if (raw.mode == TranslateMode.ON_DEVICE) {
            val src = SupportedLanguages.label(raw.sourceLanguage)
            val tgt = SupportedLanguages.label(raw.targetLanguage)
            return when (raw.offlineEngine) {
                // ML Kit 只收录了 59 种语言，语言对不在表里就只能换
                OfflineEngineKind.ML_KIT ->
                    if (SupportedLanguages.onDeviceSupported(raw.sourceLanguage, raw.targetLanguage)) {
                        raw to null
                    } else {
                        raw to getString(R.string.hint_offline_pair_unsupported, src, tgt)
                    }

                // Opus-MT 是按语言对训练的；增强 / 全能两档**都允许英语接力**（v1.6.2），
                // 差别只在可下载方向集合。两头都不行时才明确提示换引擎，绝不静默降级。
                OfflineEngineKind.OPUS_MT, OfflineEngineKind.OPUS_FULL -> when {
                    raw.sourceLanguage == "auto" ->
                        raw to getString(R.string.hint_offline_opus_auto_source)

                    else -> {
                        // 用「该档位实际会走的路由」判断，保证提示与真正会发生的行为一致
                        val route = OfflineModelCatalog.routeFor(
                            raw.offlineEngine,
                            raw.sourceLanguage,
                            raw.targetLanguage,
                        )
                        when {
                            route == null ->
                                if (raw.offlineEngine == OfflineEngineKind.OPUS_MT) {
                                    // 「增强」缺的方向可能在「全能」里有，引导过去即可
                                    raw to getString(
                                        R.string.hint_offline_opus_enhanced_pair_missing,
                                        src,
                                        tgt,
                                    )
                                } else {
                                    raw to getString(R.string.hint_offline_opus_pair_missing, src, tgt)
                                }

                            // 接力是一次性的环境说明，交给提示条只露一次脸就够
                            route.isPivot ->
                                raw to getString(
                                    R.string.hint_offline_opus_pivot,
                                    src,
                                    route.pivotLabel,
                                    tgt,
                                )

                            else -> raw to null
                        }
                    }
                }
            }
        }

        if (!hasKey) {
            return raw.copy(mode = TranslateMode.ON_DEVICE) to
                getString(R.string.hint_no_api_key, provider.label)
        }
        return raw to null
    }

    // ------------------------------------------------------------ 引擎生命周期

    private fun ensureEngine(settings: AppSettings) {
        val signature = TranslatorFactory.signatureOf(settings)
        if (engine != null && signature == engineSignature) return
        runCatching { engine?.close() }
        engine = TranslatorFactory.create(settings, this)
        engineSignature = signature
        pipeline?.reset()
        Log.i(TAG, "翻译引擎已切换：$signature")
    }

    // ------------------------------------------------------------ 触发与状态

    private fun requestManualTranslation() {
        cancelAutoClear()
        manualTrigger.trySend(Unit)
    }

    private fun cancelAutoClear() {
        autoClearJob?.cancel()
        autoClearJob = null
    }

    private fun onMenuAction(action: BubbleMenuView.Action) {
        when (action) {
            BubbleMenuView.Action.TRANSLATE_NOW -> requestManualTranslation()

            BubbleMenuView.Action.CLEAR -> {
                runOnMain { overlay?.clear() }
                setBubble(BubbleState.IDLE, 0)
            }

            BubbleMenuView.Action.TOGGLE_AUTO -> {
                val next = !settingsStore.current().autoTranslate
                settingsStore.update { it.copy(autoTranslate = next) }
                if (next) requestManualTranslation()
            }

            BubbleMenuView.Action.OPEN_APP -> {
                runCatching {
                    startActivity(
                        Intent()
                            .setClassName(packageName, "com.saltin.screentranslator.ui.MainActivity")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            BubbleMenuView.Action.STOP_SERVICE -> stopSelf()
        }
    }

    private fun scheduleAutoClear() {
        autoClearJob?.cancel()
        val seconds = settingsStore.current().autoClearSeconds
        if (seconds <= 0) return
        autoClearJob = scope.launch {
            delay(seconds * 1000L)
            runOnMain { overlay?.clear() }
            setBubble(BubbleState.IDLE, 0)
        }
    }

    private fun setBubble(state: BubbleState, blocks: Int) {
        val settings = settingsStore.current()
        overlay?.updateBubble(
            BubbleUiModel(state = state, translatedBlocks = blocks, alpha = settings.bubbleAlpha),
        )
        val status = when {
            settings.autoTranslate -> "连续翻译中"
            state == BubbleState.WORKING -> "正在识别…"
            state == BubbleState.ERROR -> "上一次出错了"
            blocks > 0 -> "已翻译 $blocks 条"
            else -> "待命 · 点我翻译"
        }
        overlay?.updateMenuContext(status, settings.autoTranslate)
    }

    /**
     * 显示提示条，并安排到期自动收起。
     *
     * 整个方法体跑在主线程：[lastBannerText] 既要被逐帧的决策读、又要被淡出回调写，
     * 统一到主线程后就不必再操心可见性与竞态。
     */
    private fun setBanner(text: String, kind: BannerKind) = runOnMain {
        if (text == lastBannerText && kind == lastBannerKind) return@runOnMain

        if (kind != BannerKind.ERROR) {
            val now = SystemClock.elapsedRealtime()
            val until = bannerCooldown[text] ?: 0L
            if (now < until) return@runOnMain // 刚提示过，别刷屏
            bannerCooldown[text] = now + BANNER_REPEAT_COOLDOWN_MS
        }

        lastBannerText = text
        lastBannerKind = kind
        overlay?.showBanner(text, kind)

        mainHandler.removeCallbacks(bannerHideRunnable)
        // 致命错误不淡出：它就是「现在用不了」的说明书，得一直摆着
        if (kind != BannerKind.ERROR) {
            mainHandler.postDelayed(bannerHideRunnable, BANNER_AUTO_HIDE_MS)
        }
    }

    private fun clearBanner() = runOnMain {
        mainHandler.removeCallbacks(bannerHideRunnable)
        if (lastBannerText == null) return@runOnMain
        lastBannerText = null
        lastBannerKind = BannerKind.INFO
        overlay?.clearBanner()
    }

    /** 提示条到期自行收起；顺带复位去重状态，让同一条提示在需要时还能再次显示 */
    private val bannerHideRunnable = Runnable {
        if (lastBannerText == null) return@Runnable
        lastBannerText = null
        lastBannerKind = BannerKind.INFO
        overlay?.clearBanner()
    }

    // ------------------------------------------------------------ 捕获回调

    private val captureListener = object : ScreenCaptureManager.Listener {

        override fun onProjectionStopped() {
            Log.i(TAG, "收到投屏撤销回调，停止服务")
            runOnMain { shutdown(getString(R.string.status_projection_revoked)) }
        }

        override fun onCapturedContentResized(width: Int, height: Int) {
            // Android 14+：旋转屏幕 / 分屏尺寸变化时，重建 Surface 链，保持坐标 1:1。
            //
            // ⚠ 这里给的是「被录制内容」的尺寸，可能小于整块屏幕（单应用投屏、分屏）。
            // 所以**只在和当前尺寸不一样时**才动取帧链，避免把好好的链路反复重建弄坏。
            if (width <= 0 || height <= 0) return
            if (width == screenConfig.width && height == screenConfig.height) {
                runOnMain { overlay?.syncGeometry() }
                return
            }
            screenConfig = screenConfig.copy(width = width, height = height)
            val ok = runCatching { capture?.resize(screenConfig) ?: true }
                .onFailure { Log.w(TAG, "重建 Surface 失败", it) }
                .getOrDefault(true)
            if (!ok) {
                // 取帧链重建失败：这是「横屏点了翻译球却一直感叹号」的直接原因，
                // 必须明确告诉用户，并给出可执行的下一步。
                Log.w(TAG, "转屏后取帧链重建失败，提示用户重新开启翻译")
                runOnMain {
                    setBanner(getString(R.string.banner_landscape_recapture_failed), BannerKind.ERROR)
                    setBubble(BubbleState.IDLE, 0)
                }
            }
            // 悬浮窗也要跟着走：横屏时球必须留在看得见的地方
            runOnMain { overlay?.syncGeometry() }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible) runOnMain { overlay?.clear() }
        }

        override fun onCaptureError(message: String, cause: Throwable?) {
            Log.w(TAG, message, cause)
        }
    }

    // ------------------------------------------------------------ 通知

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && text == lastNotificationText) return
        if (!force && now - lastNotificationAt < NOTIFICATION_REFRESH_INTERVAL_MS) return
        lastNotificationText = text
        lastNotificationAt = now
        runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.notify(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.build(this, text, manual = !settingsStore.current().autoTranslate),
            )
        }
    }

    private fun refreshNotification(result: PipelineResult) {
        updateNotification(
            getString(R.string.notif_text_running) + " · " + result.stats.renderedBlocks + " 条",
        )
    }

    // ------------------------------------------------------------ 收尾

    private fun shutdown(reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        Log.i(TAG, "收尾：$reason")

        loopJob?.cancel()
        loopJob = null
        autoClearJob?.cancel()
        autoClearJob = null
        maintainJob?.cancel()
        maintainJob = null

        unregisterDisplayListener()

        runCatching { capture?.stop() }
        capture = null

        runCatching { pipeline?.release() }
        pipeline = null

        runCatching { ocr?.close() }
        ocr = null

        runCatching { engine?.close() }
        engine = null
        engineSignature = null

        runCatching { overlay?.hide() }
        overlay = null

        ServiceState.resetForStop(reason)
        ServiceState.setHint(null)

        mainHandler.removeCallbacks(bannerHideRunnable)
        lastBannerText = null
        lastBannerKind = BannerKind.INFO
        shownReminders.clear()
        bannerCooldown.clear()

        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onDestroy() {
        shutdown(getString(R.string.status_idle))
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 屏幕旋转

    /**
     * 屏幕几何变化（横竖屏、折叠屏展开、分屏）→ 重建取帧链。
     *
     * 为什么不能只依赖 Android 14 的 `onCapturedContentResize`：那个回调**只在 API 34+ 才有**。
     * Android 13 及以下横屏时 VirtualDisplay 仍然保持着竖屏的宽高，抓到的帧是被拉伸/裁切的，
     * OCR 认出来自然是错的 —— 用户反馈的「译文变成另一种语言」「翻完还是原文」有相当一部分出自这里。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            onScreenGeometryChanged()
        }
    }

    private fun registerDisplayListener() {
        val manager = getSystemService(DisplayManager::class.java) ?: return
        runCatching { manager.registerDisplayListener(displayListener, mainHandler) }
            .onFailure { Log.w(TAG, "注册屏幕变化监听失败", it) }
    }

    private fun unregisterDisplayListener() {
        val manager = getSystemService(DisplayManager::class.java) ?: return
        runCatching { manager.unregisterDisplayListener(displayListener) }
    }

    /**
     * 尺寸真的变了才重建：读实时尺寸 → `resize` 重建 Surface 链 → 清掉旧译文。
     * 与 Android 14 的原生回调可能重复触发，靠尺寸相等判断天然幂等。
     */
    private fun onScreenGeometryChanged() {
        val next = readScreenConfig()
        if (next.width == screenConfig.width && next.height == screenConfig.height) return
        Log.i(TAG, "屏幕尺寸变化：${screenConfig.width}x${screenConfig.height} → ${next.width}x${next.height}")
        screenConfig = next
        val ok = runCatching { capture?.resize(next) ?: true }
            .onFailure { Log.w(TAG, "屏幕旋转后重建 Surface 失败", it) }
            .getOrDefault(true)
        // 旧译文的坐标是按旋转前那块屏幕算出来的，留着只会错位地挡住画面
        runOnMain {
            overlay?.clear()
            setBubble(BubbleState.IDLE, 0)
            if (!ok) {
                // 取帧链没重建成功 —— 这时候装作无事发生，用户下一次点翻译球只会再看到感叹号。
                // 所以直接说清楚原因和下一步动作。
                setBanner(getString(R.string.banner_landscape_recapture_failed), BannerKind.ERROR)
            }
            // 顺手把悬浮窗的几何状态对齐（含把跑到屏幕外的球夹回来）
            overlay?.syncGeometry()
        }
    }

    // ------------------------------------------------------------ 屏幕参数

    private fun readScreenConfig(): CaptureConfig {
        val density = resources.displayMetrics.densityDpi
        val metrics = DisplayMetrics()
        val displayManager = getSystemService(DisplayManager::class.java)
        val display: Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            return CaptureConfig(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
        val fallback = resources.displayMetrics
        return CaptureConfig(fallback.widthPixels, fallback.heightPixels, density)
    }
}

/** 便捷启动/停止/触发入口，供 UI、QS 磁贴、通知、悬浮球共用 */
object TranslationServiceController {

    fun start(context: Context, resultCode: Int, resultData: Intent) {
        val intent = Intent(context, TranslationForegroundService::class.java)
            .setAction(TranslationForegroundService.ACTION_START)
            .putExtra(TranslationForegroundService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslationForegroundService.EXTRA_RESULT_DATA, resultData)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, TranslationForegroundService::class.java)
            .setAction(TranslationForegroundService.ACTION_STOP)
        // 服务通常已在运行，普通 startService 即可；
        // 万一已经不在运行，捕获异常避免崩溃。
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "停止服务失败（可能已停止）", it) }
    }

    /** 手动触发一次翻译（通知栏按钮、QS 磁贴、设置页按钮都用这个） */
    fun translateNow(context: Context) {
        val intent = Intent(context, TranslationForegroundService::class.java)
            .setAction(TranslationForegroundService.ACTION_TRANSLATE_NOW)
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "触发翻译失败", it) }
    }

    fun clear(context: Context) {
        val intent = Intent(context, TranslationForegroundService::class.java)
            .setAction(TranslationForegroundService.ACTION_CLEAR)
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "清空译文失败", it) }
    }

    /** 把悬浮球搬回默认位置（设置在「显示」页的应急按钮用这个） */
    fun resetBubble(context: Context) {
        val intent = Intent(context, TranslationForegroundService::class.java)
            .setAction(TranslationForegroundService.ACTION_RESET_BUBBLE)
        runCatching { context.startService(intent) }
            .onFailure { Log.w(TAG, "重置悬浮球位置失败（服务可能未运行）", it) }
    }

    private const val TAG = "TranslationController"
}
