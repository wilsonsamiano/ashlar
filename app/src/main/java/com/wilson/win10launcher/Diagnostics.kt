package com.wilson.win10launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/** Plain-text snapshot of this device so a report shows what broke. */
object Diagnostics {

    const val COFFEE = "https://buymeacoffee.com/wilsonsamiano"
    const val REPORT_EMAIL = "wilsonsamiano@hotmail.com"

    fun note(ctx: Context, line: String) {
        val sp = ctx.applicationContext.getSharedPreferences("win10", Context.MODE_PRIVATE)
        val prior = sp.getString("notes", "").orEmpty()
        val stamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date())
        val next = (prior + "\n$stamp  $line").trim().takeLast(4000)
        sp.edit().putString("notes", next).apply()
    }

    fun recordCrash(ctx: Context, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        ctx.applicationContext.getSharedPreferences("win10", Context.MODE_PRIVATE).edit()
            .putString("lastCrash", sw.toString().take(8000))
            .putLong("lastCrashAt", System.currentTimeMillis())
            .commit()
    }

    fun report(ctx: Context): String {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val dm = ctx.resources.displayMetrics
        val cfg = ctx.resources.configuration
        val mem = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        val prefs = Prefs(ctx)
        val tiles = prefs.loadTiles()
        val sizes = tiles.groupingBy { it.size }.eachCount()
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        val sp = ctx.getSharedPreferences("win10", Context.MODE_PRIVATE)
        val crashAt = sp.getLong("lastCrashAt", 0L)
        val crash = sp.getString("lastCrash", null)
        val notes = sp.getString("notes", null)
        return buildString {
            appendLine("Win10 Launcher report")
            appendLine("version ${info.versionName} ($code)")
            appendLine("package ${ctx.packageName}")
            appendLine("android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT}")
            if (Build.VERSION.SDK_INT >= 23) appendLine("security ${Build.VERSION.SECURITY_PATCH}")
            appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("product ${Build.PRODUCT} ${Build.DEVICE}")
            appendLine("abi ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("screen ${dm.widthPixels}x${dm.heightPixels} dpi ${dm.densityDpi}")
            appendLine("orientation ${if (cfg.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}")
            appendLine("ram free ${mem.availMem / 1048576} MB of ${mem.totalMem / 1048576} MB low=${mem.lowMemory}")
            appendLine("locale ${if (cfg.locales.isEmpty) "?" else cfg.locales.get(0)}")
            appendLine("timezone ${java.util.TimeZone.getDefault().id}")
            appendLine("defaultHome ${def?.activityInfo?.packageName ?: "none"}")
            appendLine("theme ${prefs.themeMode}")
            appendLine("look ${prefs.look}")
            appendLine("tiles ${tiles.size} small=${sizes[Tile.SMALL] ?: 0} medium=${sizes[Tile.MEDIUM] ?: 0} wide=${sizes[Tile.WIDE] ?: 0}")
            appendLine()
            if (crash != null) {
                val whenCrash = DateFormat.getDateTimeInstance().format(Date(crashAt))
                appendLine("last crash $whenCrash")
                appendLine(crash)
                appendLine()
            } else {
                appendLine("last crash none")
                appendLine()
            }
            if (!notes.isNullOrBlank()) {
                appendLine("notes")
                appendLine(notes)
            }
        }
    }
}
