package com.wilson.win10launcher

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Press feedback for a wide app row. A glow runs from the finger to the icon
 * so a tap on the empty right side still shows which app was hit.
 */
object PressBeam {
    private val runs = java.util.WeakHashMap<View, Run>()

    fun onTouch(row: View, icon: View, e: MotionEvent, color: Int) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> show(row, icon, e.x, color)
            MotionEvent.ACTION_CANCEL -> release(row)
            MotionEvent.ACTION_UP -> linger(row)
        }
    }

    fun clear(row: View) {
        val run = runs.remove(row) ?: return
        run.release?.let { row.removeCallbacks(it) }
        run.anim?.cancel()
        run.fade?.cancel()
        row.overlay.remove(run.beam)
        run.icon.animate().cancel()
        run.icon.scaleX = 1f
        run.icon.scaleY = 1f
    }

    fun release(row: View) {
        val run = runs[row] ?: return
        if (run.fade != null) return
        run.release?.let { row.removeCallbacks(it) }
        run.anim?.cancel()
        val fade = ValueAnimator.ofFloat(run.beam.alphaF, 0f)
        fade.duration = 140
        fade.addUpdateListener {
            run.beam.alphaF = it.animatedValue as Float
            run.beam.invalidateSelf()
        }
        fade.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (runs[row] === run) clear(row)
            }
        })
        run.fade = fade
        run.icon.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        fade.start()
    }

    private fun linger(row: View) {
        val run = runs[row] ?: return
        run.release?.let { row.removeCallbacks(it) }
        val later = Runnable { release(row) }
        run.release = later
        row.postDelayed(later, 220)
    }

    private fun show(row: View, icon: View, touchX: Float, color: Int) {
        clear(row)
        if (row.width == 0) return
        val beam = Beam()
        beam.color = color
        beam.touchX = touchX
        val iconMid = icon.left + icon.width / 2f
        val gap = 8f * row.resources.displayMetrics.density
        beam.iconX = if (touchX >= iconMid) icon.right + gap else icon.left - gap
        beam.glow = 14f * row.resources.displayMetrics.density
        beam.setBounds(0, 0, row.width, row.height)
        val run = Run(beam, icon)
        runs[row] = run
        row.overlay.add(beam)
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator()
        var pulsed = false
        anim.addUpdateListener {
            val p = it.animatedValue as Float
            beam.head = p
            beam.invalidateSelf()
            if (!pulsed && p > 0.72f) {
                pulsed = true
                icon.animate().scaleX(1.14f).scaleY(1.14f).setDuration(120).start()
            }
        }
        run.anim = anim
        anim.start()
    }

    private class Run(val beam: Beam, val icon: View) {
        var anim: ValueAnimator? = null
        var fade: ValueAnimator? = null
        var release: Runnable? = null
    }

    private class Beam : Drawable() {
        var touchX = 0f
        var iconX = 0f
        var head = 0f
        var alphaF = 1f
        var color = 0xFF0078D7.toInt()
        var glow = 24f
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blob = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty || alphaF <= 0.01f) return
            val r = Color.red(color)
            val g = Color.green(color)
            val bl = Color.blue(color)
            fill.color = Color.argb((0x30 * alphaF).toInt(), r, g, bl)
            canvas.drawRect(b, fill)

            val cy = b.height() / 2f
            val x0 = touchX.coerceIn(0f, b.width().toFloat())
            val x1 = iconX.coerceIn(0f, b.width().toFloat())
            val span = x1 - x0
            if (kotlin.math.abs(span) < glow) {
                drawHead(canvas, x1, cy, r, g, bl)
                return
            }
            val hot = Color.argb((0xEE * alphaF).toInt(), 255, 255, 255)
            val mid = Color.argb((0xD0 * alphaF).toInt(), r, g, bl)
            val dim = Color.argb((0x44 * alphaF).toInt(), r, g, bl)
            stroke.shader = null
            stroke.strokeWidth = glow * 0.55f
            stroke.color = dim
            canvas.drawLine(x0, cy, x1, cy, stroke)
            val headX = x0 + span * head
            if (kotlin.math.abs(headX - x0) < 1f) {
                drawHead(canvas, headX, cy, r, g, bl)
                return
            }
            stroke.shader = LinearGradient(
                x0, cy, headX, cy,
                intArrayOf(Color.argb((0x30 * alphaF).toInt(), r, g, bl), mid, hot),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            stroke.strokeWidth = glow
            canvas.drawLine(x0, cy, headX, cy, stroke)
            stroke.shader = null
            stroke.strokeWidth = glow * 0.18f
            stroke.color = hot
            canvas.drawLine(x0, cy, headX, cy, stroke)
            drawHead(canvas, headX, cy, r, g, bl)
        }

        private fun drawHead(canvas: Canvas, x: Float, y: Float, r: Int, g: Int, b: Int) {
            val radius = glow * 1.35f
            blob.shader = RadialGradient(
                x, y, radius,
                intArrayOf(
                    Color.argb((0xFF * alphaF).toInt(), 255, 255, 255),
                    Color.argb((0xAA * alphaF).toInt(), r, g, b),
                    Color.argb(0, r, g, b)
                ),
                floatArrayOf(0f, 0.38f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, radius, blob)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
