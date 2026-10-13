package com.wilson.win10launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Two motions, kept apart on purpose.
 * Slide is translation only. A 3D turn on the same view draws a second copy
 * of the tiles and leaves the first one sitting still.
 * Turn is rotation only, used when an app opens.
 */
object PageTurn {

    fun noSystemAnim(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN, R.anim.none, R.anim.none
            )
            activity.overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_CLOSE, R.anim.none, R.anim.none
            )
        }
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(R.anim.none, R.anim.none)
    }

    fun appOptions(activity: Activity): android.os.Bundle {
        val opts = android.app.ActivityOptions.makeCustomAnimation(
            activity, R.anim.none, R.anim.none
        )
        if (Build.VERSION.SDK_INT >= 33) {
            opts.setSplashScreenStyle(0)
        }
        return opts.toBundle()
    }

    fun slideOff(page: View, then: () -> Unit) {
        flat(page)
        page.animate().cancel()
        page.animate()
            .translationX(-width(page))
            .setDuration(260)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { then() }
            .start()
    }

    fun slideBack(page: View) {
        flat(page)
        if (page.translationX > -8f) page.translationX = -width(page)
        page.animate().cancel()
        page.animate()
            .translationX(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** All apps waits off the right edge. Slide, not a turn. */
    fun poseSlideIn(page: View) {
        flat(page)
        page.translationX = width(page)
    }

    fun settleSlide(page: View) {
        flat(page)
        page.animate().cancel()
        page.animate()
            .translationX(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun slideAwayRight(page: View, then: () -> Unit) {
        var done = false
        fun once() {
            if (done) return
            done = true
            then()
        }
        flat(page)
        page.animate().cancel()
        page.animate()
            .translationX(width(page))
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { once() }
            .start()
        page.postDelayed({ once() }, 400)
    }

    /** Book page. No sideways slide, so the tiles are not drawn twice. */
    fun turnOut(page: View, then: () -> Unit) {
        page.animate().cancel()
        page.translationX = 0f
        hinge(page)
        page.animate()
            .rotationY(-72f)
            .setDuration(420)
            .setInterpolator(AccelerateInterpolator(1.1f))
            .withEndAction { then() }
            .start()
    }

    fun turnBack(page: View) {
        page.translationX = 0f
        hinge(page)
        if (page.rotationY > -8f) page.rotationY = -72f
        page.animate().cancel()
        page.animate()
            .rotationY(0f)
            .setDuration(380)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun poseTurned(page: View) {
        page.translationX = 0f
        hinge(page)
        page.rotationY = 72f
    }

    fun settleTurn(page: View) {
        page.translationX = 0f
        hinge(page)
        page.animate().cancel()
        page.animate()
            .rotationY(0f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun turnClosed(page: View, then: () -> Unit) {
        var done = false
        fun once() {
            if (done) return
            done = true
            then()
        }
        page.translationX = 0f
        hinge(page)
        page.animate().cancel()
        page.animate()
            .rotationY(72f)
            .setDuration(360)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { once() }
            .start()
        page.postDelayed({ once() }, 520)
    }

    private fun flat(page: View) {
        page.rotationX = 0f
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
    }

    private fun width(page: View): Float {
        return if (page.width > 0) page.width.toFloat()
        else page.resources.displayMetrics.widthPixels.toFloat()
    }

    private fun hinge(page: View) {
        page.pivotX = 0f
        page.pivotY = if (page.height > 0) page.height / 2f
        else page.resources.displayMetrics.heightPixels / 2f
        page.cameraDistance = page.resources.displayMetrics.density * 9000f
    }
}
