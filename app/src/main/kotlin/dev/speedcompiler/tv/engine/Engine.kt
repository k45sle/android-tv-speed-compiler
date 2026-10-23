package dev.speedcompiler.tv.engine

import java.time.Instant

data class InstalledPackage(val packageName: String, val fingerprint: String)

/**
 * [connectionLost]: the compile failed because the ADB link dropped, so the job should retry after a reconnect.
 * [terminal]: the failure needs the user to act, so a later automatic retry would only fail again.
 */
data class OptimizeResult(val ok: Boolean, val detail: String = "", val connectionLost: Boolean = false, val terminal: Boolean = false)

enum class Wakefulness { Awake, Dozing, Asleep }

/** Stored when a build cannot reset; storedText() maps it for display. */
const val RESET_UNSUPPORTED = "Didn't reset: not supported here."

interface Optimizer {
    suspend fun optimize(packageName: String): OptimizeResult

    /** The compiler filter the TV reports for [packageName], or null when unknown. */
    suspend fun verify(packageName: String): String? = null

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
