package dev.speedcompiler.tv.ui.apps

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import dev.speedcompiler.tv.ComposeTestBase
import dev.speedcompiler.tv.engine.DEBUG_BUILD
import dev.speedcompiler.tv.engine.OPTIMIZED_CHECKED
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.watch.FrameStat
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.WatchedApp
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

private fun stat(filter: String, frames: Long, pct: Int) = FrameStat("a.app", "f", filter, frames, frames * pct / 100)

class AdviceTest : ComposeTestBase() {
    private fun advice(
        stats: List<FrameStat> = emptyList(), debug: Boolean = false, profile: Boolean = false, compiled: Boolean = true, on: Boolean = false,
    ) = adviceFor(debug, profile, compiled, stats, on)

    @Test fun firstMatchWins() {
        assertEquals(Advice.DebugBuild, advice(debug = true, profile = true, compiled = false))
        assertEquals(Advice.NoProfile, advice(profile = true, compiled = false))
        assertEquals(Advice.NotOptimized, advice(compiled = false, stats = listOf(stat("speed-profile", 5000, 5))))
    }

    @Test fun smoothChoppyAndSlowByItself() {
        assertEquals(Advice.Smooth, advice(listOf(stat("speed-profile", 5000, 5))))
        assertEquals(Advice.Smooth, advice(listOf(stat("verify", 5000, 60), stat("speed", 3000, 14))))
        // Nuvio-like: verify 60, speed-profile 32.
        val nuvio = listOf(stat("verify", 4000, 60), stat("speed-profile", 4000, 32))
        assertEquals(Advice.TrySmoother, advice(nuvio))
        assertEquals(null, advice(nuvio, on = true))
        assertEquals(Advice.SlowByItself, advice(nuvio + stat("speed", 4000, 29))) // speed helps by 3 points
        assertEquals(null, advice(nuvio + stat("speed", 4000, 20))) // speed helped by 12 points
    }

    @Test fun notEnoughDataAndInBetweenGiveNoLine() {
        assertEquals(null, advice(listOf(stat("speed-profile", 2999, 90))))
        assertEquals(null, advice(listOf(stat("speed-profile", 4000, 20))))
        assertEquals(null, advice())
    }

    @Test fun framesAddUpAcrossRowsOfOneFilter() {
        val rows = listOf(stat("speed-profile", 1500, 10), stat("speed-profile", 2000, 10).copy(fingerprint = "x"))
        assertEquals(Advice.Smooth, advice(rows))
    }

    private val app = AppInfo("a.app", "Alpha", "1.0", updatedAt = 1)
    private val ok = WatchedApp("a.app", "f", lastResult = OPTIMIZED_CHECKED, lastOptimizedAt = 1)
    private var smoother = 0

    private fun show(w: WatchedApp = ok, stats: List<FrameStat> = emptyList(), runs: List<QueueItem> = emptyList()) {
        rule.setContent {
            SpeedTheme { AppDetailContent(app, w, w.lastOptimizedAt, null, 10, ZoneOffset.UTC, {}, {}, {}, runs = runs, stats = stats, onSmoother = { smoother++ }) }
        }
        rule.waitForIdle()
    }

    private fun noSmootherButton() = rule.onAllNodesWithText("Smoother mode", ignoreCase = true).assertCountEquals(0)

    @Test fun detailShowsTheLineAndNoButtonWhenSmooth() {
        show(stats = listOf(stat("speed-profile", 5000, 5)))
        rule.onNodeWithText("Optimized, runs smoothly.").assertExists()
        noSmootherButton()
    }

    @Test fun detailOffersASmootherButtonThatTakesFocus() {
        show(stats = listOf(stat("speed-profile", 5000, 40)))
        rule.onNodeWithText("Still choppy. Try Smoother mode.").assertExists()
        rule.onNodeWithText("Smoother mode", ignoreCase = true).requestFocus().assertIsFocused()
        rule.onNodeWithText("Smoother mode", ignoreCase = true).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, smoother)
    }

    @Test fun detailSaysSlowByItself() {
        show(stats = listOf(stat("speed-profile", 5000, 40), stat("speed", 5000, 38)))
        rule.onNodeWithText("Slow by itself. Compiling can't fix it.").assertExists()
        noSmootherButton()
    }

    @Test fun detailSaysDebugBuild() {
        val run = QueueItem(1, "a.app", QueueSource.AUTO, QueueState.FAILED, enqueuedAt = 1, finishedAt = 2, failureReason = DEBUG_BUILD)
        show(w = WatchedApp("a.app", "f"), runs = listOf(run))
        rule.onNodeWithText("Can't be optimized (debug build).").assertExists()
    }

    @Test fun noLineWithoutEnoughData() {
        show()
        noSmootherButton()
        rule.onAllNodesWithText("runs smoothly", substring = true).assertCountEquals(0)
    }
}
