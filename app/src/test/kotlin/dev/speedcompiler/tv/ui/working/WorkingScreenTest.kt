package dev.speedcompiler.tv.ui.working

import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.Key
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class WorkingScreenTest : ComposeTestBase() {
    private val pkg = "a.alpha"
    private val base = 1_000_000L
    private fun item(id: Long, state: QueueState, reason: String? = null, at: Long = base, runNow: Boolean = true) =
        QueueItem(
            id, pkg, QueueSource.MANUAL, state, enqueuedAt = at, failureReason = reason, runNow = runNow,
            finishedAt = if (state in setOf(QueueState.DONE, QueueState.FAILED, QueueState.SKIPPED)) at else null,
        )

    @Test
    fun followsOldQueuedRowAndRunningNonRunNowRow() {
        queue.value = listOf(QueueEntry(item(1, QueueState.QUEUED, at = base - 600_000, runNow = false), 0, Eta.InQueue(0, 0)))
        show()
        rule.onNodeWithText("#1 in queue, about 1 min").assertIsDisplayed()
        queue.value = listOf(QueueEntry(item(1, QueueState.RUNNING, at = base - 600_000, runNow = false), 0, Eta.None))
        rule.mainClock.advanceTimeBy(40_000)
        rule.onNodeWithText("Optimizing. This continues if you leave.").assertIsDisplayed()
    }

    @Test
    fun disconnectedSaysWaitingForConnectionInsteadOfFlipping() {
        val conn = MutableStateFlow(ConnectionState.Disconnected)
        rule.mainClock.autoAdvance = false
        rule.setContent { SpeedTheme { WorkingHost(pkg, AppLook("Alpha"), queue, history, { base + rule.mainClock.currentTime }, conn) { left++ } } }
        rule.mainClock.advanceTimeByFrame()
        val text = "Waiting for the TV's debugging connection. It retries on its own."
        rule.onNodeWithText(text).assertIsDisplayed()
        queue.value = listOf(QueueEntry(item(1, QueueState.QUEUED), 0, Eta.InQueue(0, 0)))
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText(text).assertIsDisplayed()
        conn.value = ConnectionState.Connected
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("#1 in queue, about 1 min").assertIsDisplayed()
    }

    private val GAME = "Alien Run game. Arrow keys move, Select to act or restart, Back to stop playing."

    private val queue = MutableStateFlow(listOf(QueueEntry(item(1, QueueState.RUNNING), 0, Eta.None)))
    private val history = MutableStateFlow(emptyList<QueueItem>())
    private var left = 0

    private fun SemanticsNodeInteraction.invokeClick() = performSemanticsAction(SemanticsActions.OnClick)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent { SpeedTheme { WorkingHost(pkg, AppLook("Alpha"), queue, history, { base + rule.mainClock.currentTime }) { left++ } } }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun finish(done: QueueItem) {
        queue.value = emptyList()
        history.value = listOf(done)
    }

    @Test
    fun runningShowsHonestProgressAndScreenshot() {
        show()
        rule.onNodeWithText("Optimizing Alpha", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Usually about 2 min").assertIsDisplayed()
        rule.onNodeWithText("00:00").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play Cosmic Worm. Eat stars, use the gates").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play Star Arrow. Fly through the rocks").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/working_running.png")
    }

    @Test
    fun runningLightScreenshot() {
        rule.mainClock.autoAdvance = false
        rule.setContent { SpeedTheme(ThemeChoice.Light) { WorkingHost(pkg, AppLook("Alpha"), queue, history, { base + rule.mainClock.currentTime + 50_000 }) { left++ } } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Optimizing Alpha", ignoreCase = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/working_running_light.png")
    }

    @Test
    fun gameFillsTheScreenWithAStatusLineOverTheTopEdge() {
        show()
        rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti").invokeClick()
        rule.mainClock.advanceTimeBy(1_000)
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val game = rule.onNodeWithContentDescription(GAME).fetchSemanticsNode().boundsInRoot
        assertEquals(root, game)
        val status = rule.onNodeWithText("Optimizing Alpha").fetchSemanticsNode().boundsInRoot
        assertTrue("status top ${status.top}", status.top >= 0f && status.bottom < root.height / 4)
        rule.onNodeWithText("Back to stop").assertIsDisplayed()
        rule.onNodeWithText("00:01").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play while you wait").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/working_playing.png")
    }

    @Test
    fun backLeavesTheGameNotTheScreen() {
        show()
        rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti").invokeClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithContentDescription(GAME).performKeyInput { keyDown(Key.Back); keyUp(Key.Back) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithContentDescription("Play while you wait").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti").assertIsFocused()
        assertEquals(0, left)
    }

    @Test
    fun cabinetsTakeFocusMoveWithRightAndSelectOpensTheGame() {
        show()
        rule.mainClock.advanceTimeBy(1000)
        val worm = rule.onNodeWithContentDescription("Play Cosmic Worm. Eat stars, use the gates")
        val alien = rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti")
        rule.onNodeWithContentDescription("Play Star Arrow. Fly through the rocks").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        worm.assertIsFocused()
        worm.performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        rule.mainClock.advanceTimeBy(100)
        alien.assertIsFocused()
        alien.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithContentDescription(GAME).assertIsDisplayed()
    }

    @Test
    fun upFromCabinetsReachesLeaveAndDownComesBack() {
        show()
        rule.mainClock.advanceTimeBy(1000)
        val worm = rule.onNodeWithContentDescription("Play Cosmic Worm. Eat stars, use the gates")
        val leave = rule.onNodeWithContentDescription("Leave, optimizing continues")
        worm.assertIsFocused()
        worm.performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        rule.mainClock.advanceTimeBy(100)
        leave.assertIsFocused()
        leave.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(100)
        worm.assertIsFocused()
    }

    @Test
    fun screenFitsWithoutScrolling() {
        show()
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
        assertEquals(0f, scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 0f)
    }

    @Test
    fun longFailureStillFitsWithoutScrolling() {
        finish(item(1, QueueState.FAILED, reason = "The device stopped answering while the compile was running, so it was stopped. ".repeat(3)))
        show()
        val scroll = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
        assertEquals(0f, scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 0f)
    }

    @Test
    fun finishedRunSavesBestScoreForTheGame() {
        val saved = mutableListOf<Pair<String, Int>>()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SpeedTheme { WorkingHost(pkg, AppLook("Alpha"), queue, history, { base + rule.mainClock.currentTime }, saveBest = { g, s -> saved += g to s }) { left++ } }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Play Alien Run. Jump the cacti").invokeClick()
        rule.mainClock.advanceTimeBy(40_000) // never jumps: three hits end the run
        assertEquals(1, saved.size)
        assertEquals("AlienRun", saved[0].first)
    }

    @Test
    fun waitingBehindAnotherJobShowsQueuePosition() {
        val other = QueueItem(9, "b.beta", QueueSource.MANUAL, QueueState.RUNNING, enqueuedAt = 1)
        queue.value = listOf(
            QueueEntry(other, 0, Eta.None),
            QueueEntry(item(1, QueueState.QUEUED), 1, Eta.InQueue(1, 240_000)),
        )
        show()
        rule.onNodeWithText("#2 in queue, about 4 min").assertIsDisplayed()
    }

    @Test
    fun jobFinishingPausesTheGameAndShowsResult() {
        show()
        rule.onNodeWithContentDescription("Play Cosmic Worm. Eat stars, use the gates").invokeClick()
        rule.mainClock.advanceTimeBy(20_000)
        rule.onNodeWithText("00:20").assertIsDisplayed()
        rule.onNodeWithContentDescription("Cosmic Worm game. Arrow keys move, Select to act or restart, Back to stop playing.").assertIsDisplayed()
        rule.onNodeWithText("Back to stop").assertIsDisplayed()

        finish(item(1, QueueState.DONE).copy(finishedAt = base + 20_000))
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Alpha optimized").assertIsDisplayed() // status line turns to the result; the game stays frozen behind DONE
        rule.onNodeWithText("Back to leave").assertIsDisplayed()
        rule.onNodeWithText("00:20").assertIsDisplayed() // the clock stopped with the job
        assertEquals(0, left) // not auto-closed
        rule.onNodeWithContentDescription(GAME.replace("Alien Run", "Cosmic Worm")).performKeyInput { keyDown(Key.Back); keyUp(Key.Back) }
        rule.waitForIdle() // applies the key press; the picker bulbs never go idle, so frames are stepped by hand
        rule.mainClock.advanceTimeBy(1000)
        rule.onNodeWithContentDescription("Done, leave this screen").invokeClick()
        assertEquals(1, left)
    }

    @Test
    fun failureFinishedBeforeSubscribingIsShown() {
        queue.value = emptyList()
        history.value = listOf(item(1, QueueState.FAILED, "the TV disconnected"))
        show()
        rule.onNodeWithText("Didn't finish: the TV disconnected").assertIsDisplayed()
    }

    @Test
    fun olderFinishedRunIsIgnoredAndNoRunTimesOut() {
        queue.value = emptyList()
        history.value = listOf(item(1, QueueState.DONE, at = base - 60_000))
        show()
        rule.onNodeWithText("Starting…").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(31_000)
        rule.onNodeWithText("Couldn't start. Try again from the app.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Done, leave this screen").assertIsDisplayed()
    }

    @Test
    fun failureShowsReason() {
        show()
        rule.mainClock.advanceTimeBy(3_000)
        finish(item(1, QueueState.FAILED, "the TV disconnected"))
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Didn't finish: the TV disconnected").assertIsDisplayed()
    }

    private fun shot(name: String, job: JobView, elapsed: Long = 90_000, verified: Boolean = false, connection: ConnectionState = ConnectionState.Connected, text: String, title: String, bar: String?) {
        rule.mainClock.autoAdvance = false
        rule.setContent { SpeedTheme { WorkingContent(WorkingState("Alpha", null, job, elapsed, 120_000, connection, verified = verified), {}) } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText(title, ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText(text).assertIsDisplayed()
        if (bar == null) rule.onNodeWithContentDescription("Time estimate", substring = true).assertDoesNotExist()
        else rule.onNodeWithContentDescription(bar).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/working_$name.png")
    }

    @Test
    fun waitingShowsWaitingTitleAndEmptyBar() =
        shot("waiting", JobView.Waiting(Eta.None), text = "Waiting for its turn", title = "Waiting to optimize Alpha", bar = "Time estimate, 0 of 10 blocks")

    @Test
    fun startingShowsWaitingTitleAndEmptyBar() =
        shot("starting", JobView.Starting, text = "Starting…", title = "Waiting to optimize Alpha", bar = "Time estimate, 0 of 10 blocks")

    @Test
    fun runningFillsTheBarFromElapsed() =
        shot("running_bar", JobView.Running, text = "Optimizing. This continues if you leave.", title = "Optimizing Alpha", bar = "Time estimate, 7 of 10 blocks")

    @Test
    fun runningWithoutConnectionSaysWaiting() =
        shot("running_disconnected", JobView.Running, connection = ConnectionState.Disconnected,
            text = "Waiting for the TV's debugging connection. It retries on its own.", title = "Waiting to optimize Alpha", bar = "Time estimate, 0 of 10 blocks")

    @Test
    fun finishedUnverifiedSaysCompiled() =
        shot("compiled", JobView.Done, text = "Optimized. This TV doesn't report the result, so it couldn't be double-checked. You can leave now.", title = "Alpha", bar = null)

    @Test
    fun finishedVerifiedSaysOptimized() =
        shot("verified", JobView.Done, verified = true, text = "Optimized. You can leave now.", title = "Alpha", bar = null)

    @Test
    fun failedShowsReasonWithoutBar() =
        shot("failed", JobView.Failed("base.apk (arm64) is verify, not speed."), text = "Didn't finish: base.apk (arm64) is verify, not speed.", title = "Alpha", bar = null)
}
