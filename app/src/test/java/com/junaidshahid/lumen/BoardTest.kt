package com.junaidshahid.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules-layer tests. [Board.move] never spawns, so every case here is exact.
 *
 * Grids are written row-major with 0 for empty and the *exponent* elsewhere, so
 * 1 is a 2 tile, 2 is a 4 tile, and so on.
 */
class BoardTest {

    private fun boardOf(vararg exps: Int, score: Long = 0L): Board {
        require(exps.size == 16) { "expected a 4x4 grid, got ${exps.size} cells" }
        return Board().apply { restore(exps, score) }
    }

    private fun Board.grid(): List<Int> = snapshot().toList()

    private fun rows(vararg r: List<Int>): List<Int> = r.flatMap { it }

    @Test
    fun `slides tiles to the wall without merging unlike neighbours`() {
        val b = boardOf(
            0, 1, 0, 2,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        assertNotNull(b.move(Dir.LEFT))
        assertEquals(
            rows(
                listOf(1, 2, 0, 0),
                listOf(0, 0, 0, 0),
                listOf(0, 0, 0, 0),
                listOf(0, 0, 0, 0)
            ),
            b.grid()
        )
    }

    @Test
    fun `four equal tiles collapse into two pairs, not one`() {
        val b = boardOf(
            1, 1, 1, 1,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        val result = b.move(Dir.LEFT)
        assertNotNull(result)
        // 2,2,2,2 -> 4,4. A tile that has just absorbed something is closed for
        // the rest of the move, which is the rule that stops a runaway 8.
        assertEquals(listOf(2, 2, 0, 0), b.grid().take(4))
        assertEquals(8L, b.score)
        assertEquals(2, result!!.ghosts.size)
    }

    @Test
    fun `a merged tile does not merge again in the same move`() {
        val b = boardOf(
            2, 1, 1, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        assertNotNull(b.move(Dir.LEFT))
        // 4,2,2 -> 4,4 and must not chain into 8.
        assertEquals(listOf(2, 2, 0, 0), b.grid().take(4))
        assertEquals(4L, b.score)
    }

    @Test
    fun `merging is resolved from the leading edge`() {
        val b = boardOf(
            1, 1, 1, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        assertNotNull(b.move(Dir.LEFT))
        // The pair nearest the wall merges; the straggler follows it.
        assertEquals(listOf(2, 1, 0, 0), b.grid().take(4))
    }

    @Test
    fun `score gains the value of the tile produced`() {
        val b = boardOf(
            3, 3, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        val result = b.move(Dir.LEFT)!!
        assertEquals(16L, result.gained) // two 8s make a 16
        assertEquals(16L, b.score)
    }

    @Test
    fun `a move that changes nothing is rejected`() {
        val b = boardOf(
            1, 2, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        assertNull(b.move(Dir.LEFT))
        assertEquals(0L, b.score)
    }

    @Test
    fun `vertical moves work the same way`() {
        val b = boardOf(
            1, 0, 0, 0,
            1, 0, 0, 0,
            0, 0, 0, 0,
            2, 0, 0, 0
        )
        assertNotNull(b.move(Dir.DOWN))
        assertEquals(listOf(0, 0, 2, 2), b.grid().filterIndexed { i, _ -> i % 4 == 0 })
    }

    @Test
    fun `a full board with no equal neighbours has no moves`() {
        val b = boardOf(
            1, 2, 1, 2,
            2, 1, 2, 1,
            1, 2, 1, 2,
            2, 1, 2, 1
        )
        assertTrue(b.isFull())
        assertFalse(b.hasMoves())
    }

    @Test
    fun `a full board with an equal neighbour still has moves`() {
        val b = boardOf(
            1, 1, 1, 2,
            2, 1, 2, 1,
            1, 2, 1, 2,
            2, 1, 2, 1
        )
        assertTrue(b.isFull())
        assertTrue(b.hasMoves())
    }

    @Test
    fun `snapshot and restore round trip`() {
        val b = boardOf(
            1, 0, 3, 0,
            0, 5, 0, 0,
            0, 0, 0, 2,
            4, 0, 0, 0,
            score = 120L
        )
        val snap = b.snapshot()
        b.move(Dir.RIGHT)
        b.restore(snap, 120L)
        assertEquals(snap.toList(), b.grid())
        assertEquals(120L, b.score)
        assertEquals(5, b.tiles.size)
    }

    @Test
    fun `tiles carry their previous position for the slide animation`() {
        val b = boardOf(
            0, 0, 0, 1,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        b.move(Dir.LEFT)
        val tile = b.tiles.single()
        assertEquals(3, tile.prevCol)
        assertEquals(0, tile.col)
        assertEquals(0, tile.prevRow)
    }

    @Test
    fun `spawn fills exactly one free cell and never overwrites`() {
        val b = boardOf(
            1, 1, 1, 1,
            1, 1, 1, 1,
            1, 1, 1, 1,
            1, 1, 1, 0
        )
        assertNotNull(b.spawn())
        assertTrue(b.isFull())
        assertNull(b.spawn())
        assertEquals(16, b.tiles.size)
    }

    @Test
    fun `zen mode assist removes the weakest tile`() {
        val b = boardOf(
            4, 3, 0, 0,
            1, 5, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0
        )
        val gone = b.dissolveWeakest()!!
        assertEquals(1, gone.exp)
        assertEquals(3, b.tiles.size)
        assertEquals(0, b.snapshot()[4])
    }
}
