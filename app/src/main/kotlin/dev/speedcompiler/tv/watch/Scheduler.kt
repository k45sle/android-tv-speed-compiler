package dev.speedcompiler.tv.watch

import dev.speedcompiler.tv.engine.GateStatus
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
    data class InQueue(val position: Int, val approxMillis: Long) : Eta
}

/** A job in the active queue. [position] is the number of jobs ahead of it (0 = next or running). */
data class QueueEntry(val item: QueueItem, val position: Int, val eta: Eta) {
    val state get() = item.state
}

const val DEFAULT_AVG_DURATION_MILLIS = 120_000L

fun etaFor(
    item: QueueItem,
    gate: GateStatus,
    position: Int,
    avgDurationMillis: Long = DEFAULT_AVG_DURATION_MILLIS,
): Eta {
    if (item.state != QueueState.QUEUED && item.state != QueueState.WAITING_GATE) return Eta.None
    val wait = position * avgDurationMillis
    if (item.runNow) return Eta.InQueue(position, wait)
    return when (gate) {
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

/** In-memory stand-in: keeps the queue order and ETAs but never runs a job. */
class FakeScheduler(var gateStatus: GateStatus = GateStatus.Open) : Scheduler {
    val calls = mutableListOf<String>()
    private val items = MutableStateFlow(emptyList<QueueItem>())
    private var nextId = 1L

    override val queue: Flow<List<QueueEntry>> = items.map { all ->
        all.filter { it.state in setOf(QueueState.QUEUED, QueueState.WAITING_GATE, QueueState.RUNNING) }
            .sortedWith(compareBy({ it.state != QueueState.RUNNING }, { !it.runNow }, { it.enqueuedAt }, { it.id }))
            .mapIndexed { i, item -> QueueEntry(item, i, etaFor(item, gateStatus, i)) }
    }

    override val history: Flow<List<QueueItem>> = items.map { all ->
        all.filter { it.state in setOf(QueueState.DONE, QueueState.FAILED, QueueState.SKIPPED) }.reversed()
    }

    override suspend fun watch(packageName: String) { calls += "watch:$packageName" }
    override suspend fun unwatch(packageName: String) { calls += "unwatch:$packageName" }
    override suspend fun enqueue(packageName: String, source: QueueSource) { calls += "enqueue:$packageName"; add(packageName, runNow = false) }
    override suspend fun optimizeNow(packageName: String) { calls += "optimizeNow:$packageName"; add(packageName, runNow = true) }
    override suspend fun cancel(id: Long) { calls += "cancel:$id"; setState(id, QueueState.SKIPPED) }
    override suspend fun retry(id: Long) { calls += "retry:$id"; setState(id, QueueState.QUEUED) }

    private fun add(packageName: String, runNow: Boolean) {
        val item = QueueItem(nextId++, packageName, QueueSource.MANUAL, enqueuedAt = System.currentTimeMillis(), runNow = runNow)
        items.update { it + item }
    }

    private fun setState(id: Long, state: QueueState) =
        items.update { all -> all.map { if (it.id == id) it.copy(state = state) else it } }
}
