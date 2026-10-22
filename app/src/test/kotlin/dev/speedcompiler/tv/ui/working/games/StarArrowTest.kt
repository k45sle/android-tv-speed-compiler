package dev.speedcompiler.tv.ui.working.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarArrowTest {
    private val lowest = object : Random() { override fun nextBits(bitCount: Int) = 0 }

    /** A game with no rocks in play, so only the ground and the ceiling can interact. */
    private fun clear() = StarArrow(lowest).also { it.columns.clear(); it.columns.add(StarArrow.Column(500f, 80, ArrayList())) }

    @Test
    fun hoversUntilTheFirstFlapThenFlapsAndFalls() {
        val g = clear()
        repeat(10) { g.step(null) }
        assertEquals(80f, g.y, 0f)
        g.step(Input.SELECT)
        assertTrue(g.started && g.vy < 0f && g.y < 80f)
        val top = g.y
        repeat(40) { g.step(null) }
        assertTrue(g.y > top)
    }

    @Test
    fun groundBouncesAndCostsOneCellThenGraceBlocksTheNextLoss() {
        val g = clear()
        g.step(Input.SELECT)
        var steps = 0
        while (g.shield == 3 && steps++ < 200) g.step(null)
        assertEquals(2, g.shield)
        assertTrue(g.vy < 0f && g.inv > 1.1f)
        // Free fall again into the ground while still blinking: bounced but not charged.
        repeat(25) { g.step(null) }
        assertEquals(2, g.shield)
        assertFalse(g.over)
    }

    @Test
    fun rockHitCostsOneCellAndInvulnerableWhileBlinking() {
        val g = StarArrow(lowest)
        g.step(Input.SELECT)
        // Hold the ship level in the gap's lower half, then run into the column; gap rocks sit at its edges.
        var steps = 0
        while (g.shield == 3 && steps++ < 300) g.step(if (g.y > 100f) Input.UP else null)
        assertEquals(2, g.shield)
        assertTrue(g.inv > 1.1f && g.hitT < 0.1f)
        val s = g.sparks.size
        assertTrue(s > 0)
        repeat(30) { g.step(if (g.y > 100f) Input.UP else null) } // 1 s, still inside the 1.2 s window
        assertEquals(2, g.shield)
        repeat(10) { g.step(null) }
        assertTrue(g.inv == 0f)
    }

    @Test
    fun scoresOnePerColumnPassed() {
        val g = StarArrow(lowest)
        var steps = 0
        // The first column has its gap centred at 80; hover around y 70-90, clear of the rocks at its edges.
        while (g.score < 1 && steps++ < 400) g.step(if (g.y > 90f || !g.started) Input.UP else null)
        assertEquals(1, g.score)
        assertTrue(g.pops.isNotEmpty())
    }

    @Test
    fun threeLossesEndTheRunAndRestartWaitsForTheExplosion() {
        val g = clear()
        g.step(Input.SELECT)
        var steps = 0
        while (!g.over && steps++ < 600) g.step(null)
        assertTrue(g.over)
        assertEquals(0, g.shield)
        assertFalse(g.canRestart)
        repeat(19) { g.step(null) } // 19 / 30 s > 0.6 s
        assertTrue(g.canRestart)
        val score = g.score
        g.step(Input.SELECT)
        assertEquals(score, g.score)
        assertTrue(g.over)
    }
}
