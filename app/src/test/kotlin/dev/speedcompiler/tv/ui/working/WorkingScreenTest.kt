package dev.speedcompiler.tv.ui.working

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp")
class WorkingScreenTest {
    init {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

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
        rule.onNodeWithText("Optimizing Alpha").assertIsDisplayed()
        rule.onNodeWithText("Usually about 2 min").assertIsDisplayed()
        rule.onNodeWithText("00:00").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play Snake. Eat, grow, don't crash").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play Dino runner. Jump the cacti").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play Flappy. Flap through the gaps").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/working_running.png")
    }

    @Test
    fun headerAndGameFitOnScreenWhilePlaying() {
        show()
        rule.onNodeWithContentDescription("Play Snake. Eat, grow, don't crash").invokeClick()
        rule.mainClock.advanceTimeBy(1_000)
        val title = rule.onNodeWithText("Optimizing Alpha").fetchSemanticsNode().boundsInRoot
        val hint = rule.onNodeWithText("Arrows to move, Select to act, Back to stop playing").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("title top ${title.top}", title.top >= 0f)
        assertTrue("hint bottom ${hint.bottom} of ${root.height}", hint.bottom <= root.height)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/working_playing.png")
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
        rule.onNodeWithContentDescription("Play Snake. Eat, grow, don't crash").invokeClick()
        rule.mainClock.advanceTimeBy(20_000)
        rule.onNodeWithText("00:20").assertIsDisplayed()
        rule.onNodeWithContentDescription("Snake game. Arrow keys move, Select to act or restart, Back to stop playing.").assertIsDisplayed()

        finish(item(1, QueueState.DONE).copy(finishedAt = base + 20_000))
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Optimized. You can leave now.").assertIsDisplayed()
        rule.onNodeWithText("Game paused. Press Done when you are ready.").assertIsDisplayed()
        rule.onNodeWithText("00:20").assertIsDisplayed() // the clock stopped with the job
        assertEquals(0, left) // not auto-closed
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
}
