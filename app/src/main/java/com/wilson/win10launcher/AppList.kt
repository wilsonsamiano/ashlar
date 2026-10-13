package com.wilson.win10launcher

import android.content.Intent
import android.content.pm.PackageManager

/** Loads every launchable app on the device, sorted A-Z. */
object AppList {
    data class App(val pkg: String, val cls: String, val label: String)

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
