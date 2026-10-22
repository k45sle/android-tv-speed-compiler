package dev.speedcompiler.tv.watch

import dev.speedcompiler.tv.engine.GateStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

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
