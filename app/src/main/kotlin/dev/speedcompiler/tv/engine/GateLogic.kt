package dev.speedcompiler.tv.engine

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** When automatic optimizing may run. Settings maps onto this in the wiring chunk. */
enum class GateMode { Idle, NothingPlaying, Window }

/** Allowed hours as minutes since local midnight. A window with start after end crosses midnight. */
data class GateWindow(val startMinute: Int, val endMinute: Int)

sealed interface WaitReason {
    data object Paused : WaitReason
    data object Playback : WaitReason
    data object NotIdle : WaitReason
    data class OutsideWindow(val opensAt: Instant) : WaitReason
}

sealed interface GateDecision {
    data object Run : GateDecision
    data class Wait(val reason: WaitReason) : GateDecision
}

/**
 * Pure gate rule (plan 3.4). [wakefulness] null means the screen state could not be read (Unknown);
 * it behaves like NothingPlaying so an unreadable screen never blocks the gate. [manual] is "Optimize now",
 * which always runs.
 */
fun decide(
    now: ZonedDateTime,
    musicActive: Boolean,
    wakefulness: Wakefulness?,
    mode: GateMode,
    window: GateWindow,
    manual: Boolean,
    paused: Boolean,
): GateDecision {
    if (manual) return GateDecision.Run
    if (paused) return GateDecision.Wait(WaitReason.Paused)
    if (mode == GateMode.Window && !inWindow(now, window)) {
        return GateDecision.Wait(WaitReason.OutsideWindow(nextWindowStart(now, window)))
    }
    if (musicActive) return GateDecision.Wait(WaitReason.Playback)
    if (mode == GateMode.Idle && wakefulness == Wakefulness.Awake) return GateDecision.Wait(WaitReason.NotIdle)
    return GateDecision.Run
}

/** Start inclusive, end exclusive. start >= end wraps past midnight (equal bounds cover the whole day). */
fun inWindow(now: ZonedDateTime, window: GateWindow): Boolean {
    val minute = now.hour * 60 + now.minute
    val lower = window.startMinute
    val upper = window.endMinute
    return if (lower < upper) minute in lower until upper else minute >= lower || minute < upper
}

/** Next local start time after [now]; called only while the window is closed, so it is always in the future. */
fun nextWindowStart(now: ZonedDateTime, window: GateWindow): Instant {
    val start = LocalTime.of(window.startMinute / 60, window.startMinute % 60)
    val today = now.toLocalDate().atTime(start).atZone(now.zone)
    val opening = if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(start).atZone(now.zone)
    return opening.toInstant()
}
