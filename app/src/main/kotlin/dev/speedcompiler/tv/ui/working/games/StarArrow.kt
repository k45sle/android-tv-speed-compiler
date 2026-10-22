package dev.speedcompiler.tv.ui.working.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import dev.speedcompiler.tv.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Flappy-style flyer in art pixels (320x180) at 30 Hz. Select or Up flaps; each hit costs a shield cell and three end the run. */
class StarArrow(private val rng: Random = Random.Default) : Game {
    class Rock(val kind: Int, val dx: Float, val y: Float, val phase: Float, val spin: Float)
    class Column(var x: Float, val gap: Int, val rocks: ArrayList<Rock>) { var passed = false }
    class Pop(val x: Float, val y: Float, var t: Float = 0f)
    class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Color, var t: Float = 0f)

    var t = 0f; private set
    /** Distance flown, in art pixels. */
    var x = 0f; private set
    var y = 80f; private set
    var vy = 0f; private set
    var started = false; private set
    /** Seconds since the last flap (or ground bounce). */
    var flap = 9f; private set
    val columns = ArrayList<Column>().apply { add(newColumn(260f, 80)) }
    val pops = ArrayList<Pop>()
    val sparks = ArrayList<Spark>()
    var shield = 3; private set
    /** Seconds left of blinking invulnerability. */
    var inv = 0f; private set
    /** Seconds since the last hit; large while none. */
    var hitT = 9f; private set
    /** Seconds since the last cell was lost; 0 while alive. */
    var dead = 0f; private set
    override var score = 0; private set
    override val over get() = shield <= 0
    override val canRestart get() = over && dead > 0.6f

    private fun newColumn(cx: Float, gap: Int): Column {
        val rocks = ArrayList<Rock>()
        // Rocks stack from the gap edge to past the screen edge, above and below; each gets its own spin and phase.
        for (dir in intArrayOf(-1, 1)) {
            var ry = (gap + dir * GAP / 2).toFloat()
            val to = if (dir < 0) -12f else GROUND + 6f
            while (if (dir < 0) ry > to else ry < to) {
                val k = rng.nextInt(3); val r = RADIUS[k]
                ry += dir * r * 0.9f
                rocks.add(Rock(k, 13f + (rng.nextFloat() - 0.5f) * 10f, ry, rng.nextFloat() * 8f, (if (rng.nextFloat() < 0.5f) -1 else 1) * (4f + rng.nextFloat() * 6f)))
                ry += dir * r * 0.8f
            }
        }
        return Column(cx, gap, rocks)
    }

    private fun burst(n: Int, speed: Float, hot: Boolean) {
        for (i in 0 until n) {
            val a = i.toFloat() / n * 6.2832f
            val c = if (hot) HOT[i % 4] else COLD[i % 2]
            sparks.add(Spark(BX, y, cos(a) * speed * (0.6f + (i % 3) * 0.2f), sin(a) * speed - 15f, c))
        }
    }

    override fun step(input: Input?) {
        t += DT; flap += DT
        for (p in pops) p.t += DT
        pops.removeAll { it.t >= 0.6f }
        for (s in sparks) { s.t += DT; s.x += s.vx * DT; s.y += s.vy * DT; s.vy += 200f * DT }
        sparks.removeAll { it.t >= 0.8f }
        if (over) { dead += DT; return }
        hitT += DT; inv = max(0f, inv - DT)
        if (input == Input.SELECT || input == Input.UP) { started = true; vy = FLAP; flap = 0f }
        if (!started) return
        x += SPEED * DT
        for (c in columns) {
            c.x -= SPEED * DT
            if (!c.passed && c.x + COL_W < BX - HALF) { c.passed = true; score++; pops.add(Pop(BX, y - 12f)) }
        }
        if (columns[0].x < -30f) columns.removeAt(0)
        val last = columns.last()
        if (last.x < Surface.W - SPACING + 0f) columns.add(newColumn(last.x + SPACING, rng.nextInt(46, 116)))
        vy = min(MAX_VY, vy + GRAVITY * DT); y += vy * DT
        if (y < HALF) { y = HALF; vy = max(0f, vy) } // the top edge is a soft ceiling, not a hit
        var hit = false
        for (c in columns) for (r in c.rocks) if (hypot(c.x + r.dx - BX, r.y - y) < RADIUS[r.kind] * 0.85f + 4f) hit = true
        val ground = y + HALF > GROUND
        if (ground) { y = GROUND - HALF; vy = BOUNCE; flap = 0f } // the moon surface throws the ship back up
        if ((hit || ground) && inv <= 0f) {
            shield--; inv = 1.2f; hitT = 0f
            if (shield > 0) burst(8, 50f, false) else { burst(24, 95f, true); dead = 1e-6f }
        }
    }

    companion object {
        const val DT = 1f / 30f
        const val BX = 72f
        const val HALF = 6f
        const val GROUND = 160
        const val GAP = 60
        const val COL_W = 31 // rightmost rock reach (dx 18 + radius 10, rounded up), so a column scores only once it can no longer hit
        const val SPACING = 120f
        const val SPEED = 70f
        const val GRAVITY = 520f
        const val MAX_VY = 220f
        const val FLAP = -170f
        const val BOUNCE = -190f
        val RADIUS = intArrayOf(5, 7, 10)
        private val COLD = arrayOf(GameColors.sky2, GameColors.white)
        private val HOT = arrayOf(GameColors.gold, GameColors.cream, GameColors.yellow, GameColors.red)
    }
}

