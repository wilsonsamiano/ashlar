package com.wilson.win10launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Book-page turn. The pose is applied before the first draw, and the swing
 * starts only once the window is on screen. scaleX from the left spine is
 * included so the turn still shows on tablets where 3D rotation is ignored.
 */
object PageTurn {

    fun noSystemAnim(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    /** Closed book: a sliver on the left edge. Call before the first draw. */
    fun poseClosed(page: View) {
        hinge(page)
        page.scaleX = 0.06f
        page.rotationY = 72f
        page.alpha = 0.55f
    }

    /** Swing the page flat. Call from onWindowFocusChanged so it is visible. */
    fun startOpen(page: View) {
        hinge(page)
        page.animate().cancel()
        page.animate()
            .scaleX(1f)
            .rotationY(0f)
            .alpha(1f)
            .setDuration(680)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    fun playClose(page: View, then: () -> Unit) {
        var done = false
        fun once() {
            if (done) return
            done = true
            then()
        }
        hinge(page)
        page.animate().cancel()
        page.animate()
            .scaleX(0.06f)
            .rotationY(72f)
            .alpha(0.4f)
            .setDuration(420)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction { once() }
            .start()
        page.postDelayed({ once() }, 700)
    }

    private fun hinge(page: View) {
        var parent = page.parent
        while (parent is ViewGroup) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent = parent.parent
        }
        if (page is ViewGroup) {
            page.clipChildren = false
            page.clipToPadding = false
        }
        page.pivotX = 0f
        page.pivotY = if (page.height > 0) page.height / 2f else page.resources.displayMetrics.heightPixels / 2f
        page.cameraDistance = page.resources.displayMetrics.density * 2400f
    }
}
