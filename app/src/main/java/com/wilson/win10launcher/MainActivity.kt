package com.wilson.win10launcher

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.graphics.drawable.ColorDrawable
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: TileAdapter
    private lateinit var grid: RecyclerView
    private lateinit var pager: View
    private lateinit var startPage: View
    private lateinit var allAppsPage: View
    private lateinit var appsPanel: AllAppsPanel
    private var drawerOpen = false
    private var drawerDragging = false
    private var drawerX = 0f
    private var drawerAnim: ValueAnimator? = null
    private lateinit var editBanner: TextView
    private lateinit var editBtn: Button
    private val cols = Tile.GRID
    private enum class Leave { NONE, SLIDE, TURN, FEATHER }
    private var leave = Leave.NONE
    private var featherX = 0f
    private var featherY = 0f
    private var featherToken = 0
    private var appliedDark = true
    /** Home pressed while Start was already the screen on top. Don't animate it. */
    private var optionsPopup: PopupWindow? = null
    private var holdEdit: Runnable? = null
    private var holdX = 0f
    private var holdY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var grabHeld: ((View, Tile) -> Unit)? = null
    /** Home pressed while Start was already the screen on top. Don't animate it. */
    private var quietHome = false
    private val timeTick = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A clock rewrite during a turn rebuilds the tile texture mid-spin.
            if (::adapter.isInitialized && ::grid.isInitialized
                && !adapter.featherHold && !PageTurn.animating()
            ) {
                adapter.paintClocks(grid)
            }
            if (prefs.themeMode == "sun" && prefs.darkTheme != appliedDark && !isFinishing) recreate()
            else if (::grid.isInitialized && !PageTurn.animating()) PageTurn.flatten(grid)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(timeTick, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(timeTick, filter)
        }
        if (prefs.themeMode == "sun") {
            Daylight.refresh(this) {
                if (!isFinishing && prefs.darkTheme != appliedDark) recreate()
            }
        }
    }

    override fun onStop() {
        unregisterReceiver(timeTick)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        prefs.ensureAppBaseline(AppList.get(packageManager).map { it.pkg })
        appliedDark = prefs.darkTheme
        setTheme(if (appliedDark) R.style.Theme_Win10_Dark else R.style.Theme_Win10_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs.chrome(this)

        adapter = TileAdapter(this, prefs) { handle(it) }
        grid = findViewById(R.id.tileGrid)
        pager = findViewById(R.id.pager)
        startPage = findViewById(R.id.startPage)
        allAppsPage = findViewById(R.id.allAppsPage)
        allAppsPage.isClickable = true
        allAppsPage.visibility = View.INVISIBLE
        appsPanel = AllAppsPanel(this, allAppsPage, prefs, onLaunch = { launchFromDrawer(it) }) {
            refresh()
        }
        appsPanel.bind()
        pager.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            placeDrawer()
        }
        editBanner = findViewById(R.id.editBanner)
        editBtn = findViewById(R.id.editBtn)
        grid.layoutManager = TileLayoutManager(
            cols,
            spanOf = { adapter.spanFor(it, cols) },
            rowsOf = { adapter.rowsFor(it) },
            originOf = { adapter.originFor(it) },
            appendAtEnd = { adapter.isAdd(it) }
        )
        grid.itemAnimator = null
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                val lm = rv.layoutManager as? TileLayoutManager ?: return
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    lm.allowBungee = true
                    lm.cancelSpring()
                } else {
                    lm.allowBungee = false
                    if (lm.hasBungee()) {
                        rv.stopScroll()
                        lm.settleBungee()
                    }
                }
            }
        })
        grid.adapter = adapter
        grid.setItemViewCacheSize(24)
        grid.setHasFixedSize(true)
        grid.setDrawingCacheEnabled(true)
        grid.addOnItemTouchListener(organizeDrag())
        grid.addOnItemTouchListener(swipeAllApps(true))
        appsPanel.list.addOnItemTouchListener(swipeAllApps(false))

        editBtn.setOnClickListener {
            adapter.editMode = !adapter.editMode
            applyChrome()
        }
        findViewById<View>(R.id.allAppsBtn).setOnClickListener { settleDrawer(true) }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            slideTo(Intent(this, SettingsActivity::class.java))
        }
        if (savedInstanceState?.getBoolean("edit") == true) adapter.editMode = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerOpen || pager.translationX != 0f) settleDrawer(false)
            }
        })
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::grid.isInitialized) return
        // Swipe-home should show Start immediately. A return wave leaves the
        // bottom tiles folded until the animation catches up.
        val home = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        if (home) {
            snapDrawerClosed()
            PageTurn.noSystemAnim(this)
            // Still on this screen. Don't rebuild or fade it.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
            leave = Leave.NONE
            featherToken++
            adapter.featherHold = false
            PageTurn.featherReset(grid)
        }
        val page = page()
        page.animate().cancel()
        page.translationX = 0f
        page.rotationX = 0f
        page.rotationY = 0f
        if (leave == Leave.SLIDE) leave = Leave.NONE
        (grid.layoutManager as? TileLayoutManager)?.let {
            it.allowBungee = false
            it.cancelSpring()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (leave == Leave.NONE) snapDrawerClosed()
    }

    override fun onPause() {
        if (leave == Leave.NONE) {
            super.onPause()
            return
        }
        quietHome = false
        val page = page()
        page.animate().cancel()
        page.translationX = 0f
        page.rotationY = 0f
        page.alpha = 1f
        VistaGlass.clearCompositor(this)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::adapter.isInitialized) outState.putBoolean("edit", adapter.editMode)
    }

    override fun onResume() {
        super.onResume()
        if (quietHome && leave == Leave.NONE) {
            quietHome = false
            val page = page()
            page.animate().cancel()
            page.alpha = 1f
            page.translationX = 0f
            page.rotationY = 0f
            return
        }
        quietHome = false
        val returning = leave
        if (returning == Leave.FEATHER) {
            featherToken++
            adapter.featherHold = true
        }
        refresh()
        val page = page()
        when (returning) {
            Leave.SLIDE -> {
                val back = page()
                back.animate().cancel()
                back.translationX = 0f
                back.rotationY = 0f
            }
            Leave.TURN -> PageTurn.returnTurn(page)
            Leave.FEATHER -> {
                PageTurn.featherReturn(grid, featherX, featherY)
                grid.postDelayed({
                    adapter.featherHold = false
                    PageTurn.flatten(grid)
                }, 1100L)
            }
            Leave.NONE -> if (!PageTurn.animating()) PageTurn.flatten(grid)
        }
        leave = Leave.NONE
    }

    private fun page(): View = findViewById(android.R.id.content)

    private fun slideTo(intent: Intent) {
        if (leave != Leave.NONE) return
        leave = Leave.SLIDE
        startActivity(PageTurn.quiet(intent))
        PageTurn.noSystemAnim(this)
    }

    private fun turnTo(intent: Intent, externalApp: Boolean = false) {
        if (leave != Leave.NONE) return
        leave = Leave.TURN
        PageTurn.leaveTurn(page()) {
            try {
                val going = PageTurn.quiet(intent)
                if (externalApp) startActivity(going, PageTurn.appOptions(this))
                else startActivity(going)
                PageTurn.noSystemAnim(this)
            } catch (e: Exception) {
                leave = Leave.NONE
                PageTurn.returnTurn(page())
            }
        }
    }

    private fun refresh() {
        findViewById<View>(R.id.root).let { root ->
            VistaGlass.clearCompositor(this)
            if (prefs.glassHome) {
                val paint = fun(bg: android.graphics.drawable.Drawable) {
                    if (isDestroyed) return
                    root.background = bg
                    window.setBackgroundDrawable(bg.constantState?.newDrawable(resources) ?: bg)
                    window.statusBarColor = 0xFF071525.toInt()
                    window.navigationBarColor = 0xFF071525.toInt()
                    dockInvalidate()
                }
                if (root.background == null) paint(VistaGlass.fallback())
                VistaGlass.frost(this, paint)
            } else {
                root.setBackgroundColor(prefs.bgColor())
                window.statusBarColor = prefs.bgColor()
                window.navigationBarColor = prefs.bgColor()
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(prefs.bgColor()))
            }
        }
        val dock = findViewById<View>(R.id.dock)
        val ink = if (prefs.glassHome) Color.WHITE else prefs.fgColor()
        editBtn.setTextColor(ink)
        findViewById<Button>(R.id.allAppsBtn).setTextColor(ink)
        findViewById<Button>(R.id.settingsBtn).setTextColor(ink)
        when (prefs.look) {
            "vista" -> {
                dock.background = VistaGlass.tile(prefs.accentColor, dp(8).toFloat(), dp(1))
                VistaGlass.unclip(dock)
            }
            "ice" -> {
                dock.background = VistaGlass.ice(prefs.accentColor, prefs.darkTheme, true).also {
                    VistaGlass.bind(it, dock)
                }
                VistaGlass.clip(dock, true)
            }
            else -> {
                dock.background = null
                VistaGlass.unclip(dock)
            }
        }
        val nextAccent = prefs.accentColor
        val next = prefs.loadTiles()
        val plates = prefs.iconShape
        val vibe = prefs.vistaVibe
        val iced = prefs.ice
        val same = nextAccent == adapter.accent && plates == adapter.iconShape &&
            vibe == adapter.vista && iced == adapter.ice && next == adapter.tiles()
        adapter.accent = nextAccent
        adapter.iconShape = plates
        adapter.vista = vibe
        adapter.ice = iced
        if (!same) {
            adapter.submit(next)
            grid.requestLayout()
        } else if (!adapter.featherHold && !PageTurn.animating()) {
            adapter.paintClocks(grid)
        }
        applyChrome()
    }

    private fun applyChrome() {
        val editing = adapter.editMode
        editBanner.visibility = if (editing) View.VISIBLE else View.GONE
        if (prefs.glassHome) {
            editBanner.setTextColor(Color.WHITE)
            editBanner.background = VistaGlass.sheet(true)
        } else {
            editBanner.setTextColor(prefs.fgColor())
            editBanner.setBackgroundColor(if (prefs.darkTheme) 0xCC000000.toInt() else 0xEEFFFFFF.toInt())
        }
        editBtn.text = if (editing) "DONE" else "EDIT"
        val top = if (editing) dp(44) else dp(8)
        grid.setPadding(dp(8), top, dp(8), dp(72))
    }

    private fun dockInvalidate() {
        val dock = findViewById<View>(R.id.dock)
        dock.invalidate()
        for (i in 0 until grid.childCount) grid.getChildAt(i).invalidate()
    }

    private fun dp(n: Int): Int =
        (n * resources.displayMetrics.density).toInt()

    /** Both pages slide. The window itself stays put, so the list can be touched. */
    private fun swipeAllApps(fromStart: Boolean) = object : RecyclerView.SimpleOnItemTouchListener() {
        private var downRawX = 0f
        private var downRawY = 0f
        private var origin = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (adapter.editMode || leave != Leave.NONE) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    origin = drawerX
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f && kotlin.math.abs(dx) > dp(24)
                    val left = fromStart && dx < 0f && horizontal
                    val right = !fromStart && dx > 0f && horizontal
                    if (left || right) {
                        dragging = true
                        drawerDragging = true
                        drawerAnim?.cancel()
                        applyDrawer(origin + dx)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            if (!dragging) return
            val w = pageWidth()
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> applyDrawer(origin + (e.rawX - downRawX))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    drawerDragging = false
                    val shown = -drawerX
                    val open = if (e.actionMasked == MotionEvent.ACTION_CANCEL) drawerOpen
                    else if (origin > -w * 0.5f) shown > w * 0.18f
                    else shown > w * 0.82f
                    settleDrawer(open)
                }
            }
        }
    }

    private fun pageWidth(): Float = pager.width.toFloat().coerceAtLeast(1f)

    private fun placeDrawer() {
        if (pager.width <= 0) return
        pager.translationX = 0f
        if (!drawerDragging && drawerAnim?.isRunning != true) {
            applyDrawer(if (drawerOpen) -pager.width.toFloat() else 0f)
        }
        if (allAppsPage.visibility != View.VISIBLE) allAppsPage.visibility = View.VISIBLE
    }

    private fun applyDrawer(x: Float) {
        val w = pageWidth()
        drawerX = x.coerceIn(-w, 0f)
        startPage.translationX = drawerX
        allAppsPage.translationX = drawerX + w
    }

    private fun settleDrawer(open: Boolean) {
        val target = if (open) -pageWidth() else 0f
        drawerOpen = open
        if (!open) appsPanel.hideKeyboard()
        drawerAnim?.cancel()
        drawerAnim = ValueAnimator.ofFloat(drawerX, target).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyDrawer(it.animatedValue as Float) }
            start()
        }
    }

    private fun snapDrawerClosed() {
        if (!::startPage.isInitialized) return
        if (!drawerOpen && drawerX == 0f && drawerAnim?.isRunning != true) return
        drawerAnim?.cancel()
        drawerDragging = false
        applyDrawer(0f)
        drawerOpen = false
        if (::appsPanel.isInitialized) appsPanel.hideKeyboard()
    }

    private fun launchFromDrawer(app: AppList.App) {
        val i = if (Tile.isClock(app.pkg, app.cls)) {
            Tile.clockIntent(this)
        } else {
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(app.pkg, app.cls)
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
        }
        try {
            startActivity(PageTurn.quiet(i), PageTurn.appOptions(this))
            PageTurn.noSystemAnim(this)
            prefs.noteLaunch(app.pkg)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handle(a: TileAdapter.Action) {
        when (a) {
            is TileAdapter.Action.Launch -> launchTile(a.tile, a.view)
            is TileAdapter.Action.Add ->
                slideTo(Intent(this, AllAppsActivity::class.java).putExtra("pick", true))
            is TileAdapter.Action.Options -> showOptions(a.tile, a.anchor)
        }
    }

    private fun launchTile(t: Tile, view: View) {
        if (leave != Leave.NONE) return
        val i = if (t.isClock()) {
            Tile.clockIntent(this)
        } else {
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(t.pkg, t.cls)
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
        }
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        featherX = loc[0] + view.width / 2f
        featherY = loc[1] + view.height / 2f
        leave = Leave.FEATHER
        val token = ++featherToken
        PageTurn.featherOut(grid, view) {
            if (token != featherToken || leave != Leave.FEATHER) {
                PageTurn.flatten(grid)
                return@featherOut
            }
            try {
                startActivity(PageTurn.quiet(i), PageTurn.appOptions(this))
                PageTurn.noSystemAnim(this)
                prefs.noteLaunch(t.pkg)
            } catch (e: Exception) {
                leave = Leave.NONE
                PageTurn.featherReset(grid)
                Diagnostics.note(this, "open ${t.pkg} failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun showOptions(t: Tile, anchor: View) {
        optionsPopup?.dismiss()
        val labels = mutableListOf("Small tile", "Medium tile", "Wide tile", "Unpin from Start")
        val remove = Uninstall.label(this, t.pkg, t.cls)
        if (remove != null) labels.add(remove)
        if (!adapter.editMode) labels.add("Move tiles")
        val ink = if (prefs.darkTheme) Color.WHITE else Color.BLACK
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val popup = PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        for (label in labels) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(ink)
                setPadding(dp(20), dp(14), dp(28), dp(14))
                setOnClickListener {
                    popup.dismiss()
                    applyTileOption(t, label)
                }
            })
        }
        // Not focusable, so the finger that opened the menu is still tracked.
        popup.setBackgroundDrawable(ColorDrawable(if (prefs.darkTheme) 0xFF2B2B2B.toInt() else 0xFFF2F2F2.toInt()))
        popup.elevation = dp(8).toFloat()
        popup.isOutsideTouchable = true
        popup.setOnDismissListener { if (optionsPopup == popup) optionsPopup = null }
        optionsPopup = popup
        popup.showAsDropDown(anchor)
        if (!adapter.editMode) armEditHold(anchor, t)
    }

    private fun armEditHold(view: View, tile: Tile) {
        holdEdit?.let { grid.removeCallbacks(it) }
        holdX = fingerX
        holdY = fingerY
        val run = Runnable {
            holdEdit = null
            if (adapter.editMode || leave != Leave.NONE) return@Runnable
            optionsPopup?.dismiss()
            adapter.editMode = true
            applyChrome()
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            grabHeld?.invoke(view, tile)
        }
        holdEdit = run
        grid.postDelayed(run, 420L)
    }

    private fun cancelHoldEdit() {
        holdEdit?.let { grid.removeCallbacks(it) }
        holdEdit = null
    }

    private fun applyTileOption(t: Tile, title: String) {
        when (title) {
            "Move tiles" -> {
                adapter.editMode = true
                applyChrome()
                return
            }
            "Uninstall", "Uninstall updates", "App info" -> {
                Uninstall.start(this, t.pkg, t.label)
                return
            }
        }
        val tiles = prefs.loadTiles().toMutableList()
        val idx = tiles.indexOfFirst { it.pkg == t.pkg && it.cls == t.cls }
        if (idx < 0) return
        when (title) {
            "Unpin from Start" -> tiles.removeAt(idx)
            "Small tile", "Medium tile", "Wide tile" -> {
                val tile = tiles[idx]
                val oldSize = tile.size
                val oldCol = tile.col
                tile.size = when (title) {
                    "Small tile" -> Tile.SMALL
                    "Medium tile" -> Tile.MEDIUM
                    else -> Tile.WIDE
                }
                tile.col = TileGrid.snapCol(tile.size, tile.col, cols)
                if (!TileGrid.fits(tiles, tile, cols)) {
                    tile.size = oldSize
                    tile.col = oldCol
                    Toast.makeText(this, "No room there", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        prefs.saveTiles(tiles)
        refresh()
    }

    /** Drag a tile by its top-left. Empty cells are allowed. Other tiles stay put. */
    private fun organizeDrag() = object : RecyclerView.SimpleOnItemTouchListener() {
        private var child: View? = null
        private var tile: Tile? = null
        private var startX = 0f
        private var startY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false

        init {
            grabHeld = { view, held ->
                child = view
                tile = held
                startX = fingerX
                startY = fingerY
                lastX = fingerX
                lastY = fingerY
                moved = false
                view.cancelLongPress()
                view.animate().cancel()
                view.scaleX = 1.06f
                view.scaleY = 1.06f
                view.translationZ = dp(12).toFloat()
            }
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (leave != Leave.NONE) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerX = e.x
                    fingerY = e.y
                    cancelHoldEdit()
                    val hit = rv.findChildViewUnder(e.x, e.y)
                    val pos = if (hit == null) RecyclerView.NO_POSITION else rv.getChildAdapterPosition(hit)
                    if (hit == null || pos == RecyclerView.NO_POSITION || adapter.isAdd(pos)) {
                        child = null
                        tile = null
                        return false
                    }
                    if (!adapter.editMode) return false
                    child = hit
                    tile = adapter.tileAt(pos)
                    startX = e.x
                    startY = e.y
                    lastX = e.x
                    lastY = e.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    fingerX = e.x
                    fingerY = e.y
                    if (!adapter.editMode) {
                        if (holdEdit != null) {
                            val dx = e.x - holdX
                            val dy = e.y - holdY
                            if (dx * dx + dy * dy > dp(16) * dp(16)) cancelHoldEdit()
                        }
                        return false
                    }
                    val view = child ?: return false
                    val dx = e.x - startX
                    val dy = e.y - startY
                    if (!moved && dx * dx + dy * dy < dp(8) * dp(8)) return false
                    if (!moved) {
                        moved = true
                        organizeTile = tile
                        rememberHomes()
                        view.cancelLongPress()
                        view.animate().cancel()
                        view.scaleX = 1.05f
                        view.scaleY = 1.05f
                        view.translationZ = dp(12).toFloat()
                        (rv.layoutManager as? TileLayoutManager)?.held = view
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelHoldEdit()
                    if (!moved) {
                        child?.let {
                            it.animate().cancel()
                            it.scaleX = 1f
                            it.scaleY = 1f
                            it.translationZ = 0f
                        }
                        child = null
                        tile = null
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            val view = child ?: return
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    view.translationX += e.x - lastX
                    view.translationY += e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    val edge = dp(36)
                    val step = dp(14)
                    val topBefore = view.top
                    if (e.y < edge) rv.scrollBy(0, -step)
                    else if (e.y > rv.height - edge) rv.scrollBy(0, step)
                    val shifted = view.top - topBefore
                    if (shifted != 0) view.translationY -= shifted
                    previewHover(rv, view)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val left = view.left + view.translationX
                    val top = view.top + view.translationY
                    val dropped = tile
                    val place = e.actionMasked == MotionEvent.ACTION_UP
                    view.translationX = 0f
                    view.translationY = 0f
                    view.translationZ = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                    (rv.layoutManager as? TileLayoutManager)?.held = null
                    child = null
                    tile = null
                    organizeTile = null
                    moved = false
                    hoverCol = Int.MIN_VALUE
                    hoverRow = Int.MIN_VALUE
                    if (place && dropped != null) dropTile(rv, dropped, left, top)
                    else {
                        restoreHomes()
                        rv.requestLayout()
                    }
                }
            }
        }
    }

    private var organizeTile: Tile? = null
    private var hoverCol = Int.MIN_VALUE
    private var hoverRow = Int.MIN_VALUE
    private var homes: List<Pair<Tile, Pair<Int, Int>>>? = null

    private fun rememberHomes() {
        homes = adapter.tiles().map { it to (it.col to it.row) }
        hoverCol = Int.MIN_VALUE
        hoverRow = Int.MIN_VALUE
    }

    private fun restoreHomes() {
        homes?.forEach { (t, spot) ->
            t.col = spot.first
            t.row = spot.second
        }
    }

    private fun previewHover(rv: RecyclerView, view: View) {
        val dragging = organizeTile ?: return
        val lm = rv.layoutManager as? TileLayoutManager ?: return
        val (col, row) = lm.cellAt(view.left + view.translationX, view.top + view.translationY)
        val c = TileGrid.snapCol(dragging.size, col, cols)
        val r = TileGrid.snapRow(row)
        if (c == hoverCol && r == hoverRow) return
        hoverCol = c
        hoverRow = r
        restoreHomes()
        val tiles = adapter.tiles()
        val target = tiles.find { it.pkg == dragging.pkg && it.cls == dragging.cls } ?: return
        if (c != target.col || r != target.row) {
            TileGrid.shiftForHover(tiles, target, c, r, cols)
        }
        rv.requestLayout()
    }

    private fun dropTile(rv: RecyclerView, tile: Tile, left: Float, top: Float) {
        val lm = rv.layoutManager as? TileLayoutManager ?: return
        val (col, row) = lm.cellAt(left, top)
        val tiles = adapter.tiles()
        val target = tiles.find { it.pkg == tile.pkg && it.cls == tile.cls } ?: return
        restoreHomes()
        val c = TileGrid.snapCol(target.size, col, cols)
        val r = TileGrid.snapRow(row)
        if (c == target.col && r == target.row) {
            rv.requestLayout()
            return
        }
        if (!TileGrid.shiftForHover(tiles, target, c, r, cols)) {
            Toast.makeText(this, "No room there", Toast.LENGTH_SHORT).show()
            rv.requestLayout()
            return
        }
        target.col = c
        target.row = r
        if (!TileGrid.fits(tiles, target, cols)) {
            restoreHomes()
            Toast.makeText(this, "No room there", Toast.LENGTH_SHORT).show()
            rv.requestLayout()
            return
        }
        prefs.saveTiles(tiles)
        adapter.submit(tiles)
    }
}
