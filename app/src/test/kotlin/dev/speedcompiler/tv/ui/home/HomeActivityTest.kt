package dev.speedcompiler.tv.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.ui.Routes
import org.junit.Assert.assertTrue
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
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val now = Instant.parse("2026-01-01T00:30:00Z")
    private val look: (String) -> AppLook = { AppLook(it.substringAfterLast('.').replaceFirstChar(Char::uppercase)) }
    private fun queued(id: Long, pkg: String, state: QueueState = QueueState.QUEUED) =
        QueueItem(id, pkg, QueueSource.AUTO, state, enqueuedAt = 1_000)
    private val queue = listOf(
        QueueEntry(queued(1, "a.alpha", QueueState.RUNNING), 0, Eta.None),
        QueueEntry(queued(2, "b.beta", QueueState.WAITING_GATE), 1, Eta.StartsAt(Instant.parse("2026-01-01T02:00:00Z"))),
    )
    private val watched = listOf(WatchedApp("a.alpha", "1"), WatchedApp("b.beta", "1", lastResult = "Optimized (checked)"))
    private val events = listOf(Event(1, 5_000, "a.alpha", "optimize_failed", "Didn't finish: base.apk (arm64) is verify, not speed."))

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
        rule.onNodeWithText("1 waiting · next: Starts at 02:00 · 0 of 2").assertIsDisplayed()
        rule.onNodeWithText("Recent activity", ignoreCase = true).assertIsDisplayed()
        everyClickableIsLabelled()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/home_connected.png")
    }

    @Test
    fun gridButtonsFitInsideTheScreenAndNavigate() {
        val went = mutableListOf<String>()
        home(ConnectionState.Connected) { went += it }
        val screen = rule.onRoot().getBoundsInRoot()
        listOf("Apps", "Activity", "Settings").forEach {
            val b = rule.onNodeWithText(it, ignoreCase = true)
            b.assertIsDisplayed()
            val r = b.getBoundsInRoot()
            assertTrue("$it clipped: $r", r.left >= 0.dp && r.top >= 0.dp && r.right <= screen.right && r.bottom <= screen.bottom)
            b.invokeClick()
        }
        rule.onNodeWithText("Setup", ignoreCase = true).assertDoesNotExist()
        assertEquals(listOf(Routes.Apps, Routes.Activity, Routes.Settings), went)
    }

    @Test
    fun recentActivityShowsFailedAndOptimizedWords() {
        show {
            HomeContent(HomeState(ConnectionState.Connected, true, watched, queue, events + Event(2, 6_000, "b.beta", "optimize_verified", "ok"), look), {}, now, utc)
        }
        rule.onNodeWithText("Failed", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Optimized", ignoreCase = true).assertIsDisplayed()
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
        rule.onNodeWithText("Fix connection", ignoreCase = true).invokeClick()
        assertEquals("reconnect", went)
        everyClickableIsLabelled()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/home_disconnected.png")
    }

    @Test
    fun homeUnpairedFixConnectionGoesToRepair() {
        var went: String? = null
        home(ConnectionState.Unpaired) { went = it }
        rule.onNodeWithText("Fix connection", ignoreCase = true).invokeClick()
        assertEquals(Routes.QuickRepair, went)
    }

    @Test
    fun jobResultMapsEveryCase() {
        assertEquals(JobResult.Verified, jobResult("Optimized (checked)", null))
        assertEquals(JobResult.Unverified, jobResult("Optimized (this TV doesn't report the result)", null))
        assertEquals(JobResult.Failed, jobResult("Didn't finish: base.apk (arm64) is verify, not speed.", QueueState.FAILED))
        assertEquals(JobResult.Running, jobResult("Optimized (checked)", QueueState.RUNNING))
        assertEquals(JobResult.Queued, jobResult(null, QueueState.QUEUED))
        assertEquals(JobResult.Queued, jobResult(null, QueueState.WAITING_GATE))
        assertEquals(null, jobResult(null, null))
        assertEquals(JobResult.entries.size, JobResult.entries.map { it.label }.toSet().size)
    }

    private fun cards(shot: String, vararg expected: String, apps: List<WatchedApp>, q: List<QueueEntry> = emptyList()) {
        show { HomeContent(HomeState(ConnectionState.Connected, true, apps, q, emptyList(), look), {}, now, utc) }
        expected.forEach { rule.onNodeWithText(it, ignoreCase = true).assertIsDisplayed() }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$shot.png")
    }

    @Test
    fun homeCardsShowLiveResultLabels() = cards(
        "home_results_live", "Running", "Queued", "Compiled",
        apps = listOf(
            WatchedApp("a.alpha", "1"), WatchedApp("b.beta", "1", lastResult = "Optimized (checked)"),
            WatchedApp("c.gamma", "1", lastResult = "Optimized (this TV doesn't report the result)"),
        ),
        q = listOf(QueueEntry(queued(1, "a.alpha", QueueState.RUNNING), 0, Eta.None), QueueEntry(queued(2, "b.beta"), 1, Eta.None)),
    )

    @Test
    fun homeCardsShowFinishedResultLabels() = cards(
        "home_results_done", "Optimized", "Didn't finish", "Not optimized yet",
        apps = listOf(
            WatchedApp("b.beta", "1", lastResult = "Optimized (checked)"),
            WatchedApp("d.delta", "1", lastResult = "Didn't finish: base.apk (arm64) is verify, not speed."), WatchedApp("a.alpha", "1"),
        ),
    )

    @Test
    fun homeDisconnectedWithEmptyQueueSaysNothingWaiting() {
        show { HomeContent(HomeState(ConnectionState.Disconnected, true, watched, emptyList(), emptyList(), look), {}, now, utc) }
        rule.onNodeWithText("Nothing waiting").assertIsDisplayed()
        rule.onNodeWithText("0 waiting").assertDoesNotExist()
    }

    @Test
    fun etaRules() {
        val zone = utc
        assertEquals("Starts at 02:00", etaText(res, Eta.StartsAt(Instant.parse("2026-01-01T02:00:00Z")), now, zone))
        assertEquals("Starts tomorrow 02:00", etaText(res, Eta.StartsAt(Instant.parse("2026-01-02T02:00:00Z")), now, zone))
        assertEquals("Starts Sat Jan 3 02:00", etaText(res, Eta.StartsAt(Instant.parse("2026-01-03T02:00:00Z")), now, zone))
        assertEquals("Waiting for the TV to be idle", etaText(res, Eta.WaitingForIdle))
        assertEquals("Waiting for playback to stop", etaText(res, Eta.WaitingForPlayback))
        assertEquals("#2 in queue, about 4 min", etaText(res, Eta.InQueue(1, 240_000)))
        assertEquals("Auto-optimize is off", etaText(res, Eta.Paused))
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun retryIsReachableByFocus() {
        // Enough finished jobs that the failed row starts below the viewport.
        val done = (1L..12).map { QueueItem(it, "p.app$it", QueueSource.AUTO, QueueState.DONE, 1_000, startedAt = 2_000, finishedAt = 3_000) }
        val failed = QueueItem(99, "a.alpha", QueueSource.AUTO, QueueState.FAILED, 1_000, startedAt = 2_000, finishedAt = 2_500, failureReason = "Connection lost")
        show { ActivityContent(queue, done + failed, events, look, {}, {}, now, utc) }
        val row = "Alpha, Failed, ${clockText(2_500, utc)}, Connection lost"
        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(row))
        rule.onNodeWithContentDescription(row).requestFocus()
        rule.onNodeWithContentDescription(row).performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Retry Alpha").assertIsFocused().assertIsDisplayed()
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
        rule.onNodeWithContentDescription("Alpha, Failed, ${clockText(6_000, utc)}, Connection lost").fetchSemanticsNode()
        rule.onNodeWithContentDescription("Retry Alpha").performScrollTo().invokeClick()
        assertEquals(listOf(7L), retried)
        rule.onNodeWithText("Failed: Connection lost").invokeClick()
        rule.onNodeWithText("Didn't finish", substring = true).performScrollTo().assertIsDisplayed()
        everyClickableIsLabelled()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/activity.png")
    }

    @Test
    fun activityLightShot() {
        val history = listOf(
            QueueItem(7, "a.alpha", QueueSource.AUTO, QueueState.FAILED, 1_000, startedAt = 2_000, finishedAt = 6_000, failureReason = "Connection lost"),
            QueueItem(8, "b.beta", QueueSource.AUTO, QueueState.DONE, 1_000, startedAt = 2_000, finishedAt = 7_000),
        )
        rule.setContent { SpeedTheme(dev.speedcompiler.tv.engine.ThemeChoice.Light) { ActivityContent(queue, history, events, look, {}, {}, now, utc) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/activity_light.png")
    }

    private fun inQueue(vararg etas: Eta) = etas.mapIndexed { i, e -> QueueEntry(queued(i + 1L, "p.$i"), i, e) }

    @Test
    fun aggregateLineCases() {
        val three = inQueue(Eta.None, Eta.InQueue(1, 120_000), Eta.InQueue(2, 240_000))
        assertEquals("0 of 3, about 6 min left", aggregateLine(res, 0, three))
        assertEquals("1 of 3, about 4 min left", aggregateLine(res, 1, three.drop(1)))
        assertEquals(null, aggregateLine(res, 3, emptyList()))
        assertEquals("1 of 3", aggregateLine(res, 1, inQueue(Eta.None, Eta.WaitingForIdle)))
        assertEquals(null, aggregateLine(res, 0, three.take(1)))
        assertEquals(null, aggregateLine(res, 0, emptyList()))
        val done = listOf(queued(9, "x").copy(state = QueueState.DONE, finishedAt = 2_000), queued(8, "y").copy(state = QueueState.DONE, finishedAt = 500))
        assertEquals(1, batchDone(three, done))
        assertEquals(0, batchDone(three, done.map { it.copy(state = QueueState.SKIPPED) }))
    }

    @Test
    fun cancelAllAsksFirstThenCancelsEveryItem() {
        val cancelled = mutableListOf<Long>()
        show { ActivityContent(queue, emptyList(), events, look, { cancelled += it }, {}, now, utc) }
        rule.onNodeWithText("CANCEL ALL").invokeClick()
        rule.onNodeWithText("KEEP").invokeClick() // dismiss
        assertEquals(emptyList<Long>(), cancelled)
        rule.onNodeWithText("CANCEL ALL").invokeClick()
        rule.onAllNodesWithText("CANCEL ALL").assertCountEquals(2)
        rule.onAllNodesWithText("CANCEL ALL")[1].invokeClick()
        assertEquals(listOf(1L, 2L), cancelled)
    }
}
