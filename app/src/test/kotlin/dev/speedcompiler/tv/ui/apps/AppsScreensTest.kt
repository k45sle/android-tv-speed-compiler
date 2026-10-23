package dev.speedcompiler.tv.ui.apps

import dev.speedcompiler.tv.ComposeTestBase
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.watch.FakeScheduler
import dev.speedcompiler.tv.watch.WatchedApp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

class AppsScreensTest : ComposeTestBase() {
    private val scheduler = FakeScheduler()
    private val watched = MutableStateFlow(emptyList<WatchedApp>())
    private val gone = mutableListOf<String>()
    private val auto = mutableListOf<Pair<String, Boolean>>()
    private var music = false
    private val apps = listOf(
        AppInfo("a.alpha", "Alpha", "1.0", updatedAt = 1_000_000_000_000),
        AppInfo("b.beta", "Beta", "2.0", updatedAt = 1_700_000_000_000),
        AppInfo("c.gamma", "Gamma", "3.1", updatedAt = 1_500_000_000_000),
    )
    private val deps = AppsDeps(
        loadApps = { apps }, scheduler = scheduler, watched = watched, musicActive = { music },
        setAutoOptimize = { p, on -> auto += p to on }, now = { 1_800_000_000_000 },
    )

    private fun show(theme: ThemeChoice = ThemeChoice.Dark, content: @Composable () -> Unit) {
        rule.setContent { SpeedTheme(theme) { content() } }
        // A Robolectric window starts in touch mode, where rows cannot take focus; a TV has none.
        rule.runOnUiThread {
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(rule.activity.window.decorView)!!
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.java).apply { isAccessible = true }.invoke(root, false)
        }
        rule.waitForIdle()
    }

    private fun listScreen() = show { AppsScreen({ gone += it }, {}, deps) }
    private fun detailScreen(pkg: String = "a.alpha", d: AppsDeps = deps) = show { AppDetailScreen(pkg, { gone += it }, {}, d) }
    private fun top(text: String) = rule.onNodeWithText(text).getBoundsInRoot().top
    private fun longPress(text: String) {
        rule.onNodeWithText(text, ignoreCase = true).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
    }
    // TV buttons react to OK, not touch, so invoke the click action directly.
    private fun click(text: String) {
        rule.onNodeWithText(text, ignoreCase = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun assertEveryFocusableIsLabelled() {
        rule.onAllNodes(isFocusable()).fetchSemanticsNodes().forEach {
            val c = it.config
            assertTrue("unlabelled focusable: $c", SemanticsProperties.Text in c || SemanticsProperties.ContentDescription in c)
        }
    }

    @Test
    fun opensWithFocusOnSortNotInTheSearchBox() {
        listScreen()
        rule.onNodeWithText("Sort: Recently updated", substring = true, ignoreCase = true).assertIsFocused()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun upFromTheSearchFieldKeepsFocusAlive() {
        listScreen()
        click("Search apps") // OK on the box starts editing
        rule.onNodeWithText("Search apps").performKeyInput { keyDown(Key.DirectionUp); keyUp(Key.DirectionUp) }
        rule.waitForIdle()
        rule.onAllNodes(isFocusable() and androidx.compose.ui.test.isFocused()).assertCountEquals(1)
    }

    @Test
    fun detailShowsTheBannerOnceTheAppLoads() {
        val d = AppsDeps(
            loadApps = { listOf(AppInfo("a.alpha", "Alpha", banner = { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) })) },
            scheduler = scheduler, watched = watched, musicActive = { false }, setAutoOptimize = { _, _ -> },
        )
        detailScreen(d = d)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("A").fetchSemanticsNodes().isEmpty() } // the letter placeholder is gone
    }

    private fun okKey(action: Int, repeat: Int = 0) {
        rule.runOnUiThread { rule.activity.dispatchKeyEvent(android.view.KeyEvent(0, 0, action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, repeat)) }
        rule.waitForIdle()
    }

    @Test
    fun aRowClickWorksAfterALongPressMenuCloses() {
        listScreen()
        rule.onNodeWithText("Beta").requestFocus()
        okKey(android.view.KeyEvent.ACTION_DOWN)
        okKey(android.view.KeyEvent.ACTION_DOWN, repeat = 1) // holding OK: the first repeat is the long press
        rule.onNodeWithText("Optimize now", ignoreCase = true).assertIsDisplayed()
        click("Add to queue") // closes the menu; the key-up went to the dialog, never to the row
        rule.onNodeWithText("Optimize now", ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("Gamma").requestFocus()
        okKey(android.view.KeyEvent.ACTION_DOWN)
        okKey(android.view.KeyEvent.ACTION_UP)
        assertEquals(listOf("apps/detail/c.gamma"), gone)
    }

    @Test
    fun searchFiltersTheList() {
        listScreen()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithText("Search apps").performTextInput("gam")
        rule.waitForIdle()
        rule.onNodeWithText("Gamma").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertDoesNotExist()
        rule.onNodeWithText("Alpha").assertDoesNotExist()
    }

    @Test
    fun sortSwitchesBetweenRecentAndAlphabetical() {
        listScreen()
        assertTrue(top("Beta") < top("Gamma") && top("Gamma") < top("Alpha")) // newest first
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/apps_list.png")
        rule.onNodeWithText("Sort: Recently updated", substring = true, ignoreCase = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(top("Alpha") < top("Beta") && top("Beta") < top("Gamma"))
        rule.onNodeWithText("Sort: A–Z", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun longPressMenuCallsTheRightMethods() {
        listScreen()
        longPress("Alpha")
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/apps_menu.png")
        click("Add to queue")
        longPress("Beta"); click("Watch")
        longPress("Gamma"); click("Optimize now")
        longPress("Beta"); click("Details")
        assertEquals(listOf("enqueue:a.alpha", "watch:b.beta", "optimizeNow:c.gamma"), scheduler.calls)
        assertEquals(listOf("working/c.gamma", "apps/detail/b.beta"), gone)
        rule.onAllNodesWithText("Queued", substring = true, ignoreCase = true).assertCountEquals(2) // Alpha and Gamma
    }

    @Test
    fun watchedAppOffersStopWatching() {
        watched.value = listOf(WatchedApp("a.alpha", "f", lastResult = "Optimized (checked)", lastOptimizedAt = 1))
        listScreen()
        rule.onNodeWithText("Optimized", ignoreCase = true).assertIsDisplayed()
        longPress("Alpha"); click("Stop watching")
        assertEquals(listOf("unwatch:a.alpha"), scheduler.calls)
    }

    @Test
    fun selectOpensDetail() {
        listScreen()
        click("Gamma")
        assertEquals(listOf("apps/detail/c.gamma"), gone)
    }

    @Test
    fun detailShowsStateAndWatchToggle() {
        watched.value = listOf(WatchedApp("a.alpha", "f", lastResult = "Optimized (checked)", lastOptimizedAt = 1_800_000_000_000 - 2 * 3_600_000))
        detailScreen()
        rule.onNodeWithText("a.alpha").assertIsDisplayed()
        rule.onNodeWithText("Optimized for speed · checked 2 h ago").assertIsDisplayed()
        rule.onNodeWithText("Version 1.0", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/app_detail.png")
        click("Stop watching")
        assertEquals(listOf("unwatch:a.alpha"), scheduler.calls)
    }

    @Test
    fun resetRunsAndUnwatches() {
        val reset = mutableListOf<String>()
        watched.value = listOf(WatchedApp("a.alpha", "f"))
        detailScreen(d = AppsDeps(
            loadApps = { apps }, scheduler = scheduler, watched = watched, musicActive = { music },
            setAutoOptimize = { _, _ -> }, now = { 1_800_000_000_000 },
            reset = { reset += it; dev.speedcompiler.tv.engine.OptimizeResult(true, "Reset done.") },
        ))
        rule.onNodeWithText("Reset", ignoreCase = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/app_detail_reset.png")
        click("Reset")
        assertEquals(listOf("a.alpha"), reset)
        assertEquals(listOf("unwatch:a.alpha"), scheduler.calls)
        rule.onNodeWithText("Reset done.").assertIsDisplayed()
    }

    @Test
    fun unwatchedDetailOffersWatch() {
        detailScreen()
        rule.onNodeWithText("Auto-optimize this app").assertDoesNotExist()
        click("Watch")
        assertEquals(listOf("watch:a.alpha"), scheduler.calls)
    }

    @Test
    fun autoOptimizeSwitchWritesTheFlag() {
        watched.value = listOf(WatchedApp("a.alpha", "f", autoOptimize = true))
        detailScreen()
        click("Auto-optimize this app")
        assertEquals(listOf("a.alpha" to false), auto)
    }

    @Test
    fun optimizeNowGoesStraightToWorking() {
        detailScreen()
        click("Optimize now")
        assertEquals(listOf("optimizeNow:a.alpha"), scheduler.calls)
        assertEquals(listOf("working/a.alpha"), gone)
    }

    @Test
    fun optimizeNowWithMusicWarnsFirst() {
        music = true
        detailScreen()
        click("Optimize now")
        rule.onNodeWithText("Something is playing", substring = true).assertIsDisplayed()
        assertTrue(scheduler.calls.isEmpty())
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/app_detail_warning.png")
        click("Continue")
        assertEquals(listOf("optimizeNow:a.alpha"), scheduler.calls)
        assertEquals(listOf("working/a.alpha"), gone)
    }

    @Test
    fun cancelOnTheWarningDoesNothing() {
        music = true
        detailScreen()
        click("Optimize now"); click("Cancel")
        assertTrue(scheduler.calls.isEmpty() && gone.isEmpty())
    }

    @Test
    fun everyFocusableElementIsLabelled() {
        watched.value = listOf(WatchedApp("a.alpha", "f"))
        listScreen()
        assertEveryFocusableIsLabelled()
    }

    @Test
    fun everyFocusableElementOnDetailIsLabelled() {
        watched.value = listOf(WatchedApp("a.alpha", "f"))
        detailScreen()
        assertEveryFocusableIsLabelled()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun holdingOkOpensTheMenuAndReleaseDoesNotNavigate() {
        listScreen()
        rule.onNodeWithText("Alpha").requestFocus()
        rule.onNodeWithText("Alpha").performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(800) // held: the framework sends repeat KeyDowns
            keyUp(Key.DirectionCenter)
        }
        rule.waitForIdle()
        rule.onNodeWithText("Add to queue", ignoreCase = true).assertIsDisplayed()
        assertTrue(gone.isEmpty())
    }

    @Test
    fun autoOptimizeRowIsASwitchWithState() {
        watched.value = listOf(WatchedApp("a.alpha", "f", autoOptimize = true))
        detailScreen()
        rule.onNodeWithText("Auto-optimize this app").assertIsOn().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
        )
    }

    @Test
    fun finishedJobMarksAnUnwatchedAppOptimized() {
        val done = dev.speedcompiler.tv.watch.QueueItem(
            1, "a.alpha", dev.speedcompiler.tv.watch.QueueSource.MANUAL,
            dev.speedcompiler.tv.watch.QueueState.DONE, enqueuedAt = 1, finishedAt = 50,
        )
        assertEquals(mapOf("a.alpha" to 50L), optimizedAtMap(emptyList(), listOf(done)))
        assertEquals(mapOf("a.alpha" to 80L), optimizedAtMap(listOf(WatchedApp("a.alpha", "f", lastOptimizedAt = 80)), listOf(done)))
    }

    @Test
    fun aLaterResetClearsCompiled() {
        val done = dev.speedcompiler.tv.watch.QueueItem(
            1, "a.alpha", dev.speedcompiler.tv.watch.QueueSource.MANUAL,
            dev.speedcompiler.tv.watch.QueueState.DONE, enqueuedAt = 1, finishedAt = 50,
        )
        fun reset(at: Long) = dev.speedcompiler.tv.watch.Event(timeMillis = at, packageName = "a.alpha", kind = "reset_done", message = "")
        assertEquals(emptyMap<String, Long>(), optimizedAtMap(emptyList(), listOf(done), listOf(reset(60))))
        assertEquals(mapOf("a.alpha" to 50L), optimizedAtMap(emptyList(), listOf(done), listOf(reset(40)))) // optimized again after the reset
    }

    private fun content(
        list: List<AppInfo> = apps, loading: Boolean = false, selecting: Boolean = false, picked: Set<String> = emptySet(),
        watchedNow: Map<String, WatchedApp> = emptyMap(), onWatch: (List<String>) -> Unit = {}, onQueue: (List<String>) -> Unit = {},
    ) = show {
        AppsContent(
            list, emptyMap(), watchedNow, emptyMap(), 1_800_000_000_000, java.time.ZoneOffset.UTC, {}, {}, {}, { _, _ -> },
            onWatch, onQueue, loading = loading, startSelecting = selecting, startSelected = picked,
        )
    }

    @Test
    fun selectingThreeAppsQueuesAllThree() {
        val queued = mutableListOf<String>()
        content(onQueue = { queued += it })
        click("Select")
        listOf("Alpha", "Beta", "Gamma").forEach { click(it) }
        rule.onNodeWithText("3 selected", ignoreCase = true).assertIsDisplayed()
        click("Optimize selected")
        assertEquals(listOf("a.alpha", "b.beta", "c.gamma").sorted(), queued.sorted())
        rule.onNodeWithText("Optimize selected", ignoreCase = true).assertDoesNotExist() // back to browsing
    }

    @Test
    fun optimizeSelectedEnqueuesEachAppOnTheScheduler() {
        listScreen()
        click("Select")
        listOf("Alpha", "Beta", "Gamma").forEach { click(it) }
        click("Optimize selected")
        rule.waitForIdle()
        assertEquals(listOf("enqueue:a.alpha", "enqueue:b.beta", "enqueue:c.gamma"), scheduler.calls.sorted())
    }

    @Test
    fun selectAllWatchedPicksOnlyWatchedApps() {
        val watching = mutableListOf<String>()
        content(selecting = true, watchedNow = mapOf("b.beta" to WatchedApp("b.beta", "f")), onWatch = { watching += it })
        click("Select all watched")
        click("Watch selected")
        assertEquals(listOf("b.beta"), watching)
    }

    @Test
    fun statusWordsComeFromTheResultEnum() {
        val zone = java.time.ZoneOffset.UTC
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals("Optimized" to dev.speedcompiler.tv.theme.Tone.Ok, pillFor(res, null, "Optimized (checked)", 1, 0, zone))
        assertEquals("Compiled" to dev.speedcompiler.tv.theme.Tone.Warn, pillFor(res, null, "Optimized", 1, 0, zone))
        assertEquals("Compiled", pillFor(res, null, null, 1, 0, zone)?.first) // finished job, no stored result
        assertEquals("Didn't finish", pillFor(res, null, "Failed: x", null, 0, zone)?.first)
        assertEquals(null, pillFor(res, null, null, null, 0, zone))
        val run = dev.speedcompiler.tv.watch.QueueEntry(
            dev.speedcompiler.tv.watch.QueueItem(1, "a", dev.speedcompiler.tv.watch.QueueSource.MANUAL, dev.speedcompiler.tv.watch.QueueState.RUNNING, enqueuedAt = 1),
            0, dev.speedcompiler.tv.watch.Eta.None,
        )
        assertEquals("Running", pillFor(res, run, "Optimized (checked)", 1, 0, zone)?.first)
    }

    @Test
    fun detailCardTakesNoFocus() {
        detailScreen()
        rule.onNodeWithContentDescription("Alpha, New").assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        rule.onAllNodes(isFocusable()).assertCountEquals(4) // Back, Optimize now, Watch, Reset; not the card
        rule.onAllNodesWithText("Optimize now", ignoreCase = true).assertCountEquals(1)
    }

    @Test
    fun loadingShot() {
        content(emptyList(), loading = true)
        rule.onNodeWithText("Loading apps", substring = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/apps_loading.png")
    }

    @Test
    fun emptyShot() {
        content(emptyList())
        rule.onNodeWithText("No apps found.").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/apps_empty.png")
    }

    @Test
    fun selectModeShot() {
        content(selecting = true, picked = setOf("a.alpha", "c.gamma"))
        rule.onNodeWithText("2 selected", ignoreCase = true).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/apps_select.png")
    }

    @Test
    fun lightShots() {
        watched.value = listOf(WatchedApp("a.alpha", "f", lastOptimizedAt = 1_800_000_000_000 - 2 * 3_600_000))
        show(ThemeChoice.Light) { AppsScreen({}, {}, deps) }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/apps_list_light.png")
    }

    @Test
    fun lightDetailShot() {
        val now = 1_800_000_000_000
        val w = WatchedApp("a.alpha", "f", autoOptimize = true, lastOptimizedAt = now - 2 * 3_600_000)
        val runs = listOf(
            dev.speedcompiler.tv.watch.QueueItem(1, "a.alpha", dev.speedcompiler.tv.watch.QueueSource.AUTO, dev.speedcompiler.tv.watch.QueueState.DONE, enqueuedAt = 1, finishedAt = now - 2 * 3_600_000),
            dev.speedcompiler.tv.watch.QueueItem(2, "a.alpha", dev.speedcompiler.tv.watch.QueueSource.MANUAL, dev.speedcompiler.tv.watch.QueueState.FAILED, enqueuedAt = 1, finishedAt = now - 90_000_000),
        )
        show(ThemeChoice.Light) { AppDetailContent(apps[0], w, w.lastOptimizedAt, null, now, java.time.ZoneOffset.UTC, {}, {}, {}, runs = runs) }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/app_detail_light.png")
    }
}
