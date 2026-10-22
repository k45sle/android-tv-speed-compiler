package dev.speedcompiler.tv.ui.working.games

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.imageResource
import dev.speedcompiler.tv.R
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Worm on a 15x7 board of 20 px cells. The field is closed by a force field with two gates per side; the two gates of a
 * side pair up and carry the worm to the far side. Moves every 0.17 s; the drawing slides between cells.
 */
class CosmicWorm(private val rng: Random = Random.Default) : Game {
    /** Body cells, head first; [px]/[py] are where each cell was before the last move, for the slide. */
    val bx = IntArray(CELLS); val by = IntArray(CELLS); val px = IntArray(CELLS); val py = IntArray(CELLS)
    var len = 4; private set
    /** Last moved direction: index into [DX]/[DY] (right, down, left, up). */
    var dir = 0; private set
    private var want = 0
    /** A second quick turn pressed within one step, played on the next step; -1 if none. */
    private var queued = -1
    var foodX = 0; internal set
    var foodY = 0; internal set
    var t = 0f; private set
    /** Seconds into the current cell step. */
    var acc = 0f; private set
    var moves = 0; private set
    /** Seconds since the crash; 0 while alive. */
    var dead = 0f; private set
    /** Seconds since the last star was eaten, or -1. */
    var sparkle = -1f; private set
    var sparkleX = 0; private set
    var sparkleY = 0; private set
    /** Seconds since each gate flashed (a pass lights both gates of its pair), or -1. */
    val flash = FloatArray(PORTALS) { -1f }
    override var score = 0; private set
    override val over get() = dead > 0f
    override val canRestart get() = dead > 0.4f

    init {
        for (i in 0 until len) { bx[i] = 4 - i; by[i] = 3; px[i] = bx[i]; py[i] = by[i] }
        placeFood()
    }

    /** Next cell from ([x],[y]) toward [d] as x + y * W, or -1 at the force field; gates wrap to the far side. */
    fun neighbour(x: Int, y: Int, d: Int): Int {
        var nx = x + DX[d]; var ny = y + DY[d]
        if (nx < 0 || nx >= W) { if (y != GATE_Y[0] && y != GATE_Y[1]) return -1; nx = (nx + W) % W }
        if (ny < 0 || ny >= H) { if (x != GATE_X[0] && x != GATE_X[1]) return -1; ny = (ny + H) % H }
        return nx + ny * W
    }

    private fun placeFood() {
        var free = 0
        for (c in 0 until CELLS) if (!onBody(c % W, c / W, len)) free++
        if (free == 0) { foodX = -1; foodY = -1; dead = 1e-6f; return }
        var k = rng.nextInt(free)
        for (c in 0 until CELLS) if (!onBody(c % W, c / W, len) && k-- == 0) { foodX = c % W; foodY = c / W; return }
    }

    private fun onBody(x: Int, y: Int, n: Int): Boolean {
        for (i in 0 until n) if (bx[i] == x && by[i] == y) return true
        return false
    }

    override fun step(input: Input?) {
        t += DT
        if (sparkle >= 0f) { sparkle += DT; if (sparkle > 0.4f) sparkle = -1f }
        for (i in flash.indices) if (flash[i] >= 0f) { flash[i] += DT; if (flash[i] > 0.4f) flash[i] = -1f }
        if (over) { dead += DT; return }
        val d = when (input) { Input.RIGHT -> 0; Input.DOWN -> 1; Input.LEFT -> 2; Input.UP -> 3; else -> -1 }
        // Reversing into the neck is ignored; a second turn within one step waits for the next step, so UP then LEFT is a quick U-turn.
        if (d >= 0) {
            if (want == dir) { if ((d + 2) % 4 != dir) want = d }
            else if (queued < 0 && d != want && (d + 2) % 4 != want) queued = d
        }
        acc += DT
        if (acc < STEP) return
        acc -= STEP
        dir = want
        if (queued >= 0) { want = queued; queued = -1 }
        val n = neighbour(bx[0], by[0], dir)
        // The tail cell frees up as the worm moves on, so it does not count.
        if (n < 0 || onBody(n % W, n / W, len - 1)) {
            dead = 1e-6f
            for (i in 0 until len) { px[i] = bx[i]; py[i] = by[i] }
            return
        }
        val nx = n % W; val ny = n / W
        if (abs(nx - bx[0]) > 1 || abs(ny - by[0]) > 1) {
            val pair = if (abs(nx - bx[0]) > 1) 2 + GATE_Y.indexOf(ny) else GATE_X.indexOf(nx)
            for (i in 0 until PORTALS) if (P_PAIR[i] == pair) flash[i] = 0f
        }
        for (i in 0 until len) { px[i] = bx[i]; py[i] = by[i] }
        val oldLen = len
        val eat = nx == foodX && ny == foodY
        if (eat) len++
        for (i in len - 1 downTo 1) { bx[i] = bx[i - 1]; by[i] = by[i - 1] }
        bx[0] = nx; by[0] = ny
        // A new tail cell grows out of the old tail cell.
        for (i in oldLen until len) { px[i] = px[oldLen - 1]; py[i] = py[oldLen - 1] }
        moves++
        if (eat) { score++; sparkle = 0f; sparkleX = nx; sparkleY = ny; placeFood() }
    }

