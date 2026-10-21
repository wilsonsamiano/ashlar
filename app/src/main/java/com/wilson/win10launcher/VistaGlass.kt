package com.wilson.win10launcher

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.WindowManager

/** See-through Vista glass. Metro tiles never call this. */
object VistaGlass {

    fun tile(accent: Int, radius: Float, stroke: Int): Drawable {
        val plate = GradientDrawable().apply {
            cornerRadius = radius
            setColor(tint(accent))
            setStroke(stroke.coerceAtLeast(1), 0xD0FFFFFF.toInt())
        }
        val shine = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xC0FFFFFF.toInt(), 0x26FFFFFF, 0x00000000, 0x40000000)
        ).apply { cornerRadius = radius }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x66FFFFFF),
            LayerDrawable(arrayOf(plate, shine)),
            mask
        )
    }

    /** Frosted sheet for pages that need readable text over the wallpaper. */
    fun sheet(dark: Boolean): Drawable {
        return GradientDrawable().apply {
            setColor(if (dark) 0xB8081420.toInt() else 0xC8F3F6FA.toInt())
        }
    }

    fun backdrop(activity: Activity, on: Boolean) {
        val window = activity.window
        if (!on) {
            if (Build.VERSION.SDK_INT >= 31) {
                window.setBackgroundBlurRadius(0)
                val lp = window.attributes
                lp.blurBehindRadius = 0
                window.attributes = lp
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 31 && window.windowManager.isCrossWindowBlurEnabled) {
            val lp = window.attributes
            lp.blurBehindRadius = 56
            window.attributes = lp
        }
    }

    private fun tint(accent: Int): Int {
        val r = (Color.red(accent) * 0.58f + 255f * 0.42f).toInt().coerceIn(0, 255)
        val g = (Color.green(accent) * 0.58f + 255f * 0.42f).toInt().coerceIn(0, 255)
        val b = (Color.blue(accent) * 0.58f + 255f * 0.42f).toInt().coerceIn(0, 255)
        return Color.argb(0x73, r, g, b)
    }
}
