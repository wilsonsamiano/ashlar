package com.wilson.win10launcher

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Press feedback for a wide app row. The row lights as one even rectangle
 * from the icon out to the finger, the same accent as a Start tile, and it
 * stays lit so a tablet press on the empty side is obvious.
 */
object PressBeam {
    private val runs = java.util.WeakHashMap<View, Run>()
    private const val MENU_DELAY = 1000L
    private const val LINGER = 900L

    fun onTouch(row: View, icon: View, e: MotionEvent, color: Int, onMenu: () -> Unit) {
        val run = runs[row]
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                show(row, icon, e.x, color)
                val next = runs[row] ?: return
                next.downX = e.x
                next.downY = e.y
                next.scrolled = false
                next.menu?.let { row.removeCallbacks(it) }
                val fire = Runnable {
                    next.menu = null
                    if (next.scrolled || runs[row] !== next) return@Runnable
                    next.sticky = true
                    next.release?.let { row.removeCallbacks(it) }
                    onMenu()
                }
                next.menu = fire
                row.postDelayed(fire, MENU_DELAY)
                row.post { row.cancelLongPress() }
            }
            MotionEvent.ACTION_MOVE -> {
                val active = run ?: return
                if (active.scrolled) return
                val slop = row.resources.displayMetrics.density * 12f
                if (abs(e.x - active.downX) > slop || abs(e.y - active.downY) > slop) {
                    active.scrolled = true
                    active.menu?.let { row.removeCallbacks(it) }
                    active.menu = null
                    fade(row, 100)
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelMenu(row)
                linger(row)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelMenu(row)
                val active = runs[row]
                if (active?.sticky == true) return
                if (active?.scrolled == true) fade(row, 100) else linger(row)
            }
        }
    }

    fun clear(row: View) {
        val run = runs.remove(row) ?: return
        run.release?.let { row.removeCallbacks(it) }
        run.menu?.let { row.removeCallbacks(it) }
        run.fade?.cancel()
        if (row.background === run.bar) row.background = null
    }

    /** Drop the bar after the menu closes. */
    fun release(row: View) {
        val run = runs[row] ?: return
        run.sticky = false
        fade(row, 220)
    }

    private fun cancelMenu(row: View) {
        val run = runs[row] ?: return
        run.menu?.let { row.removeCallbacks(it) }
        run.menu = null
    }

    private fun linger(row: View) {
        val run = runs[row] ?: return
        if (run.sticky || run.fade != null) return
        run.release?.let { row.removeCallbacks(it) }
        val later = Runnable { fade(row, 280) }
        run.release = later
        row.postDelayed(later, LINGER)
    }

    private fun fade(row: View, duration: Long) {
        val run = runs[row] ?: return
        if (run.fade != null) return
        run.release?.let { row.removeCallbacks(it) }
        val fade = ValueAnimator.ofFloat(run.bar.alphaF, 0f)
        fade.duration = duration
        fade.addUpdateListener {
            run.bar.alphaF = it.animatedValue as Float
            run.bar.invalidateSelf()
        }
        fade.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (runs[row] === run) clear(row)
            }
        })
        run.fade = fade
        fade.start()
    }

    private fun show(row: View, icon: View, touchX: Float, color: Int) {
        clear(row)
        if (row.width == 0) return
        val density = row.resources.displayMetrics.density
        val pad = 8f * density
        val bar = Bar()
        bar.color = color
        bar.inset = 3f * density
        val left = icon.left.toFloat() - pad
        val right = if (touchX > icon.right + pad) row.width.toFloat() else icon.right + pad * 2
        bar.left = left.coerceAtLeast(0f)
        bar.right = right.coerceAtMost(row.width.toFloat())
        row.background = bar
        runs[row] = Run(bar)
    }

    private class Run(val bar: Bar) {
        var fade: ValueAnimator? = null
        var release: Runnable? = null
        var menu: Runnable? = null
        var downX = 0f
        var downY = 0f
        var scrolled = false
        var sticky = false
    }

    private class Bar : Drawable() {
        var left = 0f
        var right = 0f
        var inset = 0f
        var alphaF = 1f
        var color = 0xFF0078D7.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || alphaF <= 0.01f) return
            paint.color = Color.argb(
                (0xF0 * alphaF).toInt().coerceIn(0, 255),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
            canvas.drawRect(left, b.top + inset, right, b.bottom - inset, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
