package com.saltin.screentranslator.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.saltin.screentranslator.core.AppSettings
import com.saltin.screentranslator.translate.TranslatedBlock
import kotlin.math.roundToInt

/**
 * 悬浮球在屏幕上的位置：**绝对像素** + **球心相对屏幕的比例**。
 *
 * 绝对像素用来立刻摆放窗口；比例用来在屏幕旋转之后把球放回同一个相对位置
 * （竖屏在右侧 42% 高度处，横屏也应该是右侧 42% 高度处）。
 * 只存像素正是「一横屏小球就消失」的根因：竖屏的 y=2000 拿到高度只有 1080 的横屏里，
 * 直接就掉到屏幕外面去了。
 */
data class BubbleAnchor(
    val x: Int,
    val y: Int,
    val xFrac: Float,
    val yFrac: Float,
)

/**
 * 阶段四：WindowManager 悬浮窗管理（三个窗口）。
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 1. 悬浮球  BubbleView        常驻、可拖动、**可点击**          │
 * │    单击 = 翻译当前屏幕 / 长按 = 快捷菜单 / 拖动 = 换位置        │
 * │ 2. 译文层  ComposeView       全屏、FLAG_NOT_TOUCHABLE（点击穿透）│
 * │    按 OCR 坐标把译文原位覆盖在原文之上                          │
 * │ 3. 快捷菜单 BubbleMenuView   长按悬浮球时出现在球旁边            │
 * └──────────────────────────────────────────────────────────────┘
 *
 * ⚠️ v1.0 的教训：当时只有第 2 个窗口，而它**在没有译文时是 100% 透明的**，
 * 并且默认开了点击穿透 —— 结果就是用户「什么都看不见、也点不到」。
 * 所以现在必须有一个物理上永远可见、永远可点的悬浮球作为入口。
 *
 * 坐标系：译文层用 FLAG_LAYOUT_NO_LIMITS + setFitInsetsTypes(0) + 允许画进刘海，
 * 保证窗口 (0,0) 就是物理屏幕左上角，与 MediaProjection 抓到的帧坐标一一对应。
 */
class OverlayRenderer(private val appContext: Context) {

