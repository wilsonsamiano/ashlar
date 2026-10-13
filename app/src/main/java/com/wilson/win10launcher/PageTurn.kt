package com.wilson.win10launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Interpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * Windows Phone 7 motions, taken from the open-source phone toolkit
 * (TurnstileTransition on phone.codeplex.com) and from Matthias Shapiro's
 * frame-by-frame WP7 videos.
 *
 * Turnstile (settings): the whole page is one flat card hinged on the left
 * edge. Toolkit ForwardOut swings RotationY 0 → about 50° with ExponentialEase
 * EaseIn; ForwardIn settles to 0 with EaseOut. Videos time the swing out at
 * ~250ms and the settle at ~350ms, exponent 6. Only the right edge swings.
 * Android's positive rotationY is the mirror of the phone's PlaneProjection,
 * so the angle is negative — 2.0 turned the page backwards. One hardware layer,
 * so the tile grid cannot draw a second copy.
 *
 * Tile tap is the same turnstile, not a separate door per tile. Every tile
 * hinges on the left edge of the screen (and the vertical middle), so they
 * stay one page. They are still separate views, so the gaps stay open.
 * The tapped tile presses in, then starts the turn. The others follow a
 * short beat behind.
 *
 * App list (arrow, or swipe left on Start): not a turnstile. The videos show
 * a flat horizontal slide of the whole screen. No rotation.
 */
object PageTurn {

    private const val OUT_MS = 700L
    private const val IN_MS = 900L
    private const val SLIDE_MS = 520L
    private const val EXPO = 6f
    private const val PUSH_MS = 160L
    /** Long on purpose so a slow screen still paints the whole turn. */
    private const val FEATHER_OUT = 720L
    private const val FEATHER_IN = 860L

    /** Left hinge. Negative: Android's positive rotationY is the mirror of WP7. */
    private const val OPEN = -72f

    /** Left hinge of the screen, and the vertical middle. Same point for every tile. */
    private class Hinge(val x: Float, val y: Float, val pageW: Float)

    private var wave: ValueAnimator? = null

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

