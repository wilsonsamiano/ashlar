package com.wilson.win10launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: TileAdapter
    private lateinit var grid: RecyclerView
    private lateinit var editBanner: TextView
    private lateinit var editBtn: Button
    private val cols = Tile.GRID
    private enum class Leave { NONE, SLIDE, TURN, FEATHER }
    private var leave = Leave.NONE
    private var featherX = 0f
    private var featherY = 0f
    private var featherToken = 0
    private var appliedDark = true
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
        prefs.ensureAppBaseline(AppList.load(packageManager).map { it.pkg })
        appliedDark = prefs.darkTheme
        setTheme(if (appliedDark) R.style.Theme_Win10_Dark else R.style.Theme_Win10_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = TileAdapter(this, prefs) { handle(it) }
        grid = findViewById(R.id.tileGrid)
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
        grid.addOnItemTouchListener(organizeDrag())
        grid.addOnItemTouchListener(swipeAllApps())

        editBtn.setOnClickListener {
            adapter.editMode = !adapter.editMode
            applyChrome()
        }
        findViewById<View>(R.id.allAppsBtn).setOnClickListener {
            slideTo(Intent(this, AllAppsActivity::class.java))
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            slideTo(Intent(this, SettingsActivity::class.java))
        }
        if (savedInstanceState?.getBoolean("edit") == true) adapter.editMode = true
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

    override fun onPause() {
        if (leave != Leave.TURN && leave != Leave.FEATHER && leave != Leave.SLIDE) {
            val page = page()
            page.animate().cancel()
            page.translationX = 0f
            page.rotationY = 0f
        }
        super.onPause()
    }

    private val switcherHold = Runnable {
        if (!::adapter.isInitialized || adapter.editMode || leave != Leave.NONE) return@Runnable
        val root = findViewById<ViewGroup>(R.id.root)
        if (!VistaSwitcher.isOpen(root)) VistaSwitcher.show(this, root, prefs)
    }
    private var switcherArm = false
    private var switcherReady = false
    private var switcherX = 0f
    private var switcherY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::adapter.isInitialized && !adapter.editMode && leave == Leave.NONE) {
            val root = findViewById<ViewGroup>(R.id.root)
            if (!VistaSwitcher.isOpen(root)) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        switcherArm = ev.y >= root.height - dp(96)
                        switcherReady = false
                        switcherX = ev.x
                        switcherY = ev.y
                        root.removeCallbacks(switcherHold)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (switcherArm && !switcherReady) {
                            val up = switcherY - ev.y
                            val dx = kotlin.math.abs(ev.x - switcherX)
                            if (up > dp(64) && up > dx * 1.3f) {
                                switcherReady = true
                                root.postDelayed(switcherHold, 220L)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        switcherArm = false
                        switcherReady = false
                        root.removeCallbacks(switcherHold)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::adapter.isInitialized) outState.putBoolean("edit", adapter.editMode)
    }

    override fun onResume() {
        super.onResume()
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
        findViewById<View>(R.id.root).setBackgroundColor(prefs.bgColor())
        val nextAccent = prefs.accentColor
        val next = prefs.loadTiles()
        val plates = prefs.iconShape
        val same = nextAccent == adapter.accent && plates == adapter.iconShape && next == adapter.tiles()
        adapter.accent = nextAccent
        adapter.iconShape = plates
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
        editBanner.setTextColor(prefs.fgColor())
        editBanner.setBackgroundColor(if (prefs.darkTheme) 0xCC000000.toInt() else 0xEEFFFFFF.toInt())
        editBtn.text = if (editing) "DONE" else "EDIT"
        val top = if (editing) dp(44) else dp(8)
        grid.setPadding(dp(8), top, dp(8), dp(72))
    }

    private fun dp(n: Int): Int =
        (n * resources.displayMetrics.density).toInt()

    /** Flat slide, tracked with the finger. No rotation, so the tiles cannot ghost. */
    private fun swipeAllApps() = object : RecyclerView.SimpleOnItemTouchListener() {
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (adapter.editMode || leave != Leave.NONE) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (dx < -dp(12) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                        dragging = true
                        PageTurn.trackSlide(page(), dx.coerceAtMost(0f))
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            if (!dragging) return
            val w = rv.width.toFloat().coerceAtLeast(1f)
            val dx = (e.rawX - downRawX).coerceAtMost(0f)
            val page = page()
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> PageTurn.trackSlide(page, dx)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    val commit = e.actionMasked == MotionEvent.ACTION_UP &&
                        dx < -w * 0.18f && leave == Leave.NONE
                    page.animate().cancel()
                    if (!commit) {
                        page.animate().translationX(0f).setDuration(160L).start()
                        return
                    }
                    leave = Leave.SLIDE
                    startActivity(PageTurn.quiet(
                        Intent(this@MainActivity, AllAppsActivity::class.java).putExtra("tracked", true)
                    ))
                    PageTurn.noSystemAnim(this@MainActivity)
                }
            }
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
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Small tile")
        pm.menu.add("Medium tile")
        pm.menu.add("Wide tile")
        pm.menu.add("Unpin from Start")
        val remove = Uninstall.label(this, t.pkg, t.cls)
        if (remove != null) pm.menu.add(remove)
        if (!adapter.editMode) pm.menu.add("Move tiles")
        pm.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Move tiles" -> {
                    adapter.editMode = true
                    applyChrome()
                    return@setOnMenuItemClickListener true
                }
                "Uninstall", "Uninstall updates", "App info" -> {
                    Uninstall.start(this, t.pkg, t.label)
                    return@setOnMenuItemClickListener true
                }
            }
            val tiles = prefs.loadTiles().toMutableList()
            val idx = tiles.indexOfFirst { it.pkg == t.pkg && it.cls == t.cls }
            if (idx < 0) return@setOnMenuItemClickListener true
            when (item.title.toString()) {
                "Unpin from Start" -> tiles.removeAt(idx)
                "Small tile", "Medium tile", "Wide tile" -> {
                    val tile = tiles[idx]
                    val oldSize = tile.size
                    val oldCol = tile.col
                    tile.size = when (item.title.toString()) {
                        "Small tile" -> Tile.SMALL
                        "Medium tile" -> Tile.MEDIUM
                        else -> Tile.WIDE
                    }
                    tile.col = TileGrid.snapCol(tile.size, tile.col, cols)
                    if (!TileGrid.fits(tiles, tile, cols)) {
                        tile.size = oldSize
                        tile.col = oldCol
                        Toast.makeText(this, "No room there", Toast.LENGTH_SHORT).show()
                        return@setOnMenuItemClickListener true
                    }
                }
            }
            prefs.saveTiles(tiles)
            refresh()
            true
        }
        pm.show()
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

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (!adapter.editMode || leave != Leave.NONE) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = rv.findChildViewUnder(e.x, e.y)
                    val pos = if (hit == null) RecyclerView.NO_POSITION else rv.getChildAdapterPosition(hit)
                    if (hit == null || pos == RecyclerView.NO_POSITION || adapter.isAdd(pos)) {
                        child = null
                        tile = null
                        return false
                    }
                    child = hit
                    tile = adapter.tileAt(pos)
                    startX = e.x
                    startY = e.y
                    lastX = e.x
                    lastY = e.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
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
                    if (!moved) {
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
