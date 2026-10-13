package com.wilson.win10launcher

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: TileAdapter
    private val cols = 6

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        setTheme(if (prefs.darkTheme) R.style.Theme_Win10_Dark else R.style.Theme_Win10_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = TileAdapter(this, prefs) { handle(it) }
        val rv = findViewById<RecyclerView>(R.id.tileGrid)
        val lm = GridLayoutManager(this, cols)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = adapter.spanFor(position, cols)
        }
        rv.layoutManager = lm
        rv.adapter = adapter

        findViewById<View>(R.id.allAppsBtn).setOnClickListener {
            startActivity(Intent(this, AllAppsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.hold)
        }
        findViewById<View>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.hold)
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        findViewById<View>(R.id.root).setBackgroundColor(prefs.bgColor())
        adapter.accent = prefs.accentColor
        adapter.submit(prefs.loadTiles())
    }

    private fun handle(a: TileAdapter.Action) {
        when (a) {
            is TileAdapter.Action.Launch -> launchTile(a.tile, a.view)
            is TileAdapter.Action.Add -> {
                startActivity(Intent(this, AllAppsActivity::class.java).putExtra("pick", true))
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.slide_in_right, R.anim.hold)
            }
            is TileAdapter.Action.Options -> showOptions(a.tile, a.anchor)
        }
    }

    private fun launchTile(t: Tile, view: View) {
        try {
            val i = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(t.pkg, t.cls)
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            // Tile zooms into the app, Windows 10 Mobile style.
            val opts = android.app.ActivityOptions.makeScaleUpAnimation(
                view, 0, 0, view.width, view.height
            )
            startActivity(i, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${t.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOptions(t: Tile, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add("Unpin from Start")
        pm.menu.add("Small tile")
        pm.menu.add("Medium tile")
        pm.menu.add("Wide tile")
        pm.setOnMenuItemClickListener { item ->
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
}
