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
    }
}

/**
 * Start-screen tile grid. 6 small-columns; small=1x1, medium=2x2, wide=full-width x2.
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

    fun submit(tiles: List<Tile>) {
        items = tiles + listOf(null)
        notifyDataSetChanged()
    }

    fun spanFor(position: Int, totalCols: Int): Int {
        val t = items.getOrNull(position) ?: return 1
        return when (t.size) {
            Tile.WIDE -> totalCols
            Tile.MEDIUM -> 2
            else -> 1
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: FrameLayout = v as FrameLayout
        val icon: ImageView = v.findViewById(R.id.tileIcon)
        val label: TextView = v.findViewById(R.id.tileLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(ctx).inflate(R.layout.item_tile, parent, false)
        val vh = VH(v)
        // Windows 10 Mobile 3D tilt: tile leans toward the finger while pressed.
        // Returning false lets click / long-click still fire normally.
        vh.root.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> tilt(view, e.x, e.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> untilt(view)
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
        // Recycled views must never keep a mid-tilt transform.
        h.root.animate().cancel()
        h.root.rotationX = 0f
        h.root.rotationY = 0f
        h.root.scaleX = 1f
        h.root.scaleY = 1f
        val dm = ctx.resources.displayMetrics
        val totalCols = 6
        val gap = dp(4)
        val unit = (dm.widthPixels - dp(16)) / totalCols
        val lp = h.root.layoutParams as RecyclerView.LayoutParams
        lp.setMargins(gap, gap, gap, gap)

        if (t == null) {
            // "+" tile: pin a new app
            lp.width = unit - gap * 2
            lp.height = unit - gap * 2
            h.root.layoutParams = lp
            h.root.background = ColorDrawable(darken(accent, 0.75f))
            h.icon.setImageResource(android.R.drawable.ic_input_add)
            h.icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            h.label.text = ""
            h.root.setOnClickListener { onAction(Action.Add) }
            h.root.setOnLongClickListener(null)
            return
        }

        val cols = when (t.size) {
            Tile.WIDE -> totalCols
            Tile.MEDIUM -> 2
            else -> 1
        }
        val rows = if (t.size == Tile.SMALL) 1 else 2
        lp.width = unit * cols - gap * 2
        lp.height = unit * rows - gap * 2
        h.root.layoutParams = lp
        h.root.background = rippleBg(accent)
        h.icon.imageTintList = null
        try {
            h.icon.setImageDrawable(
                ctx.packageManager.getActivityIcon(ComponentName(t.pkg, t.cls))
            )
        } catch (e: Exception) {
            h.icon.setImageDrawable(null)
        }
        h.label.text = t.label
        h.root.setOnClickListener { onAction(Action.Launch(t, h.root)) }
        h.root.setOnLongClickListener { onAction(Action.Options(t, h.root)); true }
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
            .scaleX(0.95f).scaleY(0.95f).setDuration(110).start()
    }

    private fun untilt(v: View) {
        v.animate().rotationX(0f).rotationY(0f)
            .scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun darken(c: Int, f: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = (hsv[2] * f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }
}