    /**
     * Tile tap. Press the tapped tile in, then turn every tile on the screen's
     * left edge together. The tapped one leads. The rest follow close behind.
     * [then] runs when the last tile has finished turning.
     */
    fun featherOut(grid: RecyclerView, tapped: View, then: () -> Unit) {
        val faces = facesOf(grid)
        if (faces.isEmpty()) {
            then()
            return
        }
        wave?.cancel()
        val loc = IntArray(2)
        tapped.getLocationOnScreen(loc)
        val ox = loc[0] + tapped.width / 2f
        val oy = loc[1] + tapped.height / 2f
        for (face in faces) {
            face.animate().cancel()
            face.rotationX = 0f
            face.rotationY = 0f
            face.scaleX = 1f
            face.scaleY = 1f
            if (face.layerType != View.LAYER_TYPE_HARDWARE) {
                face.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        }
        unclip(grid)
        tapped.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setStartDelay(0L)
            .setDuration(PUSH_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                if (!grid.isAttachedToWindow) {
                    then()
                    return@withEndAction
                }
                val hinge = pageHinge(grid)
                val tiles = ArrayList<TileSpin>(faces.size)
                for (face in facesOf(grid)) {
                    face.rotationX = 0f
                    face.rotationY = 0f
                    face.scaleX = 1f
                    face.scaleY = 1f
                    hingeFace(face, hinge)
                    tiles.add(TileSpin(face, featherDelay(face, ox, oy, face === tapped, true)))
                }
                playWave(tiles, 0f, OPEN, FEATHER_OUT, easeOut = false, end = then)
            }
            .start()
    }

    fun featherReturn(grid: RecyclerView, ox: Float, oy: Float) {
        onPreDraw(grid) {
            wave?.cancel()
            unclip(grid)
            val hinge = pageHinge(grid)
            val faces = facesOf(grid)
            val tiles = ArrayList<TileSpin>(faces.size)
            for (face in faces) {
                face.rotationX = 0f
                face.rotationY = 0f
                face.scaleX = 1f
                face.scaleY = 1f
                hingeFace(face, hinge)
                face.rotationY = OPEN
                tiles.add(TileSpin(face, featherDelay(face, ox, oy, origin = false, leaving = false)))
            }
            playWave(tiles, OPEN, 0f, FEATHER_IN, easeOut = true) {
                for (face in faces) releaseFace(face)
            }
        }
    }

    fun featherReset(grid: RecyclerView) {
        wave?.cancel()
        wave = null
        for (face in facesOf(grid)) releaseFace(face)
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

    private fun pageHinge(grid: RecyclerView): Hinge {
        val page = grid.rootView.findViewById<View>(android.R.id.content) ?: grid
        val loc = IntArray(2)
        page.getLocationOnScreen(loc)
        val h = if (page.height > 0) page.height else grid.height
        val w = if (page.width > 0) page.width.toFloat()
        else grid.resources.displayMetrics.widthPixels.toFloat()
        return Hinge(loc[0].toFloat(), loc[1] + h / 2f, w)
    }

    private fun facesOf(grid: RecyclerView): List<View> {
        val out = ArrayList<View>(grid.childCount)
        for (i in 0 until grid.childCount) {
            val face = grid.getChildAt(i).findViewById<View>(R.id.tileFace) ?: continue
            if (face.width > 0 && face.height > 0) out.add(face)
        }
        return out
    }

    /** Distance from the tapped tile. The tapped tile itself waits 0. */
    private fun featherDelay(
        face: View,
        ox: Float,
        oy: Float,
        origin: Boolean,
        leaving: Boolean
    ): Long {
        if (origin) return 0L
        val loc = IntArray(2)
        face.getLocationOnScreen(loc)
        val cx = loc[0] + face.width / 2f
        val cy = loc[1] + face.height / 2f
        val dist = hypot((cx - ox).toDouble(), (cy - oy).toDouble()).toFloat()
        val step = face.resources.displayMetrics.density * 96f
        return if (leaving) {
            // Tight wave. A long gap makes the page fall apart into separate doors.
            (dist / step * 18f).toLong().coerceAtMost(70L)
        } else {
            (dist / step * 12f).toLong().coerceAtMost(40L)
        }
    }

    private class TileSpin(val face: View, val delay: Long)

    /** One clock for every tile. Each tile is a GPU texture so the long turn stays smooth. */
    private fun playWave(
        tiles: List<TileSpin>,
        from: Float,
        to: Float,
        ms: Long,
        easeOut: Boolean,
        end: (() -> Unit)?
    ) {
        wave?.cancel()
        if (tiles.isEmpty()) {
            end?.invoke()
            return
        }
        val ease = ExpoEase(EXPO, easeOut)
        var maxDelay = 0L
        for (t in tiles) if (t.delay > maxDelay) maxDelay = t.delay
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = maxDelay + ms
        anim.addUpdateListener {
            val now = anim.currentPlayTime
            for (t in tiles) {
                val u = if (now <= t.delay) 0f
                else ((now - t.delay).toFloat() / ms.toFloat()).coerceIn(0f, 1f)
                t.face.rotationY = from + (to - from) * ease.getInterpolation(u)
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (wave === anim) wave = null
                if (!cancelled) end?.invoke()
            }
        })
        wave = anim
        anim.start()
    }

    private fun unclip(grid: RecyclerView) {
        var p: android.view.ViewParent? = grid
        while (p is ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            if (p.id == android.R.id.content) break
            p = p.parent
        }
    }

    /** Same hinge as the whole-page turn, so the tiles stay one sheet. */
    private fun hingeFace(face: View, hinge: Hinge) {
        val loc = IntArray(2)
        face.getLocationOnScreen(loc)
        if (face.layerType != View.LAYER_TYPE_HARDWARE) {
            face.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        face.pivotX = hinge.x - loc[0]
        face.pivotY = hinge.y - loc[1]
        face.cameraDistance = hinge.pageW * 4.8f
    }

    private fun releaseFace(face: View) {
        face.animate().cancel()
        face.rotationX = 0f
        face.rotationY = 0f
        face.scaleX = 1f
        face.scaleY = 1f
        if (face.width > 0 && face.height > 0) {
            face.pivotX = face.width / 2f
            face.pivotY = face.height / 2f
        }
        face.setLayerType(View.LAYER_TYPE_NONE, null)
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
