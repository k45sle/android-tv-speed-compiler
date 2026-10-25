package dev.speedcompiler.tv.engine

import java.time.Instant

data class InstalledPackage(val packageName: String, val fingerprint: String)

/**
 * [connectionLost]: the compile failed because the ADB link dropped, so the job should retry after a reconnect.
 * [noProfile]: the app has no usage profile yet (it was never launched since its last update), so speed-profile did nothing.
 * [terminal]: the failure needs the user to act, so a later automatic retry would only fail again.
 * [refreshable]: the app's shipped profile was loaded and the compile reached speed-profile, so a later plain compile can pick up newer profile data.
 */
data class OptimizeResult(val ok: Boolean, val detail: String = "", val connectionLost: Boolean = false, val terminal: Boolean = false, val refused: Boolean = false, val noProfile: Boolean = false, val refreshable: Boolean = false)

enum class Wakefulness { Awake, Dozing, Asleep }

/** Stored when a build cannot reset; storedText() maps it for display. */
const val RESET_UNSUPPORTED = "Didn't reset: not supported here."

interface Optimizer {
    suspend fun optimize(packageName: String, filter: String = "speed-profile"): OptimizeResult

    /** The compiler filter the TV reports for [packageName], or null when unknown. */
    suspend fun verify(packageName: String): String? = null

    /** A plain, non-forced speed-profile compile: ART recompiles only if new profile data was merged. Failures are ignored. */
    suspend fun refresh(packageName: String) {}

    /** Puts [packageName] back to the TV's own compile state. */
    suspend fun reset(packageName: String): OptimizeResult = OptimizeResult(false, RESET_UNSUPPORTED)
}

/** Why the gate is open or closed. Only [OpensAt] carries a clock time, taken from the user's set hours. */
sealed interface GateStatus {
    data object Open : GateStatus
    data object WaitingForPlayback : GateStatus
    data object WaitingForIdle : GateStatus
    data object Paused : GateStatus
    data class OpensAt(val at: Instant) : GateStatus
}

/** Decides whether optimizing is allowed right now (window, pause switch, device state). */
interface Gate {
    /** [ignorePause]: for jobs the user queued by hand, which wait for the window and device state but not the pause switch. */
    suspend fun status(ignorePause: Boolean = false): GateStatus
}

interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis() = System.currentTimeMillis()
}
