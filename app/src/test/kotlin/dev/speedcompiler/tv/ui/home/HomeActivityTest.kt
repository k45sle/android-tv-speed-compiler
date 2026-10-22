package dev.speedcompiler.tv.ui.home

import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.QueueEntry
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.WatchedApp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeActivityTest : ComposeTestBase() {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-01-01T00:30:00Z")
    private val look: (String) -> AppLook = { AppLook(it.substringAfterLast('.').replaceFirstChar(Char::uppercase)) }
    private fun queued(id: Long, pkg: String, state: QueueState = QueueState.QUEUED) =
        QueueItem(id, pkg, QueueSource.AUTO, state, enqueuedAt = 1_000)
    private val queue = listOf(
        QueueEntry(queued(1, "a.alpha", QueueState.RUNNING), 0, Eta.None),
        QueueEntry(queued(2, "b.beta", QueueState.WAITING_GATE), 1, Eta.StartsAt(Instant.parse("2026-01-01T02:00:00Z"))),
    )
    private val watched = listOf(WatchedApp("a.alpha", "1"), WatchedApp("b.beta", "1", lastResult = "Optimized (checked)"))
    private val events = listOf(Event(1, 5_000, "a.alpha", "optimize_failed", "Didn't finish: the TV still reports this app as \"verify\"."))

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { SpeedTheme { content() } }
        rule.waitForIdle()
    }

    private fun home(connection: ConnectionState, go: (String) -> Unit = {}) =
        show { HomeContent(HomeState(connection, true, watched, queue, events, look), go, now, utc) }

    // tv Surface ignores touch clicks in Robolectric; its OnClick action is what a D-pad press invokes.
    private fun SemanticsNodeInteraction.invokeClick() = performSemanticsAction(SemanticsActions.OnClick)

    private fun everyClickableIsLabelled() {
        val nodes = rule.onAllNodesWithClick()
        assertFalse(nodes.isEmpty())
        nodes.forEach {
            val c = it.config
            val label = c.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true ||
                c.getOrNull(SemanticsProperties.ContentDescription)?.isNotEmpty() == true
            assertEquals("unlabelled focusable: $c", true, label)
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithClick() =
        onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)).fetchSemanticsNodes()

    @Test
    fun homeConnectedShowsStatusEtaAndLabels() {
        home(ConnectionState.Connected)
        rule.onNodeWithText("Auto-optimize is on", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Connected", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("1 waiting · next: Starts at 02:00").assertIsDisplayed()
        rule.onNodeWithText("Recent activity", ignoreCase = true).assertIsDisplayed()
        everyClickableIsLabelled()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/home_connected.png")
    }

    @Test
    fun homeBeforeSetupSaysSoAndOffersSetup() {
        var went: String? = null
        show { HomeContent(HomeState(ConnectionState.Connecting, true, emptyList(), emptyList(), emptyList(), look, setupDone = false), { went = it }, now, utc) }
        rule.onNodeWithText("Not set up yet", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Connecting", ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("Set up", ignoreCase = true).invokeClick()
        assertEquals("setup", went)
    }

    @Test
    fun homeDisconnectedShowsWaitingCountAndReconnectNavigates() {
        var went: String? = null
        home(ConnectionState.Disconnected) { went = it }
        rule.onNodeWithText("Disconnected", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("1 waiting").assertIsDisplayed()
        rule.onNodeWithText("Reconnect", ignoreCase = true).invokeClick()
        assertEquals("reconnect", went)
        everyClickableIsLabelled()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/home_disconnected.png")
    }

    @Test
    fun etaRules() {
        val zone = utc
        assertEquals("Starts at 02:00", etaText(Eta.StartsAt(Instant.parse("2026-01-01T02:00:00Z")), now, zone))
        assertEquals("Starts tomorrow 02:00", etaText(Eta.StartsAt(Instant.parse("2026-01-02T02:00:00Z")), now, zone))
        assertEquals("Starts Sat Jan 3 02:00", etaText(Eta.StartsAt(Instant.parse("2026-01-03T02:00:00Z")), now, zone))
        assertEquals("Waiting for the TV to be idle", etaText(Eta.WaitingForIdle))
        assertEquals("Waiting for playback to stop", etaText(Eta.WaitingForPlayback))
        assertEquals("#2 in queue, about 4 min", etaText(Eta.InQueue(1, 240_000)))
        assertEquals("Auto-optimize is off", etaText(Eta.Paused))
    }

    @Test
    fun activityQueueCancelHistoryRetryAndExpand() {
        val cancelled = mutableListOf<Long>()
        val retried = mutableListOf<Long>()
        val history = listOf(
            QueueItem(7, "a.alpha", QueueSource.AUTO, QueueState.FAILED, 1_000, startedAt = 2_000, finishedAt = 6_000, failureReason = "Connection lost"),
        )
        show { ActivityContent(queue, history, events, look, { cancelled += it }, { retried += it }, now, utc) }
        rule.onNodeWithText("Running", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Waiting · Starts at 02:00").assertIsDisplayed()
        rule.onNodeWithContentDescription("Cancel Beta").invokeClick()
        assertEquals(listOf(2L), cancelled)
        rule.onNodeWithText("Failed: Connection lost").assertIsDisplayed()
        rule.onNodeWithContentDescription("Retry Alpha").performScrollTo().invokeClick()
        assertEquals(listOf(7L), retried)
        rule.onNodeWithText("Failed: Connection lost").invokeClick()
        rule.onNodeWithText("Didn't finish", substring = true).assertIsDisplayed()
        everyClickableIsLabelled()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/activity.png")
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCancel(index: Int) =
        onAllNodes(androidx.compose.ui.test.hasText("Cancel"))[index]
}
