package com.saltin.screentranslator.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 可复用的 Bitmap 槽位。
 *
 * 屏幕翻译每秒要处理 1~2 帧，如果每帧都 createBitmap，一分钟就会产生上百个
 * 大对象，GC 压力极大且容易触发 OOM。这里只在尺寸发生变化时才重新分配。
 */
class BitmapSlot {

    private var bitmap: Bitmap? = null

    val current: Bitmap? get() = bitmap

    fun obtain(width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val existing = bitmap
        if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            return existing
        }
        existing?.recycle()
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = created
        return created
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
    }
}

object ImageUtils {

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /**
     * OCR 输入位图的像素总量硬上限（约 600 万，ARGB_8888 下约 24MB）。
     * 放大会显著提升小字识别率，但不能没有上限 —— 否则一张 2K 屏截图放大 2 倍就是 50MB+。
     */
    private const val MAX_OCR_PIXELS = 6_000_000L

    /**
     * 对比度拉伸系数（见 [ocrEnhancePaint]）。
     *
     * 刻意取**温和值**：拉到 1.6 以上会把抗锯齿边缘整个压成二值，
     * 细笔画的汉字反而糊成一团、识别率不升反降。1.3 是在「低对比度字能被拉出来」
     * 与「细笔画不被压坏」之间的折中。
     */
    private const val OCR_CONTRAST = 1.3f

