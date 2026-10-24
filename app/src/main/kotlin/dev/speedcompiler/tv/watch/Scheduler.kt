package dev.speedcompiler.tv.watch

import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.engine.GateStatus
import dev.speedcompiler.tv.engine.NO_PROFILE_YET
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** When a queued job will run. Only [StartsAt] names a clock time, and only when the user set hours. */
sealed interface Eta {
    data object None : Eta
    data class StartsAt(val at: Instant) : Eta
    data object WaitingForIdle : Eta
    data object WaitingForPlayback : Eta
    data object Paused : Eta
    /** Automatic job held back by low storage. */
    data object WaitingForStorage : Eta
    /** The last compile found no usage profile: the app has not been opened since its install or update. */
    data object WaitingForLaunch : Eta
    data object WaitingForConnection : Eta
    data object Connecting : Eta
    /** Connected, but the first gate check has not come back yet. */
    data object Checking : Eta
    /** No cause is known. [etaFor] never returns it. */
    data object Unknown : Eta
    data class InQueue(val position: Int, val approxMillis: Long) : Eta
}

/** A job in the active queue. [position] is the number of jobs ahead of it (0 = next or running). */
data class QueueEntry(val item: QueueItem, val position: Int, val eta: Eta) {
    val state get() = item.state
}

const val DEFAULT_AVG_DURATION_MILLIS = 120_000L

fun etaFor(
    item: QueueItem,
    gate: GateStatus?,
    position: Int,
    avgDurationMillis: Long = DEFAULT_AVG_DURATION_MILLIS,
    connection: ConnectionState = ConnectionState.Connected,
    storageLow: Boolean = false,
): Eta {
    if (item.state != QueueState.QUEUED && item.state != QueueState.WAITING_GATE) return Eta.None
    val wait = position * avgDurationMillis
    if (item.state == QueueState.WAITING_GATE && item.failureReason == NO_PROFILE_YET) return Eta.WaitingForLaunch
    if (item.runNow) return Eta.InQueue(position, wait)
    if (storageLow) return Eta.WaitingForStorage
    return when (gate) {
        null -> when (connection) {
            ConnectionState.Unpaired, ConnectionState.Disconnected -> Eta.WaitingForConnection
            ConnectionState.Connecting -> Eta.Connecting
            ConnectionState.Connected -> Eta.Checking
        }
        GateStatus.Open -> Eta.InQueue(position, wait)
        GateStatus.WaitingForIdle -> Eta.WaitingForIdle
        GateStatus.WaitingForPlayback -> Eta.WaitingForPlayback
        GateStatus.Paused -> Eta.Paused
        is GateStatus.OpensAt -> Eta.StartsAt(gate.at.plusMillis(wait))
    }
}

/** One ordered queue, one job at a time. Jobs come from detected updates or the user. */
interface Scheduler {
    /** Active jobs (queued, waiting for the gate, running) in run order. */
    val queue: Flow<List<QueueEntry>>
    /** Most recent finished jobs, newest first, so failures can be retried. */
    val history: Flow<List<QueueItem>>
    suspend fun watch(packageName: String)
    suspend fun unwatch(packageName: String)
    /** "Add to queue" (or a detected update): waits its turn and for the gate. */
    suspend fun enqueue(packageName: String, source: QueueSource = QueueSource.MANUAL)
    /** "Optimize now": skips the gate and goes to the front. */
    suspend fun optimizeNow(packageName: String)
    suspend fun cancel(id: Long)
    suspend fun retry(id: Long)
}
