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
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.home.AppLook
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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
    private fun item(id: Long, state: QueueState, reason: String? = null) =
        QueueItem(id, pkg, QueueSource.MANUAL, state, enqueuedAt = 1_000, failureReason = reason)

    private val queue = MutableStateFlow(listOf(QueueEntry(item(1, QueueState.RUNNING), 0, Eta.None)))
    private val history = MutableStateFlow(emptyList<QueueItem>())
    private var left = 0

    private fun SemanticsNodeInteraction.invokeClick() = performSemanticsAction(SemanticsActions.OnClick)

    private fun show() {
        rule.mainClock.autoAdvance = false
        rule.setContent { SpeedTheme { WorkingHost(pkg, AppLook("Alpha"), queue, history) { left++ } } }
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
        rule.onNodeWithContentDescription("Snake game. Arrow keys move, Select to act or restart, Back to leave.").assertIsDisplayed()

        finish(item(1, QueueState.DONE))
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText("Optimized. You can leave now.").assertIsDisplayed()
        rule.onNodeWithText("Game paused. Press Done when you are ready.").assertIsDisplayed()
        rule.onNodeWithText("00:20").assertIsDisplayed() // the clock stopped with the job
        assertEquals(0, left) // not auto-closed
        rule.onNodeWithContentDescription("Done, leave this screen").invokeClick()
        assertEquals(1, left)
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
