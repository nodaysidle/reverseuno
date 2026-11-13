package com.nodaysidle.reverseuno.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The drop target shown at the bottom centre while the pill is dragged: drop the pill on it to
 * dismiss it. A separate never-touchable window of the same type as the pill, so it never takes
 * touches from the app or from the pill being dragged.
 */
internal class DismissTarget(context: Context, windowType: Int) : View(context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = resources.displayMetrics.density
    private val sizePx = (SIZE_DP * density).toInt()
    private var attached = false
    private var hot = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE8131317.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0x66F5F5F7
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
        color = 0xFFF5F5F7.toInt()
    }

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (BOTTOM_MARGIN_DP * density).toInt()
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Height of the drop zone above the bottom edge: the target plus generous slack above it. */
    val zoneHeightPx: Int get() = ((BOTTOM_MARGIN_DP + SIZE_DP + ZONE_SLACK_DP) * density).toInt()

    fun show() {
        if (attached) return
        hot = false
        scaleX = 0.8f
        scaleY = 0.8f
        alpha = 0f
        runCatching { windowManager.addView(this, params) }.onSuccess {
            attached = true
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        }
    }

    /** The pill is over the zone: the target grows so the user knows releasing dismisses. */
    fun setHot(value: Boolean) {
        if (hot == value) return
        hot = value
        val scale = if (value) HOT_SCALE else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        invalidate()
    }

    fun remove() {
        if (!attached) return
        attached = false
        animate().cancel()
        runCatching { windowManager.removeView(this) }
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        val inset = 6 * density
        fill.color = if (hot) 0xF0F45B36.toInt() else 0xE8131317.toInt()
        canvas.drawCircle(r, r, r - inset, fill)
        canvas.drawCircle(r, r, r - inset, ring)
        val arm = r * 0.32f
        canvas.drawLine(r - arm, r - arm, r + arm, r + arm, cross)
        canvas.drawLine(r + arm, r - arm, r - arm, r + arm, cross)
    }

    private companion object {
        const val SIZE_DP = 64
        const val BOTTOM_MARGIN_DP = 56
        const val ZONE_SLACK_DP = 80
        const val HOT_SCALE = 1.2f
    }
}
