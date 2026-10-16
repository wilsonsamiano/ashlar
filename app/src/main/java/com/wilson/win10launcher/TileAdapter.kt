package com.wilson.win10launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.text.format.DateFormat
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Tile(
    val pkg: String,
    val cls: String,
    val label: String,
    var size: Int,
    var col: Int = -1,
    var row: Int = -1
) {
    fun isClock() = Tile.isClock(pkg, cls)
    companion object {
        const val SMALL = 0
        const val MEDIUM = 1
        const val WIDE = 2
        /** Same width as a small tile, half the height. */
        const val HALF = 3
        /** Built-in live clock. Not a real package. */
        const val CLOCK_PKG = "com.wilson.win10launcher"
        const val CLOCK_CLS = "clock"
        /** 12 columns so a small tile is 2x2 and a medium tile is 4x4. */
        const val GRID = 12

        fun clock(size: Int = MEDIUM) = Tile(CLOCK_PKG, CLOCK_CLS, "Clock", size)

        fun isClock(pkg: String, cls: String) = pkg == CLOCK_PKG && cls == CLOCK_CLS

        /** Opens the tablet's clock. Falls back to a desk clock package. */
        fun clockIntent(ctx: Context): Intent {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            val alarm = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(flags)
            if (alarm.resolveActivity(ctx.packageManager) != null) return alarm
            for (pkg in listOf(
                "com.google.android.deskclock",
                "com.android.deskclock",
                "com.sec.android.app.clockpackage"
            )) {
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { return it.addFlags(flags) }
            }
            return alarm
        }

        fun timeText(ctx: Context): String {
            val now = java.util.Calendar.getInstance()
            val minute = now.get(java.util.Calendar.MINUTE)
            if (DateFormat.is24HourFormat(ctx)) {
                return String.format(Locale.getDefault(), "%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), minute)
            }
            var hour = now.get(java.util.Calendar.HOUR)
            if (hour == 0) hour = 12
            return String.format(Locale.getDefault(), "%d:%02d", hour, minute)
        }

        fun dateText(ctx: Context): String {
            val day = SimpleDateFormat("EEE d", Locale.getDefault()).format(Date())
            if (DateFormat.is24HourFormat(ctx)) return day
            val ap = SimpleDateFormat("a", Locale.getDefault()).format(Date())
            return "$ap  ·  $day"
        }

        fun span(size: Int, totalCols: Int = GRID) = when (size) {
            WIDE -> totalCols
            MEDIUM -> 4
            else -> 2 // small and half share the small tile's width
        }

        fun rows(size: Int) = when (size) {
            WIDE -> 4
            MEDIUM -> 4
            else -> 2 // small. Half is retired and uses the same square.
        }
    }
}

/**
 * Start-screen tile grid. Small is a quarter of a medium square (2x2 cells
 * on a 12-column grid, medium is 4x4), so four small tiles fill one square.
 * Wide is the full width and the height of a medium tile.
 * A null item at the end renders the "+" pin tile, and only while organizing.
 */
class TileAdapter(
    private val ctx: Context,
    prefs: Prefs,
    private val onAction: (Action) -> Unit
) : RecyclerView.Adapter<TileAdapter.VH>() {

    sealed class Action {
        data class Launch(val tile: Tile, val view: View) : Action()
        data class Options(val tile: Tile, val anchor: View) : Action()
        object Add : Action()
    }

    private var items: List<Tile?> = emptyList()
    var accent: Int = prefs.accentColor
    /** When false, adaptive icons draw the glyph only, with no round plate. */
    var iconPlate: Boolean = prefs.iconPlate
    var editMode: Boolean = false
        set(value) {
            if (field == value) return
            val kept = items.filterNotNull()
            field = value
            items = if (value) kept + listOf(null) else kept
            notifyDataSetChanged()
        }

    /** Rebind must not flatten tiles while they are feathering back in. */
    var featherHold: Boolean = false

    fun submit(tiles: List<Tile>) {
        items = if (editMode) tiles + listOf(null) else tiles
        notifyDataSetChanged()
    }

    fun tiles(): List<Tile> = items.filterNotNull()

    fun originFor(position: Int): Pair<Int, Int>? {
        val t = items.getOrNull(position) ?: return null
        if (t.col < 0 || t.row < 0) return null
        return t.col to t.row
    }

    fun tileAt(position: Int): Tile? = items.getOrNull(position)

    fun isAdd(position: Int) = items.getOrNull(position) == null

    fun move(from: Int, to: Int): Boolean {
        if (from == to) return false
        if (from !in items.indices || to !in items.indices) return false
        if (items[from] == null || items[to] == null) return false
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        items = list
        notifyItemMoved(from, to)
        return true
    }

    fun spanFor(position: Int, totalCols: Int): Int {
        val t = items.getOrNull(position) ?: return Tile.span(Tile.SMALL, totalCols)
        return Tile.span(t.size, totalCols)
    }

    fun rowsFor(position: Int): Int {
        val t = items.getOrNull(position) ?: return Tile.rows(Tile.SMALL)
        return Tile.rows(t.size)
    }

    /** Update the time on visible clock tiles without rebinding the whole grid. */
    fun paintClocks(grid: RecyclerView) {
        if (featherHold) return
        for (n in 0 until grid.childCount) {
            val child = grid.getChildAt(n)
            val pos = grid.getChildAdapterPosition(child)
            val tile = items.getOrNull(pos) ?: continue
            if (!tile.isClock()) continue
            child.findViewById<TextView>(R.id.tileTime)?.text = Tile.timeText(ctx)
            val date = child.findViewById<TextView>(R.id.tileLabel)
            if (date?.visibility == View.VISIBLE) date.text = Tile.dateText(ctx)
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: FrameLayout = v as FrameLayout
        val face: FrameLayout = v.findViewById(R.id.tileFace)
        val icon: ImageView = v.findViewById(R.id.tileIcon)
        val time: TextView = v.findViewById(R.id.tileTime)
        val label: TextView = v.findViewById(R.id.tileLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_tile, parent, false)
        val vh = VH(v)
        // Windows 10 Mobile 3D tilt. Skipped while rearranging.
        // Returning false lets click, long-click, and drag still fire.
        vh.face.setOnTouchListener { view, e ->
            if (!editMode) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> tilt(view, e.x, e.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> untilt(view)
                }
            }
            false
        }
        return vh
    }

    override fun onViewRecycled(h: VH) {
        PageTurn.drop(h.face)
    }

    override fun onViewAttachedToWindow(h: VH) {
        if (!PageTurn.owns(h.face)) PageTurn.drop(h.face)
    }

    override fun onViewDetachedFromWindow(h: VH) {
        if (!PageTurn.animating()) PageTurn.drop(h.face)
    }

    override fun getItemCount() = items.size

    private fun dp(n: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, n.toFloat(), ctx.resources.displayMetrics
        ).toInt()

    private fun rippleBg(base: Int): RippleDrawable {
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[2] = (hsv[2] * 0.7f).coerceAtLeast(0f)
        val pressed = Color.HSVToColor(hsv)
        return RippleDrawable(
            ColorStateList.valueOf(0x55FFFFFF),
            ColorDrawable(base),
            ColorDrawable(pressed)
        )
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        h.root.animate().cancel()
        if (!PageTurn.owns(h.face)) {
            PageTurn.drop(h.face)
        }
        h.face.elevation = if (editMode) dp(2).toFloat() else 0f
        val gap = dp(2)
        val unit = cellSize(h.root)
        val lp = h.root.layoutParams as RecyclerView.LayoutParams
        lp.setMargins(gap, gap, gap, gap)

        val rows = if (t == null) Tile.rows(Tile.SMALL) else Tile.rows(t.size)
        val faceH = (unit * rows - gap * 2).coerceAtLeast(dp(36))
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        h.root.layoutParams = lp
        h.root.background = null
        h.root.isClickable = false
        val faceLp = h.face.layoutParams as FrameLayout.LayoutParams
        faceLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        faceLp.height = ViewGroup.LayoutParams.MATCH_PARENT
        faceLp.gravity = android.view.Gravity.TOP
        h.face.layoutParams = faceLp

        if (t == null) {
            h.face.background = ColorDrawable(darken(accent, 0.75f))
            sizeIcon(h.icon, (faceH * 0.42f).toInt().coerceIn(dp(28), dp(72)))
            (h.icon.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = android.view.Gravity.CENTER
                marginStart = 0
            }
            h.icon.setImageResource(android.R.drawable.ic_input_add)
            h.icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            h.icon.visibility = View.VISIBLE
            h.time.visibility = View.GONE
            h.label.text = ""
            h.label.visibility = View.GONE
            h.face.setOnClickListener { onAction(Action.Add) }
            h.face.setOnLongClickListener(null)
            return
        }
        h.face.background = rippleBg(accent)
        h.icon.imageTintList = null
        val iconLp = h.icon.layoutParams as FrameLayout.LayoutParams
        val labelLp = h.label.layoutParams as FrameLayout.LayoutParams
        if (t.isClock()) {
            h.icon.visibility = View.GONE
            h.time.visibility = View.VISIBLE
            h.time.text = Tile.timeText(ctx)
            val small = t.size == Tile.SMALL || t.size == Tile.HALF
            val wide = t.size == Tile.WIDE
            val timeLp = h.time.layoutParams as FrameLayout.LayoutParams
            h.time.maxLines = 1
            h.time.gravity = android.view.Gravity.CENTER
            if (wide) {
                // Grow the time until it fills the wide tile, leaving a strip for the date.
                timeLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                timeLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                timeLp.gravity = android.view.Gravity.CENTER
                timeLp.setMargins(dp(12), dp(2), dp(12), dp(28))
                h.time.layoutParams = timeLp
                h.time.setAutoSizeTextTypeUniformWithConfiguration(
                    dp(28),
                    faceH.coerceAtLeast(dp(48)),
                    1,
                    TypedValue.COMPLEX_UNIT_PX
                )
            } else {
                h.time.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                timeLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                timeLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                timeLp.gravity = android.view.Gravity.CENTER
                timeLp.setMargins(0, 0, 0, if (small) 0 else dp(16))
                h.time.layoutParams = timeLp
                h.time.setTextSize(TypedValue.COMPLEX_UNIT_PX, faceH * if (small) 0.46f else 0.52f)
            }
            labelLp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            labelLp.setMargins(dp(10), 0, dp(8), dp(8))
            h.label.layoutParams = labelLp
            h.label.setPadding(0, 0, 0, 0)
            h.label.textSize = if (small) 11f else 14f
            if (small) {
                h.label.visibility = View.GONE
            } else {
                h.label.visibility = View.VISIBLE
                h.label.text = Tile.dateText(ctx)
            }
            h.face.setOnClickListener {
                if (editMode) onAction(Action.Options(t, h.face))
                else onAction(Action.Launch(t, h.face))
            }
            h.face.setOnLongClickListener {
                if (editMode) false
                else {
                    onAction(Action.Options(t, h.face))
                    true
                }
            }
            return
        }
        h.icon.visibility = View.VISIBLE
        h.time.visibility = View.GONE
        val iconPx = (faceH * if (t.size == Tile.SMALL || t.size == Tile.HALF) 0.52f else 0.46f)
            .toInt()
            .coerceAtLeast(dp(32))
        iconLp.gravity = android.view.Gravity.CENTER
        iconLp.bottomMargin = dp(10)
        iconLp.topMargin = 0
        iconLp.marginStart = 0
        iconLp.marginEnd = 0
        labelLp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
        labelLp.setMargins(dp(8), 0, dp(8), dp(8))
        h.label.setPadding(0, 0, 0, 0)
        h.label.textSize = if (t.size == Tile.SMALL || t.size == Tile.HALF) 11f else 13f
        sizeIcon(h.icon, iconPx)
        h.icon.layoutParams = iconLp
        h.label.layoutParams = labelLp
        try {
            h.icon.setImageDrawable(tileIcon(t.pkg, t.cls))
        } catch (e: Exception) {
            h.icon.setImageDrawable(null)
        }
        h.label.visibility = View.VISIBLE
        h.label.text = t.label
        h.face.setOnClickListener {
            if (editMode) onAction(Action.Options(t, h.face))
            else onAction(Action.Launch(t, h.face))
        }
        h.face.setOnLongClickListener {
            if (editMode) {
                false
            } else {
                onAction(Action.Options(t, h.face))
                true
            }
        }
    }

    private fun tileIcon(pkg: String, cls: String): Drawable? {
        val raw = ctx.packageManager.getActivityIcon(ComponentName(pkg, cls))
        return if (!iconPlate && raw is AdaptiveIconDrawable) raw.foreground else raw
    }

    private fun cellSize(view: View): Int {
        val parent = view.parent as? View
        val inner = if (parent != null && parent.width > parent.paddingLeft + parent.paddingRight) {
            parent.width - parent.paddingLeft - parent.paddingRight
        } else {
            ctx.resources.displayMetrics.widthPixels - dp(16)
        }
        return (inner / Tile.GRID).coerceAtLeast(1)
    }

    private fun sizeIcon(icon: ImageView, px: Int) {
        val lp = icon.layoutParams
        if (lp.width != px || lp.height != px) {
            lp.width = px
            lp.height = px
            icon.layoutParams = lp
        }
    }

    /** 3D press tilt, Windows 10 Mobile style. */
    private fun tilt(v: View, x: Float, y: Float) {
        val w = v.width.coerceAtLeast(1)
        val h = v.height.coerceAtLeast(1)
        v.cameraDistance = w * 10f
        val maxTilt = 11f
        val rotY = ((x - w / 2f) / (w / 2f) * maxTilt).coerceIn(-maxTilt, maxTilt)
        val rotX = ((h / 2f - y) / (h / 2f) * maxTilt).coerceIn(-maxTilt, maxTilt)
        v.animate().rotationX(rotX).rotationY(rotY)
            .setStartDelay(0L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setDuration(110)
            .start()
    }

    private fun untilt(v: View) {
        v.animate().rotationX(0f).rotationY(0f)
            .setStartDelay(0L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setDuration(200)
            .start()
    }

    private fun darken(c: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }
}
