package com.wilson.win10launcher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
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
 *
 * The swing itself runs on the RenderThread. View.animate() of rotation,
 * translation, and scale is handed to the GPU when nothing listens to each
 * frame. An end action or an update listener pulls that work back onto the UI
 * thread, which is what made a slow tablet drop frames in the middle of the
 * turn. Completion is a one-shot clock instead. The ease is still Silverlight
 * ExponentialEase exponent 6; the render thread samples it once into a table.
 */
object PageTurn {

    private const val OUT_MS = 280L
    private const val IN_MS = 350L
    private const val SLIDE_MS = 300L
    private const val EXPO = 6f
    private const val PUSH_MS = 120L
    /** Long on purpose so a slow screen still paints the whole turn. */
    private const val FEATHER_OUT = 320L
    private const val FEATHER_IN = 380L
    /** Series stagger: one item after another, not all at once. Capped so a long list still finishes in time. */
    private const val SERIES_MS = 30L
    private const val SERIES_CAP_MS = 250L
    private fun seriesDelay(index: Int): Long = (index * SERIES_MS).coerceAtMost(SERIES_CAP_MS)

    /** Left hinge. Negative: Android's positive rotationY is the mirror of WP7. */
    private const val OPEN = -72f

    private val EASE_IN = ExpoEase(EXPO, false)
    private val EASE_OUT = ExpoEase(EXPO, true)

    /** WP7 slide ease, for the drawer settle. Same ExpoEase exponent 6 as PageTurn.slide. */
    fun easeOut(): Interpolator = EASE_OUT
    fun easeIn(): Interpolator = EASE_IN
    private val DECEL = DecelerateInterpolator()
    private val handler = Handler(Looper.getMainLooper())
    private var laterToken = 0
    private var laterRunnable: Runnable? = null
    private var waveFaces: List<View> = emptyList()

    /** True while a turn, slide, or tile wave is in flight. Clocks must not invalidate then. */
    fun animating(): Boolean = laterRunnable != null

    fun owns(face: View): Boolean = waveFaces.any { it === face }

    /** Drop a tile that left the screen so it cannot come back mid-turn. */
    fun drop(face: View) {
        if (owns(face)) waveFaces = waveFaces.filter { it !== face }
        releaseFace(face)
    }

    /** Flatten every visible tile that is not in the current wave. */
    fun flatten(grid: RecyclerView) {
        for (i in 0 until grid.childCount) {
            val face = grid.getChildAt(i).findViewById<View>(R.id.tileFace) ?: continue
            if (!owns(face)) releaseFace(face)
        }
    }

    /** Left hinge of the screen, and the vertical middle. Same point for every tile. */
    private class Hinge(val x: Float, val y: Float, val pageW: Float)

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
     * Tile tap, in series, not all at once. The tapped tile presses in, then
     * the other tiles swing out one after another in child order, each
     * hinged on the left edge of the screen so they still read as one page
     * turning. The tapped tile waits, then swings last, alone, and only then
     * does [then] run and the app open. This is the WP7 application-entry
     * motion: unrelated tiles flip out first, the selected tile last.
     */
    fun featherOut(grid: RecyclerView, tapped: View, then: () -> Unit) {
        val page = sheetOf(grid)
        cancelMotion()
        for (face in facesOf(grid)) {
            if (face !== tapped) releaseFace(face)
        }
        unclip(page)
        tapped.animate().cancel()
        tapped.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setStartDelay(0L)
            .setDuration(PUSH_MS)
            .setInterpolator(DECEL)
            .start()
        later(PUSH_MS) {
            if (!page.isAttachedToWindow) {
                unlock(page)
                freeze(grid, false)
                then()
                return@later
            }
            val hinge = pageHinge(grid)
            val faces = facesOf(grid)
            val others = faces.filter { it !== tapped }
            val tiles = ArrayList<TileSpin>(faces.size)
            for ((i, face) in others.withIndex()) {
                hingeFace(face, hinge)
                face.scaleX = 1f
                face.scaleY = 1f
                tiles.add(TileSpin(face, seriesDelay(i)))
            }
            // Tapped tile last, after every other tile has started.
            val tappedFace = faces.firstOrNull { it === tapped } ?: tapped
            hingeFace(tappedFace, hinge)
            tappedFace.scaleX = 1f
            tappedFace.scaleY = 1f
            tiles.add(TileSpin(tappedFace, seriesDelay(others.size)))
            lock(page)
            freeze(grid, true)
            playWave(tiles, 0f, OPEN, FEATHER_OUT, easeOut = false) {
                freeze(grid, false)
                then()
            }
        }
    }

