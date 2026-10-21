package com.wilson.win10launcher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager

/**
 * Vista glass drawn inside this window only.
 * Never blurs other apps. A live blur-behind stays on after you leave Home
 * and will ghost, then stall whatever is playing on top.
 */
object VistaGlass {

    private const val EDGE = 48
    private var cached: Drawable? = null
    private var cachedId = Int.MIN_VALUE
    private var loading = false

    fun tile(accent: Int, radius: Float, stroke: Int): Drawable {
        val strip = (stroke * 6f).coerceAtLeast(radius.coerceAtMost(8f) + stroke)
        val frame = GlassEdge(
            mix(accent, 0.55f, 0xA8),
            mix(accent, 0.28f, 0x96),
            radius,
            strip
        )
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), frame, mask)
    }

    fun sheet(dark: Boolean): Drawable {
        return ColorDrawable(if (dark) 0xF206121C.toInt() else 0xF2F4F7FB.toInt())
    }

    /** Drop any blur or wallpaper flag a previous version left on this window. */
    fun clearCompositor(activity: Activity) {
        val window = activity.window
        if (Build.VERSION.SDK_INT >= 31) {
            window.setBackgroundBlurRadius(0)
            if (window.attributes.blurBehindRadius != 0) {
                val lp = window.attributes
                lp.blurBehindRadius = 0
                window.attributes = lp
            }
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    }

    fun fallback(): Drawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF1D4F86.toInt(), 0xFF071525.toInt())
        )
    }

    /** One small blur of the wallpaper, reused. The window itself stays opaque. */
    fun frost(activity: Activity, onReady: (Drawable) -> Unit) {
        clearCompositor(activity)
        val id = wallpaperId(activity.applicationContext)
        val hit = cached
        if (hit != null && cachedId == id) {
            onReady(hit)
            return
        }
        if (loading) return
        loading = true
        val app = activity.applicationContext
        Thread {
            val built = runCatching { build(app) }.getOrNull() ?: fallback()
            activity.runOnUiThread {
                loading = false
                if (activity.isDestroyed) return@runOnUiThread
                cached = built
                cachedId = id
                onReady(built)
            }
        }.start()
    }

    private fun wallpaperId(context: Context): Int {
        return try {
            WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) {
            0
        }
    }

    private fun build(context: Context): Drawable {
        val src = WallpaperManager.getInstance(context).drawable ?: return fallback()
        if (src is BitmapDrawable && src.bitmap != null) {
            val b = src.bitmap
            val scale = EDGE.toFloat() / maxOf(b.width, b.height).coerceAtLeast(1)
            val tw = (b.width * scale).toInt().coerceIn(8, EDGE)
            val th = (b.height * scale).toInt().coerceIn(8, EDGE)
            val scaled = Bitmap.createScaledBitmap(b, tw, th, true)
            val small = if (scaled === b) b.copy(Bitmap.Config.ARGB_8888, true) else scaled
            blur(small)
            Canvas(small).drawColor(0x55081420)
            return BitmapDrawable(context.resources, small).apply {
                gravity = Gravity.FILL
                setTileModeXY(null, null)
            }
        }
        val small = shrink(src) ?: return fallback()
        blur(small)
        Canvas(small).drawColor(0x55081420)
        return BitmapDrawable(context.resources, small).apply {
            gravity = Gravity.FILL
            setTileModeXY(null, null)
        }
    }

    private fun shrink(drawable: Drawable): Bitmap? {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        val scale = EDGE.toFloat() / maxOf(w, h)
        val tw = (w * scale).toInt().coerceIn(8, EDGE)
        val th = (h * scale).toInt().coerceIn(8, EDGE)
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawable.setBounds(0, 0, tw, th)
        drawable.draw(canvas)
        return out
    }

    private fun blur(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(pix.size)
        repeat(2) {
            box(pix, tmp, w, h)
            box(tmp, pix, w, h)
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }

    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val y0 = if (y == 0) 0 else y - 1
            val y1 = if (y == h - 1) y else y + 1
            for (x in 0 until w) {
                val x0 = if (x == 0) 0 else x - 1
                val x1 = if (x == w - 1) x else x + 1
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (yy in intArrayOf(y0, y, y1)) {
                    val row = yy * w
                    for (xx in intArrayOf(x0, x, x1)) {
                        val c = src[row + xx]
                        a += c ushr 24
                        r += (c ushr 16) and 0xFF
                        g += (c ushr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                dst[y * w + x] = (a / 9 shl 24) or (r / 9 shl 16) or (g / 9 shl 8) or (b / 9)
            }
        }
    }

    private fun mix(accent: Int, white: Float, alpha: Int): Int {
        val keep = 1f - white
        val r = (Color.red(accent) * keep + 255f * white).toInt().coerceIn(0, 255)
        val g = (Color.green(accent) * keep + 255f * white).toInt().coerceIn(0, 255)
        val b = (Color.blue(accent) * keep + 255f * white).toInt().coerceIn(0, 255)
        return Color.argb(alpha, r, g, b)
    }
}

/** Frosted band around the tile. One draw, no extra views, no window blur. */
private class GlassEdge(
    private val top: Int,
    private val bottom: Int,
    private val radius: Float,
    private val strip: Float
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var paintAlpha = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        fill.alpha = paintAlpha
        edge.alpha = paintAlpha
        fill.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            top, bottom, Shader.TileMode.CLAMP
        )
        rect.set(b)
        canvas.drawRoundRect(rect, radius, radius, fill)

        // Glass strip sitting just inside the tile, not clipped off the edge.
        val band = strip.coerceAtMost(minOf(b.width(), b.height()) / 3f)
        edge.shader = null
        edge.strokeWidth = band
        edge.color = 0x73FFFFFF
        val mid = band / 2f
        rect.set(b.left + mid, b.top + mid, b.right - mid, b.bottom - mid)
        canvas.drawRoundRect(rect, radius, radius, edge)

        edge.strokeWidth = 1.5f.coerceAtLeast(band * 0.14f)
        edge.color = 0xF2FFFFFF.toInt()
        val outer = edge.strokeWidth
        rect.set(b.left + outer, b.top + outer, b.right - outer, b.bottom - outer)
        canvas.drawRoundRect(rect, radius, radius, edge)

        edge.color = 0x59000000
        val inner = band + edge.strokeWidth
        val innerR = (radius * 0.6f).coerceAtLeast(0f)
        rect.set(b.left + inner, b.top + inner, b.right - inner, b.bottom - inner)
        if (rect.width() > 0f && rect.height() > 0f) {
            canvas.drawRoundRect(rect, innerR, innerR, edge)
        }

        // Brighter catch along the top edge, the Aero highlight.
        canvas.save()
        canvas.clipRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + band * 1.15f)
        edge.strokeWidth = band * 0.55f
        edge.color = 0xE6FFFFFF.toInt()
        val topInset = edge.strokeWidth
        rect.set(b.left + topInset, b.top + topInset, b.right - topInset, b.bottom - topInset)
        canvas.drawRoundRect(rect, radius, radius, edge)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paintAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
