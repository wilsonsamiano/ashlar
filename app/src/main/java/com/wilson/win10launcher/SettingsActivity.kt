package com.wilson.win10launcher

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
        setTheme(if (prefs.darkTheme) R.style.Theme_Win10_Dark else R.style.Theme_Win10_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.slide_in_right, R.anim.hold)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.hold, R.anim.slide_out_right)
        }
        title = "Launcher settings"

        findViewById<View>(R.id.root).setBackgroundColor(prefs.bgColor())
        for (id in listOf(R.id.accentTitle, R.id.themeTitle)) {
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
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold, R.anim.slide_out_right)
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
