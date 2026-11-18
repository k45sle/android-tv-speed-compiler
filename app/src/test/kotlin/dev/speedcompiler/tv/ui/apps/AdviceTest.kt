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

/** Cause counts and start-window JIT time, for the DrawingBound cases. */
private fun caused(filter: String, frames: Long, pct: Int, ui: Long, draw: Long, jitMs: Long = 0, durationMs: Long = 60_000, jitDurationMs: Long = durationMs) =
    FrameStat("a.app", "f", filter, frames, frames * pct / 100, "start", ui, 0, draw, jitMs, durationMs, jitDurationMs)

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
        assertEquals(Advice.Measuring, advice(nuvio, on = true)) // speed has no frames yet
        assertEquals(Advice.SlowByItself, advice(nuvio + stat("speed", 4000, 29))) // speed helps by 3 points
        assertEquals(null, advice(nuvio + stat("speed", 4000, 20))) // speed helped by 12 points
    }

    @Test fun drawingBoundWhenTheStutterIsNotInTheUiThread() {
        // AIOStreams-like: 13% of janky frames are UI thread, 86% issue draw commands, no JIT.
        val aio = listOf(caused("verify", 4000, 40, 208, 1376), caused("speed-profile", 4000, 38, 198, 1310, jitMs = 50))
        assertEquals(Advice.DrawingBound(38), advice(aio))
        // Smoother mode on: judged by the speed rows.
        assertEquals(Advice.DrawingBound(35), advice(aio + caused("speed", 4000, 35, 180, 1200), on = true))
        // Never suggests Smoother.
        assertEquals(false, advice(aio) == Advice.TrySmoother)
        // Smooth still wins.
        assertEquals(Advice.Smooth, advice(listOf(caused("speed-profile", 4000, 10, 10, 100))))
    }

    @Test fun notDrawingBoundForUiHeavyJitBusyFewOrUnexplainedStutter() {
        // Nuvio-like: 44% UI thread.
        assertEquals(Advice.TrySmoother, advice(listOf(caused("verify", 4000, 60, 1000, 1200), caused("speed-profile", 4000, 32, 563, 700))))
        // JIT busy in the start window: 3 s of 60 s.
        assertEquals(Advice.TrySmoother, advice(listOf(caused("speed-profile", 4000, 38, 100, 1300, jitMs = 3_000))))
        // Under 3000 frames.
        assertEquals(Advice.Measuring, advice(listOf(caused("speed-profile", 2999, 38, 10, 1300))))
        // No cause counts at all: unknown, not drawing.
        assertEquals(Advice.TrySmoother, advice(listOf(stat("speed-profile", 4000, 38))))
    }

    @Test fun notDrawingBoundWhenTheJitWasNeverMeasuredAtAFreshStart() {
        // Same stutter as AIOStreams, but the JIT thread was not read (unreadable /proc, or no fresh start seen): unknown is not "no JIT".
        assertEquals(Advice.TrySmoother, advice(listOf(caused("speed-profile", 4000, 38, 198, 1310, jitDurationMs = 0))))
        // Under 30 s of measured start time: too little evidence.
        assertEquals(Advice.TrySmoother, advice(listOf(caused("speed-profile", 4000, 38, 198, 1310, jitDurationMs = 29_999))))
        assertEquals(Advice.DrawingBound(38), advice(listOf(caused("speed-profile", 4000, 38, 198, 1310, jitDurationMs = 30_000))))
        // No start row at all.
        assertEquals(Advice.TrySmoother, advice(listOf(FrameStat("a.app", "f", "speed-profile", 4000, 1520, "later", 198, 0, 1310))))
    }

    @Test fun measuringUntilTheCurrentFilterHasEnoughFrames() {
        assertEquals(Advice.Measuring, advice(listOf(stat("speed-profile", 2999, 90))))
        assertEquals(Advice.Measuring, advice())
        assertEquals(Advice.Measuring, advice(listOf(stat("speed-profile", 5000, 20), stat("speed", 2999, 20)), on = true))
    }

    @Test fun mostlySmoothInTheMiddleBandOfTheCurrentFilter() {
        assertEquals(Advice.MostlySmooth(20), advice(listOf(stat("speed-profile", 4000, 20))))
        assertEquals(Advice.MostlySmooth(20), advice(listOf(stat("speed-profile", 4000, 30), stat("speed", 4000, 20)), on = true))
        assertEquals(null, advice(listOf(stat("speed-profile", 4000, 40), stat("speed", 4000, 20)))) // speed helped, but profile is still the filter in use
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

    @Test fun focusMovesToOptimizeNowWhenTheSmootherButtonGoes() {
        val w = androidx.compose.runtime.mutableStateOf(ok)
        val stats = listOf(stat("speed-profile", 5000, 40))
        rule.setContent {
            SpeedTheme { AppDetailContent(app, w.value, ok.lastOptimizedAt, null, 10, ZoneOffset.UTC, {}, {}, {}, stats = stats, onSmoother = { w.value = w.value.copy(useSpeed = true) }) }
        }
        rule.onNodeWithText("Smoother mode", ignoreCase = true).requestFocus().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        noSmootherButton()
        rule.onNodeWithText("Optimize now", ignoreCase = true).assertIsFocused()
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

    @Test fun detailShowsMeasuringAndMostlySmooth() {
        show()
        rule.onNodeWithText("Checking how smoothly it runs. Use it a little longer.").assertExists()
    }

    @Test fun detailShowsThePercentOnTheMostlySmoothLine() {
        show(stats = listOf(stat("speed-profile", 4000, 20)))
        rule.onNodeWithText("Mostly smooth, with some stutter (20% of frames).").assertExists()
    }

    @Test fun detailSaysDrawingBoundWithTheSmootherVariant() {
        val aio = listOf(caused("speed-profile", 4000, 38, 198, 1310))
        show(stats = aio)
        rule.onNodeWithText("Stutters while drawing the screen (38% of frames). Compiling can't change that.").assertExists()
        noSmootherButton()
    }

    @Test fun detailSaysDrawingBoundInSmootherModeAndOffersBackToNormal() {
        show(w = ok.copy(useSpeed = true), stats = listOf(caused("speed", 4000, 35, 180, 1200)))
        rule.onNodeWithText(
            "Stutters while drawing the screen (35% of frames). Compiling can't change that, so Smoother mode can't either; switching back saves storage and starts faster.",
        ).assertExists()
        rule.onNodeWithText("Back to normal mode", ignoreCase = true).assertExists()
    }

    @Test fun slowByItselfInSmootherModeOffersBackToNormalAndFocusMoves() {
        val w = androidx.compose.runtime.mutableStateOf(ok.copy(useSpeed = true))
        val stats = listOf(stat("speed-profile", 5000, 40), stat("speed", 5000, 38))
        var calls = emptyList<Boolean>()
        rule.setContent {
            SpeedTheme { AppDetailContent(app, w.value, ok.lastOptimizedAt, null, 10, ZoneOffset.UTC, {}, {}, {}, stats = stats, onSmoother = { calls = calls + it; w.value = w.value.copy(useSpeed = false) }) }
        }
        rule.onNodeWithText("Slow by itself. Compiling can't fix it. Smoother mode didn't help; switching back saves storage and starts faster.").assertExists()
        rule.onNodeWithText("Back to normal mode", ignoreCase = true).requestFocus().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf(false), calls)
        rule.onAllNodesWithText("Back to normal mode", ignoreCase = true).assertCountEquals(0)
        rule.onNodeWithText("Optimize now", ignoreCase = true).assertIsFocused()
        rule.onNodeWithText("Slow by itself. Compiling can't fix it.").assertExists() // plain line, no button
    }

    @Test fun noLineWithoutEnoughData() {
        show()
        noSmootherButton()
        rule.onAllNodesWithText("runs smoothly", substring = true).assertCountEquals(0)
    }
}
