package dev.speedcompiler.tv.ui.working.games

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** D-pad input as the games see it; the views map key events onto this. */
enum class Input { UP, DOWN, LEFT, RIGHT, SELECT }

/** Pure game state: [step] advances one fixed tick with the latest input (or none). No Android types, so JVM tests drive it. */
interface Game {
    val score: Int
    val over: Boolean
    /** True once a finished run may be restarted with Select; games with a death animation wait for it. */
    val canRestart: Boolean get() = over
    fun step(input: Input?)
}

/** Draws a game onto the [Surface]; [best] is the saved best score. Must not allocate per call. */
fun interface GameRenderer {
    fun draw(s: Surface, g: Game, best: Int)
}

/** One entry per playable game, so each game lives in its own file. [ticksPerStep] is how many 30 Hz ticks one [Game.step] takes. */
abstract class GameDef(val title: String, val hint: String, val ticksPerStep: Int = 1) {
    abstract fun create(): Game
    /** Loads the game's sheets; called from composition. */
    @Composable abstract fun renderer(): GameRenderer
}

/** The locked 32-color game palette. Every opaque pixel of every game_*.png must be one of these. */
object GameColors {
    private fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)
    val outline = c(0x0B2017); val navy = c(0x1B2147); val logo = c(0x153C29); val indigo = c(0x34306A)
    val green7 = c(0x2E6B4A); val green5 = c(0x4F9A5E); val green3 = c(0x6FD08C); val sage = c(0x9DBFA9)
    val grass = c(0xA8D45A); val cream = c(0xF2E6C4); val cream2 = c(0xFFF6DC); val gold = c(0xE8A33A)
    val yellow = c(0xF2C14E); val white = c(0xFFFFFF); val grey2 = c(0xC7CCD1); val grey5 = c(0x8A929B)
    val grey7 = c(0x4D5560); val sky1 = c(0xBDE6EE); val sky2 = c(0x8CCDE6); val sky3 = c(0x5AA8D6)
    val blue = c(0x3B6FA8); val sand1 = c(0xF5D58A); val sand2 = c(0xE4B464); val sand3 = c(0xC98E45)
    val brown = c(0x9C6330); val brown2 = c(0x6B3F22); val mesa1 = c(0xE07A4E); val mesa2 = c(0xB5543A)
    val mesa3 = c(0x7E3A30); val red = c(0xD8403A); val pink = c(0xFF9585); val plum = c(0x5A4A7A)

    val all = listOf(
        outline, navy, logo, indigo, green7, green5, green3, sage, grass, cream, cream2, gold, yellow, white, grey2, grey5,
        grey7, sky1, sky2, sky3, blue, sand1, sand2, sand3, brown, brown2, mesa1, mesa2, mesa3, red, pink, plum,
    )
}

/**
 * Turns elapsed vsync times into fixed 1/30 s steps. A late vsync never changes game speed (the remainder carries over),
 * and a long stall is capped at [maxSteps] so the game does not fast-forward afterwards.
 */
class StepClock(private val stepNanos: Long = 33_333_333L, private val maxSteps: Int = 4) {
    private var last = -1L
    private var acc = 0L

    /** Steps due at vsync time [now]. At 60 Hz this is 1 on every second vsync, which is also when a frame should be drawn. */
    fun due(now: Long): Int {
        if (last < 0) { last = now; return 0 }
        acc += min(now - last, stepNanos * maxSteps)
        last = now
        var n = 0
        // 4 ms of slack so jitter around the second vsync does not push a step to the third.
        while (acc >= stepNanos - SLACK && n < maxSteps) { acc -= stepNanos; n++ }
        return n
    }

    private companion object { const val SLACK = 4_000_000L }
}

/** Alternating screen shake for time [t] seconds since the hit: one entry per 30 fps frame, settling after 0.2 s. */
fun shakeX(t: Float): Int = if (t > 0f && t < 0.2f) SHAKE.getOrElse(floor(t * 30f).toInt()) { 0 } else 0
private val SHAKE = intArrayOf(2, -2, 2, -1, 1, -1)

/**
 * A 320x180 art-pixel screen drawn at a whole-number scale, centred in the canvas. One instance is reused for every frame
 * (no allocation per draw). Positions are art-pixel floats, rounded to screen pixels at draw time.
 */
class Surface {
    private lateinit var d: DrawScope
    var scale = 1; private set
    private var ox = 0f
    private var oy = 0f
    /** Art-pixel offset added to everything drawn until reset; the hit shake. */
    var shake = 0
    /** Top of the HUD row in art pixels, below the status line. */
    var hudY = 5f; private set

    inline fun frame(scope: DrawScope, topPx: Float, body: () -> Unit) {
        begin(scope, topPx)
        scope.clipRect(left, top, left + W * scale, top + H * scale) { body() }
    }
    val left get() = ox
    val top get() = oy

    fun begin(scope: DrawScope, topPx: Float) {
        d = scope
        scale = max(1, floor(min(scope.size.width / W, scope.size.height / H)).toInt())
        ox = ((scope.size.width - W * scale) / 2f).roundToInt().toFloat()
        oy = ((scope.size.height - H * scale) / 2f).roundToInt().toFloat()
        hudY = 5f + topPx / scale
        shake = 0
    }

