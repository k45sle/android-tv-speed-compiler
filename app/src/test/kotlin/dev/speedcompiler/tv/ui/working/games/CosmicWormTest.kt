package dev.speedcompiler.tv.ui.working.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmicWormTest {
    /** Ticks until the worm has moved one cell (or died). */
    private fun CosmicWorm.move(input: Input? = null) {
        val m = moves
        step(input)
        var guard = 0
        while (moves == m && !over && guard++ < 20) step(null)
    }

    private fun CosmicWorm.head() = bx[0] to by[0]

    @Test
    fun movesTurnsAndIgnoresReversal() {
        val g = CosmicWorm(Random(1))
        g.move()
        assertEquals(5 to 3, g.head())
        g.move(Input.LEFT) // reversal into the neck is ignored
        assertEquals(6 to 3, g.head())
        g.move(Input.UP)
        assertEquals(6 to 2, g.head())
        g.move(Input.DOWN)
        assertEquals(6 to 1, g.head())
        assertEquals(4, g.len)
    }

    @Test
    fun twoQuickTurnsWithinOneStepMakeAUTurn() {
        val g = CosmicWorm(Random(1))
        g.step(Input.UP); g.step(Input.LEFT) // both before the next move; LEFT alone would be a reversal
        g.move()
        assertEquals(4 to 2, g.head())
        g.move()
        assertEquals(3 to 2, g.head())
        assertFalse(g.over)
    }

    @Test
    fun eatsAStarAndGrowsOneCell() {
        val g = CosmicWorm(Random(1))
        g.foodX = 5; g.foodY = 3
        g.move()
        assertEquals(1, g.score)
        assertEquals(5, g.len)
        assertEquals(5 to 3, g.head())
        assertFalse(g.foodX == 5 && g.foodY == 3)
        assertTrue((0 until g.len).none { g.bx[it] == g.foodX && g.by[it] == g.foodY })
    }

    @Test
    fun gateTeleportsToTheFarSide() {
        val g = CosmicWorm(Random(1))
        g.move(Input.UP); g.move(); g.move() // column 4 is a gate column: (4,2) (4,1) (4,0)
        assertEquals(4 to 0, g.head())
        g.move()
        assertEquals(4 to 6, g.head())
        assertFalse(g.over)
        assertTrue(g.flash.any { it >= 0f })
        val h = CosmicWorm(Random(1))
        h.move(); h.move(); h.move(Input.UP) // column 6 is not a gate column
        repeat(2) { h.move() }
        assertEquals(6 to 0, h.head())
        h.move()
        assertTrue(h.over)
    }

    @Test
    fun forceFieldEndsTheRunAndRestartWaits() {
        val g = CosmicWorm(Random(1))
        repeat(10) { g.move() }
        assertEquals(14 to 3, g.head())
        g.move()
        assertTrue(g.over)
        assertEquals(14 to 3, g.head())
        assertFalse(g.canRestart)
        repeat(15) { g.step(null) }
        assertTrue(g.canRestart)
    }

    @Test
    fun hittingItselfEndsTheRun() {
        val g = CosmicWorm(Random(1))
        g.foodX = 5; g.foodY = 3; g.move()
        g.foodX = 6; g.foodY = 3; g.move()
        assertEquals(6, g.len)
        g.move(Input.UP); g.move(Input.LEFT)
        assertFalse(g.over)
        g.move(Input.DOWN) // into (5,3), part of the body
        assertTrue(g.over)
    }

    @Test
    fun followingTheTailIsSafe() {
        val g = CosmicWorm(Random(1))
        g.move(Input.UP); g.move(Input.LEFT); g.move(Input.DOWN) // the head takes the cell the tail just left
        assertFalse(g.over)
        assertEquals(3 to 3, g.head())
    }

    @Test
    fun sameSeedAndInputsPlayTheSame() {
        val inputs = listOf(Input.UP, null, Input.RIGHT, null, Input.DOWN, Input.LEFT, null, Input.UP)
        fun run(): List<Int> {
            val g = CosmicWorm(Random(7))
            repeat(300) { g.step(inputs[it / 6 % inputs.size]) }
            return listOf(g.score, g.len, g.bx[0], g.by[0], g.foodX, g.foodY, g.moves, if (g.over) 1 else 0)
        }
        assertEquals(run(), run())
    }
}
