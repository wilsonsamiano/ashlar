package com.wilson.win10launcher

/**
 * Each tile stays in the cell it was given. Empty cells stay empty.
 * A drag never moves any other tile.
 */
object TileGrid {

    fun ensurePlaced(tiles: MutableList<Tile>, cols: Int): Boolean {
        if (tiles.isEmpty()) return false
        if (tiles.all { it.col < 0 || it.row < 0 }) {
            packSequence(tiles, cols)
            return true
        }
        var changed = false
        val bottom = tiles.maxOf { t ->
            if (t.row < 0) 0 else t.row + Tile.rows(t.size)
        }
        for (t in tiles) {
            if (t.col >= 0 && t.row >= 0) continue
            append(t, tiles, cols, bottom)
            changed = true
        }
        return changed
    }

    /**
     * Put [tile] on this cell if nothing else is there.
     * Returns false when the cell is taken or unchanged. Other tiles are not moved.
     */
    fun tryMove(tiles: List<Tile>, tile: Tile, col: Int, row: Int, cols: Int): Boolean {
        val c = snapCol(tile.size, col, cols)
        val r = snapRow(row)
        if (c == tile.col && r == tile.row) return false
        val oldC = tile.col
        val oldR = tile.row
        tile.col = c
        tile.row = r
        if (!fits(tiles, tile, cols)) {
            tile.col = oldC
            tile.row = oldR
            return false
        }
        return true
    }

    /** True when [tile]'s current cell does not cover another tile. */
    fun fits(tiles: List<Tile>, tile: Tile, cols: Int): Boolean {
        return tiles.none { it !== tile && overlap(it, tile, cols) }
    }

    private fun packSequence(tiles: List<Tile>, cols: Int) {
        val used = HashSet<Long>()
        var depth = 0
        for (t in tiles) {
            val w = Tile.span(t.size, cols).coerceIn(1, cols)
            val h = Tile.rows(t.size).coerceAtLeast(1)
            var row = 0
            var col = 0
            var found = false
            val limit = tiles.size * (h + 2) + 8
            while (!found && row < limit) {
                var c = 0
                while (c <= cols - w) {
                    if (free(used, cols, c, row, w, h)) {
                        mark(used, cols, c, row, w, h)
                        col = c
                        found = true
                        break
                    }
                    c++
                }
                if (!found) row++
            }
            t.col = col
            t.row = row
            if (row + h > depth) depth = row + h
        }
    }

    private fun append(tile: Tile, tiles: List<Tile>, cols: Int, startRow: Int) {
        val used = HashSet<Long>()
        for (t in tiles) {
            if (t === tile || t.col < 0 || t.row < 0) continue
            mark(used, cols, t.col, t.row, Tile.span(t.size, cols), Tile.rows(t.size))
        }
        val w = Tile.span(tile.size, cols).coerceIn(1, cols)
        val h = Tile.rows(tile.size).coerceAtLeast(1)
        var row = startRow
        val limit = startRow + tiles.size * (h + 2) + 8
        while (row < limit) {
            var c = 0
            while (c <= cols - w) {
                if (free(used, cols, c, row, w, h)) {
                    tile.col = c
                    tile.row = row
                    return
                }
                c++
            }
            row++
        }
        tile.col = 0
        tile.row = startRow
    }

    private fun overlap(a: Tile, b: Tile, cols: Int): Boolean {
        val ax = a.col
        val ay = a.row
        val bx = b.col
        val by = b.row
        if (ax < 0 || ay < 0 || bx < 0 || by < 0) return false
        val aw = Tile.span(a.size, cols)
        val ah = Tile.rows(a.size)
        val bw = Tile.span(b.size, cols)
        val bh = Tile.rows(b.size)
        return ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah
    }

    fun snapCol(size: Int, col: Int, cols: Int): Int {
        val w = Tile.span(size, cols).coerceIn(1, cols)
        if (w >= cols) return 0
        val max = cols - w
        return ((col.coerceAtLeast(0)) / 2 * 2).coerceIn(0, max)
    }

    fun snapRow(row: Int) = (row.coerceAtLeast(0) / 2) * 2

    private fun free(used: HashSet<Long>, cols: Int, c: Int, r: Int, w: Int, h: Int): Boolean {
        if (c + w > cols) return false
        for (y in r until r + h) {
            for (x in c until c + w) {
                if ((y.toLong() * cols + x) in used) return false
            }
        }
        return true
    }

    private fun mark(used: HashSet<Long>, cols: Int, c: Int, r: Int, w: Int, h: Int) {
        for (y in r until r + h) {
            for (x in c until c + w) used.add(y.toLong() * cols + x)
        }
    }
}
