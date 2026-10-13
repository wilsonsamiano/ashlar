package com.wilson.win10launcher

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Packs tiles into a fixed column grid, filling holes.
 * A small tile is 2x2 cells and a medium tile is 4x4, so four small
 * tiles sit in the square one medium tile would take. GridLayoutManager
 * never does that — it leaves the space under a short tile empty.
 */
class TileLayoutManager(
    private val cols: Int,
    private val spanOf: (Int) -> Int,
    private val rowsOf: (Int) -> Int,
    private val appendAtEnd: (Int) -> Boolean = { false }
) : RecyclerView.LayoutManager() {

    private val slots = ArrayList<Rect>()
    private var scrollOffset = 0
    private var contentHeight = 0

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun canScrollVertically() = true

    override fun supportsPredictiveItemAnimations() = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (itemCount == 0) {
            detachAndScrapAttachedViews(recycler)
            slots.clear()
            contentHeight = 0
            scrollOffset = 0
            return
        }
        if (state.isPreLayout || width <= 0) return
        detachAndScrapAttachedViews(recycler)
        pack()
        val maxScroll = (contentHeight - height).coerceAtLeast(0)
        if (scrollOffset > maxScroll) scrollOffset = maxScroll
        if (scrollOffset < 0) scrollOffset = 0
        fill(recycler)
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        if (dy == 0 || itemCount == 0) return 0
        val maxScroll = (contentHeight - height).coerceAtLeast(0)
        val travel = when {
            dy > 0 -> dy.coerceAtMost(maxScroll - scrollOffset)
            else -> dy.coerceAtLeast(-scrollOffset)
        }
        if (travel == 0) return 0
        scrollOffset += travel
        offsetChildrenVertical(-travel)
        fill(recycler)
        return travel
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State) = scrollOffset

    override fun computeVerticalScrollRange(state: RecyclerView.State) = contentHeight

    override fun computeVerticalScrollExtent(state: RecyclerView.State) = height

    private fun pack() {
        slots.clear()
        val contentW = width - paddingLeft - paddingRight
        if (contentW <= 0) return
        val cell = contentW / cols
        val used = HashSet<Long>()
        fun free(c: Int, r: Int, w: Int, h: Int): Boolean {
            if (c + w > cols) return false
            for (y in r until r + h) {
                for (x in c until c + w) {
                    if ((y.toLong() * cols + x) in used) return false
                }
            }
            return true
        }
        var bottom = paddingTop
        var depth = 0
        for (i in 0 until itemCount) {
            val w = spanOf(i).coerceIn(1, cols)
            val h = rowsOf(i).coerceAtLeast(1)
            var row = if (appendAtEnd(i)) depth else 0
            var col = 0
            var found = false
            val limit = row + itemCount * (h + 2) + 8
            while (!found && row < limit) {
                var c = 0
                while (c <= cols - w) {
                    if (free(c, row, w, h)) {
                        for (y in row until row + h) {
                            for (x in c until c + w) used.add(y.toLong() * cols + x)
                        }
                        col = c
                        found = true
                        break
                    }
                    c++
                }
                if (!found) row++
            }
            val left = paddingLeft + col * cell
            val top = paddingTop + row * cell
            val rect = Rect(left, top, left + w * cell, top + h * cell)
            slots.add(rect)
            if (row + h > depth) depth = row + h
            if (rect.bottom > bottom) bottom = rect.bottom
        }
        contentHeight = bottom + paddingBottom
    }

    private fun fill(recycler: RecyclerView.Recycler) {
        val top = scrollOffset
        val bottom = scrollOffset + height
        var i = childCount - 1
        while (i >= 0) {
            val child = getChildAt(i) ?: break
            val pos = getPosition(child)
            val slot = slots.getOrNull(pos)
            if (slot == null || slot.bottom <= top || slot.top >= bottom) {
                removeAndRecycleView(child, recycler)
            }
            i--
        }
        val showing = HashSet<Int>()
        for (n in 0 until childCount) {
            showing.add(getPosition(getChildAt(n)!!))
        }
        for (pos in slots.indices) {
            if (pos in showing) continue
            val slot = slots[pos]
            if (slot.bottom <= top || slot.top >= bottom) continue
            val child = recycler.getViewForPosition(pos)
            addView(child)
            place(child, slot)
        }
    }

    private fun place(child: View, slot: Rect) {
        val lp = child.layoutParams as RecyclerView.LayoutParams
        val w = (slot.width() - lp.leftMargin - lp.rightMargin).coerceAtLeast(1)
        val h = (slot.height() - lp.topMargin - lp.bottomMargin).coerceAtLeast(1)
        child.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        val left = slot.left + lp.leftMargin
        val t = slot.top - scrollOffset + lp.topMargin
        layoutDecorated(child, left, t, left + child.measuredWidth, t + child.measuredHeight)
    }
}
