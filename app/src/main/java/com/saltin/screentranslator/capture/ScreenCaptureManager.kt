package com.saltin.screentranslator.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.saltin.screentranslator.util.BitmapSlot

/** VirtualDisplay / ImageReader 的目标参数 */
data class CaptureConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    /** 环形缓冲深度：2 足够，太多浪费显存，太少丢帧 */
    val maxImages: Int = 2,
)

/**
 * 阶段一：屏幕帧捕获与内存安全。
 *
 * 关键约束（全部在代码里落实）：
 * 1. [captureSingleFrame] 用 `acquireLatestImage()` 拿到 Image 后，**无论成功失败**
 *    都在 `finally` 里 `image.close()`，把缓冲区立刻还给 ImageReader —— 不 OOM 的根本。
 * 2. Bitmap 缓冲通过 [BitmapSlot] 复用，帧率再高也不会持续产生大对象。
 * 3. 注册 [MediaProjection.Callback]，用户随时在系统里撤销投屏时立刻收到 [Listener.onProjectionStopped]。
 * 4. [stop] / 撤销回调里把 VirtualDisplay、ImageReader、MediaProjection、HandlerThread、Bitmap 全部释放。
 *
 * ⚠️ Android 14 (API 34) 两条硬规则：
 * - 调用 `getMediaProjection()` 之前，必须已经以 `mediaProjection` 类型进入前台（由 Service 保证）。
 * - 必须在 `createVirtualDisplay()` **之前**注册 Callback，否则抛 SecurityException。
 */
class ScreenCaptureManager(private val appContext: Context) {

    interface Listener {
        /** 用户或系统撤销了投屏权限 —— 服务收到后必须立即收尾并停止 */
        fun onProjectionStopped()

        /** Android 14+：横竖屏切换 / 折叠屏展开等导致被录制内容尺寸变化 */
        fun onCapturedContentResized(width: Int, height: Int) {}

        /** Android 14+：被录制内容是否可见（例如用户切到了别的应用） */
        fun onCapturedContentVisibilityChanged(isVisible: Boolean) {}

        fun onCaptureError(message: String, cause: Throwable?) {}
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "st-virtual-display"

        /** resize 之后验证取帧的总等待时间：0.6s 足够一次 60Hz 刷新推两帧 */
        private const val VERIFY_DEADLINE_MS = 600L

        /** 验证时两次 acquireLatestImage 之间的间隔 */
        private const val VERIFY_STEP_MS = 40L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureThread = HandlerThread("st-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var listener: Listener? = null

    /** 整帧缓冲（可能含 rowStride 填充） */
    private val paddedSlot = BitmapSlot()

    /** 裁掉填充后的成品帧 */
    private val frameSlot = BitmapSlot()

    /** 串行化「取帧 / 重建尺寸 / 释放」，避免竞态 */
    private val lock = Any()

    @Volatile
    private var released = false

    @Volatile
    private var selfInitiatedStop = false

    @Volatile
    var frameWidth: Int = 0
        private set

    @Volatile
    var frameHeight: Int = 0
        private set

    /**
     * 最近一次 [resize] 的失败原因；成功时为 null。
     *
     * 服务在「点了翻译球却取不到帧」时会读它来判断「是不是转屏把取帧链弄坏了」，
     * 从而给出比「未取到画面」更有用的提示。
     */
    @Volatile
    var lastResizeError: Throwable? = null
        private set

    val isRunning: Boolean
        get() = !released && projection != null && imageReader != null

    /**
     * 拿到授权后启动捕获。
     *
     * @param resultCode Activity 返回的 resultCode
     * @param data       `MediaProjectionManager.createScreenCaptureIntent()` 的结果 Intent
     */
    fun start(resultCode: Int, data: Intent, config: CaptureConfig, listener: Listener) {
        check(!released) { "ScreenCaptureManager 已释放，请新建实例" }
        this.listener = listener

        val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("getMediaProjection 返回 null（授权无效，或前台服务未先以 mediaProjection 类型启动）")

        // ★ Android 14 要求：Callback 必须早于 createVirtualDisplay 注册
        mediaProjection.registerCallback(projectionCallback, mainHandler)
        projection = mediaProjection

        createSurfaces(config)
        Log.i(TAG, "捕获已启动 ${config.width}x${config.height} @${config.densityDpi}dpi")
    }

    private fun createSurfaces(config: CaptureConfig) {
        val reader = ImageReader.newInstance(
            config.width,
            config.height,
            PixelFormat.RGBA_8888,
            config.maxImages,
        )
        imageReader = reader
        frameWidth = config.width
        frameHeight = config.height

        // 数据流：MediaProjection → VirtualDisplay → ImageReader.surface → 主动 acquireLatestImage
        virtualDisplay = projection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            config.width,
            config.height,
            config.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler,
        )
    }

