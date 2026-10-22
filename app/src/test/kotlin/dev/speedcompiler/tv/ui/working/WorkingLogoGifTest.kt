package dev.speedcompiler.tv.ui.working

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
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

/** Records the Working screen (one 3 s loop of the animated logo banner) as a GIF. Runs only with WORKING_LOGO_GIF=<absolute path>, so it never writes in normal builds. */
@OptIn(ExperimentalTestApi::class)
class WorkingLogoGifTest : ComposeTestBase() {
    @Test
    fun recordWorkingLogo() {
        val path = System.getenv("WORKING_LOGO_GIF")
        assumeNotNull(path)
        val queue = MutableStateFlow(listOf(QueueEntry(QueueItem(1, "a.alpha", QueueSource.MANUAL, QueueState.RUNNING, enqueuedAt = 0, runNow = true), 0, Eta.None)))
        rule.mainClock.autoAdvance = false // the game never goes idle
        rule.setContent { SpeedTheme { WorkingHost("a.alpha", AppLook("Alpha"), queue, MutableStateFlow(emptyList()), { rule.mainClock.currentTime + 1_000_000L }) { } } }
        rule.mainClock.advanceTimeByFrame()
        rule.onRoot().recordRoboVideo(rule, File(path), RoboVideoOptions(fps = 12, settleTimeoutMillis = 100)) { delay(3_000) }
    }
}
