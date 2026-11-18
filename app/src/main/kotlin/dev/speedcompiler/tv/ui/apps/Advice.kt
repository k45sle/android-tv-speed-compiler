package dev.speedcompiler.tv.ui.apps

import dev.speedcompiler.tv.watch.DRAW_BOUND_UI_SHARE
import dev.speedcompiler.tv.watch.FrameStat
import dev.speedcompiler.tv.watch.MIN_CAUSE_FRAMES

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

/**
 * True when the stutter of [filter] is mostly not on the UI thread, where the app's code runs (interpreted code included).
 * JIT time at startup is not part of this: on the test TV, Nuvio Arc spent 3% of its first minute in JIT with 6% of its slow
 * frames on the UI thread, and recompiling it took JIT to 0.1% with the same share of slow frames. The JIT work has its own
 * action (the recompile in watch/Jobs.kt), not a different answer to where the stutter is.
 */
private fun drawingBound(stats: List<FrameStat>, filter: String): Boolean {
    // Only rows the sampler wrote with cause counts: rows from before schema 5 have slow frames but no causes and no duration,
    // and would make every app look drawing-bound.
    val rows = stats.filter { it.filter == filter && it.durationMs > 0 }
    val janky = rows.sumOf { it.janky }
    // No cause counts at all (a TV that does not print them) says nothing about where the stutter is.
    if (rows.sumOf { it.slowUi + it.slowBitmap + it.slowDraw } == 0L) return false
    return janky >= MIN_CAUSE_FRAMES && rows.sumOf { it.slowUi }.toDouble() / janky < DRAW_BOUND_UI_SHARE
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