    /**
     * Return home: the exact reverse of the exit, in series. Exit was
     * others in child order, tapped last. So return is tapped first, then
     * others in reverse child order. Not simply reverse child order: with
     * tiles A, B, C and B tapped, exit is A, C, B, so return must be
     * B, C, A, not C, B, A. ox/oy are the tapped tile's centre, set at
     * tap time; the closest face to them is the tapped tile. If no face
     * is close, fall back to reverse child order.
     */
    fun featherReturn(grid: RecyclerView, ox: Float, oy: Float) {
        val page = sheetOf(grid)
        onPreDraw(page) {
            cancelMotion()
            for (face in facesOf(grid)) releaseFace(face)
            unclip(page)
            lock(page)
            freeze(grid, true)
            val hinge = pageHinge(grid)
            val faces = facesOf(grid)
            val tappedFace = faces.minByOrNull { face ->
                val loc = IntArray(2)
                face.getLocationOnScreen(loc)
                val cx = loc[0] + face.width / 2f
                val cy = loc[1] + face.height / 2f
                hypot((cx - ox).toDouble(), (cy - oy).toDouble())
            }
            val ordered = ArrayList<View>(faces.size)
            if (tappedFace != null) ordered.add(tappedFace)
            for (face in faces.reversed()) {
                if (face !== tappedFace) ordered.add(face)
            }
            val tiles = ArrayList<TileSpin>(ordered.size)
            for ((i, face) in ordered.withIndex()) {
                hingeFace(face, hinge)
                tiles.add(TileSpin(face, seriesDelay(i)))
            }
            playWave(tiles, OPEN, 0f, FEATHER_IN, easeOut = true) {
                freeze(grid, false)
                unlock(page)
            }
        }
    }

    fun featherReset(grid: RecyclerView) {
        val page = sheetOf(grid)
        val extra = waveFaces
        cancelMotion()
        for (face in extra) releaseFace(face)
        for (face in facesOf(grid)) releaseFace(face)
        release(page)
        unlock(page)
        freeze(grid, false)
    }