object StarArrowDef : GameDef("Star Arrow", "Fly through the rocks") {
    /** Tests set this to make the obstacles repeatable. */
    var seed: Int? = null

    override fun create(): Game = StarArrow(seed?.let { Random(it) } ?: Random.Default)

    @Composable override fun renderer(): GameRenderer {
        val sky = ImageBitmap.imageResource(R.drawable.game_arrow_sky)
        val stars = ImageBitmap.imageResource(R.drawable.game_arrow_stars)
        val planet = ImageBitmap.imageResource(R.drawable.game_arrow_planet)
        val belt = ImageBitmap.imageResource(R.drawable.game_arrow_belt)
        val ground = ImageBitmap.imageResource(R.drawable.game_arrow_ground)
        val flame = ImageBitmap.imageResource(R.drawable.game_arrow_flame)
        val ship = ImageBitmap.imageResource(R.drawable.game_arrow_ship)
        val rocks = arrayOf(
            ImageBitmap.imageResource(R.drawable.game_arrow_rock_s),
            ImageBitmap.imageResource(R.drawable.game_arrow_rock_m),
            ImageBitmap.imageResource(R.drawable.game_arrow_rock_l),
        )
        return remember(sky, stars, planet, belt, ground, flame, ship, rocks[0], rocks[1], rocks[2]) {
            GameRenderer { s, g, best ->
                g as StarArrow
                s.shake = shakeX(g.dead)
                s.img(sky, 0f, 0f)
                s.tile(stars, g.x * 0.05f, 0f); s.tile(planet, g.x * 0.1f, 12f)
                s.tile(stars, g.x * 0.2f + 160f, 20f); s.tile(belt, g.x * 0.3f, 96f)
                for (i in 0 until g.columns.size) {
                    val c = g.columns[i]
                    for (j in 0 until c.rocks.size) {
                        val r = c.rocks[j]; val rr = StarArrow.RADIUS[r.kind]; val n = 2 * rr + 5
                        val fr = floor(r.phase + g.t * r.spin).toInt() and 7
                        s.img(rocks[r.kind], fr * n, 0, n, n, (c.x + r.dx - n / 2f).roundToInt().toFloat(),
                            (r.y - n / 2f + sin(g.t * 2f + r.phase) * 0.8f).roundToInt().toFloat())
                    }
                }
                s.tile(ground, g.x, StarArrow.GROUND.toFloat())
                val by = if (g.started) g.y else g.y + sin(g.t * 4f) * 2f
                if (g.over) explosion(s, g, by)
                else {
                    if (g.hitT < 0.3f) { ring(s, by, 12f, GameColors.sky2); ring(s, by, 13f, if (g.hitT < 0.15f) GameColors.white else GameColors.blue) }
                    val blink = g.inv > 0f && floor(g.t * 10f).toInt() % 2 == 1
                    if (!blink) {
                        val sy = by.roundToInt() - 7f; val sx = StarArrow.BX - 13f
                        if (g.flap < 0.27f) s.img(flame, (g.flap / 0.09f).toInt() * 9, 0, 9, 7, sx - 8f, sy + 4f)
                        val f = if (g.flap < 16f / 30f) (g.flap * 30f).toInt() else 16 + floor(g.t * 12f).toInt() % 12
                        s.img(ship, f * 25, 0, 25, 15, sx, sy)
                    }
                }
                for (i in 0 until g.sparks.size) {
                    val p = g.sparks[i]
                    s.rect(p.color, p.x.roundToInt().toFloat(), p.y.roundToInt().toFloat(), 2f, 2f)
                }
                for (i in 0 until g.pops.size) { val p = g.pops[i]; s.text("+1", p.x.toInt(), (p.y - p.t * 20f).roundToInt(), GameColors.cream) }
                s.shake = 0
                s.hud(g.score, best, dark = false)
                shieldBar(s, g)
                if (g.canRestart) s.banner("GAME OVER", "SCORE ", g.score, GameColors.pink)
            }
        }
    }

    private val COS = FloatArray(64) { cos(it / 64f * 6.2832f) }
    private val SIN = FloatArray(64) { sin(it / 64f * 6.2832f) }

