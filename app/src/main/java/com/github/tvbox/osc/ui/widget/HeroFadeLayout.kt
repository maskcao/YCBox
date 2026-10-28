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
        // 前紧后松而非线性：海报自带的「生日快乐」「x月x日起」这类 logo 就压在底边
        // 那一截（实测到图片下沿往上约 250px），线性到 35% 高度才抹掉九成五，
        // logo 上半截还清清楚楚地糊在片名后面；所以把 95% 那个点提到 35%、
        // 86% 那个点顶到 78%，让 logo 整段在过渡区上半部就消失，
        // 剩下的高度再用最后一段平缓收到 0，避免过渡区自己变成一条边。
        fadePaint.shader = LinearGradient(
            0f, h.toFloat(), 0f, fadeTop,
            intArrayOf(0xFF000000.toInt(), 0xF2000000.toInt(), 0xD9000000.toInt(), 0),
            floatArrayOf(0f, 0.35f, 0.78f, 1f),
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
        /**
         * 溶掉的高度。文字块现在整块压在雾化带里（下内边距只有 2dp），靠按主题成对翻转
         * 的字色读清（文字不带描影），不再要求它待在实图上。
         * 再往上调（试过 128dp）雾化带就吃掉片名那一行，浅色海报上直接看不清；
         * 再往下调（试过 72dp）海报自带的「x月x日起」会露出来一截。
         */
        private const val FADE_DP = 96f
    }
}