    /**
     * 抓取当前屏幕的一帧。
     *
     * 内存安全要点：
     * - `acquireLatestImage()` 自动丢弃队列里的旧帧，避免画面滞后；
     * - 复制像素到复用 Bitmap 之后**立刻** `image.close()`；
     * - 返回的 Bitmap 由本类复用持有，调用方只能在本帧内消费，不得缓存、不得 recycle。
     *
     * @return 失败（无帧 / 已释放 / 异常）返回 null
     */
    @SuppressLint("Recycle")
    fun captureSingleFrame(): Bitmap? = synchronized(lock) {
        if (released) return@synchronized null

        val reader = imageReader ?: return@synchronized null
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            listener?.onCaptureError("acquireLatestImage 失败", t)
            null
        }
        if (image == null) return@synchronized null

        try {
            val plane = image.planes.firstOrNull() ?: return@synchronized null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            // RGBA_8888 每行末尾可能有 padding，按 rowStride 反推真实宽度
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + if (pixelStride > 0) rowPadding / pixelStride else 0

            val padded = paddedSlot.obtain(paddedWidth, image.height)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)

            if (paddedWidth == image.width) return@synchronized padded

            val cropped = frameSlot.obtain(image.width, image.height)
            Canvas(cropped).drawBitmap(padded, 0f, 0f, null)
            cropped
        } catch (t: Throwable) {
            listener?.onCaptureError("帧拷贝失败", t)
            null
        } finally {
            // ★★ 关键：复制完像素后立即关闭，把缓冲区还给 ImageReader。
            // 漏掉这一步，maxImages 很快耗尽，之后每帧都拿到 null，最终引发 OOM。
            image.close()
        }
    }

    /**
     * 画面尺寸变化（旋转屏幕等）时重建 Surface 链。
     *
     * ## 官方要求的做法
     *
     * Android 14 起，**一个 MediaProjection 令牌只能调一次 `createVirtualDisplay()`**，
     * 所以旋转后**不能**重建 VirtualDisplay，只能按官方文档改这一块：
     * 1. `VirtualDisplay#resize(w, h, dpi)`
     * 2. 把**新尺寸**的 Surface 交给 `VirtualDisplay#setSurface(...)`
     *
     * ## 踩过的坑（v1.5.1 之前一直「横屏点了没反应 / 变感叹号」的根因）
     *
     * 旧实现在 `resize` / `setSurface` 外面套了 `runCatching {}`，**异常全被吞掉**：
     * 只要 `resize` 在某个 ROM 上抛一次（MediaProjection 的 VirtualDisplay 对 resize
     * 是有条件支持的），后面 `display.surface = reader.surface` 也会失败，
     * 于是 ImageReader 从此再也收不到帧 —— `acquireLatestImage()` 恒为 null，
     * 手动模式下用户看到的就是「点一次，球永久变成感叹号」。
     *
     * 而且旧实现先把 `surface = null` 再 resize 再挂回来，中间那一小段
     * VirtualDisplay 处于「无 surface」状态，部分 ROM 会就此停止投递，重新挂上也醒不过来。
     *
     * ## 现在的做法
     *
     * - **不再把 surface 置 null**，直接 `resize` 后 `setSurface(新 surface)`，由系统负责释放旧 surface；
     * - 每一步的失败都**记下来**（而不是吞掉），最后统一判断；
     * - resize 之后**真的去取一帧验证**（[verifyFrameLocked]）。取不到就**重做一次**
     *   （第二遍通常能成，因为第一遍已经把旧 surface 挤掉了）；还不行就
     *   [lastResizeError] 报给上层，由提示条明确告诉用户「横屏重建取帧链失败，请重新开启翻译」。
     *
     * @param force true 时忽略「尺寸没变」的快速返回，强制重做一次取帧链。
     *   用于**自愈**：某次 resize 失败后，画面尺寸可能一直没再变，
     *   这时只有强制重做才能把链路救回来。
     * @return true 表示重建后确实能取到帧（或本就无法判定）；false 表示重建后取不到帧
     */
    fun resize(config: CaptureConfig, force: Boolean = false): Boolean = synchronized(lock) {
        if (released) return@synchronized true
        val display = virtualDisplay ?: return@synchronized true
        val oldReader = imageReader ?: return@synchronized true

        // 已经就是这个尺寸就别折腾：反复 resize 会把本来好的链路弄坏。
        // 除非调用方明确要求强制重做（自愈路径），或者上一次 resize 就是失败的。
        if (!force && lastResizeError == null &&
            frameWidth == config.width && frameHeight == config.height && isRunning
        ) {
            return@synchronized true
        }

        var lastError: Throwable? = null
        // 「需要关掉的旧 reader」会随着每次尝试滚动更新：第一遍之后旧的已经被关，
        // 重试时要关的是第一遍新建出来的那个，而不是再关一次已经关掉的。
        var staleReader: ImageReader? = oldReader

        val ok = try {
            applyResize(display, staleReader, config)
        } catch (t: Throwable) {
            lastError = t
            false
        }
        staleReader = imageReader  // 第一遍新建的（可能已经可用，也可能要再换一次）

        if (!ok) {
            // 第二遍：第一遍若已经把旧 surface 解绑，这一遍从干净状态再挂一次
            Log.w(TAG, "首次 resize 未生效，重试一次：${config.width}x${config.height}")
            val ok2 = try {
                applyResize(display, staleReader, config)
            } catch (t: Throwable) {
                lastError = t
                false
            }
            lastResizeError = if (ok2) null else (lastError ?: IllegalStateException("resize 后取不到帧"))
            Log.i(TAG, "捕获尺寸重试${if (ok2) "成功" else "仍失败"}：${config.width}x${config.height}")
            return@synchronized ok2
        }

        lastResizeError = null
        Log.i(TAG, "捕获尺寸已调整为 ${config.width}x${config.height}")
        true
    }

    /**
     * 真正做一次「换 reader + resize + 换 surface + 关旧 reader」，并验证能取到帧。
     *
     * @param staleReader 这一次要关掉的旧 reader；允许为 null（没有旧的）
     */
    private fun applyResize(display: VirtualDisplay, staleReader: ImageReader?, config: CaptureConfig): Boolean {
        val reader = ImageReader.newInstance(
            config.width,
            config.height,
            PixelFormat.RGBA_8888,
            config.maxImages,
        )
        imageReader = reader
        frameWidth = config.width
        frameHeight = config.height

        // 踩坑点 1：不要先 `surface = null`。直接 resize 再 setSurface，
        // 系统会把旧 surface 释放掉；先置 null 会让部分 ROM 直接停投递且不再恢复。
        runCatching { display.resize(config.width, config.height, config.densityDpi) }
            .onFailure { Log.w(TAG, "VirtualDisplay.resize 失败，继续尝试换 surface", it) }
        runCatching { display.setSurface(reader.surface) }
            .onFailure { Log.w(TAG, "VirtualDisplay.setSurface 失败", it) }

        // 换上新 surface 之后再关旧的 —— 顺序反了会出现「新 surface 还没挂上、
        // 旧 buffer 已经没了」的空窗，部分 ROM 就此停投递。
        if (staleReader != null && staleReader !== reader) {
            runCatching {
                staleReader.setOnImageAvailableListener(null, null)
                staleReader.close()
            }
        }

        return verifyFrameLocked()
    }

    /**
     * resize 之后验证「真的能取到帧」。
     *
     * VirtualDisplay 只有在画面有更新时才会推缓冲，所以这里给一点时间并多试几次；
     * 最多等约 [VERIFY_DEADLINE_MS]。取到的帧立刻关掉（只验流程，不消费内容）。
     */
    private fun verifyFrameLocked(): Boolean {
        val reader = imageReader ?: return false
        val deadline = System.currentTimeMillis() + VERIFY_DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) {
                image.close()
                return true
            }
            try {
                Thread.sleep(VERIFY_STEP_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return false
    }

    /** 主动停止并释放全部资源。幂等。 */
    fun stop() {
        synchronized(lock) {
            if (released) return
            released = true
            selfInitiatedStop = true
            releaseResources()
            listener = null
            quitCaptureThread()
        }
    }

    private fun releaseResources() {
        // 1) VirtualDisplay：先脱开 ImageReader.surface 的引用再 release
        val display = virtualDisplay
        virtualDisplay = null
        if (display != null) {
            runCatching { display.surface = null }
            runCatching { display.release() }.onFailure { Log.w(TAG, "release VirtualDisplay 失败", it) }
        }

        // 2) ImageReader
        val reader = imageReader
        imageReader = null
        if (reader != null) {
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }.onFailure { Log.w(TAG, "close ImageReader 失败", it) }
        }

        // 3) MediaProjection：主动 stop 时已注销回调，不会再来一轮
        val mediaProjection = projection
        projection = null
        if (mediaProjection != null) {
            runCatching { mediaProjection.unregisterCallback(projectionCallback) }
            if (!selfInitiatedStop) {
                runCatching { mediaProjection.stop() }
            }
        }

        // 4) Bitmap 缓冲
        paddedSlot.release()
        frameSlot.release()
        frameWidth = 0
        frameHeight = 0
        lastResizeError = null
    }

    private fun quitCaptureThread() {
        runCatching { captureThread.quitSafely() }
    }

    /**
     * 响应「用户随时撤销投屏权限」。
     * 系统会在 [mainHandler] 上回调，这里完成收尾并通知上层停机。
     */
    private val projectionCallback = object : MediaProjection.Callback() {

        override fun onStop() {
            Log.i(TAG, "MediaProjection.onStop —— 投屏权限被用户或系统撤销")
            synchronized(lock) {
                if (released) return
                released = true
                selfInitiatedStop = true
                releaseResources()
                quitCaptureThread()
            }
            val callback = listener
            listener = null
            runCatching { callback?.onProjectionStopped() }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (released) return
            Log.i(TAG, "onCapturedContentResize ${width}x$height")
            runCatching { listener?.onCapturedContentResized(width, height) }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (released) return
            Log.i(TAG, "onCapturedContentVisibilityChanged visible=$isVisible")
            runCatching { listener?.onCapturedContentVisibilityChanged(isVisible) }
        }
    }
}
