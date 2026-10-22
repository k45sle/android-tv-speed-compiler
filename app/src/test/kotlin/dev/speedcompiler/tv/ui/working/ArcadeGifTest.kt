package dev.speedcompiler.tv.ui.working

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
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

/** Records the arcade picker (focus moving Right twice) as a GIF. Runs only with ARCADE_GIF=<absolute path>, so it never writes in normal builds. */
@OptIn(ExperimentalTestApi::class)
class ArcadeGifTest : ComposeTestBase() {
    @Test
    fun recordArcade() {
        val path = System.getenv("ARCADE_GIF")
        assumeNotNull(path)
        val queue = MutableStateFlow(listOf(QueueEntry(QueueItem(1, "a.alpha", QueueSource.MANUAL, QueueState.RUNNING, enqueuedAt = 0, runNow = true), 0, Eta.None)))
        rule.mainClock.autoAdvance = false // the previews never go idle
        rule.setContent { SpeedTheme { WorkingHost("a.alpha", AppLook("Alpha"), queue, MutableStateFlow(emptyList()), { rule.mainClock.currentTime + 1_000_000L }) { } } }
        rule.mainClock.advanceTimeByFrame()
        fun right(from: String) = rule.onNodeWithContentDescription(from).performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        rule.onRoot().recordRoboVideo(rule, File(path), RoboVideoOptions(fps = 15, settleTimeoutMillis = 100)) {
            delay(1_500)
            right("Play Cosmic Worm. Eat stars, use the gates")
            delay(1_500)
            right("Play Alien Run. Jump the cacti")
            delay(1_500)
        }
    }
}
