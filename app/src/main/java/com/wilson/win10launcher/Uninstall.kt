package com.wilson.win10launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Same uninstall path from Start and from All apps. */
object Uninstall {

    /** Null when the tile is the built-in clock. System apps say App info. */
    fun label(ctx: Context, pkg: String, cls: String): String? {
        if (Tile.isClock(pkg, cls)) return null
        if (pkg == ctx.packageName) return "App info"
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
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

    fun start(activity: Activity, pkg: String, label: String): Boolean {
        if (label == "App info") return openInfo(activity, pkg, label)
        val uri = Uri.fromParts("package", pkg, null)
        val delete = Intent(Intent.ACTION_DELETE, uri).addCategory(Intent.CATEGORY_DEFAULT)
        val legacy = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        val intent = when {
            delete.resolveActivity(activity.packageManager) != null -> delete
            legacy.resolveActivity(activity.packageManager) != null -> legacy
            else -> null
        }
        if (intent != null) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: Exception) {
                Diagnostics.note(activity, "uninstall failed: ${e.javaClass.simpleName}")
            }
        }
        return openInfo(activity, pkg, label)
    }

    private fun openInfo(activity: Activity, pkg: String, label: String): Boolean {
        val uri = Uri.fromParts("package", pkg, null)
        return try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "Can't uninstall $label", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