    private fun px(v: Float) = (v * scale).roundToInt()

    fun img(im: ImageBitmap, sx: Int, sy: Int, sw: Int, sh: Int, x: Float, y: Float, w: Int = sw, h: Int = sh) {
        d.drawImage(
            im, IntOffset(sx, sy), IntSize(sw, sh),
            IntOffset(ox.toInt() + px(x + shake), oy.toInt() + px(y)), IntSize(w * scale, h * scale),
            filterQuality = FilterQuality.None,
        )
    }

    fun img(im: ImageBitmap, x: Float, y: Float) = img(im, 0, 0, im.width, im.height, x, y)

    /** A looping layer scrolled by [x] art pixels: two copies side by side. */
    fun tile(im: ImageBitmap, x: Float, y: Float) {
        val o = x % im.width
        img(im, -o, y); img(im, im.width - o, y)
    }

    fun rect(c: Color, x: Float, y: Float, w: Float, h: Float) {
        val l = px(x + shake); val t = px(y)
        d.drawRect(c, Offset(ox + l, oy + t), Size((px(x + shake + w) - l).toFloat(), (px(y + h) - t).toFloat()))
    }

    /** Fills the whole art screen (inside the letterbox). */
    fun fill(c: Color) = rect(c, 0f, 0f, W.toFloat(), H.toFloat())

    fun text(s: String, x: Int, y: Int, c: Color) {
        var cx = x
        for (i in s.indices) { glyph(FONT[s[i].code.coerceAtMost(127)], cx, y, c); cx += 4 }
    }

    /** [n] zero-padded to [digits] digits, without building a string. */
    fun number(n: Int, digits: Int, x: Int, y: Int, c: Color) {
        var div = 1
        repeat(digits - 1) { div *= 10 }
        var cx = x
        while (div > 0) { glyph(FONT['0'.code + (n / div) % 10], cx, y, c); cx += 4; div /= 10 }
    }

    private fun glyph(bits: Int, x: Int, y: Int, c: Color) {
        for (i in 0 until 15) if (bits and (1 shl (14 - i)) != 0) rect(c, (x + i % 3).toFloat(), (y + i / 3).toFloat(), 1f, 1f)
    }

    /** Two-line centred box; [sub] is followed by a 4-digit [num] when it is not negative. */
    fun banner(title: String, sub: String, num: Int, c: Color) {
        val subW = (sub.length + if (num >= 0) 4 else 0) * 4 - 1
        val titleW = title.length * 4 - 1
        val w = max(titleW, subW) + 12; val h = 2 * 8 + 8; val x = (W - w) / 2; val y = 74
        rect(GameColors.outline, x - 1f, y - 1f, w + 2f, h + 2f); rect(GameColors.logo, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
        text(title, x + (w - titleW) / 2, y + 5, c)
        text(sub, x + (w - subW) / 2, y + 13, GameColors.cream)
        if (num >= 0) number(num, 4, x + (w - subW) / 2 + sub.length * 4, y + 13, GameColors.cream)
    }

    /** Score top right and best to its left, with [dark] text for light backgrounds. */
    fun hud(score: Int, best: Int, dark: Boolean) {
        val y = hudY.roundToInt()
        text("BEST ", 258, y, if (dark) GameColors.logo else GameColors.gold)
        number(best, 4, 258 + 20, y, if (dark) GameColors.logo else GameColors.gold)
        number(score, 4, 301, y, if (dark) GameColors.outline else GameColors.cream)
    }

    companion object {
        /** Bits of the 3x5 glyph for [ch] (0 when it has none), for drawing outside a [Surface] frame. */
        fun glyph(ch: Char) = FONT[ch.code.coerceAtMost(127)]

        const val W = 320
        const val H = 180

        /** 3x5 glyphs as 15-bit masks, row by row, left to right (bit 14 first). */
        private val FONT = IntArray(128).also { f ->
            val g = mapOf(
                '0' to "111101101101111", '1' to "010110010010111", '2' to "111001111100111", '3' to "111001111001111",
                '4' to "101101111001001", '5' to "111100111001111", '6' to "111100111101111", '7' to "111001001001001",
                '8' to "111101111101111", '9' to "111101111001111", 'A' to "010101111101101", 'B' to "100100110101110",
                'C' to "111100100100111", 'D' to "110101101101110", 'E' to "111100110100111", 'G' to "111100101101111",
                'H' to "101101111101101", 'I' to "111010010010111", 'K' to "101101110101101", 'L' to "100100100100111",
                'M' to "101111111101101", 'N' to "110101101101101", 'O' to "111101101101111", 'P' to "111101111100100",
                'R' to "110101110101101", 'S' to "011100010001110", 'T' to "111010010010010", 'U' to "101101101101111",
                'V' to "101101101101010", 'W' to "101101111111101", 'Y' to "101101010010010", '+' to "000010111010000", '%' to "101001010100101",
            )
            g.forEach { (ch, bits) -> f[ch.code] = bits.toInt(2) }
        }
    }
}
