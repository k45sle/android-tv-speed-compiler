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
import dev.speedcompiler.tv.ui.working.games.CosmicWormDef
import dev.speedcompiler.tv.ui.working.games.GameColors
import dev.speedcompiler.tv.ui.working.games.Input
import dev.speedcompiler.tv.ui.working.games.Surface
import java.io.File
import kotlin.random.Random
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * Records Cosmic Worm as a GIF: eats a star, passes a top/bottom gate and a side gate, eats another. A scripted run on a
 * fixed seed, stepped at 30 Hz like the player. Runs only with COSMIC_WORM_GIF=<absolute path> and -Proborazzi.record.
 */
@OptIn(ExperimentalTestApi::class)
class CosmicWormGifTest : ComposeTestBase() {
    @Test
    fun recordCosmicWorm() {
        val path = System.getenv("COSMIC_WORM_GIF")
        assumeNotNull(path)
        val game = CosmicWorm(Random(5)).apply { foodX = 6; foodY = 3 }
        rule.mainClock.autoAdvance = false // the game never goes idle
        rule.setContent {
            val renderer = CosmicWormDef.renderer()
            val surface = remember { Surface() }
            var frame by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                var n = 0
                while (true) withFrameNanos {
                    if (n++ % 2 != 0) return@withFrameNanos // 60 Hz frames, 30 Hz game
                    val x = game.bx[0]; val y = game.by[0]
                    if (x == 10 && y == 3) game.step(Input.UP)
                    else if (x == 10 && y == 5 && game.dir == 3) { game.foodX = 8; game.foodY = 5; game.step(Input.LEFT) }
                    else game.step(null)
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
        rule.onRoot().recordRoboVideo(rule, File(path), RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) { delay(6_000) }
    }
}
