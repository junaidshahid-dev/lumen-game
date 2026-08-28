package com.junaidshahid.lumen

import kotlin.random.Random

enum class Dir(val dr: Int, val dc: Int) {
    UP(-1, 0),
    DOWN(1, 0),
    LEFT(0, -1),
    RIGHT(0, 1)
}

/**
 * A single tile with a stable identity.
 *
 * Identity matters here: the renderer animates a tile from [prevRow]/[prevCol] to
 * [row]/[col], so tiles must survive a move rather than being rebuilt from a grid
 * snapshot each turn.
 */
class Tile(val id: Long, var row: Int, var col: Int, var exp: Int) {
    var prevRow: Int = row
    var prevCol: Int = col

    /** True for the frame batch in which this tile appears out of nowhere. */
    var spawned: Boolean = false

    /** True when this tile is the surviving half of a merge, so it should pop. */
    var mergedThisMove: Boolean = false

    val value: Long get() = 1L shl exp
}

/** Everything the renderer needs to animate one completed move. */
class MoveResult(
    val gained: Long,
    /** Tiles that were absorbed into another tile; drawn during the slide, then dropped. */
    val ghosts: List<Tile>
)

/**
 * Classic power-of-two merge logic on a [size] x [size] grid.
 *
 * Tiles store the *exponent* rather than the face value, which keeps the colour
 * lookup, the atlas lookup and the score arithmetic to a single small integer.
 */
class Board(val size: Int = 4, private val rng: Random = Random.Default) {

    val tiles = ArrayList<Tile>(size * size)
    private val grid = arrayOfNulls<Tile>(size * size)
    private var nextId = 1L

    var score: Long = 0L
        private set

    private fun at(r: Int, c: Int): Tile? = grid[r * size + c]

    private fun put(r: Int, c: Int, t: Tile?) {
        grid[r * size + c] = t
    }

    fun reset() {
        tiles.clear()
        grid.fill(null)
        score = 0L
        spawn()
        spawn()
    }

    /** Places a tile on a random free cell. Returns null when the grid is full. */
    fun spawn(): Tile? {
        val free = ArrayList<Int>(size * size)
        for (i in grid.indices) if (grid[i] == null) free.add(i)
        if (free.isEmpty()) return null

        val idx = free[rng.nextInt(free.size)]
        // The familiar 90/10 split between 2 and 4.
        val exp = if (rng.nextInt(10) == 0) 2 else 1
        val tile = Tile(nextId++, idx / size, idx % size, exp)
        tile.spawned = true
        grid[idx] = tile
        tiles.add(tile)
        return tile
    }

    /**
     * Applies a move. Returns null when nothing shifted, in which case the board
     * is untouched and no tile should be spawned.
     *
     * Deliberately does *not* spawn: keeping the only source of randomness in
     * [spawn] leaves this method fully deterministic, so the rules can be tested
     * without seeding or stubbing anything.
     */
    fun move(dir: Dir): MoveResult? {
        for (t in tiles) {
            t.prevRow = t.row
            t.prevCol = t.col
            t.spawned = false
            t.mergedThisMove = false
        }

        val ghosts = ArrayList<Tile>(size * size)
        var moved = false
        var gained = 0L

        // Walk the grid starting from the edge the tiles are travelling towards,
        // so each tile only ever sees cells that have already been settled.
        val rows = if (dir.dr > 0) (size - 1) downTo 0 else 0 until size
        val cols = if (dir.dc > 0) (size - 1) downTo 0 else 0 until size

        for (r in rows) for (c in cols) {
            val tile = at(r, c) ?: continue

            var nr = r
            var nc = c
            var target: Tile? = null

            while (true) {
                val tr = nr + dir.dr
                val tc = nc + dir.dc
                if (tr < 0 || tr >= size || tc < 0 || tc >= size) break
                val occupant = at(tr, tc)
                if (occupant == null) {
                    nr = tr
                    nc = tc
                    continue
                }
                // A tile that already absorbed something this move is closed off,
                // which is what stops 2,2,2,2 from collapsing straight to 8.
                if (occupant.exp == tile.exp && !occupant.mergedThisMove) target = occupant
                break
            }

            if (target != null) {
                put(r, c, null)
                target.exp += 1
                target.mergedThisMove = true
                tile.row = target.row
                tile.col = target.col
                tiles.remove(tile)
                ghosts.add(tile)
                gained += target.value
                moved = true
            } else if (nr != r || nc != c) {
                put(r, c, null)
                put(nr, nc, tile)
                tile.row = nr
                tile.col = nc
                moved = true
            }
        }

        if (!moved) return null

        score += gained
        return MoveResult(gained, ghosts)
    }

    fun hasMoves(): Boolean {
        for (i in grid.indices) if (grid[i] == null) return true
        for (r in 0 until size) for (c in 0 until size) {
            val e = at(r, c)?.exp ?: continue
            if (c + 1 < size && at(r, c + 1)?.exp == e) return true
            if (r + 1 < size && at(r + 1, c)?.exp == e) return true
        }
        return false
    }

    fun highestExp(): Int {
        var best = 0
        for (t in tiles) if (t.exp > best) best = t.exp
        return best
    }

    fun isFull(): Boolean = grid.none { it == null }

    /**
     * Removes the lowest-valued tile on the board, the one assist the game offers.
     * Returns the cleared tile so the renderer can dissolve it.
     */
    fun dissolveWeakest(): Tile? {
        val victim = tiles.minByOrNull { it.exp * 100 + it.row * 10 + it.col } ?: return null
        put(victim.row, victim.col, null)
        tiles.remove(victim)
        return victim
    }

    // ---- snapshot / restore, used by undo and by save-on-exit ----

    fun snapshot(): IntArray {
        val out = IntArray(size * size)
        for (i in grid.indices) out[i] = grid[i]?.exp ?: 0
        return out
    }

    fun restore(exps: IntArray, restoredScore: Long) {
        tiles.clear()
        grid.fill(null)
        score = restoredScore
        for (i in exps.indices) {
            val e = exps[i]
            if (e <= 0) continue
            val tile = Tile(nextId++, i / size, i % size, e)
            grid[i] = tile
            tiles.add(tile)
        }
    }
}
