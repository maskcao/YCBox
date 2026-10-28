package com.github.tvbox.osc.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 首页 hero 的图片层：把底边一段渐变溶成真透明，让海报化进页面背景。
 *
 * 为什么不能用一条深色渐变遮罩假装过渡：hero 下面是全局壁纸层（虚化图 + 遮罩），
 * 沿屏幕方向本身就有明暗变化，实测接缝处从 #18130F 直接跳到 #4B4542，
 * 任何固定色的压暗带都盖不住这个台阶，只会变成第二条边。
 *
 * 做法是在自己的离屏图层里用 DST_OUT 叠一条「底部不透明 → 上端全透明」的黑渐变，
 * 把这段像素的 alpha 直接抹掉，壁纸从被抹掉的地方透上来，接缝就不存在了。
 */
class HeroFadeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val layerBounds = RectF()
    private var fadeTop = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fadePx = FADE_DP * resources.displayMetrics.density
        fadeTop = h - fadePx
        // DST_OUT 只看 src 的 alpha：底边 alpha=255 全抹掉，往上 alpha=0 原样保留。
        // 前紧后松而非线性：海报自带的「x月x日起」就压在底边那一截，线性到 30% 高度
        // 才抹掉三成，字还清清楚楚；到 35% 就要抹掉 95%，让这段直接消失，
        // 剩下的高度再用后两段平缓收到 0，避免过渡区自己变成一条边。
        fadePaint.shader = LinearGradient(
            0f, h.toFloat(), 0f, fadeTop,
            intArrayOf(0xFF000000.toInt(), 0xF2000000.toInt(), 0x8C000000.toInt(), 0),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        val h = height
        val w = width
        if (h == 0 || w == 0 || fadeTop <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        layerBounds[0f, 0f, w.toFloat()] = h.toFloat()
        val saved = canvas.saveLayer(layerBounds, null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, fadeTop, w.toFloat(), h.toFloat(), fadePaint)
        canvas.restoreToCount(saved)
    }

    companion object {
        /** 溶掉的高度；item_home_hero.xml 里片名/指示器的下内边距要和它对齐 */
        private const val FADE_DP = 110f
    }
}
