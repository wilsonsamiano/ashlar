package com.wilson.win10launcher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/** Adaptive-icon masks, the same set Android offers in its icon shape picker. */
object IconMasks {
    const val SYSTEM = "system"
    const val CIRCLE = "circle"
    const val SQUIRCLE = "squircle"
    const val ROUNDED = "rounded"
    const val SQUARE = "square"
    const val TEARDROP = "teardrop"

    val choices = listOf(
        SYSTEM to "System",
        CIRCLE to "Circle",
        SQUIRCLE to "Squircle",
        ROUNDED to "Rounded",
        SQUARE to "Square",
        TEARDROP to "Teardrop"
    )

    private val cache = LruCache<String, Drawable>(80)

    fun apply(res: Resources, src: Drawable, shape: String, size: Int): Drawable {
        if (shape == SYSTEM || size <= 0) return src
        val key = "${src.constantState?.hashCode() ?: src.hashCode()}:$shape:$size"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.clipPath(path(shape, size.toFloat()))
        val copy = src.constantState?.newDrawable(res)?.mutate() ?: src
        if (copy is AdaptiveIconDrawable) {
            // Draw the raw layers. AdaptiveIconDrawable.draw() would force the system circle.
            val extra = (size * AdaptiveIconDrawable.getExtraInsetFraction()).toInt()
            val bounds = Rect(-extra, -extra, size + extra, size + extra)
            layer(res, copy.background, bounds)?.draw(canvas)
            layer(res, copy.foreground, bounds)?.draw(canvas)
        } else {
            copy.setBounds(0, 0, size, size)
            copy.draw(canvas)
        }
        val out = BitmapDrawable(res, bmp)
        cache.put(key, out)
        return out
    }

    private fun layer(res: Resources, src: Drawable?, bounds: Rect): Drawable? {
        val d = src?.constantState?.newDrawable(res)?.mutate() ?: src ?: return null
        d.bounds = bounds
        return d
    }

    private fun path(shape: String, s: Float): Path {
        val p = Path()
        val box = RectF(0f, 0f, s, s)
        when (shape) {
            CIRCLE -> p.addOval(box, Path.Direction.CW)
            SQUARE -> p.addRoundRect(box, s * 0.06f, s * 0.06f, Path.Direction.CW)
            ROUNDED -> p.addRoundRect(box, s * 0.22f, s * 0.22f, Path.Direction.CW)
            TEARDROP -> {
                val r = s * 0.5f
                p.addRoundRect(
                    box,
                    floatArrayOf(r, r, r, r, 0f, 0f, r, r),
                    Path.Direction.CW
                )
            }
            else -> p.addRoundRect(box, s * 0.36f, s * 0.36f, Path.Direction.CW)
        }
        return p
    }
}
