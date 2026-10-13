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
import androidx.recyclerview.widget.ItemTouchHelper
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
            if (::adapter.isInitialized && ::grid.isInitialized) adapter.paintClocks(grid)
            if (prefs.themeMode == "sun" && prefs.darkTheme != appliedDark && !isFinishing) recreate()
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
            appendAtEnd = { adapter.isAdd(it) }
        )
        grid.itemAnimator = null
        grid.adapter = adapter
        ItemTouchHelper(dragCallback()).attachToRecyclerView(grid)
        grid.addOnItemTouchListener(swipeAllApps())

        editBtn.setOnClickListener {
            adapter.editMode = !adapter.editMode
            applyChrome()
        }
        findViewById<View>(R.id.allAppsBtn).setOnClickListener {
            slideTo(Intent(this, AllAppsActivity::class.java))
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            turnTo(Intent(this, SettingsActivity::class.java))
        }
        if (savedInstanceState?.getBoolean("edit") == true) adapter.editMode = true
        refresh()
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
                val w = if (page.width > 0) page.width.toFloat()
                else resources.displayMetrics.widthPixels.toFloat()
                PageTurn.returnSlide(page, -w)
            }
            Leave.TURN -> PageTurn.returnTurn(page)
            Leave.FEATHER -> {
                PageTurn.featherReturn(grid, featherX, featherY)
                grid.postDelayed({ adapter.featherHold = false }, 720L)
            }
            Leave.NONE -> Unit
        }
        leave = Leave.NONE
    }

    private fun page(): View = findViewById(android.R.id.content)

    private fun slideTo(intent: Intent) {
        if (leave != Leave.NONE) return
        leave = Leave.SLIDE
        val page = page()
        val w = if (page.width > 0) page.width.toFloat()
        else resources.displayMetrics.widthPixels.toFloat()
        PageTurn.leaveSlide(page, -w) {
            startActivity(PageTurn.quiet(intent))
            PageTurn.noSystemAnim(this)
        }
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
        adapter.accent = prefs.accentColor
        adapter.submit(prefs.loadTiles())
        grid.requestLayout()
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
                    if (dx < -dp(28) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            if (!dragging) return
            val page = page()
            val w = if (page.width > 0) page.width.toFloat()
            else resources.displayMetrics.widthPixels.toFloat()
            val dx = (e.rawX - downRawX).coerceAtMost(0f)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> PageTurn.trackSlide(page, dx.coerceAtLeast(-w))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (e.actionMasked == MotionEvent.ACTION_UP && page.translationX < -w * 0.22f) {
                        if (leave != Leave.NONE) return
                        leave = Leave.SLIDE
                        PageTurn.leaveSlide(page, -w) {
                            startActivity(PageTurn.quiet(Intent(this@MainActivity, AllAppsActivity::class.java)))
                            PageTurn.noSystemAnim(this@MainActivity)
                        }
                    } else {
                        PageTurn.leaveSlide(page, 0f) {}
                    }
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
            if (token != featherToken || leave != Leave.FEATHER) return@featherOut
            try {
                startActivity(PageTurn.quiet(i), PageTurn.appOptions(this))
                PageTurn.noSystemAnim(this)
            } catch (e: Exception) {
                leave = Leave.NONE
                PageTurn.featherReset(grid)
            }
        }
    }

    private fun showOptions(t: Tile, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Small tile")
        pm.menu.add("Medium tile")
        pm.menu.add("Wide tile")
        pm.menu.add("Unpin from Start")
        if (!adapter.editMode) pm.menu.add("Move tiles")
        pm.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Move tiles" -> {
                    adapter.editMode = true
                    applyChrome()
                    return@setOnMenuItemClickListener true
                }
            }
            val tiles = prefs.loadTiles().toMutableList()
            val idx = tiles.indexOfFirst { it.pkg == t.pkg && it.cls == t.cls }
            if (idx < 0) return@setOnMenuItemClickListener true
            when (item.title.toString()) {
                "Unpin from Start" -> tiles.removeAt(idx)
                "Small tile" -> tiles[idx].size = Tile.SMALL
                "Medium tile" -> tiles[idx].size = Tile.MEDIUM
                "Wide tile" -> tiles[idx].size = Tile.WIDE
            }
            prefs.saveTiles(tiles)
            refresh()
            true
        }
        pm.show()
    }

    private fun dragCallback() = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
        0
    ) {
        override fun isLongPressDragEnabled() = adapter.editMode

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val pos = viewHolder.bindingAdapterPosition
            if (!adapter.editMode || pos == RecyclerView.NO_POSITION || adapter.isAdd(pos)) {
                return 0
            }
            return makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
                0
            )
        }

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val pos = target.bindingAdapterPosition
            return pos != RecyclerView.NO_POSITION && !adapter.isAdd(pos)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (!adapter.move(from, to)) return false
            prefs.saveTiles(adapter.tiles())
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                val face = viewHolder.itemView.findViewById<View>(R.id.tileFace) ?: viewHolder.itemView
                face.animate().scaleX(1.05f).scaleY(1.05f)
                    .setStartDelay(0L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(100).start()
                face.elevation = dp(12).toFloat()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val face = viewHolder.itemView.findViewById<View>(R.id.tileFace) ?: viewHolder.itemView
            face.animate().scaleX(1f).scaleY(1f)
                .setStartDelay(0L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setDuration(100).start()
            face.elevation = if (adapter.editMode) dp(2).toFloat() else 0f
            prefs.saveTiles(adapter.tiles())
        }
    }
}
