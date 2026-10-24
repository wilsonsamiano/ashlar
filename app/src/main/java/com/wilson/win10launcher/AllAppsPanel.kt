package com.wilson.win10launcher

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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

/** All Apps page that lives next to Start, so a swipe can drag it with the finger. */
class AllAppsPanel(
    private val activity: AppCompatActivity,
    private val page: View,
    private val prefs: Prefs,
    private val onLaunch: (AppList.App, View) -> Unit,
    private val onPinned: () -> Unit
) {
    val list: RecyclerView = page.findViewById(R.id.appsList)
    private val search: EditText = page.findViewById(R.id.searchBox)
    private var all: List<AppList.App> = emptyList()
    private val adapter = AppsAdapter()
    private val icons = java.util.concurrent.ConcurrentHashMap<String, Drawable>()

    fun bind() {
        val title = page.findViewById<TextView>(R.id.titleBar)
        title.text = "All apps"
        title.setTextColor(prefs.fgColor())
        page.setBackgroundColor(if (prefs.glassHome) 0xF206121C.toInt() else prefs.bgColor())
        search.setTextColor(prefs.fgColor())
        search.setHintTextColor(if (prefs.darkTheme) 0xFF888888.toInt() else 0xFF666666.toInt())
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = show(s?.toString().orEmpty())
        })
        search.isFocusable = true
        search.isFocusableInTouchMode = true
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(24)
        (list.layoutManager as LinearLayoutManager).initialPrefetchItemCount = 8
        all = (AppList.get(activity.packageManager) + AppList.App(Tile.CLOCK_PKG, Tile.CLOCK_CLS, "Clock"))
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
        show("")
        preloadIcons()
    }

    private fun preloadIcons() {
        val apps = all
        val pm = activity.packageManager
        Thread {
            var n = 0
            for (app in apps) {
                if (Tile.isClock(app.pkg, app.cls)) continue
                val key = app.pkg + "\n" + app.cls
                if (icons.containsKey(key)) continue
                try {
                    icons[key] = pm.getActivityIcon(android.content.ComponentName(app.pkg, app.cls))
                } catch (_: Exception) {
                }
                n++
                if (n % 24 == 0) activity.runOnUiThread { if (!activity.isDestroyed) adapter.notifyDataSetChanged() }
            }
            activity.runOnUiThread { if (!activity.isDestroyed) adapter.notifyDataSetChanged() }
        }.start()
    }

    fun hideKeyboard() {
        search.clearFocus()
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(page.windowToken, 0)
    }

    private fun show(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) }
        adapter.submit(buildSections(filtered, q.isEmpty()))
    }

    private fun buildSections(apps: List<AppList.App>, grouped: Boolean): List<Row> {
        val rows = mutableListOf<Row>()
        val body = if (!grouped) apps else {
            val fresh = apps.filter { prefs.isNewApp(it.pkg) }
            val used = apps.filter { !prefs.isNewApp(it.pkg) && prefs.launchCount(it.pkg) > 0 }
                .sortedWith(compareByDescending<AppList.App> { prefs.launchCount(it.pkg) }
                    .thenBy { it.label.lowercase() })
                .take(prefs.topAppCount)
            val skip = (fresh + used).map { it.pkg + "\n" + it.cls }.toSet()
            if (fresh.isNotEmpty()) {
                rows.add(Row.Header("New", null))
                fresh.forEach { rows.add(Row.Item(it)) }
            }
            if (used.isNotEmpty()) {
                rows.add(Row.Header("Most used", null))
                used.forEach { rows.add(Row.Item(it)) }
            }
            apps.filter { (it.pkg + "\n" + it.cls) !in skip }
        }
        var last = '\u0000'
        for (app in body) {
            val letter = app.label.firstOrNull()?.uppercaseChar() ?: '#'
            val bucket = if (letter.isLetter()) letter else '#'
            if (bucket != last) {
                rows.add(Row.Header(bucket.toString(), bucket))
                last = bucket
            }
            rows.add(Row.Item(app))
        }
        return rows
    }

    private fun pin(app: AppList.App) {
        prefs.addTile(Tile(app.pkg, app.cls, app.label, Tile.MEDIUM))
        Toast.makeText(activity, "Pinned ${app.label}", Toast.LENGTH_SHORT).show()
        onPinned()
    }

    private fun menu(app: AppList.App, anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add("Pin to Start")
        val uninstall = Uninstall.label(activity, app.pkg, app.cls)
        if (uninstall != null) popup.menu.add(uninstall)
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Pin to Start" -> pin(app)
                "Uninstall", "Uninstall updates", "App info" ->
                    Uninstall.start(activity, app.pkg, app.label)
            }
            true
        }
        popup.setOnDismissListener { PressBeam.release(anchor) }
        popup.show()
    }

    private fun letters() {
        val letters = all.map {
            val c = it.label.firstOrNull()?.uppercaseChar() ?: '#'
            if (c.isLetter()) c else '#'
        }.distinct().sorted()
        val grid = GridView(activity).apply {
            numColumns = 5
            adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, letters.map { it.toString() })
        }
        val dialog = AlertDialog.Builder(activity).setView(grid).create()
        grid.setOnItemClickListener { _, _, pos, _ ->
            val want = letters[pos]
            val idx = adapter.current().indexOfFirst { it is Row.Header && it.letter == want }
            if (idx >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
            dialog.dismiss()
        }
        dialog.show()
    }

    private sealed class Row {
        data class Header(val title: String, val letter: Char?) : Row()
        data class Item(val app: AppList.App) : Row()
    }

    private inner class AppsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()
        fun submit(r: List<Row>) {
            rows = r
            notifyDataSetChanged()
        }
        fun current() = rows
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inf.inflate(R.layout.item_header, parent, false))
            } else {
                AppVH(inf.inflate(R.layout.item_app, parent, false)).also { vh ->
                    vh.itemView.isLongClickable = false
                    vh.itemView.setOnTouchListener { v, e ->
                        PressBeam.onTouch(v, vh.icon, e, prefs.accentColor) {
                            val app = vh.app ?: return@onTouch
                            menu(app, v)
                        }
                    }
                }
            }
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Header -> {
                    val vh = h as HeaderVH
                    vh.letter.text = r.title
                    vh.letter.setTextColor(android.graphics.Color.WHITE)
                    vh.box.setBackgroundColor(prefs.accentColor)
                    vh.itemView.setOnClickListener { if (r.letter != null) letters() }
                }
                is Row.Item -> {
                    val vh = h as AppVH
                    vh.app = r.app
                    vh.label.text = r.app.label
                    vh.label.setTextColor(prefs.fgColor())
                    val key = r.app.pkg + "\n" + r.app.cls
                    val raw = icons[key]
                    if (Tile.isClock(r.app.pkg, r.app.cls)) {
                        vh.icon.setImageResource(android.R.drawable.ic_lock_idle_alarm)
                        vh.icon.imageTintList = ColorStateList.valueOf(prefs.fgColor())
                    } else if (raw != null) {
                        vh.icon.imageTintList = null
                        val copy = raw.constantState?.newDrawable(activity.resources) ?: raw
                        val iconPx = (44 * activity.resources.displayMetrics.density).toInt()
                        vh.icon.setImageDrawable(IconMasks.apply(activity.resources, copy, prefs.iconShape, iconPx))
                    } else {
                        vh.icon.imageTintList = null
                        vh.icon.setImageDrawable(null)
                    }
                    vh.itemView.setOnClickListener { onLaunch(r.app, vh.itemView) }
                    vh.itemView.setOnLongClickListener(null)
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is AppVH) PressBeam.clear(holder.itemView)
        }
        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val box: View = v.findViewById(R.id.headerBox)
            val letter: TextView = v.findViewById(R.id.headerLetter)
        }
        inner class AppVH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val label: TextView = v.findViewById(R.id.appLabel)
            var app: AppList.App? = null
        }
    }
}
