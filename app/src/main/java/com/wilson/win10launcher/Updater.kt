package com.wilson.win10launcher

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks at GitHub releases and installs a newer APK over this one.
 * Same signing key means Android updates in place; tiles and settings stay.
 */
object Updater {

    private const val RELEASES =
        "https://api.github.com/repos/wilsonsamiano/ashlar/releases?per_page=20"

    data class Remote(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String
    )

    fun check(onResult: (Result<Remote?>) -> Unit) {
        background {
            val result = runCatching { newest() }
            main { onResult(result) }
        }
    }

    fun download(context: Context, url: String, onResult: (Result<File>) -> Unit) {
        val app = context.applicationContext
        background {
            val result = runCatching {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "Ashlar-update.apk")
                if (out.exists()) out.delete()
                val conn = open(url)
                try {
                    if (conn.responseCode !in 200..299) error("Download failed (${conn.responseCode})")
                    conn.inputStream.use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                } finally {
                    conn.disconnect()
                }
                if (out.length() < 50_000L) error("Download looked empty")
                out
            }
            main { onResult(result) }
        }
    }

    fun canInstall(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
    }

    fun askInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    fun launchInstall(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun newest(): Remote? {
        val conn = open(RELEASES)
        val body = try {
            if (conn.responseCode !in 200..299) error("GitHub returned ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val arr = JSONArray(body)
        var best: Remote? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optBoolean("draft")) continue
            val notes = o.optString("body")
            val code = Regex("""versionCode\s+(\d+)""")
                .find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val name = Regex("""versionName\s+([0-9.]+)""")
                .find(notes)?.groupValues?.get(1)
                ?: o.optString("tag_name").removePrefix("v")
            val assets = o.optJSONArray("assets") ?: continue
            val apk = pickApk(assets) ?: continue
            if (best == null || code > best.versionCode) {
                best = Remote(code, name, apk)
            }
        }
        return best
    }

    private fun pickApk(assets: JSONArray): String? {
        var versioned: String? = null
        var generic: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue
            if (name.equals("Ashlar.apk", ignoreCase = true) ||
                name.equals("Win10Launcher.apk", ignoreCase = true)
            ) generic = url
            else versioned = url
        }
        return versioned ?: generic
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "Ashlar")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return conn
    }

    private fun background(block: () -> Unit) {
        Thread(block, "win10-update").start()
    }

    private fun main(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
