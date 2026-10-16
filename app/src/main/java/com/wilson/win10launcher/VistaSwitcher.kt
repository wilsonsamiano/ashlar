package com.wilson.win10launcher

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Windows Vista Flip 3D. Windows sit in a tilted stack over Start.
 * Swipe up or down to flip. Tap the front window to open it.
 * Android does not let a launcher replace the system overview, so this is
 * what swipe-up-and-hold opens on Start.
 */
object VistaSwitcher {
    private const val TAG = "vista-switcher"

    fun isOpen(root: ViewGroup) = root.findViewWithTag<View>(TAG) != null

    fun show(host: android.app.Activity, root: ViewGroup, prefs: Prefs) {
        if (isOpen(root)) return
        val density = host.resources.displayMetrics.density
        fun dp(n: Int) = (n * density)
        val wins = ArrayList<Win>()
        wins.addAll(recent(host, prefs))
        wins.add(Win(null, "Desktop", null))

        val scrim = FrameLayout(host)
        scrim.tag = TAG
        scrim.clipChildren = false
        scrim.setBackgroundColor(0x33000000)
        scrim.setOnClickListener { hide(root) }

        val stack = FrameLayout(host)
        stack.clipChildren = false
        val cardW = (host.resources.displayMetrics.widthPixels * 0.62f).toInt()
        val cardH = (cardW * 0.70f).toInt()
        val order = ArrayList<View>(wins.size)
        for (i in wins.indices.reversed()) {
            val card = window(host, wins[i], density)
            card.tag = wins[i]
            stack.addView(card, FrameLayout.LayoutParams(cardW, cardH, Gravity.CENTER))
            order.add(0, card)
        }
        scrim.addView(stack, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        fun pose(animate: Boolean) {
            order.forEachIndexed { i, card ->
                card.cameraDistance = density * 3400f
                if (card.width > 0) {
                    card.pivotX = card.width / 2f
                    card.pivotY = card.height / 2f
                }
                val x = -i * dp(32)
                val y = -i * dp(24)
                val s = 1f - i * 0.04f
                card.animate().cancel()
                if (!animate) {
                    card.rotationY = -24f
                    card.rotationX = 12f
                    card.translationX = x
                    card.translationY = y
                    card.scaleX = s
                    card.scaleY = s
                } else {
                    card.animate()
                        .rotationY(-24f)
                        .rotationX(12f)
                        .translationX(x)
                        .translationY(y)
                        .scaleX(s)
                        .scaleY(s)
                        .setDuration(240L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
        }

        fun flip(forward: Boolean) {
            if (order.size < 2) return
            val moved = if (forward) order.removeAt(0) else order.removeAt(order.lastIndex)
            if (forward) {
                order.add(moved)
                stack.removeView(moved)
                stack.addView(moved, 0)
            } else {
                order.add(0, moved)
                stack.removeView(moved)
                stack.addView(moved)
            }
            pose(true)
        }

        for (card in order) {
            var downY = 0f
            var moved = false
            card.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(e.rawY - downY) > dp(16)) moved = true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dy = downY - e.rawY
                        if (!moved) {
                            val win = card.tag as Win
                            hide(root)
                            if (win.pkg != null) launch(host, prefs, win)
                        } else if (dy > dp(28)) {
                            flip(true)
                        } else if (dy < -dp(28)) {
                            flip(false)
                        }
                    }
                }
                true
            }
        }

        root.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        stack.post { pose(false) }
    }

    fun hide(root: ViewGroup) {
        val scrim = root.findViewWithTag<View>(TAG) ?: return
        scrim.animate().alpha(0f).setDuration(140L).withEndAction { root.removeView(scrim) }.start()
    }

    private fun window(
        host: android.app.Activity,
        win: Win,
        density: Float
    ): View {
        fun dp(n: Int) = (n * density).toInt()
        val card = FrameLayout(host)
        card.background = GradientDrawable().apply {
            setColor(if (win.pkg == null) 0xFF0F6FBE.toInt() else 0xFFF3F7FB.toInt())
            cornerRadius = dp(2).toFloat()
            setStroke(dp(1), 0x99FFFFFF.toInt())
        }
        card.elevation = 10 * density
        val bar = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(6), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFB9D7EF.toInt(), 0xFF3E78B0.toInt())
            )
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
        }
        if (win.icon != null) {
            bar.addView(ImageView(host).apply {
                setImageDrawable(win.icon)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            })
        }
        bar.addView(TextView(host).apply {
            text = win.label
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 1
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(dp(6), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(mark(host, 0x55FFFFFF, density))
        bar.addView(mark(host, 0x55FFFFFF, density))
        bar.addView(mark(host, 0xFFC75050.toInt(), density))
        card.addView(bar)
        if (win.icon != null) {
            card.addView(ImageView(host).apply {
                setImageDrawable(win.icon)
                val size = dp(72)
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            })
        }
        return card
    }

    private fun mark(host: android.app.Activity, color: Int, density: Float): View {
        val s = (11 * density).toInt()
        val gap = (4 * density).toInt()
        return View(host).apply {
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = density
            }
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = gap }
        }
    }

    private data class Win(val pkg: String?, val label: String, val icon: Drawable?)

    private fun recent(host: android.app.Activity, prefs: Prefs): List<Win> {
        val pm = host.packageManager
        val out = ArrayList<Win>()
        for (pkg in prefs.recentApps()) {
            if (pkg == host.packageName) continue
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (pm.getLaunchIntentForPackage(pkg) == null) continue
                out.add(Win(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info)))
            } catch (e: Exception) {
                continue
            }
            if (out.size >= 6) break
        }
        return out
    }

    private fun launch(host: android.app.Activity, prefs: Prefs, win: Win) {
        val pkg = win.pkg ?: return
        val intent = host.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(host, "Can't open ${win.label}", Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            host.startActivity(intent)
            prefs.noteLaunch(pkg)
        } catch (e: Exception) {
            Toast.makeText(host, "Can't open ${win.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
