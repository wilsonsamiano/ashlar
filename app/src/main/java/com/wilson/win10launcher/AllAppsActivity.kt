package com.wilson.win10launcher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Alphabetical app list with letter headers. Tap a header letter to jump.
 * Tap an app to launch; long-press to pin or uninstall.
 * Launched with extra "pick"=true from the "+" tile: tapping pins and closes.
 */
class AllAppsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var appliedDark = true
    private lateinit var adapter: AppsAdapter
    private lateinit var rv: RecyclerView
    private var all: List<AppList.App> = emptyList()
    private var pickMode = false

    sealed class Row {
        data class Header(val letter: Char, val position: Int) : Row()
        data class Item(val app: AppList.App) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        appliedDark = prefs.darkTheme
        setTheme(if (appliedDark) R.style.Theme_Win10_Dark else R.style.Theme_Win10_Light)
        super.onCreate(savedInstanceState)
        PageTurn.noSystemAnim(this)
        setContentView(R.layout.activity_all_apps)
        val page = findViewById<View>(android.R.id.content)
        page.setBackgroundColor(prefs.bgColor())
        if (savedInstanceState == null) {
            PageTurn.enterSlide(page, resources.displayMetrics.widthPixels.toFloat())
        }
        pickMode = intent.getBooleanExtra("pick", false)
        val titleBar = findViewById<TextView>(R.id.titleBar)
        titleBar.text = if (pickMode) "Tap an app to pin it" else "All apps"
        titleBar.setTextColor(prefs.fgColor())

        all = (AppList.load(packageManager) + AppList.App(Tile.CLOCK_PKG, Tile.CLOCK_CLS, "Clock"))
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
        adapter = AppsAdapter(
            onHeaderClick = { showLetterPicker() },
            onAppClick = { app -> pinApp(app) },
            onAppLongClick = { app, anchor -> showAppMenu(app, anchor) }
        )
        // In pick mode a plain tap pins; otherwise tap launches.
        adapter.tapLaunches = !pickMode

        rv = findViewById(R.id.appsList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        adapter.submit(buildSections(all))

        findViewById<View>(R.id.root).setBackgroundColor(prefs.bgColor())
        val search = findViewById<EditText>(R.id.searchBox)
        search.setTextColor(prefs.fgColor())
        search.setHintTextColor(if (prefs.darkTheme) 0xFF888888.toInt() else 0xFF666666.toInt())
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                val filtered = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
                adapter.submit(buildSections(filtered))
            }
        })
    }

    private fun buildSections(apps: List<AppList.App>): List<Row> {
        val rows = mutableListOf<Row>()
        var lastLetter = '\u0000'
        for (app in apps) {
            val letter = app.label.firstOrNull()?.uppercaseChar() ?: '#'
            val bucket = if (letter.isLetter()) letter else '#'
            if (bucket != lastLetter) {
                rows.add(Row.Header(bucket, rows.size))
                lastLetter = bucket
            }
            rows.add(Row.Item(app))
        }
        return rows
    }

    private fun showLetterPicker() {
        val letters = all.map {
            val c = it.label.firstOrNull()?.uppercaseChar() ?: '#'
            if (c.isLetter()) c else '#'
        }.distinct().sorted()
        val grid = GridView(this).apply {
            numColumns = 5
            adapter = ArrayAdapter(
                this@AllAppsActivity,
                android.R.layout.simple_list_item_1,
                letters.map { it.toString() }
            )
        }
        val dialog = AlertDialog.Builder(this).setView(grid).create()
        grid.setOnItemClickListener { _, _, pos, _ ->
            val want = letters[pos]
            val rows = adapter.current()
            val idx = rows.indexOfFirst { it is Row.Header && it.letter == want }
            if (idx >= 0) (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
            dialog.dismiss()
        }
        dialog.show()
    }

    inner class AppsAdapter(
        private val onHeaderClick: () -> Unit,
        private val onAppClick: (AppList.App) -> Unit,
        private val onAppLongClick: (AppList.App, View) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var tapLaunches = true
        private var rows: List<Row> = emptyList()

        fun submit(r: List<Row>) {
            rows = r
            notifyDataSetChanged()
        }

        fun current() = rows

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Item -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inf.inflate(R.layout.item_header, parent, false))
            } else {
                AppVH(inf.inflate(R.layout.item_app, parent, false))
            }
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> {
                    val vh = h as HeaderVH
                    vh.letter.text = r.letter.toString()
                    vh.box.setBackgroundColor(prefs.accentColor)
                    vh.itemView.setOnClickListener { onHeaderClick() }
                }
                is Row.Item -> {
                    val vh = h as AppVH
                    vh.label.text = r.app.label
                    vh.label.setTextColor(prefs.fgColor())
                    if (Tile.isClock(r.app.pkg, r.app.cls)) {
                        vh.icon.setImageResource(android.R.drawable.ic_lock_idle_alarm)
                        vh.icon.imageTintList = ColorStateList.valueOf(prefs.fgColor())
                    } else {
                        vh.icon.imageTintList = null
                        try {
                            vh.icon.setImageDrawable(
                                packageManager.getActivityIcon(
                                    android.content.ComponentName(r.app.pkg, r.app.cls)
                                )
                            )
                        } catch (e: Exception) {
                            vh.icon.setImageDrawable(null)
                        }
                    }
                    vh.itemView.setOnClickListener {
                        if (tapLaunches) launchApp(r.app, vh.itemView) else onAppClick(r.app)
                    }
                    vh.itemView.setOnLongClickListener { onAppLongClick(r.app, vh.itemView); true }
                }
            }
        }

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val box: View = v.findViewById(R.id.headerBox)
            val letter: TextView = v.findViewById(R.id.headerLetter)
        }

        inner class AppVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val label: TextView = v.findViewById(R.id.appLabel)
        }
    }

    private var turnedAway = false
    private var reloadOnResume = false
    private var featherX = 0f
    private var featherY = 0f

    private fun pinApp(app: AppList.App) {
        prefs.addTile(Tile(app.pkg, app.cls, app.label, Tile.MEDIUM))
        Toast.makeText(this, "Pinned ${app.label}", Toast.LENGTH_SHORT).show()
        if (pickMode) finish()
    }

    /** Menu label. System apps that cannot be removed open App info instead. */
    private fun uninstallLabel(app: AppList.App): String? {
        if (Tile.isClock(app.pkg, app.cls)) return null
        return try {
            val info = packageManager.getApplicationInfo(app.pkg, 0)
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            when {
                system && updated -> "Uninstall updates"
                system -> "App info"
                else -> "Uninstall"
            }
        } catch (e: Exception) {
            "Uninstall"
        }
    }

    private fun showAppMenu(app: AppList.App, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Pin to Start")
        val uninstall = uninstallLabel(app)
        if (uninstall != null) menu.menu.add(uninstall)
        menu.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Pin to Start" -> pinApp(app)
                "Uninstall", "Uninstall updates" -> requestUninstall(app)
                "App info" -> openAppInfo(app)
            }
            true
        }
        menu.show()
    }

    private fun requestUninstall(app: AppList.App) {
        val uri = Uri.fromParts("package", app.pkg, null)
        val delete = Intent(Intent.ACTION_DELETE, uri).addCategory(Intent.CATEGORY_DEFAULT)
        val legacy = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        val intent = when {
            delete.resolveActivity(packageManager) != null -> delete
            legacy.resolveActivity(packageManager) != null -> legacy
            else -> null
        }
        if (intent != null) {
            try {
                reloadOnResume = true
                startActivity(intent)
                return
            } catch (e: Exception) {
                reloadOnResume = false
            }
        }
        openAppInfo(app)
    }

    private fun openAppInfo(app: AppList.App) {
        val uri = Uri.fromParts("package", app.pkg, null)
        try {
            reloadOnResume = true
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (e: Exception) {
            reloadOnResume = false
            Toast.makeText(this, "Can't uninstall ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reloadApps() {
        all = (AppList.load(packageManager) + AppList.App(Tile.CLOCK_PKG, Tile.CLOCK_CLS, "Clock"))
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
        val q = findViewById<EditText>(R.id.searchBox).text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
        adapter.submit(buildSections(filtered))
    }

    private fun launchApp(app: AppList.App, view: View) {
        if (turnedAway) return
        val i = if (Tile.isClock(app.pkg, app.cls)) {
            Tile.clockIntent(this)
        } else {
            Intent(Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(app.pkg, app.cls)
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
        }
        turnedAway = true
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        featherX = loc[0] + view.width / 2f
        featherY = loc[1] + view.height / 2f
        PageTurn.featherOut(rv, view) {
            if (!turnedAway) return@featherOut
            try {
                startActivity(PageTurn.quiet(i), PageTurn.appOptions(this))
                PageTurn.noSystemAnim(this)
            } catch (e: Exception) {
                turnedAway = false
                PageTurn.featherReset(rv)
                Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.themeMode == "sun" && prefs.darkTheme != appliedDark) {
            recreate()
            return
        }
        if (reloadOnResume) {
            reloadOnResume = false
            reloadApps()
        }
        if (!turnedAway) return
        turnedAway = false
        PageTurn.featherReturn(rv, featherX, featherY)
    }

    private var closing = false

    override fun finish() {
        if (closing) return
        closing = true
        PageTurn.slideAwayRight(findViewById(android.R.id.content)) {
            super.finish()
            PageTurn.noSystemAnim(this)
        }
    }
}
