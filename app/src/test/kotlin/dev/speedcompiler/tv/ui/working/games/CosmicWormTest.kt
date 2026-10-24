package dev.speedcompiler.tv.ui.working.games

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmicWormTest {
    /** Ticks until the worm has moved one cell (or died). [input] is pressed late in the step, so a turn starts at the head. */
    private fun CosmicWorm.move(input: Input? = null) {
        val m = moves
        while (acc < CosmicWorm.STEP / 2 && !over) step(null)
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
        g.step(Input.UP); g.step(Input.LEFT) // both within one step; LEFT alone would be a reversal
        assertEquals(4 to 2, g.head()) // the first turn moves at once
        g.move()
        assertEquals(3 to 2, g.head()) // the second waits for the step to end
        assertFalse(g.over)
    }

    @Test
    fun aSecondQuickTurnAlsoMovesAtOnce() {
        val g = CosmicWorm(Random(1))
        g.foodX = 0; g.foodY = 0
        g.move() // (4,3) -> (5,3), a plain move
        g.step(Input.UP) // early in the step: the move is redone from (4,3) to (4,2)
        g.step(Input.RIGHT) // a staircase, not an undo of the first turn
        assertEquals(5 to 2, g.head())
        assertEquals(4 to 2, g.bx[1] to g.by[1])
        g.step(Input.UP) // a third turn this soon waits for the step to end
        assertEquals(5 to 2, g.head())
        assertFalse(g.over)
    }

    /** Head at (7,0) heading right along the top wall, just past a step boundary. */
    private fun alongTheTopWall(): CosmicWorm {
        val g = CosmicWorm(Random(1))
        g.foodX = 0; g.foodY = 6
        g.move(); g.move(); g.move(Input.UP); g.move(); g.move(); g.move(Input.RIGHT)
        assertEquals(7 to 0, g.head())
        while (g.acc < CosmicWorm.STEP / 2) g.step(null)
        return g
    }

    @Test
    fun aTurnIntoAWallDiesOnlyWhenTheDrawingGetsThere() {
        val g = alongTheTopWall()
        g.step(Input.UP) // column 7 has no gate
        assertFalse(g.over)
        assertEquals(7 to 0, g.head())
        while (g.acc < CosmicWorm.STEP) g.step(null)
        assertFalse(g.over) // the drawing has just reached (7,0); the head now bumps the wall
        assertEquals(0f, g.bumpY(), 0.05f)
        while (g.acc < CosmicWorm.STEP + CosmicWorm.GRACE - 1e-4f) { assertFalse(g.over); g.step(null) }
        assertTrue(g.over)
        assertEquals(7 to 0, g.head())
        assertEquals(-CosmicWorm.BUMP, g.bumpY(), 1e-6f)
    }

    @Test
    fun aTurnJustAfterHittingAWallStillSavesTheWorm() {
        val g = CosmicWorm(Random(1))
        g.foodX = 0; g.foodY = 6
        g.move(); g.move(); g.move(Input.UP); g.move(); g.move() // (6,0), heading up into a wall with no gate
        var before: Float
        do { before = g.acc; g.step(null) } while (g.acc >= before) // up to the next step boundary, where the move is blocked
        assertFalse(g.over) // the head bumps the wall first
        assertEquals(g.moves + 1f, g.travel(), 0f) // the body stands at its last cell, so its specks stand too
        g.step(null)
        assertEquals(g.moves + 1f, g.travel(), 0f)
        val push = g.bumpY()
        assertTrue(push < 0f)
        g.step(Input.RIGHT)
        assertEquals(7 to 0, g.head())
        assertEquals(0f, g.bumpY(), 0f)
        // Glides on from the pushed spot toward (7,0): one tick in, the head is still almost as far up, not snapped back.
        assertEquals(push, g.slideY(0, g.slide()), 0.03f)
        repeat(10) { g.step(null) }
        assertFalse(g.over)
    }

    @Test
    fun pressingIntoTheWallAgainDoesNotPutOffTheCrash() {
        fun ticksToDie(press: Boolean): Int {
            val g = alongTheTopWall()
            g.step(Input.UP)
            var n = 0
            while (!g.over) { g.step(if (press) Input.UP else null); n++ }
            return n
        }
        assertEquals(ticksToDie(false), ticksToDie(true))
    }

    @Test
    fun aTurnBeforeTheDrawingGetsThereSavesTheWorm() {
        val g = alongTheTopWall()
        g.step(Input.UP)
        g.step(Input.DOWN)
        assertEquals(7 to 1, g.head())
        repeat(10) { g.step(null) }
        assertFalse(g.over)
    }

    @Test
    fun aTurnLateInAStepMovesAtOnce() {
        val g = CosmicWorm(Random(1))
        while (g.acc < CosmicWorm.STEP / 2) g.step(null)
        val m = g.moves
        g.step(Input.UP)
        assertEquals(m + 1, g.moves)
        assertEquals(4 to 2, g.head())
    }

    @Test
    fun aTurnEarlyInAStepRedoesTheLastMove() {
        val g = CosmicWorm(Random(1))
        g.foodX = 0; g.foodY = 0
        g.move() // (4,3) -> (5,3), a plain move
        val m = g.moves
        g.step(Input.UP)
        assertEquals(m, g.moves)
        assertEquals(4 to 2, g.head()) // turned from (4,3), where the head was drawn
        assertEquals(4 to 3, g.bx[1] to g.by[1])
        assertEquals(3, g.dir)
        g.step(Input.LEFT) // the reverse of the move before, so not a redo: the U-turn moves on at once
        assertEquals(3 to 2, g.head())
        assertFalse(g.over)
    }

    /** Drawn head (i = 0) or body cell position, in cells. */
    private fun CosmicWorm.drawn(i: Int) = slideX(i, slide()) to slideY(i, slide())

    @Test
    fun aTurnAtAnyPointInAStepGlidesWithoutAJump() {
        for (input in listOf(Input.UP, Input.DOWN)) for (at in 0..5) {
            val g = CosmicWorm(Random(1))
            g.foodX = 0; g.foodY = 0
            g.move()
            repeat(at) { g.step(null) }
            val m = g.moves
            var last = (0 until g.len).map { g.drawn(it) }
            for (tick in 0 until 10) {
                g.step(if (tick == 0) input else null)
                if (tick == 0) assertTrue("the head heads $input at once (at $at)", g.dir == if (input == Input.UP) 3 else 1)
                val now = (0 until g.len).map { g.drawn(it) }
                for (i in now.indices) {
                    val d = abs(now[i].first - last[i].first) + abs(now[i].second - last[i].second)
                    // A plain slide covers 0.2 cell a tick; the head may hurry to cut its corner, the body never jumps.
                    assertTrue("cell $i moved $d at tick $tick (press at $at, $input)", d <= if (i == 0) 0.7f else 0.25f)
                }
                last = now
            }
            assertFalse(g.over)
            assertTrue(g.moves > m)
        }
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
