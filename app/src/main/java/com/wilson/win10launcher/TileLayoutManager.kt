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
    private val originOf: (Int) -> Pair<Int, Int>? = { null },
    private val appendAtEnd: (Int) -> Boolean = { false }
) : RecyclerView.LayoutManager() {

    private val slots = ArrayList<Rect>()
    private var scrollOffset = 0
    private var contentHeight = 0
    /** Pixels the tiles are pulled past the edge. Positive moves them down. */
    private var bungee = 0
    private var pull = 0f
    private var spring: android.animation.ValueAnimator? = null
    var allowBungee = false

    fun hasBungee() = bungee != 0 || pull != 0f

    fun cancelSpring() {
        spring?.cancel()
        spring = null
    }

    /** Rubber-band back to the edge, with a little overshoot. */
    fun settleBungee() {
        if (!hasBungee()) return
        if (spring?.isRunning == true) return
        val from = bungee
        pull = 0f
        spring = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480
            interpolator = android.view.animation.OvershootInterpolator(1.35f)
            addUpdateListener {
                val t = it.animatedValue as Float
                bungee = (from * (1f - t)).toInt()
                requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bungee = 0
                    pull = 0f
                    spring = null
                    requestLayout()
                }
            })
            start()
        }
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun canScrollVertically() = true

    override fun supportsPredictiveItemAnimations() = false

    /** The tile under the finger. Layout must not scrap it or the drag dies. */
    var held: View? = null

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (itemCount == 0) {
            detachAndScrapAttachedViews(recycler)
            slots.clear()
            contentHeight = 0
            scrollOffset = 0
            return
        }
        if (state.isPreLayout || width <= 0) return
        val keep = held
        val keepX = keep?.translationX ?: 0f
        val keepY = keep?.translationY ?: 0f
        var i = childCount - 1
        while (i >= 0) {
            if (getChildAt(i) !== keep) detachAndScrapViewAt(i, recycler)
            i--
        }
        pack()
        val maxScroll = (contentHeight - height).coerceAtLeast(0)
        if (scrollOffset > maxScroll) scrollOffset = maxScroll
        if (scrollOffset < 0) scrollOffset = 0
        fill(recycler)
        keep?.translationX = keepX
        keep?.translationY = keepY
        keep?.bringToFront()
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        if (dy == 0 || itemCount == 0) return 0
        val maxScroll = (contentHeight - height).coerceAtLeast(0)
        if (allowBungee && (bungee != 0 || pull != 0f)) {
            spring?.cancel()
            spring = null
            pull -= dy
            val sameSide = (bungee > 0 && pull > 0f) || (bungee < 0 && pull < 0f)
            if (!sameSide) {
                offsetChildrenVertical(-bungee)
                val rest = (-pull).toInt()
                bungee = 0
                pull = 0f
                if (rest != 0) scrollVerticallyBy(rest, recycler, state)
                return dy
            }
            val next = resist(pull)
            offsetChildrenVertical(next - bungee)
            bungee = next
            return dy
        }
        val travel = when {
            dy > 0 -> dy.coerceAtMost(maxScroll - scrollOffset)
            else -> dy.coerceAtLeast(-scrollOffset)
        }
        if (allowBungee && travel != dy) {
            if (travel != 0) {
                scrollOffset += travel
                offsetChildrenVertical(-travel)
                fill(recycler)
            }
            pull = -(dy - travel).toFloat()
            val next = resist(pull)
            offsetChildrenVertical(next - bungee)
            bungee = next
            return dy
        }
        if (travel == 0) return 0
        scrollOffset += travel
        offsetChildrenVertical(-travel)
        fill(recycler)
        return travel
    }

    private fun resist(raw: Float): Int {
        val max = (height / 2.2f).coerceAtLeast(1f)
        val sign = if (raw < 0f) -1f else 1f
        val dist = kotlin.math.abs(raw)
        val eased = max * (1f - 1f / (1f + dist / (max * 0.5f)))
        return (sign * eased).toInt()
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State) = scrollOffset

    override fun computeVerticalScrollRange(state: RecyclerView.State) = contentHeight

    override fun computeVerticalScrollExtent(state: RecyclerView.State) = height

    /** Cell under a dragged tile's top-left, in grid coordinates. */
    fun cellAt(viewLeft: Float, viewTop: Float): Pair<Int, Int> {
        val contentW = width - paddingLeft - paddingRight
        val cell = if (contentW > 0) contentW / cols else 1
        val col = kotlin.math.round((viewLeft - paddingLeft) / cell).toInt()
        val row = kotlin.math.round((viewTop + scrollOffset - paddingTop) / cell).toInt()
        return col.coerceAtLeast(0) to row.coerceAtLeast(0)
    }

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
            val saved = if (appendAtEnd(i)) null else originOf(i)
            var row = if (appendAtEnd(i)) depth else 0
            var col = 0
            if (saved != null) {
                col = saved.first.coerceIn(0, (cols - w).coerceAtLeast(0))
                row = saved.second.coerceAtLeast(0)
                for (y in row until row + h) {
                    for (x in col until col + w) used.add(y.toLong() * cols + x)
                }
            } else {
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
            if (child === held) {
                i--
                continue
            }
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
        val t = slot.top - scrollOffset + lp.topMargin + bungee
        layoutDecorated(child, left, t, left + child.measuredWidth, t + child.measuredHeight)
    }
}
