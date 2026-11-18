package dev.speedcompiler.tv.ui.apps

import dev.speedcompiler.tv.watch.DRAW_BOUND_UI_SHARE
import dev.speedcompiler.tv.watch.FrameStat
import dev.speedcompiler.tv.watch.JIT_BUSY_SHARE
import dev.speedcompiler.tv.watch.MIN_CAUSE_FRAMES
import dev.speedcompiler.tv.watch.WINDOW_START
import dev.speedcompiler.tv.watch.RECOMPILE_MIN_START_MS
import dev.speedcompiler.tv.watch.jitShare

/** Filters need this many frames before their share of slow frames is trusted. */
const val MIN_FILTER_FRAMES = 3000
const val SMOOTH_BELOW = 15
const val CHOPPY_FROM = 25
private const val HELPS_BY = 5

/** One line of advice for App detail, from the first condition that holds. NoProfile and NotOptimized are already the state line. */
sealed interface Advice {
    data object DebugBuild : Advice
    data object NoProfile : Advice
    data object NotOptimized : Advice
    data object Smooth : Advice
    /** The stutter is in drawing the screen, not in code that compiling could speed up. */
    data class DrawingBound(val percent: Int) : Advice
    data object TrySmoother : Advice
    data object SlowByItself : Advice
    /** Compiled, but the filter in use has too few frames yet. */
    data object Measuring : Advice
    data class MostlySmooth(val percent: Int) : Advice
}

/** Percent of slow frames for [filter] over [stats] (already one app and version), or null with under [MIN_FILTER_FRAMES] frames. */
private fun slowPercent(stats: List<FrameStat>, filter: String): Double? {
    val rows = stats.filter { it.filter == filter }
    val frames = rows.sumOf { it.frames }
    return if (frames < MIN_FILTER_FRAMES) null else 100.0 * rows.sumOf { it.janky } / frames
}

/** True when the stutter of [filter] is mostly not the UI thread and the app was not busy compiling at startup. */
private fun drawingBound(stats: List<FrameStat>, filter: String): Boolean {
    val rows = stats.filter { it.filter == filter }
    val janky = rows.sumOf { it.janky }
    // No cause counts at all (a TV that does not print them) says nothing about where the stutter is.
    if (rows.sumOf { it.slowUi + it.slowBitmap + it.slowDraw } == 0L) return false
    if (janky < MIN_CAUSE_FRAMES || rows.sumOf { it.slowUi }.toDouble() / janky >= DRAW_BOUND_UI_SHARE) return false
    val start = rows.filter { it.window == WINDOW_START }
    // Without enough measured start time the JIT load is unknown, which is not the same as none.
    if (start.sumOf { it.jitDurationMs } < RECOMPILE_MIN_START_MS) return false
    val share = jitShare(start.sumOf { it.jitMs }, start.sumOf { it.jitDurationMs }) ?: return false
    return share < JIT_BUSY_SHARE
}

fun adviceFor(debugBuild: Boolean, waitingForProfile: Boolean, compiled: Boolean, stats: List<FrameStat>, smootherOn: Boolean): Advice? {
    if (debugBuild) return Advice.DebugBuild
    if (waitingForProfile) return Advice.NoProfile
    if (!compiled) return Advice.NotOptimized
    val profile = slowPercent(stats, "speed-profile")
    val full = slowPercent(stats, "speed")
    val current = if (smootherOn) full else profile
    return when {
        listOfNotNull(profile, full).minOrNull()?.let { it < SMOOTH_BELOW } == true -> Advice.Smooth
        current != null && current >= SMOOTH_BELOW && drawingBound(stats, if (smootherOn) "speed" else "speed-profile") -> Advice.DrawingBound(current.toInt())
        profile != null && profile >= CHOPPY_FROM && full == null && !smootherOn -> Advice.TrySmoother
        profile != null && profile >= CHOPPY_FROM && full != null && full >= CHOPPY_FROM && profile - full <= HELPS_BY -> Advice.SlowByItself
        current == null -> Advice.Measuring
        current < CHOPPY_FROM -> Advice.MostlySmooth(current.toInt())
        else -> null
    }
}
