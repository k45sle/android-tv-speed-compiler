package dev.speedcompiler.tv.ui.working

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
    fun dinoDiesOnCactusWhenNotJumping() {
        val g = DinoGame(lowest)
        repeat(60) { g.step(null) }
        assertTrue(g.over)
        assertEquals(0, g.score)
    }

    @Test
    fun dinoJumpClearsCactusAndScores() {
        val g = DinoGame(lowest)
        repeat(39) { g.step(null) }
        g.step(Input.SELECT)
        assertTrue(g.y > 0)
        repeat(15) { g.step(null) }
        assertFalse(g.over)
        assertEquals(1, g.score)
        assertEquals(0, g.y)
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
}