    companion object {
        private const val TAG = "OverlayRenderer"
        private const val EDGE_MARGIN_DP = 6f

        /** 没有记录过位置时的兜底球心比例：屏幕右侧、高度 42% 处 */
        private const val FALLBACK_X_FRAC = 0.92f
        private const val FALLBACK_Y_FRAC = 0.42f

        /** 悬浮球：不抢焦点，但要能点、能拖 */
        private val BUBBLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /** 快捷菜单：同理，可点但不抢焦点 */
        private val MENU_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        /** 译文层：不抢焦点，且**完全不接收触摸**，点击全部穿透到下面的应用 */
        private val CANVAS_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    // ---------------------------------------------------------------- 回调

    /** 悬浮球被单击 —— 服务收到后立即抓帧翻译 */
    var onBubbleTap: (() -> Unit)? = null

    /** 快捷菜单里点了某一项 */
    var onMenuAction: ((BubbleMenuView.Action) -> Unit)? = null

    /** 拖动结束，把新位置（像素 + 比例）交回服务持久化 */
    var onBubbleMoved: ((BubbleAnchor) -> Unit)? = null

    // ---------------------------------------------------------------- 内部

    private val windowManager: WindowManager =
        appContext.getSystemService(WindowManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val canvasState = mutableStateOf(OverlayUiState())

    private var canvasView: ComposeView? = null
    private var canvasOwner: OverlayViewTreeOwner? = null
    private var canvasParams: WindowManager.LayoutParams? = null

    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleModel = BubbleUiModel()

    private var menuView: BubbleMenuView? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var menuStatus = "待命"
    private var menuAuto = false

    /**
     * 用户正在拖动悬浮球。
     *
     * 拖的过程中禁止 [syncGeometry] 插手 —— 否则守护协程会在手指底下把球夹走，
     * 体验上就是「拖到一半自己弹回去」。
     */
    private var draggingBubble = false

    private var screenWidth = 1080
    private var screenHeight = 1920
    private val density: Float get() = appContext.resources.displayMetrics.density

    val isBubbleAttached: Boolean get() = bubbleView != null
    val isCanvasAttached: Boolean get() = canvasView != null
    val isAttached: Boolean get() = isBubbleAttached || isCanvasAttached
    val isMenuShowing: Boolean get() = menuView != null

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    // ---------------------------------------------------------------- 屏幕旋转

    private var displayListenerRegistered = false

    /**
     * 监听「屏幕可用区域变了」——旋转、折叠屏展开、分屏拖动分隔条都会触发。
     *
     * 这里刻意**不**依赖 `Service.onConfigurationChanged`：Service 默认收不到那个回调
     * （它不像 Activity 会重建，除非在清单里声明 configChanges），而 DisplayManager
     * 的监听对 Service 一样有效，而且覆盖面更宽。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            syncGeometry()
        }
    }

    private fun ensureDisplayListener() {
        if (displayListenerRegistered) return
        val manager = appContext.getSystemService(DisplayManager::class.java) ?: return
        runCatching { manager.registerDisplayListener(displayListener, mainHandler) }
            .onSuccess { displayListenerRegistered = true }
            .onFailure { Log.w(TAG, "注册屏幕变化监听失败", it) }
    }

    private fun releaseDisplayListener() {
        if (!displayListenerRegistered) return
        displayListenerRegistered = false
        val manager = appContext.getSystemService(DisplayManager::class.java) ?: return
        runCatching { manager.unregisterDisplayListener(displayListener) }
    }

    /**
     * 把悬浮球与译文层的几何状态跟**当前屏幕**对齐一次。
     *
     * 故意做成**幂等**的：随便调、调多少次都不会有副作用，所以调用方不需要判断
     * 「现在到底需不需要重排」。服务的守护协程每秒调一次，旋转/配置变化也会调。
     *
     * 有两件事必须做，缺一不可：
     * 1. **尺寸变了** → 按球心比例把球搬到新尺寸的同一个相对位置
     *    （竖屏停在右侧 42% 高度，横屏也该在右侧 42% 高度）。像素直接搬过去会落到屏幕外，
     *    用户看到的就是「一横屏小球就不见了」。
     * 2. **尺寸没变也要重新夹一次**。这是横屏丢球的关键修复：只要尺寸读数有一瞬间还是旧的
     *    （或者 ROM 干脆不发 [displayListener] 的回调），球的 y 就留在竖屏坐标上（屏幕外），
     *    而「尺寸没变就跳过」会让它永远回不来 —— 手动模式下更是死锁，
     *    因为唯一能触发重排的路径是「点一下球」，而球已经不在屏幕上了。
     *
     * `updateViewLayout` 只能在窗口所属线程调用，所以整体切到主线程。
     */
    fun syncGeometry() = runOnMain {
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        refreshScreenSize()
        val resized = oldWidth != screenWidth || oldHeight != screenHeight
        if (resized) {
            Log.i(TAG, "屏幕尺寸变化：${oldWidth}x$oldHeight → ${screenWidth}x${screenHeight}")
            // 菜单是临时浮在球旁边的窗口，尺寸一变位置就没意义了，直接收掉
            hideMenu()

            // 译文层是 MATCH_PARENT，系统会自己改尺寸；但旋转后重新提交一次参数，
            // 可以让刘海/圆角的避让按新方向重算，避免横屏时译文被裁掉一条。
            // 只在真的变了时才提交 —— 这是个全屏窗口，每秒提交一次会白白触发重绘。
            val canvas = canvasView
            val canvasLp = canvasParams
            if (canvas != null && canvasLp != null) {
                runCatching { windowManager.updateViewLayout(canvas, canvasLp) }
                    .onFailure { Log.w(TAG, "刷新译文层失败", it) }
            }
        }

        val view = bubbleView ?: return@runOnMain
        val params = bubbleParams ?: return@runOnMain
        // 用户正在拖的时候别去抢方向盘，松手后的下一次守护会对齐
        if (draggingBubble) return@runOnMain

        val (x, y) = if (resized && oldWidth > 0 && oldHeight > 0 && params.width > 0) {
            val centerX = params.x + params.width / 2f
            val centerY = params.y + params.height / 2f
            val xFrac = (centerX / oldWidth).coerceIn(0f, 1f)
            val yFrac = (centerY / oldHeight).coerceIn(0f, 1f)
            clamp(
                (xFrac * screenWidth - params.width / 2f).roundToInt(),
                (yFrac * screenHeight - params.height / 2f).roundToInt(),
                params.width,
            )
        } else {
            clamp(params.x, params.y, params.width)
        }

        if (x == params.x && y == params.y) return@runOnMain
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "重新对齐悬浮球失败", it) }
        Log.i(TAG, "悬浮球已对齐到 ($x,$y)（屏幕 ${screenWidth}x$screenHeight）")
    }

