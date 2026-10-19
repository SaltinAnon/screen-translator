package com.saltin.screentranslator.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 长按悬浮球弹出的快捷菜单。
 *
 * 用原生 View 而不是 Compose，理由和 [BubbleView] 一样：这个窗口必须可靠地接收点击。
 */
class BubbleMenuView(context: Context) : LinearLayout(context) {

    enum class Action {
        TRANSLATE_NOW,
        CLEAR,
        TOGGLE_AUTO,
        OPEN_APP,
        STOP_SERVICE,
    }

    var onAction: ((Action) -> Unit)? = null

    private val autoItem: TextView
    private val statusItem: TextView

    init {
        orientation = VERTICAL
        setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(0xF21B1F27.toInt())
            setStroke(dp(1f).toInt(), 0x33FFFFFF)
        }
        elevation = dp(8f)

        statusItem = addItem("待命", 0xFF9FB0C7.toInt(), dp(11f), false) {}
        autoItem = addItem("开启连续翻译", 0xFFFFFFFF.toInt(), dp(14f), true) {
            onAction?.invoke(Action.TOGGLE_AUTO)
        }
        addItem("翻译当前页面", 0xFFFFFFFF.toInt(), dp(14f), true) {
            onAction?.invoke(Action.TRANSLATE_NOW)
        }
        addItem("清空译文", 0xFFFFFFFF.toInt(), dp(14f), true) {
            onAction?.invoke(Action.CLEAR)
        }
        addItem("打开设置", 0xFFFFFFFF.toInt(), dp(14f), true) {
            onAction?.invoke(Action.OPEN_APP)
        }
        addItem("停止翻译服务", 0xFFFF9A8F.toInt(), dp(14f), true) {
            onAction?.invoke(Action.STOP_SERVICE)
        }
    }

    fun bind(status: String, autoTranslate: Boolean) {
        statusItem.text = status
        autoItem.text = if (autoTranslate) "关闭连续翻译" else "开启连续翻译"
    }

    private fun addItem(
        text: String,
        color: Int,
        size: Float,
        clickable: Boolean,
        onClick: () -> Unit,
    ): TextView {
        val item = TextView(context).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f).toInt(), dp(9f).toInt(), dp(10f).toInt(), dp(9f).toInt())
            isSingleLine = true
            isClickable = clickable
            isFocusable = clickable
            if (clickable) {
                val content = GradientDrawable().apply {
                    cornerRadius = dp(9f)
                    setColor(Color.TRANSPARENT)
                }
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)
                setOnClickListener { onClick() }
            } else {
                setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(6f).toInt())
                alpha = 0.9f
            }
        }
        addView(
            item,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        return item
    }

    /** 让弹层宽度固定，避免每次文案变化时窗口跳动 */
    fun fixedWidthDp(): Int = 132

    fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = dp(fixedWidthDp().toFloat()).toInt()
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
    }
}
