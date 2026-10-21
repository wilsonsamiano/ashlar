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
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Vista glass drawn inside this window only.
 * Never blurs other apps. A live blur-behind stays on after you leave Home
 * and will ghost, then stall whatever is playing on top.
 */
object VistaGlass {

    private const val EDGE = 48
    private var cached: Drawable? = null
    private var plateBmp: Bitmap? = null
    private var cachedId = Int.MIN_VALUE
    private var loading = false

    /** Blurred wallpaper with the scrim left off, so a tile can lens it. */
    fun sample(): Bitmap? = plateBmp

    fun ice(accent: Int, dark: Boolean, capsule: Boolean): Drawable = IcePlate(accent, dark, capsule)

    fun bind(drawable: Drawable, view: View) {
        (drawable as? IcePlate)?.host = view
    }

    fun tile(accent: Int, radius: Float, stroke: Int): Drawable {
        val strip = (stroke * 6f).coerceIn(stroke * 4f, stroke * 10f)
        val frame = GlassEdge(accent, radius.coerceAtLeast(stroke.toFloat()), strip)
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
            plateBmp = small.copy(Bitmap.Config.ARGB_8888, false)
            Canvas(small).drawColor(0x55081420)
            return BitmapDrawable(context.resources, small).apply {
                gravity = Gravity.FILL
                setTileModeXY(null, null)
            }
        }
        val small = shrink(src) ?: return fallback()
        blur(small)
        plateBmp = small.copy(Bitmap.Config.ARGB_8888, false)
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

/** Glass frame around a flat phone tile. The color stays in the middle. */
private class GlassEdge(
    private val accent: Int,
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
        val shadow = (strip * 0.45f).coerceAtLeast(2f)
        val tile = RectF(
            b.left + shadow * 0.25f,
            b.top + shadow * 0.15f,
            b.right - shadow,
            b.bottom - shadow
        )
        if (tile.width() < 4f || tile.height() < 4f) return

        fill.alpha = paintAlpha
        edge.alpha = paintAlpha
        fill.shader = null
        fill.color = 0x55000000
        rect.set(tile.left, tile.top + shadow * 0.55f, tile.right, tile.bottom + shadow * 0.2f)
        canvas.drawRoundRect(rect, radius, radius, fill)

        // The rim is the glass. Cool, brighter along the top.
        fill.shader = LinearGradient(
            tile.left, tile.top, tile.left, tile.bottom,
            intArrayOf(0xF4FFFFFF.toInt(), 0xC8D5E6F4.toInt(), 0x99778BA3.toInt()),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(tile, radius, radius, fill)

        val band = strip.coerceAtMost(minOf(tile.width(), tile.height()) * 0.22f)
        val plateR = (radius * 0.35f).coerceAtLeast(1f)
        val plate = RectF(tile.left + band, tile.top + band, tile.right - band, tile.bottom - band)
        if (plate.width() <= 0f || plate.height() <= 0f) return

        fill.shader = LinearGradient(
            plate.left, plate.top, plate.left, plate.bottom,
            mix(accent, 0.16f, 0xF4),
            mix(accent, 0.02f, 0xF7),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(plate, plateR, plateR, fill)

        edge.shader = null
        edge.strokeWidth = 1f
        edge.color = 0x66000000
        edge.alpha = paintAlpha
        canvas.drawRoundRect(plate, plateR, plateR, edge)

        edge.strokeWidth = 1.25f
        edge.color = 0xEEFFFFFF.toInt()
        edge.alpha = paintAlpha
        val outer = 0.8f
        rect.set(tile.left + outer, tile.top + outer, tile.right - outer, tile.bottom - outer)
        canvas.drawRoundRect(rect, radius, radius, edge)

        canvas.save()
        canvas.clipRect(tile.left, tile.top, tile.right, tile.top + band * 0.9f)
        edge.strokeWidth = (band * 0.62f).coerceAtLeast(1.5f)
        edge.color = 0xD9FFFFFF.toInt()
        edge.alpha = paintAlpha
        val topInset = edge.strokeWidth / 2f
        rect.set(tile.left + topInset, tile.top + topInset, tile.right - topInset, tile.bottom - topInset)
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

    private fun mix(color: Int, white: Float, alpha: Int): Int {
        val keep = 1f - white
        return Color.argb(
            alpha,
            (Color.red(color) * keep + 255f * white).toInt().coerceIn(0, 255),
            (Color.green(color) * keep + 255f * white).toInt().coerceIn(0, 255),
            (Color.blue(color) * keep + 255f * white).toInt().coerceIn(0, 255)
        )
    }
}

/**
 * Liquid-glass tile. The wallpaper is painted into the tile, then milked over,
 * so the window itself stays opaque and nothing blurs the app underneath.
 */
private class IcePlate(
    private val accent: Int,
    private val dark: Boolean,
    private val capsule: Boolean
) : Drawable() {

    var host: View? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var shader: BitmapShader? = null
    private var shaderBmp: Bitmap? = null
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val rad = if (capsule) {
            minOf(rect.width(), rect.height()) / 2f
        } else {
            minOf(rect.width(), rect.height()) * 0.26f
        }

        val bmp = VistaGlass.sample()
        val view = host
        fill.alpha = 255
        if (bmp != null && view != null && view.isAttachedToWindow) {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val root = view.rootView
            val sx = root.width.coerceAtLeast(1) / bmp.width.toFloat()
            val sy = root.height.coerceAtLeast(1) / bmp.height.toFloat()
            matrix.setScale(sx, sy)
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            val sh = if (shaderBmp === bmp) shader else null
            val live = sh ?: BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                shader = it
                shaderBmp = bmp
            }
            live.setLocalMatrix(matrix)
            fill.shader = live
            canvas.drawRoundRect(rect, rad, rad, fill)
        } else {
            fill.shader = null
            fill.color = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
            canvas.drawRoundRect(rect, rad, rad, fill)
        }

        fill.shader = null
        fill.color = Color.argb(
            if (dark) 0x55 else 0x48,
            Color.red(accent), Color.green(accent), Color.blue(accent)
        )
        canvas.drawRoundRect(rect, rad, rad, fill)

        fill.color = if (dark) 0xA61C1C1E.toInt() else 0xC4FFFFFF.toInt()
        canvas.drawRoundRect(rect, rad, rad, fill)

        val topH = rect.height() * 0.40f
        canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + topH)
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + topH,
            0xD6FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, rad, rad, fill)
        canvas.restore()

        val botH = rect.height() * 0.20f
        canvas.save()
        canvas.clipRect(rect.left, rect.bottom - botH, rect.right, rect.bottom)
        fill.shader = LinearGradient(
            0f, rect.bottom - botH, 0f, rect.bottom,
            0x00FFFFFF, 0xA6FFFFFF.toInt(), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, rad, rad, fill)
        canvas.restore()

        fill.shader = null
        edge.strokeWidth = 1.6f
        edge.color = 0xB3000000.toInt()
        val inset = 0.8f
        canvas.drawRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            rad, rad, edge
        )
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
