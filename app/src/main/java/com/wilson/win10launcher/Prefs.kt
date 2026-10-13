package com.wilson.win10launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences wrapper: accent color, theme, pinned tiles. */
class Prefs(private val appCtx: Context) {

    private val sp = appCtx.getSharedPreferences("win10", Context.MODE_PRIVATE)

    var accentColor: Int
        get() = sp.getInt("accent", 0xFF0078D7.toInt())
        set(v) { sp.edit().putInt("accent", v).apply() }

    var darkTheme: Boolean
        get() = sp.getBoolean("dark", true)
        set(v) { sp.edit().putBoolean("dark", v).apply() }

    fun bgColor() = if (darkTheme) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    fun fgColor() = if (darkTheme) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    /** Load pinned tiles; drops tiles whose app was uninstalled. */
    fun loadTiles(): List<Tile> {
        val raw = sp.getString("tiles", null)
        if (raw == null) {
            val d = defaultTiles()
            saveTiles(d)
            sp.edit().putBoolean("clockSeeded", true).apply()
            return d
        }
        val pm = appCtx.packageManager
        val out = mutableListOf<Tile>()
        var retiredHalf = false
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.getString("pkg")
                val cls = o.getString("cls")
                try {
                    var size = o.optInt("size", Tile.MEDIUM)
                    if (size == Tile.HALF) {
                        size = Tile.SMALL
                        retiredHalf = true
                    }
                    if (Tile.isClock(pkg, cls)) {
                        out.add(Tile(pkg, cls, "Clock", size))
                        continue
                    }
                    pm.getActivityInfo(android.content.ComponentName(pkg, cls), 0)
                    out.add(Tile(pkg, cls, o.getString("label"), size))
                } catch (e: PackageManager.NameNotFoundException) {
                    // app uninstalled -> drop the tile
                }
            }
        } catch (e: Exception) {
            return defaultTiles()
        }
        if (retiredHalf) saveTiles(out)
        if (!sp.getBoolean("clockSeeded", false)) {
            if (out.none { it.isClock() }) out.add(Tile.clock())
            saveTiles(out)
            sp.edit().putBoolean("clockSeeded", true).apply()
        }
        return out
    }

    fun saveTiles(tiles: List<Tile>) {
        val arr = JSONArray()
        for (t in tiles) {
            arr.put(
                JSONObject()
                    .put("pkg", t.pkg)
                    .put("cls", t.cls)
                    .put("label", t.label)
                    .put("size", t.size)
            )
        }
        sp.edit().putString("tiles", arr.toString()).apply()
    }

    fun addTile(t: Tile) {
        val tiles = loadTiles().toMutableList()
        if (tiles.any { it.pkg == t.pkg && it.cls == t.cls }) return
        tiles.add(t)
        saveTiles(tiles)
    }

    /** First-run pins: match well-known packages, fill the rest alphabetically. */
    fun defaultTiles(): List<Tile> {
        val pm = appCtx.packageManager
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val all = pm.queryIntentActivities(probe, 0)
        fun labelOf(pkg: String): Pair<String, String>? {
            val r = all.firstOrNull { it.activityInfo.packageName == pkg } ?: return null
            return Pair(r.activityInfo.name, r.loadLabel(pm)?.toString() ?: pkg)
        }
        val wanted = listOf(
            "com.android.settings" to Tile.MEDIUM,
            "com.android.chrome" to Tile.MEDIUM,
            "com.google.android.youtube" to Tile.MEDIUM,
            "com.android.vending" to Tile.WIDE,
            "com.google.android.gm" to Tile.MEDIUM,
            "com.android.camera2" to Tile.SMALL,
            "com.google.android.apps.photos" to Tile.MEDIUM,
            "com.google.android.calendar" to Tile.MEDIUM
        )
        val out = mutableListOf(Tile.clock())
        for ((pkg, size) in wanted) {
            val (cls, label) = labelOf(pkg) ?: continue
            out.add(Tile(pkg, cls, label, size))
        }
        if (out.size < 8) {
            val sorted = all.sortedBy { it.loadLabel(pm)?.toString()?.lowercase() ?: "" }
            for (r in sorted) {
                if (out.size >= 10) break
                if (out.any { it.pkg == r.activityInfo.packageName }) continue
                out.add(
                    Tile(
                        r.activityInfo.packageName,
                        r.activityInfo.name,
                        r.loadLabel(pm)?.toString() ?: r.activityInfo.packageName,
                        Tile.MEDIUM
                    )
                )
            }
        }
        return out
    }
}
