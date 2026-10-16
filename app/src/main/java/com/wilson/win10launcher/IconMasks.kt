package com.wilson.win10launcher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
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
        val copy = src.constantState?.newDrawable(res)?.mutate() ?: return src
        copy.setBounds(0, 0, size, size)
        canvas.clipPath(path(shape, size.toFloat()))
        copy.draw(canvas)
        val out = BitmapDrawable(res, bmp)
        cache.put(key, out)
        return out
    }

    private fun path(shape: String, s: Float): Path {
        val p = Path()
        val box = RectF(0f, 0f, s, s)
        when (shape) {
            CIRCLE -> p.addOval(box, Path.Direction.CW)
            SQUARE -> p.addRoundRect(box, s * 0.04f, s * 0.04f, Path.Direction.CW)
            ROUNDED -> p.addRoundRect(box, s * 0.22f, s * 0.22f, Path.Direction.CW)
            TEARDROP -> {
                val r = s * 0.5f
                p.addRoundRect(
                    box,
                    floatArrayOf(r, r, r, r, 0f, 0f, r, r),
                    Path.Direction.CW
                )
            }
            else -> p.addRoundRect(box, s * 0.38f, s * 0.38f, Path.Direction.CW)
        }
        return p
    }
}
