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
    fun snakeMovesTurnsAndIgnoresReversal() {
        val g = SnakeGame(rng = lowest)
        g.step(null)
        assertEquals(Pt(9, 4), g.body.first())
        g.step(Input.LEFT) // reversal is ignored
        assertEquals(Pt(10, 4), g.body.first())
        g.step(Input.UP)
        assertEquals(Pt(10, 3), g.body.first())
        assertEquals(2, g.body.size)
    }

    @Test
    fun snakeEatsFoodGrowsAndScores() {
        val g = SnakeGame(rng = lowest)
        assertEquals(Pt(0, 0), g.food)
        g.step(Input.UP)
        repeat(3) { g.step(null) }
        repeat(8) { g.step(Input.LEFT) }
        assertEquals(1, g.score)
        assertEquals(3, g.body.size)
        assertFalse(g.over)
    }

    @Test
    fun snakeDiesOnWallAndSelfThenStaysStill() {
        val wall = SnakeGame(rng = lowest)
        repeat(8) { wall.step(null) }
        assertTrue(wall.over)
        val frozen = wall.body
        wall.step(Input.UP)
        assertEquals(frozen, wall.body)

        val self = SnakeGame(rng = lowest)
        self.body = listOf(Pt(5, 5), Pt(5, 6), Pt(6, 6), Pt(6, 5), Pt(6, 4))
        self.step(Input.RIGHT) // head moves into (6,5), part of the body
        assertTrue(self.over)
    }

    @Test
    fun flappyWaitsForFirstFlapThenFlapsAndFalls() {
        val g = FlappyGame(lowest)
        repeat(10) { g.step(null) }
        assertEquals(FlappyGame.H / 2, g.y)
        g.step(Input.SELECT)
        assertEquals(FlappyGame.H / 2 - 3, g.y)
        repeat(100) { g.step(null) }
        assertTrue(g.over) // fell to the floor
    }

    @Test
    fun flappyScoresWhenPassingPipesAndDiesOnTheNext() {
        val g = FlappyGame(lowest) // every gap spans y 6..32
        var steps = 0
        while (g.score < 2 && steps++ < 400) g.step(if (g.y >= 22) Input.SELECT else null)
        assertFalse(g.over)
        assertEquals(2, g.score)
        repeat(100) { g.step(null) } // stop flapping
        assertTrue(g.over)
    }

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
