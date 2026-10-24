package com.wilson.win10launcher

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Press light for a wide app row. A soft even glow stretches from the icon
 * to the finger and follows it. Letting go clears it, the same way a Start
 * tile settles, and the tap still launches.
 */
object PressBeam {
    private val runs = java.util.WeakHashMap<View, Run>()
    private const val MENU_DELAY = 1000L

    fun onTouch(row: View, icon: View, e: MotionEvent, color: Int, onMenu: () -> Unit): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                show(row, icon, e.x, e.y, color)
                val next = runs[row] ?: return false
                next.downX = e.x
                next.downY = e.y
                next.scrolled = false
                next.owned = false
                lean(row, e.x, e.y)
                next.menu?.let { row.removeCallbacks(it) }
                val fire = Runnable {
                    next.menu = null
                    if (next.scrolled || next.owned || runs[row] !== next) return@Runnable
                    next.sticky = true
                    onMenu()
                }
                next.menu = fire
                row.postDelayed(fire, MENU_DELAY)
                row.post { row.cancelLongPress() }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val active = runs[row] ?: return false
                if (active.scrolled) return false
                track(row, icon, e.x, e.y)
                lean(row, e.x, e.y)
                val slop = row.resources.displayMetrics.density * 14f
                val dx = abs(e.x - active.downX)
                val dy = abs(e.y - active.downY)
                if (dy > slop && dy > dx) {
                    active.scrolled = true
                    active.menu?.let { row.removeCallbacks(it) }
                    active.menu = null
                    (row.parent as? View)?.parent?.requestDisallowInterceptTouchEvent(false)
                    settle(row, 80)
                    return false
                }
                if (dx > slop || dy > slop) {
                    active.owned = true
                    active.menu?.let { row.removeCallbacks(it) }
                    active.menu = null
                    row.parent?.requestDisallowInterceptTouchEvent(true)
                }
                return active.owned
            }
            MotionEvent.ACTION_UP -> {
                val active = runs[row]
                val launch = active?.owned == true && active.scrolled.not() && active.sticky.not()
                cancelMenu(row)
                settle(row, 160)
                if (launch) row.performClick()
                return active?.owned == true
            }
            MotionEvent.ACTION_CANCEL -> {
                val active = runs[row]
                cancelMenu(row)
                if (active?.sticky == true) {
                    settle(row, 160)
                    return true
                }
                settle(row, 120)
                return active?.owned == true
            }
        }
        return false
    }

    fun clear(row: View) {
        val run = runs.remove(row) ?: return
        run.menu?.let { row.removeCallbacks(it) }
        run.fade?.cancel()
        row.overlay.remove(run.bar)
        row.animate().cancel()
        row.rotationX = 0f
        row.rotationY = 0f
    }

    /** Menu closed. The light is already gone with the finger. */
    fun release(row: View) {
        settle(row, 160)
    }

    private fun cancelMenu(row: View) {
        val run = runs[row] ?: return
        run.menu?.let { row.removeCallbacks(it) }
        run.menu = null
    }

    private fun settle(row: View, duration: Long) {
        val run = runs[row] ?: return
        run.sticky = false
        row.animate().cancel()
        row.animate().rotationX(0f).rotationY(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (run.fade != null) return
        val fade = ValueAnimator.ofFloat(run.bar.alphaF, 0f)
        fade.duration = duration
        fade.interpolator = DecelerateInterpolator()
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

    private fun show(row: View, icon: View, x: Float, y: Float, color: Int) {
        clear(row)
        if (row.width == 0) return
        val bar = Bar()
        bar.color = color
        bar.radius = 28f * row.resources.displayMetrics.density
        row.overlay.add(bar)
        val run = Run(bar)
        runs[row] = run
        bar.setBounds(0, 0, row.width, row.height)
        track(row, icon, x, y)
    }

    private fun track(row: View, icon: View, x: Float, y: Float) {
        val run = runs[row] ?: return
        val bar = run.bar
        val pad = 6f * row.resources.displayMetrics.density
        val iconL = icon.left.toFloat()
        val iconR = icon.right.toFloat()
        bar.hotX = x
        bar.hotY = y.coerceIn(0f, row.height.toFloat())
        bar.left = min(iconL, x) - pad
        bar.right = max(iconR, x) + pad
        bar.left = bar.left.coerceAtLeast(0f)
        bar.right = bar.right.coerceAtMost(row.width.toFloat())
        if (bar.bounds.width() != row.width || bar.bounds.height() != row.height) {
            bar.setBounds(0, 0, row.width, row.height)
        }
        bar.invalidateSelf()
    }

    /** Same lean as a Start tile, kept small because the row is wide. */
    private fun lean(row: View, x: Float, y: Float) {
        val w = row.width.coerceAtLeast(1).toFloat()
        val h = row.height.coerceAtLeast(1).toFloat()
        row.cameraDistance = w * 14f
        val max = 5f
        row.rotationY = ((x - w / 2f) / (w / 2f) * max).coerceIn(-max, max)
        row.rotationX = ((h / 2f - y) / (h / 2f) * max).coerceIn(-max, max)
    }

    private class Run(val bar: Bar) {
        var fade: ValueAnimator? = null
        var menu: Runnable? = null
        var downX = 0f
        var downY = 0f
        var scrolled = false
        var owned = false
        var sticky = false
    }

    private class Bar : Drawable() {
        var left = 0f
        var right = 0f
        var hotX = 0f
        var hotY = 0f
        var radius = 40f
        var alphaF = 1f
        var color = 0xFF0078D7.toInt()
        private val wash = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bloom = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || alphaF <= 0.01f || right - left < 2f) return
            val r = Color.red(color)
            val g = Color.green(color)
            val bl = Color.blue(color)
            val top = b.top.toFloat()
            val bottom = b.bottom.toFloat()
            wash.shader = null
            wash.color = Color.argb((0x28 * alphaF).toInt().coerceIn(0, 255), r, g, bl)
            canvas.drawRect(left, top, right, bottom, wash)
            wash.color = Color.argb((0x42 * alphaF).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawRect(left, top, right, bottom, wash)
            bloom.shader = RadialGradient(
                hotX, hotY, radius,
                intArrayOf(
                    Color.argb((0xB0 * alphaF).toInt().coerceIn(0, 255), 255, 255, 255),
                    Color.argb((0x38 * alphaF).toInt().coerceIn(0, 255), r, g, bl),
                    Color.argb(0, r, g, bl)
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(hotX, hotY, radius, bloom)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