    companion object {
        const val DT = 1f / 30f
        const val STEP = 0.17f
        const val W = 15
        const val H = 7
        const val CELLS = W * H
        val DX = intArrayOf(1, 0, -1, 0)
        val DY = intArrayOf(0, 1, 0, -1)
        val GATE_X = intArrayOf(4, 10)
        val GATE_Y = intArrayOf(1, 5)

        /** Gates: wide ones in the top and bottom walls, narrow ones in the side walls; twins share a pair index (= colour). */
        const val PORTALS = 8
        val P_WIDE = booleanArrayOf(true, true, true, true, false, false, false, false)
        val P_PAIR = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3)
        val P_X = intArrayOf(14 + 4 * 20, 14 + 4 * 20, 14 + 10 * 20, 14 + 10 * 20, 0, 310, 0, 310)
        val P_Y = intArrayOf(18, 169, 18, 169, 34 + 1 * 20, 34 + 1 * 20, 34 + 5 * 20, 34 + 5 * 20)
    }
}

object CosmicWormDef : GameDef("Cosmic Worm", "Eat stars, use the gates") {
    override fun create(): Game = CosmicWorm()

    @Composable override fun renderer(): GameRenderer {
        val board = ImageBitmap.imageResource(R.drawable.game_worm_board)
        val pad = ImageBitmap.imageResource(R.drawable.game_worm_pad)
        val star = ImageBitmap.imageResource(R.drawable.game_worm_star)
        val sparkle = ImageBitmap.imageResource(R.drawable.game_worm_sparkle)
        val portalW = ImageBitmap.imageResource(R.drawable.game_worm_portal_w)
        val portalN = ImageBitmap.imageResource(R.drawable.game_worm_portal_n)
        return remember(board, pad, star, sparkle, portalW, portalN) { WormRenderer(board, pad, star, sparkle, portalW, portalN) }
    }
}

