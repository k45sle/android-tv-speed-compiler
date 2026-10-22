package dev.speedcompiler.tv.ui.working.games

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import dev.speedcompiler.tv.R
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Side-on runner in art pixels (320x180), stepped at 30 Hz. Select or Up jumps; three hits end the run. */
class AlienRun(private val rng: Random = Random.Default) : Game {
    class Cactus(var x: Float, val kind: Int)
    class Dust(val x: Float, var t: Float = 0f)

    var t = 0f; private set
    /** Distance run, in art pixels. */
    var x = 0f; private set
    var speed = BASE_SPEED; private set
    /** Height above the ground: 0 or negative. */
    var y = 0f; private set
    var vy = 0f; private set
    var air = false; private set
    var land = 0f; private set
    val cacti = ArrayList<Cactus>().apply { add(Cactus(300f, 0)) }
    val dust = ArrayList<Dust>()
    var lives = 3; private set
    /** Seconds left of the hurt face. */
    var hurt = 0f; private set
    /** Seconds left of blinking invulnerability. */
    var inv = 0f; private set
    /** Seconds since the third hit; 0 while alive. */
    var dead = 0f; private set
    override var score = 0; private set
    override val over get() = lives <= 0
    override val canRestart get() = over && dead > 0.25f

    override fun step(input: Input?) {
        t += DT
        if (over) { dead += DT; return }
        x += speed * DT
        speed = BASE_SPEED + min(60f, score / 10f * 6f)
        score = floor(x / 40f).toInt()
        hurt = max(0f, hurt - DT); inv = max(0f, inv - DT)
        for (c in cacti) c.x -= speed * DT
        if (cacti[0].x < -40f) cacti.removeAt(0)
        val last = cacti.last()
        if (last.x < 200f) cacti.add(Cactus(last.x + 150f + rng.nextFloat() * 140f, rng.nextInt(3)))
        if ((input == Input.SELECT || input == Input.UP) && !air) { vy = JUMP; air = true }
        if (air) {
            vy += GRAVITY * DT; y += vy * DT
            if (y >= 0f) { y = 0f; vy = 0f; air = false; land = 0.12f; dust.add(Dust(50f)); dust.add(Dust(68f)) }
        }
        land = max(0f, land - DT)
        for (d in dust) d.t += DT
        dust.removeAll { it.t >= 0.3f }
        if (inv > 0f) return
        for (c in cacti) {
            val s = SHAPES[c.kind]
            if (c.x + 2 < 66f && c.x + s.w - 2 > 54f && y > -s.h + 3) {
                lives--; hurt = 0.25f; inv = 1.5f
                if (!air) { vy = KNOCKBACK; air = true } // a small hop so the hit reads as a reaction, not a freeze
                if (over) dead = 1e-6f
                break
            }
        }
    }

    /** Where a cactus sits in game_cacti.png: source rectangle of the cactus pixels in its third of the sheet. */
    class Shape(val sx: Int, val sy: Int, val w: Int, val h: Int)

    companion object {
        const val DT = 1f / 30f
        const val BASE_SPEED = 110f
        const val GRAVITY = 900f
        const val JUMP = -290f
        const val KNOCKBACK = -150f
        const val GROUND = 148
        val SHAPES = listOf(Shape(3, 5, 8, 14), Shape(17, 0, 11, 19), Shape(30, 7, 15, 12))
    }
}

object AlienRunDef : GameDef("Alien Run", "Jump the cacti") {
    /** Tests set this to make the obstacles repeatable. */
    var seed: Int? = null

    override fun create(): Game = AlienRun(seed?.let { Random(it) } ?: Random.Default)

    @Composable override fun renderer(): GameRenderer {
        val sky = ImageBitmap.imageResource(R.drawable.game_sky)
        val clouds = ImageBitmap.imageResource(R.drawable.game_clouds)
        val mesas = ImageBitmap.imageResource(R.drawable.game_mesas)
        val dunes = ImageBitmap.imageResource(R.drawable.game_dunes)
        val ground = ImageBitmap.imageResource(R.drawable.game_ground)
        val cacti = ImageBitmap.imageResource(R.drawable.game_cacti)
        val shadow = ImageBitmap.imageResource(R.drawable.game_shadow)
        val dust = ImageBitmap.imageResource(R.drawable.game_dust)
        val runner = ImageBitmap.imageResource(R.drawable.game_runner)
        val hearts = ImageBitmap.imageResource(R.drawable.game_hearts)
        val ufo = ImageBitmap.imageResource(R.drawable.game_ufo)
        return remember(sky, clouds, mesas, dunes, ground, cacti, shadow, dust, runner, hearts, ufo) {
            GameRenderer { s, g, best ->
                g as AlienRun
                val gy = AlienRun.GROUND
                s.shake = shakeX(if (g.over) g.dead else if (g.hurt > 0f) 0.2501f - g.hurt else 0f)
                s.img(sky, 0f, 0f)
                s.tile(clouds, g.x * 0.05f, 8f)
                val ux = 340f - (g.t * 16f) % 440f
                s.img(ufo, (floor(g.t * 4f).toInt() % 2) * 12, 0, 12, 7, ux, 60f + sin(g.t * 2f) * 3f)
                s.tile(mesas, g.x * 0.1f, 90f)
                s.tile(dunes, g.x * 0.3f, 122f)
                s.tile(ground, g.x, gy.toFloat())
                for (i in 0 until g.cacti.size) {
                    val c = g.cacti[i]; val sh = AlienRun.SHAPES[c.kind]
                    s.img(shadow, 0, 0, 18, 3, c.x - 2f, gy - 1f, sh.w + 4, 3)
                    s.img(cacti, sh.sx, sh.sy, sh.w, sh.h, c.x, (gy - sh.h + 1).toFloat())
                }
                val h = -g.y
                s.img(shadow, (if (h < 12f) 0 else if (h < 30f) 1 else 2) * 18, 0, 18, 3, 51f, gy - 1f)
                val f = if (g.over || g.hurt > 0f) 7 else if (g.air) (if (g.vy < 0f) 4 else 5)
                    else if (g.land > 0f) 6 else floor(g.t * 10f).toInt() % 4
                val blink = g.inv > 0f && g.hurt <= 0f && floor(g.t * 10f).toInt() % 2 == 1
                if (!blink) s.img(runner, f * 24, 0, 24, 24, 48f, gy - 23f + g.y)
                for (i in 0 until g.dust.size) {
                    val d = g.dust[i]
                    s.img(dust, min(2, (d.t / 0.1f).toInt()) * 6, 0, 6, 3, d.x, gy - 3f)
                }
                s.shake = 0
                for (i in 0 until 3) s.img(hearts, if (i < g.lives) 0 else 18, 0, 18, 18, 2f + i * 15, s.hudY - 6f)
                s.hud(g.score, best, dark = true)
                if (g.over && g.dead > 0.25f) s.banner("GAME OVER", "SCORE ", g.score, GameColors.pink)
            }
        }
    }
}
