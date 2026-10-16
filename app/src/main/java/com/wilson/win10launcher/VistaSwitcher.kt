package com.wilson.win10launcher

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Windows Vista Aero window list. Glass panel, a row of apps, tap one to open it.
 */
object VistaSwitcher {
    private const val TAG = "vista-switcher"

    fun isOpen(root: ViewGroup) = root.findViewWithTag<View>(TAG) != null

    fun show(host: android.app.Activity, root: ViewGroup, prefs: Prefs) {
        if (isOpen(root)) return
        val apps = recent(host, prefs)
        val scrim = FrameLayout(host)
        scrim.tag = TAG
        scrim.setBackgroundColor(0x9901030A.toInt())
        scrim.isClickable = true
        scrim.setOnClickListener { hide(root) }

        val density = host.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()

        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = glass(0xE61A4A78.toInt(), 0xD0061828.toInt(), dp(6).toFloat())
            isClickable = true
        }
        val title = TextView(host).apply {
            text = "Switch windows"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        panel.addView(title)

        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        if (apps.isEmpty()) {
            row.addView(TextView(host).apply {
                text = "Open an app, then swipe up and hold."
                setTextColor(0xFFD6E6F5.toInt())
                textSize = 14f
            })
        }
        for (app in apps) {
            val card = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(12), dp(10), dp(10))
                background = glass(0x66243E5C.toInt(), 0x88101828.toInt(), dp(4).toFloat())
                val lp = LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(10)
                layoutParams = lp
                setOnClickListener {
                    hide(root)
                    launch(host, prefs, app)
                }
            }
            val icon = ImageView(host).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            }
            val label = TextView(host).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(8), 0, 0)
            }
            card.addView(icon)
            card.addView(label)
            row.addView(card)
        }
        val scroller = HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        panel.addView(scroller)
        val hint = TextView(host).apply {
            text = "Tap a window. Tap outside to close."
            setTextColor(0xFFB7C9DA.toInt())
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        panel.addView(hint)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        lp.marginStart = dp(20)
        lp.marginEnd = dp(20)
        scrim.addView(panel, lp)
        root.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        panel.alpha = 0f
        panel.translationY = dp(28).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(200L).start()
    }

    fun hide(root: ViewGroup) {
        val scrim = root.findViewWithTag<View>(TAG) ?: return
        scrim.animate().alpha(0f).setDuration(140L).withEndAction { root.removeView(scrim) }.start()
    }

    private fun glass(top: Int, bottom: Int, radius: Float): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radius
            setStroke(1, 0x88FFFFFF.toInt())
        }
    }

    private data class App(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable)

    private fun recent(host: android.app.Activity, prefs: Prefs): List<App> {
        val pm = host.packageManager
        val out = ArrayList<App>()
        for (pkg in prefs.recentApps()) {
            if (pkg == host.packageName) continue
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                if (launch.component == null && launch.action == null) continue
                out.add(App(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info)))
            } catch (e: Exception) {
                continue
            }
            if (out.size >= 8) break
        }
        return out
    }

    private fun launch(host: android.app.Activity, prefs: Prefs, app: App) {
        val intent = host.packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            Toast.makeText(host, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            host.startActivity(intent)
            prefs.noteLaunch(app.pkg)
        } catch (e: Exception) {
            Toast.makeText(host, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
