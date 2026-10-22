package dev.speedcompiler.tv.ui.working

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoboVideoOptions
import com.github.takahirom.roborazzi.recordRoboVideo
import dev.speedcompiler.tv.ComposeTestBase
import dev.speedcompiler.tv.ui.working.games.CosmicWorm
import dev.speedcompiler.tv.ui.working.games.StarArrow
import dev.speedcompiler.tv.ui.working.games.StarArrowDef
import dev.speedcompiler.tv.ui.working.games.GameColors
import dev.speedcompiler.tv.ui.working.games.Input
import dev.speedcompiler.tv.ui.working.games.Surface
import java.io.File
import kotlin.random.Random
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * Records Star Arrow as a GIF: flies through two rock columns, then stops flapping to take a hit and bounce off the moon. A
 * scripted run on a fixed seed, stepped at 30 Hz like the player. Runs only with STAR_ARROW_GIF=<absolute path> and -Proborazzi.record.
 */
@OptIn(ExperimentalTestApi::class)
class StarArrowGifTest : ComposeTestBase() {
    @Test
    fun recordStarArrow() {
        val path = System.getenv("STAR_ARROW_GIF")
        assumeNotNull(path)
        val game = StarArrow(Random(7))
        rule.mainClock.autoAdvance = false // the game never goes idle
        rule.setContent {
            val renderer = StarArrowDef.renderer()
            val surface = remember { Surface() }
            var frame by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                var n = 0
                while (true) withFrameNanos {
                    if (n++ % 2 != 0) return@withFrameNanos // 60 Hz frames, 30 Hz game
                    val next = game.columns.firstOrNull { it.x + StarArrow.COL_W > StarArrow.BX - 4f }
                    val giveUp = game.score == 2 && next != null && next.x < StarArrow.BX + 20f // let the third column hit
                    val flap = !giveUp && (next == null || game.y > next.gap + 4f) && game.vy > -20f
                    game.step(if (flap || !game.started) Input.SELECT else null)
                    frame++
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                frame
                drawRect(GameColors.sky3)
                surface.frame(this, 0f) { renderer.draw(surface, game, 12) }
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onRoot().recordRoboVideo(rule, File(path), RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) { delay(9_000) }
    }
}
