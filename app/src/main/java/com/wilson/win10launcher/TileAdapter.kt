package com.wilson.win10launcher

import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Tile(val pkg: String, val cls: String, val label: String, var size: Int) {
    companion object {
        const val SMALL = 0
        const val MEDIUM = 1
        const val WIDE = 2
        /** Same width as a small tile, half the height. */
        const val HALF = 3
        /** 12 columns so a half tile is one cell and a small tile stays 2x2. */
        const val GRID = 12

        fun span(size: Int, totalCols: Int = GRID) = when (size) {
            WIDE -> totalCols
            MEDIUM -> 4
            else -> 2 // small and half share the small tile's width
        }

        fun rows(size: Int) = when (size) {
            WIDE -> 4
            MEDIUM -> 4
            HALF -> 1
            else -> 2
        }
    }
}

/**
 * Start-screen tile grid. Half is a small tile cut in half horizontally
 * (same width, half height). Small=2x2, medium=4x4, wide=full width x4
 * on a 12-column grid.
 * A null item at the end renders the "+" pin-new-app tile.
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
    var editMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(tiles: List<Tile>) {
        items = tiles + listOf(null)
        notifyDataSetChanged()
    }

    fun tiles(): List<Tile> = items.filterNotNull()

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

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: FrameLayout = v as FrameLayout
        val face: FrameLayout = v.findViewById(R.id.tileFace)
        val icon: ImageView = v.findViewById(R.id.tileIcon)
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
        h.face.animate().cancel()
        h.face.rotationX = 0f
        h.face.rotationY = 0f
        h.face.scaleX = 1f
        h.face.scaleY = 1f
        h.face.elevation = if (editMode) dp(2).toFloat() else 0f
        val gap = dp(2)
        val unit = cellSize(h.root)
        val lp = h.root.layoutParams as RecyclerView.LayoutParams
        lp.setMargins(gap, gap, gap, gap)

        val rows = if (t == null) Tile.rows(Tile.SMALL) else Tile.rows(t.size)
        val faceH = (unit * rows - gap * 2).coerceAtLeast(if (t?.size == Tile.HALF) dp(36) else dp(36))
        // Root height is what the grid measures first. The grid then stretches every
        // tile in the row to the tallest one, which turned quarter tiles into tall slices.
        // The face keeps the real height and stays at the top of that cell.
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = faceH
        h.root.layoutParams = lp
        h.root.background = null
        h.root.isClickable = false
        val faceLp = h.face.layoutParams as FrameLayout.LayoutParams
        faceLp.width = ViewGroup.LayoutParams.MATCH_PARENT
        faceLp.height = faceH
        faceLp.gravity = android.view.Gravity.TOP
        h.face.layoutParams = faceLp

        if (t == null) {
            h.face.background = ColorDrawable(darken(accent, 0.75f))
            sizeIcon(h.icon, dp(28))
            (h.icon.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = android.view.Gravity.CENTER
                marginStart = 0
            }
            h.icon.setImageResource(android.R.drawable.ic_input_add)
            h.icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            h.label.text = ""
            h.label.visibility = View.GONE
            h.face.setOnClickListener { onAction(Action.Add) }
            h.face.setOnLongClickListener(null)
            return
        }
        h.face.background = rippleBg(accent)
        h.icon.imageTintList = null
        val iconPx = when (t.size) {
            Tile.HALF -> dp(18)
            Tile.SMALL -> dp(26)
            Tile.MEDIUM -> dp(42)
            else -> dp(48)
        }
        val iconLp = h.icon.layoutParams as FrameLayout.LayoutParams
        val labelLp = h.label.layoutParams as FrameLayout.LayoutParams
        if (t.size == Tile.HALF) {
            val iconSide = dp(18).coerceAtMost((faceH * 0.7f).toInt().coerceAtLeast(dp(12)))
            iconLp.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            iconLp.setMargins(dp(8), 0, 0, 0)
            labelLp.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            labelLp.setMargins(dp(32), 0, dp(6), 0)
            h.label.setPadding(0, 0, 0, 0)
            h.label.textSize = 12f
            h.label.maxLines = 1
            sizeIcon(h.icon, iconSide)
        } else {
            iconLp.gravity = android.view.Gravity.CENTER
            iconLp.setMargins(0, 0, 0, 0)
            labelLp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            labelLp.setMargins(dp(8), 0, dp(8), dp(8))
            h.label.setPadding(0, 0, 0, 0)
            h.label.textSize = if (t.size == Tile.SMALL) 10f else 12f
            sizeIcon(h.icon, iconPx)
        }
        h.icon.layoutParams = iconLp
        h.label.layoutParams = labelLp
        try {
            h.icon.setImageDrawable(
                ctx.packageManager.getActivityIcon(ComponentName(t.pkg, t.cls))
            )
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
        v.animate().rotationX(rotX).rotationY(rotY).setDuration(110).start()
    }

    private fun untilt(v: View) {
        v.animate().rotationX(0f).rotationY(0f).setDuration(200).start()
    }

    private fun darken(c: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }
}
