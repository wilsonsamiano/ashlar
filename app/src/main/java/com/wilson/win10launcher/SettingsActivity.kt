package com.wilson.win10launcher

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Accent color picker (classic Windows Phone palette), dark/light theme, reset layout. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    // Classic Windows Phone 8.1 accent palette
    private val accents = listOf(
        0xFFA4C400.toInt(), 0xFF60A917.toInt(), 0xFF008A00.toInt(), 0xFF00ABA9.toInt(),
        0xFF1BA1E2.toInt(), 0xFF0078D7.toInt(), 0xFF0050EF.toInt(), 0xFF6A00FF.toInt(),
        0xFFAA00FF.toInt(), 0xFFF472B7.toInt(), 0xFFD80073.toInt(), 0xFFA20025.toInt(),
        0xFFE51400.toInt(), 0xFFFA6800.toInt(), 0xFFF0A30A.toInt(), 0xFFE3C800.toInt(),
        0xFF825A2C.toInt(), 0xFF6D8764.toInt(), 0xFF647687.toInt(), 0xFF76608A.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        setTheme(if (prefs.darkTheme) R.style.Theme_Win10_Dark_Page else R.style.Theme_Win10_Light_Page)
        super.onCreate(savedInstanceState)
        PageTurn.noSystemAnim(this)
        setContentView(R.layout.activity_settings)
        val page = findViewById<View>(android.R.id.content)
        page.setBackgroundColor(prefs.bgColor())
        if (savedInstanceState == null) PageTurn.playOpen(page)
        title = "Launcher settings"

        findViewById<View>(R.id.root).setBackgroundColor(prefs.bgColor())
        for (id in listOf(R.id.accentTitle, R.id.themeTitle, R.id.updateTitle, R.id.updateStatus)) {
            findViewById<TextView>(id).setTextColor(prefs.fgColor())
        }

        val rv = findViewById<RecyclerView>(R.id.accentGrid)
        rv.layoutManager = GridLayoutManager(this, 5)
        rv.isNestedScrollingEnabled = false
        val adapter = AccentAdapter(accents, prefs.accentColor) { color ->
            prefs.accentColor = color
            recreate()
        }
        rv.adapter = adapter

        val darkBtn = findViewById<Button>(R.id.darkBtn)
        val lightBtn = findViewById<Button>(R.id.lightBtn)
        darkBtn.setOnClickListener { prefs.darkTheme = true; recreate() }
        lightBtn.setOnClickListener { prefs.darkTheme = false; recreate() }

        findViewById<Button>(R.id.resetBtn).setOnClickListener {
            prefs.saveTiles(prefs.defaultTiles())
            Toast.makeText(this, "Start layout reset", Toast.LENGTH_SHORT).show()
        }

        val updateBtn = findViewById<Button>(R.id.updateBtn)
        val updateStatus = findViewById<TextView>(R.id.updateStatus)
        updateStatus.text = "Version ${installedVersionName()}"
        updateBtn.setOnClickListener {
            val remote = updateBtn.tag as? Updater.Remote
            if (remote != null && remote.versionCode > installedVersionCode()) {
                downloadUpdate(updateBtn, updateStatus, remote)
            } else {
                lookForUpdate(updateBtn, updateStatus)
            }
        }
        lookForUpdate(updateBtn, updateStatus)
    }

    private fun installedVersionCode(): Int {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return code.toInt()
    }

    private fun installedVersionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }

    private fun lookForUpdate(button: Button, status: TextView) {
        button.isEnabled = false
        button.text = "Check for updates"
        button.tag = null
        status.text = "Checking GitHub…"
        Updater.check { result ->
            if (isFinishing) return@check
            button.isEnabled = true
            result.onSuccess { remote ->
                val here = installedVersionCode()
                if (remote == null || remote.versionCode <= here) {
                    status.text = "Version ${installedVersionName()} — you're up to date"
                    button.text = "Check for updates"
                } else {
                    button.tag = remote
                    status.text = "Version ${remote.versionName} is ready. Installs over this one. Tiles and settings stay."
                    button.text = "Download and install"
                }
            }.onFailure {
                status.text = "Couldn't reach GitHub. Check the connection and try again."
                button.text = "Check for updates"
            }
        }
    }

    private fun downloadUpdate(button: Button, status: TextView, remote: Updater.Remote) {
        if (!Updater.canInstall(this)) {
            status.text = "Allow Win10 Launcher to install updates, then tap Download again."
            Updater.askInstallPermission(this)
            return
        }
        button.isEnabled = false
        status.text = "Downloading ${remote.versionName}…"
        Updater.download(this, remote.apkUrl) { result ->
            if (isFinishing) return@download
            button.isEnabled = true
            result.onSuccess { file ->
                status.text = "Confirm Update on the next screen. Do not uninstall first."
                try {
                    Updater.launchInstall(this, file)
                } catch (e: Exception) {
                    status.text = "Couldn't open the installer."
                    Toast.makeText(this, e.message ?: "Install failed", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                status.text = "Download failed. Tap to try again."
            }
        }
    }

    private var closing = false

    override fun finish() {
        if (closing) return
        closing = true
        val page = findViewById<View>(android.R.id.content)
        PageTurn.playClose(page) {
            super.finish()
            PageTurn.noSystemAnim(this)
        }
    }

    inner class AccentAdapter(
        private val colors: List<Int>,
        private var selected: Int,
        private val onPick: (Int) -> Unit
    ) : RecyclerView.Adapter<AccentAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val swatch: View = v.findViewById(R.id.swatch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_swatch, parent, false)
            return VH(v)
        }

        override fun getItemCount() = colors.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = colors[position]
            val dp = { n: Int ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), resources.displayMetrics
                ).toInt()
            }
            val d = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(c)
                if (c == selected) {
                    setStroke(dp(3), Color.WHITE)
                }
            }
            h.swatch.background = d
            h.swatch.setOnClickListener {
                selected = c
                notifyDataSetChanged()
                onPick(c)
            }
        }
    }
}
