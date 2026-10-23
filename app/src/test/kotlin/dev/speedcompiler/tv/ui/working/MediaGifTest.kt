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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoboVideoOptions
import com.github.takahirom.roborazzi.recordRoboVideo
import dev.speedcompiler.tv.ComposeTestBase
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.ui.working.games.CosmicWorm
import dev.speedcompiler.tv.ui.working.games.CosmicWormDef
import dev.speedcompiler.tv.ui.working.games.GameColors
import dev.speedcompiler.tv.ui.working.games.Game
import dev.speedcompiler.tv.ui.working.games.GameDef
import dev.speedcompiler.tv.ui.working.games.Input
import dev.speedcompiler.tv.ui.working.games.StarArrow
import dev.speedcompiler.tv.ui.working.games.StarArrowDef
import dev.speedcompiler.tv.ui.working.games.Surface
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Records the README GIFs of the Working screen and its games. Each test runs only with -Pmedia and its env var set to an
 * absolute output path (ARCADE_GIF, WORKING_LOGO_GIF, ALIEN_RUN_GIF, COSMIC_WORM_GIF, STAR_ARROW_GIF), so it never writes in
 * normal builds. The two scripted games are a fixed seed stepped at 30 Hz like the player, and need -Proborazzi.record too.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
class MediaGifTest : ComposeTestBase() {
    private fun outFile(env: String): File {
        assumeTrue(System.getProperty("media") != null)
        val path = System.getenv(env)
        assumeNotNull(path)
        return File(path!!)
    }

    private fun workingHost() {
        val queue = MutableStateFlow(listOf(QueueEntry(QueueItem(1, "a.alpha", QueueSource.MANUAL, QueueState.RUNNING, enqueuedAt = 0, runNow = true), 0, Eta.None)))
        rule.mainClock.autoAdvance = false // the previews and games never go idle
        rule.setContent { SpeedTheme { WorkingHost("a.alpha", AppLook("Alpha"), queue, MutableStateFlow(emptyList()), { rule.mainClock.currentTime + 1_000_000L }) { } } }
        rule.mainClock.advanceTimeByFrame()
    }

    /** [step] drives [game] at 30 Hz from 60 Hz frames, then the clip runs [millis] of game time. */
    private fun scripted(file: File, def: GameDef, game: Game, millis: Long, step: () -> Unit) {
        rule.mainClock.autoAdvance = false // the game never goes idle
        rule.setContent {
            val renderer = def.renderer()
            val surface = remember { Surface() }
            var frame by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                var n = 0
                while (true) withFrameNanos {
                    if (n++ % 2 != 0) return@withFrameNanos
                    step()
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
        rule.onRoot().recordRoboVideo(rule, file, RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) { delay(millis) }
    }

    @Test fun recordArcade() {
        val file = outFile("ARCADE_GIF")
        workingHost()
        fun right(from: String) = rule.onNodeWithContentDescription(from).performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        rule.onRoot().recordRoboVideo(rule, file, RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) {
            delay(1_500)
            right("Play Cosmic Worm. Eat stars, use the gates")
            delay(1_500)
            right("Play Alien Run. Jump the cacti")
            delay(1_500)
        }
    }

    /** One 3 s loop of the animated logo banner. */
    @Test fun recordWorkingLogo() {
        val file = outFile("WORKING_LOGO_GIF")
        workingHost()
        rule.onRoot().recordRoboVideo(rule, file, RoboVideoOptions(fps = 12, settleTimeoutMillis = 100)) { delay(3_000) }
    }

    /** One jump, then a hit. */
    @Test fun recordAlienRun() {
        val file = outFile("ALIEN_RUN_GIF")
        workingHost()
        rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti").performSemanticsAction(SemanticsActions.OnClick)
        rule.onRoot().recordRoboVideo(rule, file, RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) {
            delay(1_700) // the first cactus reaches the alien at about 2.1 s
            rule.onNodeWithContentDescription("Alien Run game. Arrow keys move, Select to act or restart, Back to stop playing.")
                .performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            delay(4_500) // the second cactus is not jumped
        }
    }

    /** Eats a star, passes a top/bottom gate and a side gate, eats another. */
    @Test fun recordCosmicWorm() {
        val file = outFile("COSMIC_WORM_GIF")
        val game = CosmicWorm(Random(5)).apply { foodX = 6; foodY = 3 }
        scripted(file, CosmicWormDef, game, 6_000) {
            val x = game.bx[0]; val y = game.by[0]
            if (x == 10 && y == 3) game.step(Input.UP)
            else if (x == 10 && y == 5 && game.dir == 3) { game.foodX = 8; game.foodY = 5; game.step(Input.LEFT) }
            else game.step(null)
        }
    }

    /** Flies through two rock columns, then stops flapping to take a hit and bounce off the moon. */
    @Test fun recordStarArrow() {
        val file = outFile("STAR_ARROW_GIF")
        val game = StarArrow(Random(7))
        scripted(file, StarArrowDef, game, 9_000) {
            val next = game.columns.firstOrNull { it.x + StarArrow.COL_W > StarArrow.BX - 4f }
            val giveUp = game.score == 2 && next != null && next.x < StarArrow.BX + 20f // let the third column hit
            val flap = !giveUp && (next == null || game.y > next.gap + 4f) && game.vy > -20f
            game.step(if (flap || !game.started) Input.SELECT else null)
        }
    }
}
