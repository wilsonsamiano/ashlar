package com.wilson.win10launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Windows Phone 7 turnstile, done on the view itself.
 * Activity XML animations were dropped by the system, so the page never moved.
 * The screen hinges on the left edge and swings like a book page.
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

    fun playOpen(page: View) {
        page.visibility = View.INVISIBLE
        page.post {
            hinge(page)
            page.rotationY = 86f
            page.alpha = 0.35f
            page.visibility = View.VISIBLE
            page.animate()
                .rotationY(0f)
                .alpha(1f)
                .setDuration(560)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .withEndAction { page.setLayerType(View.LAYER_TYPE_NONE, null) }
                .start()
        }
    }

    fun playClose(page: View, then: () -> Unit) {
        page.animate().cancel()
        hinge(page)
        page.animate()
            .rotationY(86f)
            .alpha(0.2f)
            .setDuration(420)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withEndAction {
                page.setLayerType(View.LAYER_TYPE_NONE, null)
                then()
            }
            .start()
    }

    private fun hinge(page: View) {
        (page.parent as? ViewGroup)?.clipChildren = false
        if (page is ViewGroup) page.clipChildren = false
        page.pivotX = 0f
        page.pivotY = page.height / 2f
        page.cameraDistance = page.resources.displayMetrics.density * 4200f
        page.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }
}
