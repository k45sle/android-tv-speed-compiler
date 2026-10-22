package dev.speedcompiler.tv.ui.working.games

import androidx.compose.runtime.Composable
import kotlin.random.Random
import kotlin.math.min

data class Pt(val x: Int, val y: Int)

class SnakeGame(val cols: Int = 16, val rows: Int = 9, private val rng: Random = Random.Default) : Game {
    var body: List<Pt> = listOf(Pt(cols / 2, rows / 2), Pt(cols / 2 - 1, rows / 2))
        internal set
    var dir = Input.RIGHT
        private set
    var food: Pt = place()
        private set
    override var score = 0
        private set
    override var over = false
        private set

    private fun place(): Pt {
        val free = (0 until cols).flatMap { x -> (0 until rows).map { Pt(x, it) } } - body.toSet()
        return if (free.isEmpty()) Pt(-1, -1) else free[rng.nextInt(free.size)]
    }

    private fun opposite(a: Input, b: Input) =
        setOf(a, b) == setOf(Input.LEFT, Input.RIGHT) || setOf(a, b) == setOf(Input.UP, Input.DOWN)

    override fun step(input: Input?) {
        if (over) return
        if (input != null && input != Input.SELECT && !opposite(input, dir)) dir = input
        val h = body.first()
        val next = when (dir) {
            Input.LEFT -> Pt(h.x - 1, h.y)
            Input.RIGHT -> Pt(h.x + 1, h.y)
            Input.UP -> Pt(h.x, h.y - 1)
            else -> Pt(h.x, h.y + 1)
        }
        val eats = next == food
        // The tail cell frees up this tick unless the snake grows.
        val solid = if (eats) body else body.dropLast(1)
        if (next.x !in 0 until cols || next.y !in 0 until rows || next in solid) { over = true; return }
        body = listOf(next) + solid
        if (eats) {
            score++
            food = place()
            if (food.x < 0) over = true
        }
    }
}

/** One 150 ms move is five 30 Hz ticks. */
object SnakeDef : GameDef("Snake", "Eat, grow, don't crash", ticksPerStep = 5) {
    override fun create(): Game = SnakeGame()
    @Composable override fun renderer() = GameRenderer { s, g, best ->
        g as SnakeGame
        s.fill(GameColors.logo)
        // shortcut: placeholder board kept below the status line and HUD until Cosmic Worm replaces it (G3).
        val top = s.hudY + 7f
        val c = min(Surface.W / g.cols.toFloat(), (Surface.H - top) / g.rows)
        val x0 = (Surface.W - c * g.cols) / 2f
        s.rect(GameColors.outline, 0f, top - 1f, Surface.W.toFloat(), 1f) // the top wall
        g.body.forEach { s.rect(GameColors.green3, x0 + it.x * c + 1, top + it.y * c + 1, c - 2, c - 2) }
        s.rect(GameColors.red, x0 + g.food.x * c + 3, top + g.food.y * c + 3, c - 6, c - 6)
        s.hud(g.score, best, dark = false)
    }
}