    /**
     * OCR 前的图像增强。
     *
     * 屏幕上「彩色文字 + 花哨背景板」是识别率的两大杀手：ML Kit 的文本识别器主要以
     * 高对比度黑白正文训练，低对比度的彩色字很容易整行漏掉。这里做两件成本极低
     * （纯 Canvas 层，**不读像素**，几十微秒级）的事：
     *
     * 1. **灰度化** —— 抹掉「红字压在绿底」这类同亮度不同色相的组合。灰度之后，
     *    两者只剩亮度差这一条路，而这正是识别器真正在用的信号；
     * 2. **温和对比度拉伸** —— 把「浅灰字 + 白底」这种濒临淹没的组合重新拉开。
     *
     * ⚠️ 这只对**低对比度**有明确帮助。如果画面本身就是清晰的黑白正文，
     * 开启与否几乎无差别；而如果 ML Kit 是**压根没有对应文字系统的识别器**
     * （西里尔/阿拉伯/泰文…），再增强也没用 —— 那是能力边界，不是画质问题。
     *
     * ⚠️ 必须用**独立的 Paint**：共享的 [scalePaint] 还被 [rotateCcwInto] 等在用，
     * 给它挂 colorFilter 会连带影响所有绘制路径。
     */
    private val ocrEnhancePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val c = OCR_CONTRAST
        // out = (in − 128) × c + 128  →  常数项 = 128 × (1 − c)
        val t = 128f * (1f - c)
        val stretch = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        // postConcat = 先做 this（灰度），再做 stretch（对比度）
        gray.postConcat(stretch)
        colorFilter = ColorMatrixColorFilter(gray)
    }

    /**
     * 等比缩放到 [maxWidth] 以内并写入 [slot]。
     * 返回的 Bitmap 由 slot 持有，调用方**不得** recycle，也不能跨帧缓存。
     */
    fun scaleInto(src: Bitmap, slot: BitmapSlot, maxWidth: Int): Bitmap {
        val targetWidth = if (src.width <= maxWidth) src.width else maxWidth
        val ratio = targetWidth.toFloat() / src.width.toFloat()
        val targetHeight = (src.height * ratio).roundToInt().coerceAtLeast(1)
        val dst = slot.obtain(targetWidth, targetHeight)
        if (dst === src) return dst
        Canvas(dst).drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            Rect(0, 0, targetWidth, targetHeight),
            scalePaint,
        )
        return dst
    }

    /**
     * 给 OCR 准备的帧：先按 [maxWidth] 限制宽度，再整体放大 [upscale] 倍。
     *
     * 为什么要在已经够大的图上再放大：屏幕上的小字（状态栏、列表副标题、游戏 HUD）
     * 往往只有十几像素高，ML Kit 对这类字的识别率会明显下降。放大到 1.5~2 倍后再识别，
     * 中文与日文的准确率提升尤其明显 —— 这是纯软件层面的、不需要换模型的改善。
     *
     * [enhanceContrast] 为真时额外来一遍「灰度 + 温和对比度拉伸」，
     * 专门救「彩色文字 / 花哨背景板」这类低对比度画面，详见 [ocrEnhancePaint]。
     *
     * 代价是 CPU 与内存，所以上面有像素总量硬上限，超出时按面积等比缩回。
     */
    fun ocrFrameInto(
        src: Bitmap,
        slot: BitmapSlot,
        maxWidth: Int,
        upscale: Float,
        enhanceContrast: Boolean = false,
    ): Bitmap {
        val baseWidth = if (src.width <= maxWidth) src.width else maxWidth
        val baseRatio = baseWidth.toFloat() / src.width.toFloat()
        val factor = upscale.coerceIn(1f, 3f)

        var targetWidth = (baseWidth * factor).roundToInt().coerceAtLeast(1)
        var targetHeight = (src.height * baseRatio * factor).roundToInt().coerceAtLeast(1)

        val pixels = targetWidth.toLong() * targetHeight.toLong()
        if (pixels > MAX_OCR_PIXELS) {
            val k = kotlin.math.sqrt(MAX_OCR_PIXELS.toDouble() / pixels.toDouble()).toFloat()
            targetWidth = (targetWidth * k).roundToInt().coerceAtLeast(1)
            targetHeight = (targetHeight * k).roundToInt().coerceAtLeast(1)
        }

        val dst = slot.obtain(targetWidth, targetHeight)
        if (dst === src) return dst
        Canvas(dst).drawBitmap(
            src,
            Rect(0, 0, src.width, src.height),
            Rect(0, 0, targetWidth, targetHeight),
            if (enhanceContrast) ocrEnhancePaint else scalePaint,
        )
        return dst
    }

    /**
     * 逆时针旋转 90° 并写入 [slot]（尺寸会变成 H×W）。
     *
     * 用在竖排文字（日式縦書き）上：转完之后「竖排的列」就变成「横排的行」，
     * ML Kit 才认得准。详见 `ocr/TextOrientation.kt`。
     *
     * 变换：先绕原点旋转 −90°，再把 y 平移一个原图宽度，
     * 得到 `(x, y) → (y, W − x)`，正好把原图完整搬进 H×W 的新画布。
     */
    fun rotateCcwInto(src: Bitmap, slot: BitmapSlot): Bitmap {
        val dst = slot.obtain(src.height, src.width)
        val canvas = Canvas(dst)
        // 旋转的取整可能留下 1px 的边角，先把上一帧的残留清掉
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val matrix = Matrix().apply {
            postRotate(-90f)
            postTranslate(0f, src.width.toFloat())
        }
        canvas.drawBitmap(src, matrix, scalePaint)
        return dst
    }

    /**
     * 把「逆时针旋转 90° 之后」坐标系里的矩形搬回原图坐标系。
     *
     * 正向 `(x, y) → (y, W − x)`，所以反向是 `(x', y') → (W − y', x')`。
     */
    fun unrotateCcwRect(rect: Rect, sourceWidth: Int): Rect = Rect(
        sourceWidth - rect.bottom,
        rect.left,
        sourceWidth - rect.top,
        rect.right,
    )

    /**
     * 竖排文字回映射后会得到一个**又窄又高**的框（一「列」的宽度只有一两个字宽）。
     * 译文是横排的，塞进这种框会被压成一条细线，所以按框高把它横向撑开成接近方形。
     *
     * 只在「单独一列、没有和邻居合并」时才需要 —— 合并过的段落框本来就是整个对话泡，
     * 宽高比正常，再撑开反而会盖住旁边的画面。
     */
    fun widenTallRect(rect: Rect, frameWidth: Int, ratio: Float = 0.72f): Rect {
        val h = rect.height()
        val w = rect.width()
        if (h <= w * 1.6f) return rect

        val target = (h * ratio).roundToInt().coerceAtLeast(w)
        val limit = (frameWidth - target).coerceAtLeast(0)
        val left = (rect.centerX() - target / 2).coerceIn(0, limit)
        return Rect(left, rect.top, left + target, rect.bottom)
    }

    /** 编码为 JPEG 并转 Base64（给云端多模态用）。内部临时位图会立即回收。 */
    fun toJpegBase64(src: Bitmap, maxSide: Int, quality: Int): String {
        val longest = maxOf(src.width, src.height)
        val scaled: Bitmap = if (longest <= maxSide) {
            src
        } else {
            val ratio = maxSide.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).roundToInt().coerceAtLeast(1),
                (src.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        val out = ByteArrayOutputStream(64 * 1024)
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), out)
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 把 [src] 坐标系里的矩形乘上系数 [scale]，得到另一坐标系（通常是真实屏幕）的矩形。 */
    fun scaleRect(src: Rect, scale: Float): Rect =
        if (scale == 1f) {
            Rect(src)
        } else {
            Rect(
                (src.left * scale).roundToInt(),
                (src.top * scale).roundToInt(),
                (src.right * scale).roundToInt(),
                (src.bottom * scale).roundToInt(),
            )
        }
}
