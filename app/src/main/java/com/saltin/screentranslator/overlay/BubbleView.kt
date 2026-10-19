package com.saltin.screentranslator.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 常驻悬浮球。
 *
 * 为什么用一个手写的 [View] 而不是 Compose：悬浮窗里的 Compose 与「拖动 + 单击 + 长按」
 * 三套手势很容易互相抢事件，而这个控件的交互必须 100% 确定 ——
 * 点一下就必须触发翻译，拖一下就必须要能挪位置。自绘 View 的事件处理完全可控。
 *
 * 交互：
 * - 单击   → [onTap]（翻译当前屏幕）
 * - 长按   → [onLongPress]（打开快捷菜单）
 * - 拖动   → [onDragStart] / [onDrag] / [onDragEnd]（移动窗口位置）
 */
class BubbleView(context: Context) : View(context) {

    var model: BubbleUiModel = BubbleUiModel()
        set(value) {
            if (field == value) return
            val wasWorking = field.state == BubbleState.WORKING
            field = value
            if (wasWorking != (value.state == BubbleState.WORKING)) syncSpinner()
            invalidate()
        }

    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = Color.WHITE
    }
    private val arcRect = RectF()

    private var spinner: ValueAnimator? = null
    private var sweep = 0f

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        longPressFired = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = "屏幕翻译悬浮球"
    }

    // ------------------------------------------------------------ 状态

    private fun syncSpinner() {
        if (model.state == BubbleState.WORKING) {
            if (spinner == null) {
                spinner = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1100L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        sweep = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            spinner?.cancel()
            spinner = null
            sweep = 0f
        }
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        spinner = null
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(2f)
        if (radius <= 0f) return

        val alpha = (model.alpha.coerceIn(0.25f, 1f) * 255f).roundToInt()
        val (deep, bright) = paletteFor(model.state)

        // 1) 外圈柔光，让球在浅色/深色背景上都看得见
        fillPaint.shader = null
        fillPaint.color = Color.argb((alpha * 0.28f).roundToInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, radius, fillPaint)

        // 2) 主体渐变
        fillPaint.shader = LinearGradient(
            cx - radius, cy - radius, cx + radius, cy + radius,
            Color.argb(alpha, Color.red(bright), Color.green(bright), Color.blue(bright)),
            Color.argb(alpha, Color.red(deep), Color.green(deep), Color.blue(deep)),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, fillPaint)

        // 3) 高光
        fillPaint.shader = null
        fillPaint.color = Color.argb((alpha * 0.30f).roundToInt(), 255, 255, 255)
        canvas.drawCircle(cx - radius * 0.28f, cy - radius * 0.34f, radius * 0.34f, fillPaint)

        // 4) 描边
        ringPaint.color = Color.argb((alpha * 0.85f).roundToInt(), 255, 255, 255)
        ringPaint.strokeWidth = dp(1.6f)
        canvas.drawCircle(cx, cy, radius - dp(0.8f), ringPaint)

        // 5) 工作时转圈
        if (model.state == BubbleState.WORKING) {
            ringPaint.color = Color.WHITE
            ringPaint.strokeWidth = dp(2.6f)
            val inset = dp(4f)
            arcRect.set(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)
            canvas.drawArc(arcRect, sweep, 82f, false, ringPaint)
            canvas.drawArc(arcRect, sweep + 180f, 82f, false, ringPaint)
        }

        // 6) 中间的字
        glyphPaint.color = Color.argb(alpha, 255, 255, 255)
        glyphPaint.textSize = radius * 1.02f
        val baseline = cy - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
        canvas.drawText(glyphFor(model.state), cx, baseline, glyphPaint)

        // 7) 译文条数角标
        if (model.badgeVisible && model.translatedBlocks > 0) {
            val badgeR = radius * 0.40f
            val bx = cx + radius * 0.66f
            val by = cy - radius * 0.66f
            badgePaint.color = Color.argb(alpha, 0xE0.toInt(), 0x5A.toInt(), 0x2B.toInt())
            canvas.drawCircle(bx, by, badgeR, badgePaint)
            badgeTextPaint.color = Color.argb(alpha, 255, 255, 255)
            badgeTextPaint.textSize = badgeR * 1.28f
            val text = if (model.translatedBlocks > 99) "99+" else model.translatedBlocks.toString()
            val badgeBaseline = by - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(text, bx, badgeBaseline, badgeTextPaint)
        }
    }

    private fun glyphFor(state: BubbleState): String = when (state) {
        BubbleState.IDLE -> "译"
        BubbleState.WORKING -> "…"
        BubbleState.DONE -> "✓"
        BubbleState.ERROR -> "!"
        BubbleState.OFFLINE -> "·"
    }

    private fun paletteFor(state: BubbleState): Pair<Int, Int> = when (state) {
        BubbleState.IDLE -> 0xFF16263C.toInt() to 0xFF3D6BB5.toInt()
        BubbleState.WORKING -> 0xFF123A66.toInt() to 0xFF2E8BE6.toInt()
        BubbleState.DONE -> 0xFF12503A.toInt() to 0xFF2FA96B.toInt()
        BubbleState.ERROR -> 0xFF6B1D18.toInt() to 0xFFD4503F.toInt()
        BubbleState.OFFLINE -> 0xFF3A414B.toInt() to 0xFF6B7480.toInt()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    // ------------------------------------------------------------ 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                longPressFired = false
                isPressed = true
                postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalX = event.rawX - downRawX
                val totalY = event.rawY - downRawY
                if (!dragging && abs(totalX) + abs(totalY) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    isPressed = false
                    onDragStart?.invoke()
                }
                if (dragging) {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDrag?.invoke(dx, dy)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                if (dragging) {
                    onDragEnd?.invoke()
                } else if (!longPressFired) {
                    performClick()
                    onTap?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isPressed = false
                if (dragging) onDragEnd?.invoke()
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