/** Draws the worm as one tube of pixel-circle stamps along the interpolated centre line. Holds its scratch arrays, so it allocates nothing per frame. */
private class WormRenderer(
    val board: ImageBitmap, val pad: ImageBitmap, val star: ImageBitmap, val sparkle: ImageBitmap,
    val portalW: ImageBitmap, val portalN: ImageBitmap,
) : GameRenderer {
    private lateinit var s: Surface
    private var kx = FloatArray(128); private var ky = FloatArray(128)
    private var ptX = FloatArray(512); private var ptY = FloatArray(512)
    private var n = 0
    private var minX = 0f; private var maxX = 0f; private var minY = 0f; private var maxY = 0f

    override fun draw(s: Surface, g: Game, best: Int) {
        g as CosmicWorm
        this.s = s
        s.shake = shakeX(g.dead)
        s.img(board, 0f, 0f)
        val cx = 20 + 20 * g.foodX; val cy = 40 + 20 * g.foodY
        if (g.foodX >= 0) {
            s.img(pad, (cx - 11).toFloat(), (cy + 2).toFloat())
            s.img(star, (floor(g.t * 3f).toInt() % 2) * 9, 0, 9, 9, (cx - 4).toFloat(), (cy - 8 + BOB[floor(g.t * 5f).toInt() % 4]).toFloat())
        }
        val p = if (g.over) 1f else min(1f, g.acc / CosmicWorm.STEP)
        path(g, p)
        val travel = (g.moves + p) * 20f
        // The unwrapped line can sit one board away on both axes after two gate passes, so try all nine copies; most are culled.
        for (oy in -1..1) for (ox in -1..1) tube(g, ox * 300f, oy * 140f, travel)
        val pf = floor(g.t * 8f).toInt() % 4
        for (i in 0 until CosmicWorm.PORTALS) {
            val wide = CosmicWorm.P_WIDE[i]
            val w = if (wide) 13 else 10; val h = if (wide) 12 else 13
            s.img(if (wide) portalW else portalN, (CosmicWorm.P_PAIR[i] * 4 + pf) * w, 0, w, h, CosmicWorm.P_X[i].toFloat(), CosmicWorm.P_Y[i].toFloat())
            val f = g.flash[i]
            if (f >= 0f) s.img(sparkle, min(3, (f / 0.1f).toInt()) * 11, 0, 11, 11, (CosmicWorm.P_X[i] + if (wide) 1 else -1).toFloat(), (CosmicWorm.P_Y[i] + if (wide) 0 else 1).toFloat())
        }
        if (g.sparkle >= 0f) s.img(sparkle, min(3, (g.sparkle / 0.1f).toInt()) * 11, 0, 11, 11, (20 + 20 * g.sparkleX - 5).toFloat(), (40 + 20 * g.sparkleY - 12).toFloat())
        s.shake = 0
        s.rect(GameColors.outline, 255f, s.hudY - 2f, 63f, 9f) // plate under the HUD, which overlaps the top rail
        s.hud(g.score, best, dark = false)
        if (g.canRestart) s.banner("GAME OVER", "SCORE ", g.score, GameColors.pink)
    }

    private fun grow(a: FloatArray, need: Int) = if (a.size >= need) a else a.copyOf(max(need, a.size * 2))

    /** The centre line, unwrapped so it stays continuous through a gate, resampled about every 2 px into [ptX]/[ptY]. */
    private fun path(g: CosmicWorm, p: Float) {
        val len = g.len
        kx = grow(kx, len); ky = grow(ky, len)
        for (i in 0 until len) {
            val ax = (20 + 20 * g.px[i]).toFloat(); val ay = (40 + 20 * g.py[i]).toFloat()
            var bx = (20 + 20 * g.bx[i]).toFloat(); var by = (40 + 20 * g.by[i]).toFloat()
            if (bx - ax > 30) bx -= 300; if (ax - bx > 30) bx += 300
            if (by - ay > 30) by -= 140; if (ay - by > 30) by += 140
            kx[i] = ax + (bx - ax) * p; ky[i] = ay + (by - ay) * p
        }
        for (i in 1 until len) for (k in 0 until 4) {
            val dx = WRAP_X[k]; val dy = WRAP_Y[k]
            val qx = kx[i - 1]; val qy = ky[i - 1]
            val a = kx[i] + dx - qx; val b = ky[i] + dy - qy; val c = kx[i] - qx; val d = ky[i] - qy
            if (a * a + b * b < c * c + d * d) { kx[i] += dx; ky[i] += dy }
        }
        n = 0
        for (i in 0 until len - 1) {
            val dx = kx[i + 1] - kx[i]; val dy = ky[i + 1] - ky[i]
            val cnt = max(1, floor(sqrt(dx * dx + dy * dy) / 2f + 0.5f).toInt())
            ptX = grow(ptX, n + cnt + 1); ptY = grow(ptY, n + cnt + 1)
            for (k in 0 until cnt) { ptX[n] = kx[i] + dx * k / cnt; ptY[n] = ky[i] + dy * k / cnt; n++ }
        }
        ptX = grow(ptX, n + 1); ptY = grow(ptY, n + 1)
        ptX[n] = kx[len - 1]; ptY[n] = ky[len - 1]; n++
        minX = ptX[0]; maxX = minX; minY = ptY[0]; maxY = minY
        for (i in 1 until n) { minX = min(minX, ptX[i]); maxX = max(maxX, ptX[i]); minY = min(minY, ptY[i]); maxY = max(maxY, ptY[i]) }
    }

    private fun rad(i: Int): Int {
        val fromTail = (n - 1 - i) * 2
        return if (i < 3) 7 else if (fromTail < 24) max(3, floor(3f + fromTail / 8f + 0.5f).toInt()) else 6
    }

    private fun rnd(v: Float) = floor(v + 0.5f).toInt()

    /** One copy of the worm shifted by ([ox],[oy]); everything outside the force field is cut off, so a gate pass shows two halves. */
    private fun tube(g: CosmicWorm, ox: Float, oy: Float, travel: Float) {
        // 14 px covers the widest stamp, the shadow drop and the antennae.
        if (maxX + ox + 14 < FX0 || minX + ox - 14 > FX1 || maxY + oy + 14 < FY0 || minY + oy - 14 > FY1) return
        // Shadow, outline, belly side, body: each layer from the tail to the head.
        for (layer in 0 until 4) for (i in n - 1 downTo 0) {
            val r = rad(i) + if (layer == 1) 1 else 0
            stamp(LAYER_COLOR[layer], r, ptX[i] + ox, ptY[i] + oy + LAYER_DY[layer])
        }
        for (i in n - 1 downTo 0) {
            val r = max(1, rnd(rad(i) * 0.4f))
            val x = rnd(ptX[i] + ox) - r - 1; val y = rnd(ptY[i] + oy) - r - 3
            cut(STAMPS[3][r], x, y, 2 * r + 1, 2 * r + 1)
        }
        // Star specks are tied to the body material, so they ride along as it moves.
        for (i in 3 until n) {
            val m = floor(i * 2f - travel).toInt()
            if (Math.floorMod(m, 13) == 0) dot(if ((m / 13) % 2 != 0) GameColors.white else GameColors.yellow, rnd(ptX[i] + ox), rnd(ptY[i] + oy) - 1, 1, 1)
        }
        head(g, ptX[0] + ox, ptY[0] + oy)
    }

    private fun stamp(color: Int, r: Int, cx: Float, cy: Float) = cut(STAMPS[color][r], rnd(cx) - r, rnd(cy) - r, 2 * r + 1, 2 * r + 1)

    /** Draws [im] (all of it) at ([x],[y]) cut to the field. */
    private fun cut(im: ImageBitmap, x: Int, y: Int, w: Int, h: Int) {
        val x0 = max(x, FX0); val y0 = max(y, FY0); val x1 = min(x + w, FX1); val y1 = min(y + h, FY1)
        if (x1 > x0 && y1 > y0) s.img(im, x0 - x, y0 - y, x1 - x0, y1 - y0, x0.toFloat(), y0.toFloat())
    }

    private fun dot(c: androidx.compose.ui.graphics.Color, x: Int, y: Int, w: Int, h: Int) {
        val x0 = max(x, FX0); val y0 = max(y, FY0); val x1 = min(x + w, FX1); val y1 = min(y + h, FY1)
        if (x1 > x0 && y1 > y0) s.rect(c, x0.toFloat(), y0.toFloat(), (x1 - x0).toFloat(), (y1 - y0).toFloat())
    }

    /** Antennae with glowing tips, eyes looking toward the move, pink cheeks. */
    private fun head(g: CosmicWorm, hx0: Float, hy0: Float) {
        val dx = CosmicWorm.DX[g.dir]; val dy = CosmicWorm.DY[g.dir]
        val hx = hx0; val hy = hy0 - 1f
        for (sd in -1..1 step 2) {
            val ax = hx + sd * 3 - dx * 2; val wob = sin(g.t * 8f + sd) * 1.2f
            dot(GameColors.outline, rnd(ax), rnd(hy - 10f), 1, 4)
            dot(GameColors.yellow, rnd(ax + wob - 1f), rnd(hy - 12f), 2, 2)
        }
        if (g.over && floor(g.dead * 8f).toInt() % 2 == 1) { cheeks(hx, hy); return } // blinks out after the crash
        for (sd in -1..1 step 2) {
            val ex = if (dx != 0) hx + dx * 2 + (if (sd < 0) -1 else 2) else hx + sd * 3
            val ey = if (dx != 0) hy - 3 else hy - 2 + dy
            val x = rnd(ex); val y = rnd(ey)
            dot(GameColors.white, x - 1, y - 1, 3, 3)
            if (g.over) { // dead eyes: four corner pixels
                dot(GameColors.outline, x - 1, y - 1, 1, 1); dot(GameColors.outline, x + 1, y + 1, 1, 1)
                dot(GameColors.outline, x + 1, y - 1, 1, 1); dot(GameColors.outline, x - 1, y + 1, 1, 1)
            } else dot(GameColors.outline, x + sign(dx.toFloat()).toInt(), y + max(0, dy), 1, 1)
        }
        cheeks(hx, hy)
    }

    private fun cheeks(hx: Float, hy: Float) {
        dot(GameColors.pink, rnd(hx - 5f), rnd(hy + 1f), 2, 1); dot(GameColors.pink, rnd(hx + 4f), rnd(hy + 1f), 2, 1)
    }

    private companion object {
        const val FX0 = 8; const val FY0 = 28; const val FX1 = 312; const val FY1 = 172
        val BOB = intArrayOf(0, -1, -2, -1)
        val WRAP_X = floatArrayOf(300f, -300f, 0f, 0f)
        val WRAP_Y = floatArrayOf(0f, 0f, 140f, -140f)
        /** Layers: shadow, outline, belly side, body; indexes into [STAMPS] (outline, indigo, blue, sky2). */
        val LAYER_COLOR = intArrayOf(0, 0, 1, 2)
        val LAYER_DY = floatArrayOf(4f, 0f, 0f, -1f)

        /** Filled pixel circles of radius 1..8 in the four worm colours, built once. */
        val STAMPS: Array<Array<ImageBitmap>> by lazy {
            val cols = intArrayOf(GameColors.outline.toArgb(), GameColors.indigo.toArgb(), GameColors.blue.toArgb(), GameColors.sky2.toArgb())
            Array(4) { c ->
                Array(9) { r ->
                    val size = 2 * r + 1
                    val px = IntArray(size * size) { i ->
                        val x = i % size - r; val y = i / size - r
                        if (x * x + y * y <= r * r + r * 0.6f) cols[c] else 0
                    }
                    Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            }
        }
    }
}