    /** 把窗口参数换算成「绝对像素 + 球心比例」 */
    private fun anchorOf(params: WindowManager.LayoutParams): BubbleAnchor {
        val centerX = params.x + params.width / 2f
        val centerY = params.y + params.height / 2f
        return BubbleAnchor(
            x = params.x,
            y = params.y,
            xFrac = if (screenWidth > 0) {
                (centerX / screenWidth).coerceIn(0f, 1f)
            } else {
                FALLBACK_X_FRAC
            },
            yFrac = if (screenHeight > 0) {
                (centerY / screenHeight).coerceIn(0f, 1f)
            } else {
                FALLBACK_Y_FRAC
            },
        )
    }

    // ---------------------------------------------------------------- 悬浮球

    /** 添加常驻悬浮球。缺权限返回 false。 */
    fun showBubble(settings: AppSettings): Boolean {
        if (bubbleView != null) return true
        if (!canDrawOverlays()) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，无法添加悬浮球")
            return false
        }

        refreshScreenSize()
        ensureDisplayListener()
        return try {
            val sizePx = (settings.bubbleSizeDp * density).roundToInt().coerceAtLeast(28)
            val view = BubbleView(appContext).apply {
                model = bubbleModel.copy(alpha = settings.bubbleAlpha)
                onTap = { onBubbleTap?.invoke() }
                onLongPress = { toggleMenu() }
                onDragStart = {
                    hideMenu()
                    draggingBubble = true
                }
                onDrag = { dx, dy -> moveBubbleBy(dx, dy) }
                onDragEnd = {
                    draggingBubble = false
                    bubbleParams?.let { onBubbleMoved?.invoke(anchorOf(it)) }
                }
            }

            val position = resolveBubblePosition(settings, sizePx)
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                BUBBLE_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = position.first
                y = position.second
            }

