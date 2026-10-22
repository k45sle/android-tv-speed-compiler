package dev.speedcompiler.tv.ui.working.games

import androidx.compose.runtime.Composable
import kotlin.random.Random

/** Flappy-style: gravity pulls down, Select or Up flaps. The bird stays put until the first flap. */
class FlappyGame(private val rng: Random = Random.Default) : Game {
    data class Pipe(val x: Int, val gapTop: Int, val passed: Boolean = false)

    var y = H / 2
        private set
    private var vy = 0
    private var started = false
    var pipes: List<Pipe> = emptyList()
        private set
    override var score = 0
        private set
    override var over = false
        private set

    override fun step(input: Input?) {
        if (over) return
        if (input == Input.SELECT || input == Input.UP) { started = true; vy = FLAP }
        if (!started) return
        vy += 1
        y += vy
        pipes = pipes.map { it.copy(x = it.x - SPEED) }
        if (pipes.isEmpty() || pipes.last().x < W - SPACING) pipes = pipes + Pipe(W, rng.nextInt(6, H - GAP - 6))
        val hit = pipes.any { it.x < BX + SIZE && it.x + PIPE_W > BX && (y < it.gapTop || y + SIZE > it.gapTop + GAP) }
        if (y < 0 || y + SIZE > H || hit) { over = true; return }
        pipes = pipes.map { if (!it.passed && it.x + PIPE_W <= BX) { score++; it.copy(passed = true) } else it }
            .filter { it.x + PIPE_W > 0 }
    }

    companion object {
        const val W = 100; const val H = 56; const val BX = 20; const val SIZE = 4
        const val PIPE_W = 8; const val GAP = 26; const val SPEED = 2; const val SPACING = 40; const val FLAP = -4
    }
}

/** The old 50 ms tick is two 30 Hz ticks (a little slower). */
object FlappyDef : GameDef("Flappy", "Flap through the gaps", ticksPerStep = 2) {
    override fun create(): Game = FlappyGame()
    @Composable override fun renderer() = GameRenderer { s, g, _ ->
        g as FlappyGame
        s.fill(GameColors.sky3)
        val u = Surface.W / FlappyGame.W.toFloat()
        s.rect(GameColors.gold, FlappyGame.BX * u, g.y * u, FlappyGame.SIZE * u, FlappyGame.SIZE * u)
        g.pipes.forEach {
            s.rect(GameColors.green5, it.x * u, 0f, FlappyGame.PIPE_W * u, it.gapTop * u)
            val bottom = (it.gapTop + FlappyGame.GAP) * u
            s.rect(GameColors.green5, it.x * u, bottom, FlappyGame.PIPE_W * u, Surface.H - bottom)
        }
    }
}
