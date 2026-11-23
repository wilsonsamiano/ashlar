package com.wilson.win10launcher

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager

/**
 * Vista glass drawn inside this window only.
 * Never blurs other apps. A live blur-behind stays on after you leave Home
 * and will ghost, then stall whatever is playing on top.
 */
object VistaGlass {

    private const val EDGE = 96
    /** Lightly blurred wallpaper for clear glass. */
    private const val CLEAR_EDGE = 360
    /** Sharp wallpaper behind Vista and Ice tiles. */
    private const val PAGE_EDGE = 1440
    private var cached: Drawable? = null
    private var plateBmp: Bitmap? = null
    private var clearBmp: Bitmap? = null
    private var cachedId = Int.MIN_VALUE
    private var loading = false

    /**
     * Ice glass, 0 = frosted, 0.5 = clear, 1 = clearer. Set from Prefs.iceClarity
     * before the tiles draw. Every Ice plate reads it when it draws.
     */
    @Volatile
    var clarity: Float = 0.5f

    /** Blurred wallpaper with the scrim left off, so a tile can lens it. */
    fun sample(): Bitmap? = plateBmp

    /** Lightly blurred wallpaper, same framing as [sample], for clear glass. */
    fun clearSample(): Bitmap? = clearBmp

    fun ice(accent: Int, dark: Boolean, capsule: Boolean): Drawable = IcePlate(accent, dark, capsule)

    fun bind(drawable: Drawable, view: View) {
        (drawable as? IcePlate)?.host = view
    }

