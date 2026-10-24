package com.wilson.win10launcher

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Accent color picker, dark/light/sunrise theme, saved Start layout. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var appliedDark = true

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            prefs.themeMode = "sun"
            Toast.makeText(this, "Using the time zone for sunrise and sunset", Toast.LENGTH_LONG).show()
            recreate()
            return@registerForActivityResult
        }
        prefs.themeMode = "sun"
        Daylight.refresh(this) { if (!isFinishing) recreate() }
    }

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
        appliedDark = prefs.darkTheme
        setTheme(if (appliedDark) R.style.Theme_Win10_Dark_Page else R.style.Theme_Win10_Light_Page)
        super.onCreate(savedInstanceState)
        PageTurn.noSystemAnim(this)
        setContentView(R.layout.activity_settings)
        prefs.chrome(this)
        VistaGlass.clearCompositor(this)
        val page = findViewById<View>(android.R.id.content)
        if (prefs.glassHome) {
            val glass = VistaGlass.sheet(prefs.darkTheme)
            page.background = glass
            window.setBackgroundDrawable(glass.constantState?.newDrawable(resources) ?: glass)
        } else {
            page.setBackgroundColor(prefs.bgColor())
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                slideClosed = true
                finish()
            }
        })
        title = "Ashlar"

        findViewById<View>(R.id.root).setBackgroundColor(if (prefs.glassHome) android.graphics.Color.TRANSPARENT else prefs.bgColor())
        for (id in listOf(R.id.accentTitle, R.id.lookTitle, R.id.lookStatus, R.id.themeTitle, R.id.themeStatus, R.id.iconTitle, R.id.topTitle, R.id.topBlurb, R.id.topCount, R.id.updateTitle, R.id.updateStatus, R.id.supportTitle, R.id.supportBlurb)) {
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
        val sunBtn = findViewById<Button>(R.id.sunBtn)
        darkBtn.setOnClickListener { prefs.themeMode = "dark"; recreate() }
        lightBtn.setOnClickListener { prefs.themeMode = "light"; recreate() }
        sunBtn.setOnClickListener { chooseSun() }
        val mode = prefs.themeMode
        darkBtn.alpha = if (mode == "dark") 1f else 0.45f
        lightBtn.alpha = if (mode == "light") 1f else 0.45f
        sunBtn.alpha = if (mode == "sun") 1f else 0.45f
        findViewById<TextView>(R.id.themeStatus).text = themeStatus()

        val metroBtn = findViewById<Button>(R.id.metroBtn)
        val vistaBtn = findViewById<Button>(R.id.vistaBtn)
        val iceBtn = findViewById<Button>(R.id.iceBtn)
        metroBtn.setOnClickListener { prefs.look = "metro"; recreate() }
        vistaBtn.setOnClickListener { prefs.look = "vista"; recreate() }
        iceBtn.setOnClickListener { prefs.look = "ice"; recreate() }
        val look = prefs.look
        metroBtn.alpha = if (look == "metro") 1f else 0.45f
        vistaBtn.alpha = if (look == "vista") 1f else 0.45f
        iceBtn.alpha = if (look == "ice") 1f else 0.45f
        findViewById<TextView>(R.id.lookStatus).text = when (look) {
            "vista" -> "Vista Vibe keeps the flat tiles and puts a glass frame around each one."
            "ice" -> "Ice is pale see-through glass. The wallpaper shows through, with a bright shine across the top."
            else -> "Metro is the flat Start screen."
        }

        val iconRow = findViewById<android.widget.LinearLayout>(R.id.iconShapes)
        fun paintShapes() {
            iconRow.removeAllViews()
            val pad = (6 * resources.displayMetrics.density).toInt()
            for ((id, label) in IconMasks.choices) {
                val btn = Button(this)
                btn.text = label
                btn.isAllCaps = false
                val on = prefs.iconShape == id
                btn.alpha = if (on) 1f else 0.45f
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    (48 * resources.displayMetrics.density).toInt()
                )
                lp.marginEnd = pad
                btn.layoutParams = lp
                btn.setOnClickListener {
                    prefs.iconShape = id
                    paintShapes()
                }
                iconRow.addView(btn)
            }
            tintButtons(iconRow)
        }
        paintShapes()

        val topCount = findViewById<TextView>(R.id.topCount)
        fun paintTop() {
            val n = prefs.topAppCount
            topCount.text = if (n == 0) "No most-used list" else "$n most used"
        }
        paintTop()
        findViewById<Button>(R.id.topMinus).setOnClickListener {
            prefs.topAppCount = prefs.topAppCount - 1
            paintTop()
        }
        findViewById<Button>(R.id.topPlus).setOnClickListener {
            prefs.topAppCount = prefs.topAppCount + 1
            paintTop()
        }

        findViewById<Button>(R.id.saveBtn).setOnClickListener {
            prefs.saveLayoutSnapshot()
            Toast.makeText(this, "Start layout saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.restoreBtn).setOnClickListener {
            if (prefs.restoreLayoutSnapshot()) {
                Toast.makeText(this, "Saved layout restored", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No saved layout yet", Toast.LENGTH_SHORT).show()
            }
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
        findViewById<Button>(R.id.diagBtn).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.reportBtn).setOnClickListener { sendReport() }
        findViewById<Button>(R.id.coffeeBtn).setOnClickListener { openCoffee() }
        tintButtons(findViewById(R.id.root))
        if (savedInstanceState == null) {
            PageTurn.enterSlide(findViewById(android.R.id.content), resources.displayMetrics.widthPixels.toFloat())
        }
    }

    private fun tintButtons(root: View) {
        val ink = if (bright(prefs.accentColor)) Color.BLACK else Color.WHITE
        val tint = android.content.res.ColorStateList.valueOf(prefs.accentColor)
        fun walk(v: View) {
            if (v is Button) {
                v.backgroundTintList = tint
                v.setTextColor(ink)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }

    private fun bright(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.299 * r + 0.587 * g + 0.114 * b > 0.72
    }

    private fun showDiagnostics() {
        val text = TextView(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        text.setPadding(pad, pad, pad, pad)
        text.setTextColor(prefs.fgColor())
        text.textSize = 13f
        text.setTextIsSelectable(true)
        text.text = Diagnostics.report(this)
        val scroll = ScrollView(this)
        scroll.addView(text)
        AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setView(scroll)
            .setPositiveButton("Send report") { _, _ -> sendReport() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sendReport() {
        val body = Diagnostics.report(this)
        val email = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Diagnostics.REPORT_EMAIL}")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Ashlar report")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(email)
            return
        } catch (e: Exception) {
            Diagnostics.note(this, "mail failed: ${e.javaClass.simpleName}")
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(Diagnostics.REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Ashlar report")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(Intent.createChooser(share, "Send report"))
        } catch (e: Exception) {
            val clip = getSystemService(ClipboardManager::class.java)
            clip.setPrimaryClip(ClipData.newPlainText("Ashlar report", body))
            Toast.makeText(this, "Report copied. Paste it in an email to ${Diagnostics.REPORT_EMAIL}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openCoffee() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Diagnostics.COFFEE)))
        } catch (e: Exception) {
            Toast.makeText(this, Diagnostics.COFFEE, Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseSun() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            prefs.themeMode = "sun"
            Daylight.refresh(this) { if (!isFinishing) recreate() }
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun themeStatus(): String {
        return when (prefs.themeMode) {
            "light" -> "Always light"
            "sun" -> {
                val clock = DateFormat.getTimeFormat(this).format(Daylight.nextChange(this))
                if (prefs.darkTheme) "Dark until sunrise, $clock" else "Light until sunset, $clock"
            }
            else -> "Always dark"
        }
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
            status.text = "Allow Ashlar to install updates, then tap Download again."
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