    private fun ring(s: Surface, cy: Float, r: Float, c: Color) {
        for (a in 0 until 64) s.rect(c, (StarArrow.BX + COS[a] * r).roundToInt().toFloat(), (cy + SIN[a] * r).roundToInt().toFloat(), 1f, 1f)
    }

    /** A flash, growing fire rings, then smoke. */
    private fun explosion(s: Surface, g: StarArrow, cy: Float) {
        val d = g.dead
        if (d < 0.08f) s.rect(GameColors.cream2, StarArrow.BX - 10f, cy - 10f, 20f, 20f)
        if (d < 0.5f) {
            val r = 4f + d * 70f
            ring(s, cy, r, if (d < 0.15f) GameColors.cream2 else if (d < 0.3f) GameColors.yellow else GameColors.red)
            ring(s, cy, r * 0.7f, if (d < 0.25f) GameColors.yellow else GameColors.gold)
            ring(s, cy, r * 0.4f, GameColors.red)
        } else if (d < 1f) ring(s, cy, 30f + (d - 0.5f) * 10f, GameColors.grey7)
    }

    /**
     * Reactor shield gauge under the status line: a breathing core inside a ring of three arc cells with a highlight orbiting
     * the lit ones, and a percent readout. A lost cell flashes white; at one cell the gauge flickers and the readout blinks pink.
     */
    private fun shieldBar(s: Surface, g: StarArrow) {
        val cx = 12f; val cy = s.hudY.roundToInt() + 7f
        val low = g.shield == 1
        val flick = low && g.t % 0.7f < 0.1f
        val lamp = low && g.t % 0.5f < 0.25f
        val bright = sin(g.t * 4f) > 0f && !flick
        val age = if (g.over) g.dead else g.hitT // hitT stops once the run is over; dead keeps counting from the final hit
        val flash = age < 0.45f && floor(age / 0.06f).toInt() % 2 == 0
        val orbit = (g.t * 2.5f) % TAU
        for (i in RD.indices) {
            val d = RD[i]
            val c = when {
                d <= 3.6f -> if (g.over) GameColors.grey7 else if (d < 1.6f) (if (flick) GameColors.sky2 else GameColors.white) else if (bright) GameColors.sky1 else GameColors.sky2
                d <= 4.6f -> GameColors.outline
                d <= 5.6f -> if (g.over) GameColors.navy else GameColors.blue
                d <= 6.4f -> GameColors.outline
                d <= 9.4f -> {
                    val seg = RSEG[i]
                    if (seg < 0) GameColors.outline
                    else if (seg == g.shield && flash) GameColors.white
                    else if (seg < g.shield) {
                        val da = abs((RA[i] - orbit + 3 * PI_F) % TAU - PI_F)
                        if (flick) GameColors.blue else if (da < 0.35f) GameColors.cream2 else if (d < 7.4f) GameColors.sky1 else if (d < 8.4f) GameColors.sky2 else GameColors.sky3
                    } else if (d < 7.4f) GameColors.indigo else GameColors.navy
                }
                else -> GameColors.outline
            }
            s.rect(c, cx + RX[i], cy + RY[i], 1f, 1f)
        }
        val ry = cy - 3f
        s.rect(GameColors.outline, cx + 11f, ry, 22f, 9f)
        s.rect(GameColors.navy, cx + 12f, ry + 1f, 20f, 7f)
        val tc = if (g.over) GameColors.grey7 else if (lamp) GameColors.pink else GameColors.sky2
        s.number(g.shield * 100 / 3, 3, (cx + 14f).toInt(), (ry + 2f).toInt(), tc) // 0, 33, 66, 100
        s.text("%", (cx + 26f).toInt(), (ry + 2f).toInt(), tc)
    }

    private const val PI_F = 3.1415927f
    private const val TAU = 2 * PI_F

    // Ring pixels within radius 10.4 of the core, measured once: offset, distance, angle from the top (clockwise), arc cell or -1 in a gap.
    private val RX: FloatArray; private val RY: FloatArray; private val RD: FloatArray; private val RA: FloatArray; private val RSEG: IntArray
    init {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val ds = ArrayList<Float>(); val angs = ArrayList<Float>(); val segs = ArrayList<Int>()
        for (y in -10..10) for (x in -10..10) {
            val d = sqrt((x * x + y * y).toFloat())
            if (d > 10.4f) continue
            val a = (atan2(y.toFloat(), x.toFloat()) + PI_F / 2 + 2 * TAU) % TAU
            val third = TAU / 3; val seg = (a / third).toInt().coerceAtMost(2); val into = a - seg * third
            xs += x.toFloat(); ys += y.toFloat(); ds += d; angs += a; segs += if (into < 0.22f || into > third - 0.22f) -1 else seg
        }
        RX = xs.toFloatArray(); RY = ys.toFloatArray(); RD = ds.toFloatArray(); RA = angs.toFloatArray(); RSEG = segs.toIntArray()
    }
}
