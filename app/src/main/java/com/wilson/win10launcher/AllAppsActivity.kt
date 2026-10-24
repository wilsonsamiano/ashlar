package com.wilson.win10launcher

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
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
import androidx.activity.OnBackPressedCallback
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
        data class Header(val title: String, val letter: Char?, val position: Int) : Row()
        data class Item(val app: AppList.App) : Row()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        appliedDark = prefs.darkTheme
        setTheme(if (appliedDark) R.style.Theme_Win10_Dark_Page else R.style.Theme_Win10_Light_Page)
        super.onCreate(savedInstanceState)
        PageTurn.noSystemAnim(this)
        setContentView(R.layout.activity_all_apps)
        prefs.chrome(this)
        VistaGlass.clearCompositor(this)
        val page = findViewById<View>(android.R.id.content)
        if (prefs.glassHome) {
            val glass = VistaGlass.sheet(prefs.darkTheme)
            page.background = glass
            window.setBackgroundDrawable(glass.constantState?.newDrawable(resources) ?: glass)
        } else {
            page.setBackgroundColor(prefs.bgColor())
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(prefs.bgColor()))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                slideClosed = true
                finish()
            }
        })
        if (savedInstanceState == null && !intent.getBooleanExtra("tracked", false)) {
            PageTurn.enterSlide(page, resources.displayMetrics.widthPixels.toFloat())
        }
        pickMode = intent.getBooleanExtra("pick", false)
        val titleBar = findViewById<TextView>(R.id.titleBar)
        titleBar.text = if (pickMode) "Tap an app to pin it" else "All apps"
        titleBar.setTextColor(prefs.fgColor())

        all = (AppList.get(packageManager) + AppList.App(Tile.CLOCK_PKG, Tile.CLOCK_CLS, "Clock"))
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
        prefs.ensureAppBaseline(all.map { it.pkg })
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
        rv.setItemViewCacheSize(30)
        rv.setHasFixedSize(true)
        (rv.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 8
        adapter.submit(buildSections(all, true))

        findViewById<View>(R.id.root).setBackgroundColor(if (prefs.glassHome) android.graphics.Color.TRANSPARENT else prefs.bgColor())
        val search = findViewById<EditText>(R.id.searchBox)
        search.setTextColor(prefs.fgColor())
        search.setHintTextColor(if (prefs.darkTheme) 0xFF888888.toInt() else 0xFF666666.toInt())
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                val filtered = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
                adapter.submit(buildSections(filtered, q.isEmpty()))
            }
        })
    }

    private fun buildSections(apps: List<AppList.App>, grouped: Boolean): List<Row> {
        val rows = mutableListOf<Row>()
        val body = if (!grouped) apps else {
            val fresh = apps.filter { prefs.isNewApp(it.pkg) }
                .sortedByDescending { installTime(it.pkg) }
            val used = apps.filter { !prefs.isNewApp(it.pkg) && prefs.launchCount(it.pkg) > 0 }
                .sortedWith(compareByDescending<AppList.App> { prefs.launchCount(it.pkg) }
                    .thenBy { it.label.lowercase() })
                .take(prefs.topAppCount)
            val skip = (fresh + used).map { it.pkg + "\n" + it.cls }.toSet()
            if (fresh.isNotEmpty()) {
                rows.add(Row.Header("New", null, rows.size))
                fresh.forEach { rows.add(Row.Item(it)) }
            }
            if (used.isNotEmpty()) {
                rows.add(Row.Header("Most used", null, rows.size))
                used.forEach { rows.add(Row.Item(it)) }
            }
            apps.filter { (it.pkg + "\n" + it.cls) !in skip }
        }
        var lastLetter = '\u0000'
        for (app in body) {
            val letter = app.label.firstOrNull()?.uppercaseChar() ?: '#'
            val bucket = if (letter.isLetter()) letter else '#'
            if (bucket != lastLetter) {
                rows.add(Row.Header(bucket.toString(), bucket, rows.size))
                lastLetter = bucket
            }
            rows.add(Row.Item(app))
        }
        return rows
    }

    private fun installTime(pkg: String): Long {
        if (pkg == packageName) return 0L
        installedAt[pkg]?.let { return it }
        return 0L
    }

    private val installedAt = HashMap<String, Long>()

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
                    vh.letter.text = r.title
                    vh.letter.setTextColor(android.graphics.Color.WHITE)
                    if (prefs.vistaVibe || prefs.ice) {
                        vh.letter.setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
                    } else {
                        vh.letter.setShadowLayer(0f, 0f, 0f, 0)
                    }
                    vh.box.background = when {
                        prefs.ice -> VistaGlass.ice(prefs.accentColor, prefs.darkTheme, true).also {
                            VistaGlass.bind(it, vh.box)
                        }
                        prefs.vistaVibe -> VistaGlass.tile(
                            prefs.accentColor,
                            8f * resources.displayMetrics.density,
                            resources.displayMetrics.density.toInt().coerceAtLeast(1)
                        )
                        else -> android.graphics.drawable.ColorDrawable(prefs.accentColor)
                    }
                    vh.itemView.setOnClickListener { if (r.letter != null) onHeaderClick() }
                }
                is Row.Item -> {
                    val vh = h as AppVH
                    vh.label.text = r.app.label
                    vh.label.setTextColor(prefs.fgColor())
                    if ((prefs.vistaVibe || prefs.ice) && prefs.darkTheme) {
                        vh.label.setShadowLayer(3f, 0f, 1f, 0x88000000.toInt())
                    } else {
                        vh.label.setShadowLayer(0f, 0f, 0f, 0)
                    }
                    if (Tile.isClock(r.app.pkg, r.app.cls)) {
                        vh.icon.setImageResource(android.R.drawable.ic_lock_idle_alarm)
                        vh.icon.imageTintList = ColorStateList.valueOf(prefs.fgColor())
                    } else {
                        vh.icon.imageTintList = null
                        try {
                            val iconPx = vh.icon.layoutParams.width.let { w ->
                                if (w > 1) w else (44 * resources.displayMetrics.density).toInt()
                            }
                            vh.icon.setImageDrawable(
                                IconMasks.apply(
                                    resources,
                                    packageManager.getActivityIcon(
                                        android.content.ComponentName(r.app.pkg, r.app.cls)
                                    ),
                                    prefs.iconShape,
                                    iconPx
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

    private fun showAppMenu(app: AppList.App, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Pin to Start")
        val uninstall = Uninstall.label(this, app.pkg, app.cls)
        if (uninstall != null) menu.menu.add(uninstall)
        menu.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Pin to Start" -> pinApp(app)
                "Uninstall", "Uninstall updates", "App info" -> {
                    if (Uninstall.start(this, app.pkg, app.label)) reloadOnResume = true
                }
            }
            true
        }
        menu.show()
    }

    private fun reloadApps() {
        all = (AppList.get(packageManager) + AppList.App(Tile.CLOCK_PKG, Tile.CLOCK_CLS, "Clock"))
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
        val q = findViewById<EditText>(R.id.searchBox).text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
        adapter.submit(buildSections(filtered, q.isEmpty()))
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
                prefs.noteLaunch(app.pkg)
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
        if (!turnedAway) return
        turnedAway = false
        PageTurn.featherReturn(rv, featherX, featherY)
        rv.postDelayed({ if (!isFinishing) reloadApps() }, 1000L)
    }

    private var closing = false
    private var slideClosed = false

    override fun finish() {
        if (closing) return
        closing = true
        if (!slideClosed) {
            super.finish()
            PageTurn.noSystemAnim(this)
            return
        }
        PageTurn.slideAwayRight(findViewById(android.R.id.content)) {
            super.finish()
            PageTurn.noSystemAnim(this)
        }
    }
}
