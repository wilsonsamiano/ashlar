package com.wilson.win10launcher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.Interpolator

/**
 * Windows Phone 7 motions, taken from the open-source phone toolkit
 * (TurnstileTransition on phone.codeplex.com) and from Matthias Shapiro's
 * frame-by-frame WP7 videos.
 *
 * Turnstile (opening an app, settings): the whole page is one flat card
 * hinged on the left edge. Toolkit ForwardOut swings RotationY 0 → about 50°
 * with ExponentialEase EaseIn; ForwardIn settles to 0 with EaseOut.
 * Videos time the swing out at ~250ms and the settle at ~350ms, exponent 6.
 * Only the right edge swings. Android's positive rotationY is the mirror of the
 * phone's PlaneProjection, so the angle is negative — 2.0 turned the page
 * backwards. One hardware layer, so the tile grid cannot draw a second copy.
 *
 * App list (arrow, or swipe left on Start): not a turnstile. The videos show
 * a flat horizontal slide of the whole screen. No rotation.
 */
object PageTurn {

    private const val OUT_MS = 260L
    private const val IN_MS = 350L
    private const val SLIDE_MS = 280L
    private const val EXPO = 6f

    /** Left hinge. Negative: Android's positive rotationY is the mirror of WP7. */
    private const val OPEN = -72f

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

    fun quiet(intent: Intent): Intent {
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return intent
    }

    fun appOptions(activity: Activity): android.os.Bundle {
        val opts = android.app.ActivityOptions.makeCustomAnimation(
            activity, R.anim.none, R.anim.none
        )
        if (Build.VERSION.SDK_INT >= 33) opts.setSplashScreenStyle(0)
        return opts.toBundle()
    }

    /** First frame already off to the right, then a flat slide to rest. */
    fun enterSlide(page: View, fromX: Float) {
        page.animate().cancel()
        flat(page)
        page.translationX = fromX
        onPreDraw(page) {
            flat(page)
            page.translationX = fromX
            page.post { slide(page, 0f, easeOut = true, hold = false) }
        }
    }

    /** Start is already parked off the left. Slide it back once it is on screen. */
    fun returnSlide(page: View, fromX: Float) {
        page.animate().cancel()
        flat(page)
        page.translationX = fromX
        onPreDraw(page) {
            flat(page)
            page.translationX = fromX
            page.post { slide(page, 0f, easeOut = true, hold = false) }
        }
    }

    fun leaveSlide(page: View, toX: Float, then: () -> Unit) {
        page.animate().cancel()
        flat(page)
        slide(page, toX, easeOut = true, hold = true, then = then)
    }

    /** Arriving page: posed folded back, then EaseOut to flat. No zoom. */
    fun enterTurn(page: View) {
        page.animate().cancel()
        page.translationX = 0f
        page.rotationY = OPEN
        onPreDraw(page) {
            hinge(page)
            page.translationX = 0f
            page.rotationY = OPEN
            page.post { spin(page, 0f, IN_MS, easeOut = true, hold = false) }
        }
    }

    /** Page underneath is still folded. Unfold it (toolkit BackwardIn). */
    fun returnTurn(page: View) {
        page.animate().cancel()
        onPreDraw(page) {
            hinge(page)
            page.translationX = 0f
            page.rotationY = OPEN
            page.post { spin(page, 0f, IN_MS, easeOut = true, hold = false) }
        }
    }

    /** Toolkit ForwardOut: 0 → folded, EaseIn, then hold so it doesn't snap flat. */
    fun leaveTurn(page: View, then: () -> Unit) {
        page.animate().cancel()
        hinge(page)
        page.translationX = 0f
        page.rotationY = 0f
        spin(page, OPEN, OUT_MS, easeOut = false, hold = true, then = then)
    }

    fun slideAwayRight(page: View, then: () -> Unit) {
        val w = widthOf(page)
        leaveSlide(page, w, then)
    }

    /** Finger tracking. Flat. The view itself moves — there is no second copy. */
    fun trackSlide(page: View, x: Float) {
        page.animate().cancel()
        page.rotationY = 0f
        page.rotationX = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.translationX = x
    }

    private fun slide(
        page: View,
        toX: Float,
        easeOut: Boolean,
        hold: Boolean,
        then: (() -> Unit)? = null
    ) {
        page.rotationY = 0f
        page.animate().cancel()
        page.animate()
            .translationX(toX)
            .rotationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SLIDE_MS)
            .setInterpolator(ExpoEase(EXPO, easeOut))
            .withEndAction {
                if (!hold) release(page)
                then?.invoke()
            }
            .start()
    }

    private fun spin(
        page: View,
        toDeg: Float,
        ms: Long,
        easeOut: Boolean,
        hold: Boolean,
        then: (() -> Unit)? = null
    ) {
        page.animate().cancel()
        page.animate()
            .rotationY(toDeg)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ms)
            .setInterpolator(ExpoEase(EXPO, easeOut))
            .withEndAction {
                if (!hold) release(page)
                then?.invoke()
            }
            .start()
    }

    private fun flat(page: View) {
        page.rotationX = 0f
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
    }

    /** One texture for the whole page. Rotating the live grid draws the tiles twice. */
    private fun hinge(page: View) {
        flat(page)
        page.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val h = if (page.height > 0) page.height else (page.parent as? View)?.height ?: 0
        page.pivotX = 0f
        page.pivotY = h / 2f
        val w = widthOf(page)
        // Far enough that the near edge doesn't balloon, close enough to read as a hinge.
        page.cameraDistance = w * 4.8f
    }

    private fun release(page: View) {
        page.animate().cancel()
        page.translationX = 0f
        page.rotationX = 0f
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun widthOf(page: View): Float {
        if (page.width > 0) return page.width.toFloat()
        return page.resources.displayMetrics.widthPixels.toFloat()
    }

    private fun onPreDraw(page: View, block: () -> Unit) {
        val obs = page.viewTreeObserver
        obs.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (page.viewTreeObserver.isAlive) {
                    page.viewTreeObserver.removeOnPreDrawListener(this)
                }
                block()
                return true
            }
        })
    }

    /** Silverlight ExponentialEase, exponent 6, as in the phone toolkit. */
    private class ExpoEase(private val exponent: Float, private val out: Boolean) : Interpolator {
        override fun getInterpolation(t: Float): Float {
            fun easeIn(x: Float): Float {
                val e = Math.exp(exponent.toDouble())
                return ((Math.exp(exponent * x.toDouble()) - 1.0) / (e - 1.0)).toFloat()
            }
            return if (out) 1f - easeIn(1f - t) else easeIn(t)
        }
    }
}
