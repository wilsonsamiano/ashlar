package com.wilson.win10launcher

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: TileAdapter
    private lateinit var grid: RecyclerView
    private lateinit var editBanner: TextView
    private lateinit var editBtn: Button
    private val cols = Tile.GRID
    private enum class Leave { NONE, SLIDE, TURN }
    private var leave = Leave.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        setTheme(if (prefs.darkTheme) R.style.Theme_Win10_Dark else R.style.Theme_Win10_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = TileAdapter(this, prefs) { handle(it) }
        grid = findViewById(R.id.tileGrid)
        editBanner = findViewById(R.id.editBanner)
        editBtn = findViewById(R.id.editBtn)
        val lm = GridLayoutManager(this, cols)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = adapter.spanFor(position, cols)
        }
        grid.layoutManager = lm
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
        refresh()
        val page = findViewById<View>(R.id.root)
        when (leave) {
            Leave.SLIDE -> PageTurn.slideBack(page)
            Leave.TURN -> PageTurn.turnBack(page)
            Leave.NONE -> {}
        }
        leave = Leave.NONE
    }

    private fun slideTo(intent: Intent) {
        if (leave != Leave.NONE) return
        leave = Leave.SLIDE
        PageTurn.slideOff(findViewById(R.id.root)) {
            startActivity(intent)
            PageTurn.noSystemAnim(this)
        }
    }

    private fun turnTo(intent: Intent, externalApp: Boolean = false) {
        if (leave != Leave.NONE) return
        leave = Leave.TURN
        val page = findViewById<View>(R.id.root)
        PageTurn.turnOut(page) {
            try {
                if (externalApp) startActivity(intent, PageTurn.appOptions(this))
                else {
                    startActivity(intent)
                    PageTurn.noSystemAnim(this)
                }
            } catch (e: Exception) {
                leave = Leave.NONE
                PageTurn.turnBack(page)
            }
        }
    }

    private fun refresh() {
        findViewById<View>(R.id.root).setBackgroundColor(prefs.bgColor())
        adapter.accent = prefs.accentColor
        adapter.submit(prefs.loadTiles())
        (grid.layoutManager as GridLayoutManager).spanSizeLookup.invalidateSpanIndexCache()
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

    /** Finger-follow slide into All apps. Rotation is not used here. */
    private fun swipeAllApps() = object : RecyclerView.SimpleOnItemTouchListener() {
        private var downX = 0f
        private var downY = 0f

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (adapter.editMode || leave != Leave.NONE) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (dx < -dp(28) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        val page = findViewById<View>(R.id.root)
                        page.animate().cancel()
                        page.rotationY = 0f
                        page.rotationX = 0f
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            val page = findViewById<View>(R.id.root)
            val w = page.width.coerceAtLeast(1).toFloat()
            val dx = (e.x - downX).coerceAtMost(0f)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    page.rotationY = 0f
                    page.rotationX = 0f
                    page.translationX = dx.coerceAtLeast(-w)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP && page.translationX < -w * 0.22f) {
                        slideTo(Intent(this@MainActivity, AllAppsActivity::class.java))
                    } else {
                        page.animate().translationX(0f).rotationY(0f).setDuration(180).start()
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
        val i = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(t.pkg, t.cls)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        turnTo(i, externalApp = true)
    }

    private fun showOptions(t: Tile, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Half tile")
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
                "Half tile" -> tiles[idx].size = Tile.HALF
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
            (recyclerView.layoutManager as? GridLayoutManager)
                ?.spanSizeLookup?.invalidateSpanIndexCache()
            prefs.saveTiles(adapter.tiles())
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                viewHolder.itemView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(100).start()
                viewHolder.itemView.elevation = dp(12).toFloat()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            viewHolder.itemView.elevation = if (adapter.editMode) dp(2).toFloat() else 0f
            prefs.saveTiles(adapter.tiles())
        }
    }
}
