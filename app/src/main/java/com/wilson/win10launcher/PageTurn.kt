package com.wilson.win10launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * The page you are already looking at turns on its left edge and slides left.
 * That motion runs before the next screen opens, so it cannot finish off-screen.
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

    /** Turn the current page and slide it off to the left, then continue. */
    fun slideLeft(page: View, then: () -> Unit) {
        hinge(page)
        page.animate().cancel()
        val w = width(page)
        page.animate()
            .translationX(-w * 0.92f)
            .rotationY(-58f)
            .setDuration(520)
            .setInterpolator(AccelerateInterpolator(1.15f))
            .withEndAction { then() }
            .start()
    }

    /** Bring a page back from the left after an app closes. */
    fun slideBack(page: View) {
        hinge(page)
        val w = width(page)
        if (page.translationX > -8f) {
            page.translationX = -w * 0.92f
            page.rotationY = -58f
        }
        page.animate().cancel()
        page.animate()
            .translationX(0f)
            .rotationY(0f)
            .setDuration(460)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /** Next page waits to the right, turned, until the window is visible. */
    fun poseRight(page: View) {
        hinge(page)
        page.translationX = width(page) * 0.88f
        page.rotationY = 46f
    }

    fun settle(page: View) {
        hinge(page)
        page.animate().cancel()
        page.animate()
            .translationX(0f)
            .rotationY(0f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    /** Close the book: this page slides back to the right. */
    fun slideRight(page: View, then: () -> Unit) {
        var done = false
        fun once() {
            if (done) return
            done = true
            then()
        }
        hinge(page)
        page.animate().cancel()
        page.animate()
            .translationX(width(page) * 0.9f)
            .rotationY(50f)
            .setDuration(420)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction { once() }
            .start()
        page.postDelayed({ once() }, 650)
    }

    private fun width(page: View): Float {
        return if (page.width > 0) page.width.toFloat()
        else page.resources.displayMetrics.widthPixels.toFloat()
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
        page.pivotY = if (page.height > 0) page.height / 2f
        else page.resources.displayMetrics.heightPixels / 2f
        page.cameraDistance = page.resources.displayMetrics.density * 2200f
    }
}
