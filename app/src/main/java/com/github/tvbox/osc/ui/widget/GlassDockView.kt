package com.github.tvbox.osc.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.github.tvbox.osc.R
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮磨砂玻璃 dock：底图交给 [GlassBackdrop]（与 NewBox 的 GlassBackdrop 同思路），
 * 玻璃表面本身照抄 NewBox 的 qf0：模糊底 -&gt; 40% 黑雾面 -&gt; 1dp 40% 白内描边，
 * 外面靠 8dp 抬起一层柔和投影，不再叠顶部高光渐变。
 */
class GlassDockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val cornerRadius = 28f * density

    private val glass = GlassBackdrop(this).cornerRadius(cornerRadius)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        // 硬件模糊节点是矩形，靠 outline 裁出圆角
        clipToOutline = true
        elevation = 8f * density
    }

    /**
     * 主题是在 attachBaseContext 之后才由 appcompat 定下来的，构造期取色会拿到日间值
     * （描边因此变成几乎透明的 12% 黑），所以颜色一律在绘制时读。
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadow = ContextCompat.getColor(context, R.color.glass_shadow)
            outlineAmbientShadowColor = shadow
            outlineSpotShadowColor = shadow
        }
        glass.attach()
    }

    override fun onDetachedFromWindow() {
        glass.detach()
        super.onDetachedFromWindow()
    }

    /**
     * 请求重录底图。玻璃本身由 preDraw 驱动，这里只是打个标记；
     * delayMs &gt; 0 用于「内容是异步填进来的」这类要等一下的场景。
     */
    fun refreshBackdrop(delayMs: Long = 0L) {
        if (delayMs <= 0L) {
            glass.refresh()
        } else {
            postDelayed({ glass.refresh() }, delayMs)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(cornerRadius, h / 2f)

        fillPaint.color = ContextCompat.getColor(
            context,
            if (glass.draw(canvas, width, height)) R.color.dock_glass_fill
            else R.color.glass_fill_static
        )
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, fillPaint)
        strokePaint.color = ContextCompat.getColor(context, R.color.dock_glass_stroke)
        val inset = strokePaint.strokeWidth / 2f
        canvas.drawRoundRect(
            inset, inset, w - inset, h - inset,
            max(0f, radius - inset), max(0f, radius - inset), strokePaint
        )
    }
}
