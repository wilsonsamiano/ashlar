package com.wilson.win10launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** Loads every launchable app on the device, sorted A-Z. */
object AppList {
    data class App(val pkg: String, val cls: String, val label: String)

    @Volatile
    private var cached: List<App>? = null

    /** Last list, or a fresh load. The Start screen fills this before All Apps opens. */
    fun get(pm: PackageManager): List<App> = cached ?: load(pm).also { cached = it }

    fun refreshAsync(pm: PackageManager, onDone: (List<App>) -> Unit) {
        val appPm = pm
        Thread {
            val fresh = load(appPm)
            cached = fresh
            Handler(Looper.getMainLooper()).post { onDone(fresh) }
        }.start()
    }

    @Suppress("DEPRECATION")
    fun load(pm: PackageManager): List<App> {
        val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(probe, 0)
            .map {
                App(
                    it.activityInfo.packageName,
                    it.activityInfo.name,
                    it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName
                )
            }
            .distinctBy { it.pkg + "\n" + it.cls }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
    }
}
