package dev.speedcompiler.tv.ui.apps

import dev.speedcompiler.tv.watch.FrameStat

/** Filters need this many frames before their share of slow frames is trusted. */
const val MIN_FILTER_FRAMES = 3000
const val SMOOTH_BELOW = 15
const val CHOPPY_FROM = 25
private const val HELPS_BY = 5

/** One line of advice for App detail, from the first condition that holds. NoProfile and NotOptimized are already the state line. */
enum class Advice { DebugBuild, NoProfile, NotOptimized, Smooth, TrySmoother, SlowByItself }

/** Percent of slow frames for [filter] over [stats] (already one app and version), or null with under [MIN_FILTER_FRAMES] frames. */
private fun slowPercent(stats: List<FrameStat>, filter: String): Double? {
    val rows = stats.filter { it.filter == filter }
    val frames = rows.sumOf { it.frames }
    return if (frames < MIN_FILTER_FRAMES) null else 100.0 * rows.sumOf { it.janky } / frames
}

fun adviceFor(debugBuild: Boolean, waitingForProfile: Boolean, compiled: Boolean, stats: List<FrameStat>, smootherOn: Boolean): Advice? {
    if (debugBuild) return Advice.DebugBuild
    if (waitingForProfile) return Advice.NoProfile
    if (!compiled) return Advice.NotOptimized
    val profile = slowPercent(stats, "speed-profile")
    val full = slowPercent(stats, "speed")
    return when {
        listOfNotNull(profile, full).minOrNull()?.let { it < SMOOTH_BELOW } == true -> Advice.Smooth
        profile == null || profile < CHOPPY_FROM -> null
        full == null -> if (smootherOn) null else Advice.TrySmoother
        full >= CHOPPY_FROM && profile - full <= HELPS_BY -> Advice.SlowByItself
        else -> null
    }
}