    /**
     * App drawer entrance, in series. Visible All Apps rows slide in from
     * the right one after another, top to bottom, while the drawer strip
     * itself slides. Only the visible rows stagger; scrolling later does
     * not re-run it. Call at the same time as the strip settle, not after
     * it, so the whole entrance still finishes in about 550ms.
     */
    fun drawerStaggerIn(list: RecyclerView) {
        val density = list.resources.displayMetrics.density
        val offset = 64f * density
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val face = if (child.id == R.id.tileFace) child
                else child.findViewById<View>(R.id.tileFace) ?: child
            face.animate().cancel()
            face.translationX = offset
            face.alpha = 0f
            face.animate()
                .translationX(0f)
                .alpha(1f)
                .setStartDelay(seriesDelay(i))
                .setDuration(SLIDE_MS)
                .setInterpolator(EASE_OUT)
                .start()
        }
    }

    /**
     * App drawer close, in reverse series. Visible rows peel off last
     * first, sliding right and fading, while the strip slides back. The
     * next drawerStaggerIn resets translation and alpha, so a quick
     * reopen is never left faded.
     */
    fun drawerStaggerOut(list: RecyclerView) {
        val density = list.resources.displayMetrics.density
        val offset = 64f * density
        val count = list.childCount
        for (i in 0 until count) {
            val child = list.getChildAt(i)
            val face = if (child.id == R.id.tileFace) child
                else child.findViewById<View>(R.id.tileFace) ?: child
            face.animate().cancel()
            face.animate()
                .translationX(offset)
                .alpha(0f)
                .setStartDelay(seriesDelay(count - 1 - i))
                .setDuration(SLIDE_MS)
                .setInterpolator(EASE_OUT)
                .start()
        }
    }

    fun slideAwayRight(page: View, then: () -> Unit) {
        val w = widthOf(page)
        leaveSlide(page, w, then)
    }

    /** Finger tracking. Flat. The view itself moves — there is no second copy. */
    fun trackSlide(page: View, x: Float) {
        cancelLater()
        page.animate().cancel()
        page.rotationY = 0f
        page.rotationX = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.translationX = x
    }

    /** The page that owns the tiles, so the fold is one sheet instead of many layers. */
    private fun sheetOf(grid: RecyclerView): View {
        var v: View = grid
        while (true) {
            if (v.id == R.id.startPage || v.id == R.id.allAppsPage) return v
            v = v.parent as? View ?: return grid
        }
    }

    private val locked = HashMap<View, Int>()

    /** Hold the page height so a taskbar inset cannot reflow tiles mid-fold. */
    private fun lock(page: View) {
        if (locked.containsKey(page)) return
        val h = page.height
        if (h <= 0) return
        locked[page] = page.layoutParams.height
        page.layoutParams.height = h
    }

    private fun unlock(page: View) {
        val prev = locked.remove(page) ?: return
        page.layoutParams.height = prev
        page.requestLayout()
    }

    private fun freeze(grid: RecyclerView, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) grid.suppressLayout(on)
        else @Suppress("DEPRECATION") grid.isLayoutFrozen = on
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

    /**
     * One render-thread animator per tile. No per-frame listener, or the whole
     * wave would fall back to the UI thread. The clock below only starts the
     * next screen; it does not move the tiles.
     */
    private fun playWave(
        tiles: List<TileSpin>,
        from: Float,
        to: Float,
        ms: Long,
        easeOut: Boolean,
        end: (() -> Unit)?
    ) {
        if (tiles.isEmpty()) {
            end?.invoke()
            return
        }
        val ease = if (easeOut) EASE_OUT else EASE_IN
        var maxDelay = 0L
        val faces = ArrayList<View>(tiles.size)
        for (t in tiles) {
            if (t.delay > maxDelay) maxDelay = t.delay
            val face = t.face
            face.animate().cancel()
            face.rotationY = from
            face.animate()
                .rotationY(to)
                .setStartDelay(t.delay)
                .setDuration(ms)
                .setInterpolator(ease)
                .start()
            faces.add(face)
        }
        waveFaces = faces
        later(maxDelay + ms) {
            waveFaces = emptyList()
            end?.invoke()
        }
    }

    private fun cancelMotion() {
        cancelLater()
        val faces = waveFaces
        waveFaces = emptyList()
        for (face in faces) releaseFace(face)
    }

    private fun cancelLater() {
        laterToken++
        laterRunnable?.let { handler.removeCallbacks(it) }
        laterRunnable = null
    }

    private fun later(ms: Long, block: () -> Unit) {
        cancelLater()
        val token = laterToken
        val run = Runnable {
            if (token != laterToken) return@Runnable
            laterRunnable = null
            block()
        }
        laterRunnable = run
        handler.postDelayed(run, ms)
    }

    private fun unclip(start: View) {
        var p: View? = start
        while (p is ViewGroup) {
            p.clipChildren = false
            p.clipToPadding = false
            if (p.id == android.R.id.content) break
            p = p.parent as? View
        }
    }

    /** Same hinge as the whole-page turn, so the tiles stay one sheet. */
    private fun hingeFace(face: View, hinge: Hinge) {
        val loc = IntArray(2)
        face.getLocationOnScreen(loc)
        warm(face)
        face.pivotX = hinge.x - loc[0]
        face.pivotY = hinge.y - loc[1]
        face.cameraDistance = hinge.pageW * 4.8f
    }

    private fun releaseFace(face: View) {
        val clean = face.rotationX == 0f && face.rotationY == 0f &&
            face.scaleX == 1f && face.scaleY == 1f &&
            face.translationX == 0f && face.translationY == 0f &&
            face.alpha == 1f &&
            face.layerType == View.LAYER_TYPE_NONE
        if (clean) return
        face.animate().setStartDelay(0L)
        face.animate().cancel()
        face.rotationX = 0f
        face.rotationY = 0f
        face.scaleX = 1f
        face.scaleY = 1f
        face.translationX = 0f
        face.translationY = 0f
        face.alpha = 1f
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
            .setStartDelay(0L)
            .setDuration(SLIDE_MS)
            .setInterpolator(if (easeOut) EASE_OUT else EASE_IN)
            .start()
        later(SLIDE_MS) {
            if (!hold) release(page)
            then?.invoke()
        }
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
            .setStartDelay(0L)
            .setDuration(ms)
            .setInterpolator(if (easeOut) EASE_OUT else EASE_IN)
            .start()
        later(ms) {
            if (!hold) release(page)
            then?.invoke()
        }
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
        warm(page)
        val h = if (page.height > 0) page.height else (page.parent as? View)?.height ?: 0
        page.pivotX = 0f
        page.pivotY = h / 2f
        val w = widthOf(page)
        // Far enough that the near edge doesn't balloon, close enough to read as a hinge.
        page.cameraDistance = w * 4.8f
    }

    /** Rasterize the hardware layer now, so the turn only has to spin a texture. */
    private fun warm(view: View) {
        if (view.layerType != View.LAYER_TYPE_HARDWARE) {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        if (view.isAttachedToWindow && view.width > 0 && view.height > 0) {
            view.buildLayer()
        }
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
    private class ExpoEase(exponent: Float, private val out: Boolean) : Interpolator {
        private val scale = Math.exp(exponent.toDouble()) - 1.0
        private val n = exponent.toDouble()

        private fun easeIn(x: Float): Float {
            return ((Math.exp(n * x) - 1.0) / scale).toFloat()
        }

        override fun getInterpolation(t: Float): Float {
            return if (out) 1f - easeIn(1f - t) else easeIn(t)
        }
    }
}
