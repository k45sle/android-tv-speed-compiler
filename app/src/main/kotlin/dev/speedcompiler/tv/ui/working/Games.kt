package dev.speedcompiler.tv.ui.working

import kotlin.random.Random

/** D-pad input as the games see it; the views map key events onto this. */
enum class Input { UP, DOWN, LEFT, RIGHT, SELECT }

/** Pure game state: [step] advances one tick with the latest input (or none). No Android types, so JVM tests drive it. */
interface Game {
    val score: Int
    val over: Boolean
    fun step(input: Input?)
}

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

/** Side-on runner: units are abstract, y is height above the ground. Select or Up jumps. */
class DinoGame(private val rng: Random = Random.Default) : Game {
    data class Cactus(val x: Int)

    var y = 0
        private set
    private var vy = 0
    var cacti: List<Cactus> = emptyList()
        private set
    override var score = 0
        private set
    override var over = false
        private set

    override fun step(input: Input?) {
        if (over) return
        if ((input == Input.SELECT || input == Input.UP) && y == 0) vy = JUMP
        if (y > 0 || vy > 0) {
            y += vy
            vy -= 1
            if (y <= 0) { y = 0; vy = 0 }
        }
        cacti = cacti.map { Cactus(it.x - SPEED) }
        if (cacti.isEmpty() || cacti.last().x < WIDTH - MIN_GAP - rng.nextInt(MIN_GAP)) cacti = cacti + Cactus(WIDTH)
        if (cacti.any { it.x < X + SIZE && it.x + CACTUS_W > X && y < CACTUS_H }) { over = true; return }
        score += cacti.count { it.x + CACTUS_W <= X }
        cacti = cacti.filter { it.x + CACTUS_W > X }
    }

    companion object {
        const val WIDTH = 100; const val X = 10; const val SIZE = 4
        const val CACTUS_W = 4; const val CACTUS_H = 12
        const val SPEED = 2; const val JUMP = 7; const val MIN_GAP = 30
    }
}

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