            windowManager.addView(view, params)
            bubbleView = view
            bubbleParams = params
            Log.i(TAG, "悬浮球已添加 ${sizePx}px @(${position.first},${position.second})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "添加悬浮球失败", t)
            cleanupBubble()
            false
        }
    }

    /** 抓帧前把球藏起来，避免它自己被 OCR 认进去；抓完再显示。 */
    fun setBubbleCovered(covered: Boolean) = runOnMain {
        val view = bubbleView ?: return@runOnMain
        val target = if (covered) android.view.View.INVISIBLE else android.view.View.VISIBLE
        if (view.visibility != target) view.visibility = target
    }

    fun updateBubble(model: BubbleUiModel) = runOnMain {
        bubbleModel = model
        bubbleView?.model = model
    }

    fun updateBubbleAppearance(settings: AppSettings) = runOnMain {
        val view = bubbleView ?: return@runOnMain
        view.model = view.model.copy(alpha = settings.bubbleAlpha)

        val params = bubbleParams ?: return@runOnMain
        val sizePx = (settings.bubbleSizeDp * density).roundToInt().coerceAtLeast(28)
        if (params.width == sizePx && params.height == sizePx) return@runOnMain
        params.width = sizePx
        params.height = sizePx
        clampBubbleInto(params)
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "调整悬浮球尺寸失败", it) }
    }

    fun hideBubble() = runOnMain {
        hideMenu()
        cleanupBubble()
    }

    private fun cleanupBubble() {
        val view = bubbleView
        bubbleView = null
        bubbleParams = null
        if (view != null) {
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "移除悬浮球失败", it) }
        }
    }

    /**
     * 决定球这次出现在哪里，优先级从高到低：
     *
     * 1. **比例**（新版本记录的位置）—— 横竖屏都落在同一个相对位置；
     * 2. **绝对像素**（v1.4.5 及以前只存过这个）—— 直接拿来用，`clamp` 会兜住
     *    「在竖屏里拖到屏幕下方、升级后第一次正好是横屏启动」这类越界坐标，
     *    所以老用户升级后第一眼就能看到球，而不是以为它坏了；
     * 3. 全新安装 —— 屏幕右侧、高度 42% 处。
     */
    private fun resolveBubblePosition(settings: AppSettings, sizePx: Int): Pair<Int, Int> {
        if (settings.bubbleXFrac >= 0f && settings.bubbleYFrac >= 0f) {
            val centerX = settings.bubbleXFrac.coerceIn(0f, 1f) * screenWidth
            val centerY = settings.bubbleYFrac.coerceIn(0f, 1f) * screenHeight
            return clamp(
                (centerX - sizePx / 2f).roundToInt(),
                (centerY - sizePx / 2f).roundToInt(),
                sizePx,
            )
        }

        if (settings.bubbleX >= 0 && settings.bubbleY >= 0) {
            return clamp(settings.bubbleX, settings.bubbleY, sizePx)
        }

        val margin = (EDGE_MARGIN_DP * density).roundToInt()
        val defaultX = screenWidth - sizePx - margin
        val defaultY = (screenHeight * FALLBACK_Y_FRAC).roundToInt()
        return clamp(defaultX, defaultY, sizePx)
    }

    private fun moveBubbleBy(dx: Float, dy: Float) = runOnMain {
        val view = bubbleView ?: return@runOnMain
        val params = bubbleParams ?: return@runOnMain
        val (x, y) = clamp(
            (params.x + dx).roundToInt(),
            (params.y + dy).roundToInt(),
            params.width,
        )
        if (params.x == x && params.y == y) return@runOnMain
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "移动悬浮球失败", it) }
    }

    private fun clampBubbleInto(params: WindowManager.LayoutParams) {
        val (x, y) = clamp(params.x, params.y, params.width)
        params.x = x
        params.y = y
    }

    /**
     * 把悬浮球搬回默认位置（右侧、42% 高度处），并记住新位置。
     *
     * 这是给「球找不到了」留的应急入口：设置在「显示」页有一个按钮，
     * 万一某种没预料到的 ROM 行为让球落到了看不见的地方，用户至少还能自救，
     * 而不是只能卸载重装。
     */
    fun resetBubblePosition() = runOnMain {
        val view = bubbleView ?: return@runOnMain
        val params = bubbleParams ?: return@runOnMain
        refreshScreenSize()

        val margin = (EDGE_MARGIN_DP * density).roundToInt()
        val (x, y) = clamp(
            screenWidth - params.width - margin,
            (screenHeight * FALLBACK_Y_FRAC).roundToInt(),
            params.width,
        )
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "重置悬浮球位置失败", it) }
        Log.i(TAG, "悬浮球已重置到 ($x,$y)")
        onBubbleMoved?.invoke(anchorOf(params))
    }

    private fun clamp(x: Int, y: Int, sizePx: Int): Pair<Int, Int> {
        val margin = (EDGE_MARGIN_DP * density).roundToInt()
        val maxX = (screenWidth - sizePx - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - sizePx - margin).coerceAtLeast(margin)
        return x.coerceIn(margin, maxX) to y.coerceIn(margin, maxY)
    }

    // ---------------------------------------------------------------- 快捷菜单

    private fun toggleMenu() {
        if (menuView != null) hideMenu() else showMenu()
    }

    /**
     * 把「当前状态 + 是否连续模式」交给悬浮球自己记住。
     * 长按发生在 BubbleView 的事件里，那里拿不到服务的状态，所以必须提前灌进来。
     */
    fun updateMenuContext(status: String, autoTranslate: Boolean) = runOnMain {
        menuStatus = status
        menuAuto = autoTranslate
        menuView?.bind(status, autoTranslate)
    }

    private fun showMenu() = runOnMain {
        val status = menuStatus
        val autoTranslate = menuAuto
        if (menuView != null) {
            menuView?.bind(status, autoTranslate)
            return@runOnMain
        }
        if (!canDrawOverlays()) return@runOnMain
        val anchor = bubbleParams ?: return@runOnMain
        val bubble = bubbleView ?: return@runOnMain

        try {
            val view = BubbleMenuView(appContext).apply {
                bind(status, autoTranslate)
                onAction = { action ->
                    hideMenu()
                    onMenuAction?.invoke(action)
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                MENU_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            windowManager.addView(view, params)
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )

            val menuWidth = view.measuredWidth
            val menuHeight = view.measuredHeight
            val gap = (8f * density).roundToInt()
            val margin = (EDGE_MARGIN_DP * density).roundToInt()

            // 优先放在球的左侧，左边放不下就放右侧
            var x = anchor.x - menuWidth - gap
            if (x < margin) x = anchor.x + bubble.width + gap
            x = x.coerceIn(margin, (screenWidth - menuWidth - margin).coerceAtLeast(margin))

            // 底边尽量不超过屏幕
            var y = anchor.y + anchor.height / 2 - menuHeight / 2
            y = y.coerceIn(margin, (screenHeight - menuHeight - margin).coerceAtLeast(margin))

            params.x = x
            params.y = y
            runCatching { windowManager.updateViewLayout(view, params) }

            menuView = view
            menuParams = params
        } catch (t: Throwable) {
            Log.w(TAG, "弹出快捷菜单失败", t)
            cleanupMenu()
        }
    }

    fun hideMenu() = runOnMain { cleanupMenu() }

    private fun cleanupMenu() {
        val view = menuView
        menuView = null
        menuParams = null
        if (view != null) {
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "移除快捷菜单失败", it) }
        }
    }

    // ---------------------------------------------------------------- 译文层

    fun showCanvas(settings: AppSettings): Boolean {
        if (canvasView != null) return true
        if (!canDrawOverlays()) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，无法添加译文层")
            return false
        }

        // 译文层同样要跟着旋转走：横屏时 (0,0) 必须仍是物理屏幕左上角
        refreshScreenSize()
        ensureDisplayListener()

        return try {
            val owner = OverlayViewTreeOwner().also { it.attach() }
            val view = ComposeView(appContext).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent { OverlayContent(canvasState.value) }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                CANVAS_FLAGS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                // 不参与系统栏避让，保证 (0,0) 就是物理屏幕左上角（API 30+ 才有该接口）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            windowManager.addView(view, params)

            canvasView = view
            canvasOwner = owner
            canvasParams = params
            Log.i(TAG, "译文层已添加")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "添加译文层失败", t)
            cleanupCanvas()
            false
        }
    }

    fun hideCanvas() = runOnMain { cleanupCanvas() }

    private fun cleanupCanvas() {
        val view = canvasView
        canvasView = null
        canvasParams = null
        if (view != null) {
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "移除译文层失败", it) }
        }
        canvasOwner?.detach()
        canvasOwner = null
    }

    /** 刷新译文。可从任意线程调用，内部统一切到主线程。 */
    fun render(settings: AppSettings, blocks: List<TranslatedBlock>) {
        val snapshot = buildState(settings, blocks, canvasState.value.banner, canvasState.value.bannerKind)
        runOnMain {
            canvasState.value = snapshot
            bubbleModel = bubbleModel.copy(
                translatedBlocks = blocks.size,
                alpha = settings.bubbleAlpha,
            )
            bubbleView?.model = bubbleModel
        }
    }

    fun clear() = runOnMain {
        canvasState.value = canvasState.value.copy(blocks = emptyList(), visible = false)
        bubbleModel = bubbleModel.copy(translatedBlocks = 0)
        bubbleView?.model = bubbleModel
    }

    // ---------------------------------------------------------------- 顶部提示条

    fun showBanner(text: String, kind: BannerKind = BannerKind.INFO) {
        runOnMain {
            canvasState.value = canvasState.value.copy(banner = text, bannerKind = kind)
        }
    }

    fun clearBanner() {
        runOnMain {
            if (canvasState.value.banner == null) return@runOnMain
            canvasState.value = canvasState.value.copy(banner = null)
        }
    }

    fun hide() = runOnMain {
        hideMenu()
        cleanupBubble()
        cleanupCanvas()
        releaseDisplayListener()
    }

    private fun buildState(
        settings: AppSettings,
        blocks: List<TranslatedBlock>,
        banner: String?,
        bannerKind: BannerKind,
    ): OverlayUiState = OverlayUiState(
        blocks = blocks,
        visible = true,
        fontSizeScale = settings.fontSizeScale,
        textColor = settings.textColor,
        scrimColor = settings.scrimColor,
        scrimAlpha = settings.scrimAlpha,
        offsetX = settings.offsetXDp * density,
        offsetY = settings.offsetYDp * density,
        banner = banner,
        bannerKind = bannerKind,
    )

    // ---------------------------------------------------------------- 工具

    /**
     * 读出「当前屏幕到底多大」。
     *
     * ## 为什么这里要读三遍再校正
     *
     * 悬浮球的合法性完全建立在这个尺寸上：只要读成竖屏的 2400 高而实际是横屏的 1080，
     * [clamp] 就会认为 y=2200 是个合理位置，球于是永远待在屏幕外。
     *
     * 三种来源各有短板，所以叠着用：
     * 1. **`WindowMetrics.bounds`**（API 30+）：唯一明确「按当前旋转」给出的尺寸，
     *    首选；application context 上拿到的就是整块屏幕。
     * 2. **`getRealMetrics`**：老设备兜底。
     * 3. **`Configuration.orientation`**：拿它**校正前两者的宽高方向**。
     *    个别 ROM 在旋转瞬间会先返回「自然方向」的尺寸（还是竖的 1080x2400），
     *    而 Configuration 是框架全局用来决定横竖屏的依据，可信度最高 ——
     *    两者结论矛盾时，交换宽高。
     */
    private fun refreshScreenSize() {
        var width = 0
        var height = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val bounds = windowManager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    width = bounds.width()
                    height = bounds.height()
                }
            }.onFailure { Log.d(TAG, "读取 WindowMetrics 失败", it) }
        }

        if (width <= 0 || height <= 0) {
            val manager = appContext.getSystemService(DisplayManager::class.java)
            val display: Display? = manager?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    width = metrics.widthPixels
                    height = metrics.heightPixels
                }
            }
        }

        if (width <= 0 || height <= 0) {
            val fallback = appContext.resources.displayMetrics
            width = fallback.widthPixels
            height = fallback.heightPixels
        }

        // 用 Configuration 的横竖屏结论校正一次宽高方向
        val landscape = appContext.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val looksLandscape = width > height
        if (width != height && landscape != looksLandscape) {
            Log.w(TAG, "屏幕尺寸($width x $height)与横竖屏(${if (landscape) "横" else "竖"})矛盾，已按后者校正")
            val swap = width
            width = height
            height = swap
        }

        if (width > 0 && height > 0) {
            screenWidth = width
            screenHeight = height
        }
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