    /** Keep labels and icons inside the same rounded glass the tile paints. */
    fun clip(view: View, capsule: Boolean) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val w = v.width
                val h = v.height
                if (w <= 0 || h <= 0) return
                val rad = if (capsule) minOf(w, h) / 2f else minOf(w, h) * 0.22f
                outline.setRoundRect(0, 0, w, h, rad)
            }
        }
        view.invalidateOutline()
    }

    fun unclip(view: View) {
        if (!view.clipToOutline) return
        view.clipToOutline = false
    }

    fun tile(accent: Int, radius: Float, stroke: Int): Drawable {
        val band = stroke * 8f
        val frame = GlassEdge(accent, radius.coerceAtLeast(stroke * 3f), band, stroke.toFloat())
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

    /**
     * The page behind the tiles, built once per wallpaper and reused.
     * Vista and Ice both get the wallpaper sharp, so the glass reads against
     * it: Vista's clear edges show it directly, Ice lenses it. The window
     * itself stays opaque.
     */
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

    /**
     * The picture the glass looks use. Android 13 and newer do not let a normal
     * app read the phone's wallpaper (getDrawable throws), so the person picks
     * a picture once in Settings and Ashlar keeps its own copy here.
     */
    fun pictureFile(context: Context): java.io.File =
        java.io.File(context.applicationContext.filesDir, "glass_wallpaper.jpg")

    fun hasPicture(context: Context): Boolean = pictureFile(context).isFile

    /**
     * Copy a picked picture into [pictureFile], at most 1920 px on its long
     * side, turned the right way up. Runs off the main thread. False if the
     * picture could not be read.
     */
    fun savePicture(context: Context, uri: android.net.Uri): Boolean {
        val app = context.applicationContext
        val bmp = runCatching { decodeScaled(app, uri, 1920) }.getOrNull() ?: return false
        val out = pictureFile(app)
        val tmp = java.io.File(out.parentFile, out.name + ".tmp")
        val ok = runCatching {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.getOrDefault(false)
        if (!ok || !tmp.renameTo(out)) {
            tmp.delete()
            return false
        }
        return true
    }

    fun clearPicture(context: Context) {
        pictureFile(context).delete()
    }

    private fun decodeScaled(context: Context, uri: android.net.Uri, edge: Int): Bitmap? {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder turns camera photos the right way up on its own.
            val source = android.graphics.ImageDecoder.createSource(cr, uri)
            return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val long = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (long / (sample * 2) >= edge) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= edge) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun wallpaperId(context: Context): Int {
        val own = pictureFile(context)
        if (own.isFile) return (own.lastModified() xor own.length()).toInt() or 1
        return try {
            WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) {
            0
        }
    }

    /** The picked picture, or the phone's wallpaper where Android still allows reading it. */
    private fun source(context: Context): Drawable? {
        val own = pictureFile(context)
        if (own.isFile) {
            val bmp = android.graphics.BitmapFactory.decodeFile(own.path)
            if (bmp != null) return BitmapDrawable(context.resources, bmp)
        }
        return runCatching { WallpaperManager.getInstance(context).drawable }.getOrNull()
    }

    private fun build(context: Context): Drawable {
        val src = source(context) ?: run {
            plateBmp = null
            clearBmp = null
            return fallback()
        }
        val dm = context.resources.displayMetrics
        // Frame the wallpaper the way the screen does: fill it, crop the overflow.
        // Every sample uses the same framing, so a tile lines up with the page.
        val aspect = dm.heightPixels.toFloat() / dm.widthPixels.coerceAtLeast(1)
        val plate = render(src, EDGE, aspect) ?: return fallback()
        blur(plate)
        plateBmp = plate
        val clear = render(src, CLEAR_EDGE, aspect)
        if (clear != null) blurOnce(clear)
        clearBmp = clear
        val long = maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(PAGE_EDGE)
        val page = render(src, long, aspect) ?: return fallback()
        Canvas(page).drawColor(0x14000000)
        return pageDrawable(context, page)
    }

    private fun pageDrawable(context: Context, bmp: Bitmap): Drawable {
        return BitmapDrawable(context.resources, bmp).apply {
            gravity = Gravity.FILL
            setTileModeXY(null, null)
            isFilterBitmap = true
        }
    }

    /** Draw the wallpaper into a bitmap of the screen's shape, longest side [edge], cropped to fill. */
    private fun render(src: Drawable, edge: Int, aspect: Float): Bitmap? {
        val iw = src.intrinsicWidth
        val ih = src.intrinsicHeight
        if (iw <= 0 || ih <= 0) return null
        val tw: Int
        val th: Int
        if (aspect >= 1f) {
            th = edge
            tw = (edge / aspect).toInt().coerceAtLeast(8)
        } else {
            tw = edge
            th = (edge * aspect).toInt().coerceAtLeast(8)
        }
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val scale = maxOf(tw / iw.toFloat(), th / ih.toFloat())
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        val left = (tw - dw) / 2
        val top = (th - dh) / 2
        val keep = Rect(src.bounds)
        src.setBounds(left, top, left + dw, top + dh)
        src.draw(Canvas(out))
        src.bounds = keep
        return out
    }

    private fun blurOnce(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        val tmp = IntArray(pix.size)
        box(pix, tmp, w, h)
        bitmap.setPixels(tmp, 0, w, 0, 0, w, h)
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

/**
 * Vista Aero tile. A clear, colored glass edge around a solid color tile,
 * with Vista's gloss over the whole thing:
 *  - the edge is see-through: the wallpaper page behind the tile shows
 *    through, tinted with the accent color (nothing is painted under it)
 *  - diagonal light streaks, strongest on the glass edge
 *  - a bright top half with a hard horizon, and a darker bottom half
 *  - a dark outer line and a bright inner line, as on a Vista window
 */
private class GlassEdge(
    private val accent: Int,
    private val radius: Float,
    private val band: Float,
    private val line: Float
) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tile = RectF()
    private val plate = RectF()
    private val rect = RectF()
    private var paintAlpha = 255

    // Built when the size changes, not on every frame.
    private val builtFor = RectF()
    private var streak: LinearGradient? = null
    private var gloss: LinearGradient? = null
    private var edgeGloss: LinearGradient? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val shadow = (band * 0.35f).coerceAtLeast(2f)
        tile.set(b.left + 1f, b.top + 1f, b.right - shadow, b.bottom - shadow)
        if (tile.width() < 8f || tile.height() < 8f) return
        val inset = band.coerceAtMost(minOf(tile.width(), tile.height()) * 0.18f)
        plate.set(tile.left + inset, tile.top + inset, tile.right - inset, tile.bottom - inset)
        val plateR = (radius * 0.4f).coerceAtLeast(1f)
        prepare()

        // Soft shadow: a few widening, faint rings.
        fill.shader = null
        for (i in 0 until 3) {
            val grow = i * 1.5f
            fill.color = 0x16000000
            fill.alpha = 0x16 * paintAlpha / 255
            rect.set(tile.left - grow, tile.top + 2f + grow, tile.right + grow, tile.bottom + 2f + grow)
            canvas.drawRoundRect(rect, radius + grow, radius + grow, fill)
        }

        // Clear colored glass: only a tint, so the wallpaper behind shows through.
        fill.color = Color.argb(0x3A, Color.red(accent), Color.green(accent), Color.blue(accent))
        fill.alpha = 0x3A * paintAlpha / 255
        canvas.drawRoundRect(tile, radius, radius, fill)

        // The color tile inside the glass.
        fill.color = accent
        fill.alpha = paintAlpha
        canvas.drawRoundRect(plate, plateR, plateR, fill)

        // Streaks: faint across the tile, strong on the glass edge.
        fill.color = Color.WHITE
        fill.shader = streak
        fill.alpha = 0x70 * paintAlpha / 255
        canvas.drawRoundRect(tile, radius, radius, fill)
        canvas.save()
        canvas.clipOutRect(plate)
        fill.alpha = paintAlpha
        canvas.drawRoundRect(tile, radius, radius, fill)
        // The edge is glossier than the tile.
        fill.shader = edgeGloss
        canvas.drawRoundRect(tile, radius, radius, fill)
        canvas.restore()

        // Vista gloss over everything: bright top half, hard horizon, darker bottom half.
        fill.shader = gloss
        fill.alpha = paintAlpha
        canvas.drawRoundRect(tile, radius, radius, fill)
        fill.shader = null

        // Lines: around the color tile, then the outer dark and inner bright edge.
        edge.strokeWidth = line
        edge.color = 0x80FFFFFF.toInt()
        edge.alpha = 0x80 * paintAlpha / 255
        rect.set(plate.left - line, plate.top - line, plate.right + line, plate.bottom + line)
        canvas.drawRoundRect(rect, plateR + line, plateR + line, edge)
        edge.color = 0xA0000000.toInt()
        edge.alpha = 0xA0 * paintAlpha / 255
        canvas.drawRoundRect(plate, plateR, plateR, edge)
        edge.color = 0xB8000000.toInt()
        edge.alpha = 0xB8 * paintAlpha / 255
        val h = line / 2f
        rect.set(tile.left + h, tile.top + h, tile.right - h, tile.bottom - h)
        canvas.drawRoundRect(rect, radius, radius, edge)
        edge.color = 0xE6FFFFFF.toInt()
        edge.alpha = 0xE6 * paintAlpha / 255
        val i = line * 1.5f
        rect.set(tile.left + i, tile.top + i, tile.right - i, tile.bottom - i)
        canvas.drawRoundRect(rect, radius - line, radius - line, edge)
    }

    private fun prepare() {
        if (builtFor == tile && gloss != null) return
        builtFor.set(tile)
        // One bright narrow streak and one wide faint one per repeat, running
        // diagonally down to the right, as on Vista's glass.
        val period = maxOf(tile.width(), tile.height()) * 1.1f
        streak = LinearGradient(
            tile.left, tile.top, tile.left + period * 0.8f, tile.top + period * 0.6f,
            intArrayOf(0, 0, 0x4CFFFFFF, 0, 0, 0x24FFFFFF, 0, 0),
            floatArrayOf(0f, 0.25f, 0.30f, 0.35f, 0.50f, 0.62f, 0.74f, 1f),
            Shader.TileMode.REPEAT
        )
        val mid = tile.top + tile.height() * 0.5f
        gloss = LinearGradient(
            0f, tile.top, 0f, tile.bottom,
            intArrayOf(0x8CFFFFFF.toInt(), 0x30FFFFFF, 0x00000000, 0x26000000),
            floatArrayOf(0f, 0.5f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        edgeGloss = LinearGradient(
            0f, tile.top, 0f, mid,
            0x40FFFFFF, 0x10FFFFFF,
            Shader.TileMode.CLAMP
        )
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

/**
 * Liquid-glass tile. The wallpaper is painted into the tile, then lit like
 * glass, so the window itself stays opaque and nothing blurs the app underneath.
 *
 * VistaGlass.clarity sets the glass, 0 to 1:
 *  0    frosted. Heavily blurred wallpaper, milky, bright sheen.
 *  0.5  clear. Lightly blurred wallpaper, magnified a little, as a thick lens does.
 *  1    clearer. Almost no milk, thin sheen, strong edges.
 */
private class IcePlate(
    private val accent: Int,
    private val dark: Boolean,
    private val capsule: Boolean
) : Drawable() {

    var host: View? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val ring = RectF()
    private val loc = IntArray(2)
    private val matrix = Matrix()

    private var frostShader: BitmapShader? = null
    private var frostOf: Bitmap? = null
    private var clearShader: BitmapShader? = null
    private var clearOf: Bitmap? = null

    // Gradients depend only on the plate's size and the clarity, so they are
    // built when one of those changes, not on every frame.
    private val builtFor = RectF()
    private var builtClarity = -1f
    private var sheen: LinearGradient? = null
    private var shade: LinearGradient? = null
    private var rim: LinearGradient? = null
    private var boost: ColorMatrixColorFilter? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val rad = if (capsule) {
            minOf(rect.width(), rect.height()) / 2f
        } else {
            minOf(rect.width(), rect.height()) * 0.22f
        }
        val c = VistaGlass.clarity.coerceIn(0f, 1f)
        prepare(c)

        // Soft shadow under the glass: a few widening, faint rings.
        fill.shader = null
        fill.colorFilter = null
        fill.alpha = 255
        for (i in 0 until 4) {
            val grow = i * 1.5f
            fill.color = 0x12000000
            ring.set(rect.left - grow, rect.top + 3f + grow, rect.right + grow, rect.bottom + 3f + grow)
            canvas.drawRoundRect(ring, rad + grow, rad + grow, fill)
        }

        // The lens: what is behind the tile, brightened and a touch more colorful.
        val view = host
        val frost = VistaGlass.sample()
        val clear = VistaGlass.clearSample() ?: frost
        if (frost != null && clear != null && view != null && view.isAttachedToWindow) {
            view.getLocationInWindow(loc)
            val root = view.rootView
            val rw = root.width.coerceAtLeast(1).toFloat()
            val rh = root.height.coerceAtLeast(1).toFloat()
            fill.colorFilter = boost

            matrix.setScale(rw / clear.width, rh / clear.height)
            matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
            val mag = magnify(c)
            if (mag != 1f) matrix.postScale(mag, mag, rect.centerX(), rect.centerY())
            val cs = clearShader.takeIf { clearOf === clear }
                ?: BitmapShader(clear, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                    clearShader = it
                    clearOf = clear
                }
            cs.setLocalMatrix(matrix)
            fill.shader = cs
            canvas.drawRoundRect(rect, rad, rad, fill)

            val frostAlpha = ((1f - 2f * c).coerceIn(0f, 1f) * 255f).toInt()
            if (frostAlpha > 0) {
                matrix.setScale(rw / frost.width, rh / frost.height)
                matrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
                val fs = frostShader.takeIf { frostOf === frost }
                    ?: BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
                        frostShader = it
                        frostOf = frost
                    }
                fs.setLocalMatrix(matrix)
                fill.shader = fs
                fill.alpha = frostAlpha
                canvas.drawRoundRect(rect, rad, rad, fill)
                fill.alpha = 255
            }
        } else {
            fill.shader = null
            fill.color = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
            canvas.drawRoundRect(rect, rad, rad, fill)
        }
        fill.colorFilter = null
        fill.shader = null

        // A hint of the accent, then the milk that frosts the glass.
        fill.color = Color.argb(
            step(c, 0x16, 0x10, 0x06),
            Color.red(accent), Color.green(accent), Color.blue(accent)
        )
        canvas.drawRoundRect(rect, rad, rad, fill)
        fill.color = if (dark) {
            Color.argb(step(c, 0x26, 0x12, 0x08), 0, 0, 0)
        } else {
            Color.argb(step(c, 0x30, 0x12, 0x04), 255, 255, 255)
        }
        canvas.drawRoundRect(rect, rad, rad, fill)

        // Light across the top, and the glass thickening toward the bottom.
        fill.color = Color.WHITE
        fill.shader = sheen
        canvas.drawRoundRect(rect, rad, rad, fill)
        fill.shader = shade
        canvas.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null

        // Clear glass bends light away at its edge: a faint dark ring inside the rim.
        val ringAlpha = step(c, 0x00, 0x30, 0x40)
        if (ringAlpha > 0) {
            edge.shader = null
            edge.strokeWidth = 3f
            edge.color = Color.argb(ringAlpha, 0, 0, 0)
            ring.set(rect.left + 3f, rect.top + 3f, rect.right - 3f, rect.bottom - 3f)
            canvas.drawRoundRect(ring, rad - 2f, rad - 2f, edge)
        }

        // Rim: bright where the light hits (top-left), fading, catching again bottom-right.
        edge.color = Color.WHITE
        edge.shader = rim
        edge.strokeWidth = 2f
        ring.set(rect.left + 1f, rect.top + 1f, rect.right - 1f, rect.bottom - 1f)
        canvas.drawRoundRect(ring, rad, rad, edge)
        edge.shader = null
    }

    private fun prepare(c: Float) {
        if (builtFor == rect && builtClarity == c && sheen != null) return
        builtFor.set(rect)
        builtClarity = c
        val top = step(c, 0x55, 0x40, 0x2C)
        sheen = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * 0.5f,
            Color.argb(top, 255, 255, 255), 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        shade = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            0x00000000, 0x26000000,
            Shader.TileMode.CLAMP
        )
        rim = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xF2FFFFFF.toInt(), 0x4DFFFFFF, Color.argb(step(c, 0x40, 0x8C, 0xA6), 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val sat = lerp3(c, 1.35f, 1.20f, 1.12f)
        val light = lerp3(c, 1.10f, 1.08f, 1.05f)
        val m = ColorMatrix().apply { setSaturation(sat) }
        m.postConcat(ColorMatrix().apply { setScale(light, light, light, 1f) })
        boost = ColorMatrixColorFilter(m)
    }

    private fun magnify(c: Float): Float {
        return 1f + 0.20f * minOf(c, 0.5f) + 0.04f * maxOf(c - 0.5f, 0f)
    }

    /** Linear from [a] at 0 to [b] at 0.5 to [z] at 1. */
    private fun lerp3(c: Float, a: Float, b: Float, z: Float): Float {
        return if (c <= 0.5f) a + (b - a) * (c / 0.5f) else b + (z - b) * ((c - 0.5f) / 0.5f)
    }

    private fun step(c: Float, a: Int, b: Int, z: Int): Int {
        return lerp3(c, a.toFloat(), b.toFloat(), z.toFloat()).toInt().coerceIn(0, 255)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
