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

    /** "dark", "light", or "sun" (light from sunrise to sunset). */
    var themeMode: String
        get() = sp.getString("themeMode", null) ?: if (sp.getBoolean("dark", true)) "dark" else "light"
        set(v) { sp.edit().putString("themeMode", v).apply() }

    val darkTheme: Boolean
        get() = when (themeMode) {
            "light" -> false
            "sun" -> !Daylight.isDay(appCtx)
            else -> true
        }

    fun bgColor() = if (darkTheme) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    fun fgColor() = if (darkTheme) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    /** "metro", "vista", or "ice". Older installs only stored the Vista switch. */
    var look: String
        get() {
            val saved = sp.getString("look", null)
            if (saved == "metro" || saved == "vista" || saved == "ice") return saved
            return if (sp.getBoolean("vistaVibe", false)) "vista" else "metro"
        }
        set(v) {
            val next = if (v == "vista" || v == "ice") v else "metro"
            sp.edit().putString("look", next).putBoolean("vistaVibe", next == "vista").apply()
        }

    val vistaVibe: Boolean get() = look == "vista"
    val ice: Boolean get() = look == "ice"
    val glassHome: Boolean get() = look == "vista" || look == "ice"

    /** Dark status icons on a light background, light icons on a dark one. */
    fun chrome(activity: android.app.Activity) {
        val light = !darkTheme && !glassHome
        androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** Keep a copy of the current Start layout so it can be put back later. */
    fun saveLayoutSnapshot() {
        sp.edit().putString("savedTiles", sp.getString("tiles", "[]")).apply()
    }

    fun hasSavedLayout() = !sp.getString("savedTiles", null).isNullOrEmpty()

    fun restoreLayoutSnapshot(): Boolean {
        val raw = sp.getString("savedTiles", null) ?: return false
        if (raw.isEmpty()) return false
        sp.edit().putString("tiles", raw).apply()
        return true
    }

    /** Load pinned tiles; drops tiles whose app was uninstalled. */
    fun loadTiles(): List<Tile> {
        val raw = sp.getString("tiles", null)
        if (raw == null) {
            val d = defaultTiles().toMutableList()
            TileGrid.ensurePlaced(d, Tile.GRID)
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
                    val col = o.optInt("col", -1)
                    val row = o.optInt("row", -1)
                    if (Tile.isClock(pkg, cls)) {
                        out.add(Tile(pkg, cls, "Clock", size, col, row))
                        continue
                    }
                    pm.getActivityInfo(android.content.ComponentName(pkg, cls), 0)
                    out.add(Tile(pkg, cls, o.getString("label"), size, col, row))
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
            sp.edit().putBoolean("clockSeeded", true).apply()
        }
        if (TileGrid.ensurePlaced(out, Tile.GRID) || retiredHalf) saveTiles(out)
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
                    .put("col", t.col)
                    .put("row", t.row)
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

    /** How many most-used apps sit under the new apps in All apps. */
    var topAppCount: Int
        get() = sp.getInt("topAppCount", 8).coerceIn(0, 16)
        set(v) { sp.edit().putInt("topAppCount", v.coerceIn(0, 16)).apply() }

    /** Icon mask: system, circle, squircle, rounded, square, teardrop. */
    var iconShape: String
        get() = sp.getString("iconShape", IconMasks.SYSTEM) ?: IconMasks.SYSTEM
        set(v) { sp.edit().putString("iconShape", v).apply() }

    fun recentApps(): List<String> {
        val saved = sp.getString("recent", null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (saved.isNotEmpty()) return saved
        val counts = launchCounts()
        val keys = counts.keys()
        val ranked = ArrayList<String>()
        while (keys.hasNext()) ranked.add(keys.next())
        ranked.sortByDescending { counts.optInt(it) }
        return ranked.take(8)
    }

    /**
     * Packages already on the device the first time this runs are not "new".
     * Anything installed after that stays at the top of All apps until it is opened.
     */
    fun ensureAppBaseline(pkgs: Collection<String>) {
        if (sp.getBoolean("appsBaselined", false)) return
        sp.edit().putStringSet("knownApps", pkgs.toSet()).putBoolean("appsBaselined", true).apply()
    }

    fun isNewApp(pkg: String): Boolean {
        if (!sp.getBoolean("appsBaselined", false)) return false
        if (pkg == appCtx.packageName) return false
        return pkg !in knownApps()
    }

    fun noteLaunch(pkg: String) {
        val known = knownApps().toMutableSet()
        known.add(pkg)
        val counts = launchCounts()
        counts.put(pkg, counts.optInt(pkg) + 1)
        val recent = recentApps().toMutableList()
        recent.remove(pkg)
        if (pkg != appCtx.packageName) recent.add(0, pkg)
        sp.edit()
            .putStringSet("knownApps", known)
            .putString("launches", counts.toString())
            .putString("recent", recent.take(12).joinToString("\n"))
            .apply()
    }

    fun launchCount(pkg: String): Int = launchCounts().optInt(pkg)

    private fun knownApps(): Set<String> = sp.getStringSet("knownApps", emptySet()) ?: emptySet()

    private fun launchCounts(): org.json.JSONObject {
        return try {
            org.json.JSONObject(sp.getString("launches", "{}") ?: "{}")
        } catch (e: Exception) {
            org.json.JSONObject()
        }
    }
}
