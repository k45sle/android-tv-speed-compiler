package dev.speedcompiler.tv.ui.working

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.RoboVideoOptions
import com.github.takahirom.roborazzi.recordRoboVideo
import dev.speedcompiler.tv.ComposeTestBase
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Records Alien Run (one jump, then a hit) as a GIF. Runs only with ALIEN_RUN_GIF=<absolute path>, so it never writes in normal builds. */
@OptIn(ExperimentalTestApi::class)
class AlienRunGifTest : ComposeTestBase() {
    @Test
    fun recordAlienRun() {
        val path = System.getenv("ALIEN_RUN_GIF")
        assumeNotNull(path)
        val queue = MutableStateFlow(listOf(QueueEntry(QueueItem(1, "a.alpha", QueueSource.MANUAL, QueueState.RUNNING, enqueuedAt = 0, runNow = true), 0, Eta.None)))
        rule.mainClock.autoAdvance = false // the game never goes idle
        rule.setContent { SpeedTheme { WorkingHost("a.alpha", AppLook("Alpha"), queue, MutableStateFlow(emptyList()), { rule.mainClock.currentTime + 1_000_000L }) { } } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti").performSemanticsAction(SemanticsActions.OnClick)
        rule.onRoot().recordRoboVideo(rule, File(path), RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) {
            delay(1_700) // the first cactus reaches the alien at about 2.1 s
            rule.onNodeWithContentDescription("Alien Run game. Arrow keys move, Select to act or restart, Back to stop playing.")
                .performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            delay(4_500) // the second cactus is not jumped
        }
    }
}
