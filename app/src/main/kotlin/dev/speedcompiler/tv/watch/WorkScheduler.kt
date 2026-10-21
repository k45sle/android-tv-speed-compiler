package dev.speedcompiler.tv.watch

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.speedcompiler.tv.AppGraph
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val KEY_ITEM_ID = "itemId"
private const val DEBOUNCE_MINUTES = 5L
private const val BACKOFF_MINUTES = 15L

fun workName(packageName: String) = "optimize:$packageName"

val QueueState.isActive get() = this == QueueState.QUEUED || this == QueueState.WAITING_GATE || this == QueueState.RUNNING

/** Room holds the queue, WorkManager holds one unique job per package. Reads [graph] at call time so tests can swap parts. */
class WorkScheduler(private val context: Context, private val graph: AppGraph) : Scheduler {
    private val queueDao get() = graph.database.queue()
    private val work get() = WorkManager.getInstance(context)

    override val queue: Flow<List<QueueEntry>> get() = queueDao.active().map { items ->
        val gate = graph.gate.status()
        items.mapIndexed { i, item -> QueueEntry(item, i, etaFor(item, gate, i)) }
    }

    override val history: Flow<List<QueueItem>> get() = queueDao.history()

    /** One lock for every mutation: concurrent callers would both miss [QueueDao.activeFor] and insert twice. */
    private val lock = Mutex()

    override suspend fun watch(packageName: String) = lock.withLock {
        val fingerprint = graph.packageSource.installed().firstOrNull { it.packageName == packageName }?.fingerprint ?: ""
        graph.database.watchedApps().upsert(WatchedApp(packageName, fingerprint))
        doEnqueue(packageName, QueueSource.AUTO)
    }

    override suspend fun unwatch(packageName: String): Unit = lock.withLock {
        graph.database.watchedApps().delete(packageName)
        queueDao.activeFor(packageName)?.let { doCancel(it.id) } ?: Unit
    }

    override suspend fun enqueue(packageName: String, source: QueueSource) = lock.withLock { doEnqueue(packageName, source) }

    private suspend fun doEnqueue(packageName: String, source: QueueSource) {
        val existing = queueDao.activeFor(packageName)
        // shortcut: an update that lands during a running compile is not re-queued (REPLACE would kill the worker); the weekly verify catches it.
        if (existing != null && (existing.state == QueueState.RUNNING || existing.runNow)) return
        schedule(existing ?: insert(packageName, source, runNow = false))
    }

    override suspend fun optimizeNow(packageName: String) = lock.withLock { doOptimizeNow(packageName) }

    private suspend fun doOptimizeNow(packageName: String) {
        val existing = queueDao.activeFor(packageName)
        if (existing?.state == QueueState.RUNNING) return
        val item = if (existing == null) insert(packageName, QueueSource.MANUAL, runNow = true)
        else existing.copy(runNow = true, state = QueueState.QUEUED, failureReason = null).also { queueDao.update(it) }
        schedule(item)
    }

    override suspend fun cancel(id: Long) = lock.withLock { doCancel(id) }

    private suspend fun doCancel(id: Long) {
        val item = queueDao.get(id)?.takeIf { it.state.isActive } ?: return
        work.cancelUniqueWork(workName(item.packageName))
        queueDao.update(item.copy(state = QueueState.SKIPPED, finishedAt = graph.clock.nowMillis()))
    }

    override suspend fun retry(id: Long) = lock.withLock { doRetry(id) }

    private suspend fun doRetry(id: Long) {
        val item = queueDao.get(id) ?: return
        if (item.state != QueueState.FAILED && item.state != QueueState.SKIPPED) return
        if (queueDao.activeFor(item.packageName) != null) return
        val queued = item.copy(state = QueueState.QUEUED, finishedAt = null, failureReason = null)
        queueDao.update(queued)
        schedule(queued)
    }

    private suspend fun insert(packageName: String, source: QueueSource, runNow: Boolean): QueueItem {
        val item = QueueItem(packageName = packageName, source = source, enqueuedAt = graph.clock.nowMillis(), runNow = runNow)
        return item.copy(id = queueDao.insert(item))
    }

    /** REPLACE restarts the debounce for a waiting job. Never call it for a running one. */
    private fun schedule(item: QueueItem) {
        val request = OneTimeWorkRequestBuilder<OptimizeWorker>()
            .setInputData(workDataOf(KEY_ITEM_ID to item.id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
        if (item.runNow) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        else request.setInitialDelay(DEBOUNCE_MINUTES, TimeUnit.MINUTES)
        work.enqueueUniqueWork(workName(item.packageName), ExistingWorkPolicy.REPLACE, request.build())
    }
}
