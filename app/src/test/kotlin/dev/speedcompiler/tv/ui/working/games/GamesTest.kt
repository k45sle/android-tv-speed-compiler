package dev.speedcompiler.tv.ui.working.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Random that always picks the lowest value, so spawns are predictable. */
private val lowest = object : Random() {
    override fun nextBits(bitCount: Int) = 0
}

class GamesTest {
    @Test
    fun alienRunJumpsAndLandsWithDust() {
        val g = AlienRun(lowest)
        g.step(Input.SELECT)
        assertTrue(g.air && g.y < 0f)
        g.step(Input.SELECT) // no double jump
        repeat(40) { g.step(null) }
        assertFalse(g.air)
        assertEquals(0f, g.y, 0f)
    }

    @Test
    fun alienRunHitCostsALifeThenBlinksInvulnerable() {
        val g = AlienRun(lowest)
        // First cactus starts at x=300 and reaches the alien after about 2 s while standing still.
        var steps = 0
        while (g.lives == 3 && steps++ < 200) g.step(null)
        assertEquals(2, g.lives)
        assertTrue(g.hurt > 0f && g.inv > 1.4f && g.air) // knockback hop
        // The cactus overlaps the alien for several more ticks but cannot hurt again during the window.
        repeat(20) { g.step(null) }
        assertEquals(2, g.lives)
        assertFalse(g.over)
    }

    @Test
    fun alienRunEndsAfterThirdHitAndStaysOver() {
        val g = AlienRun(lowest)
        repeat(1000) { g.step(null) }
        assertTrue(g.over)
        assertEquals(0, g.lives)
        val score = g.score
        g.step(Input.SELECT)
        assertEquals(score, g.score)
        assertTrue(g.dead > 0f)
    }

    @Test
    fun alienRunRestartWaitsForTheDeathAnimation() {
        val g = AlienRun(lowest)
        while (!g.over) g.step(null)
        assertFalse(g.canRestart)
        repeat(8) { g.step(null) } // 8 / 30 s > 0.25 s
        assertTrue(g.canRestart)
    }

    @Test
    fun alienRunSpeedRampsWithScoreAndCaps() {
        val g = AlienRun(lowest)
        assertEquals(110f, g.speed, 0f)
        // Jump over everything: score climbs. Jumping each time the alien is grounded clears most cacti; hits are fine here.
        repeat(3000) { g.step(Input.SELECT) }
        assertTrue("score ${g.score}", g.score > 10)
        assertTrue("speed ${g.speed}", g.speed > 110f && g.speed <= 170f)
    }

    @Test
    fun shakeAlternatesThenSettles() {
        assertEquals(listOf(2, -2, 2, -1, 1, -1, 0), (0..6).map { shakeX(it / 30f + 0.001f) })
        assertEquals(0, shakeX(0f))
    }
}
