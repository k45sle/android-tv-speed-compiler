package dev.speedcompiler.tv.watch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.speedcompiler.tv.AppGraph
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.engine.GateCache
import dev.speedcompiler.tv.engine.NO_PROFILE_YET
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val KEY_ITEM_ID = "itemId"
private const val DEBOUNCE_MINUTES = 5L
private const val BACKOFF_MINUTES = 15L
private const val GATE_TICK_MILLIS = 30_000L

fun workName(packageName: String) = "optimize:$packageName"

val QueueState.isActive get() = this == QueueState.QUEUED || this == QueueState.WAITING_GATE || this == QueueState.RUNNING

/** Entries with ETAs from [cache], which refreshes on every queue change, on a connection change and every 30 s while collected. */
fun queueWithEta(
    active: Flow<List<QueueItem>>,
    connection: Flow<ConnectionState>,
    cache: GateCache,
    storageLow: () -> Boolean = { false },
    launchFallbacks: suspend () -> Map<String, Instant> = { emptyMap() },
    tickMillis: Long = GATE_TICK_MILLIS,
): Flow<List<QueueEntry>> = flow {
    coroutineScope {
        launch { connection.distinctUntilChanged().collect { cache.refreshIfStale(force = true) } }
        // Each tick refreshes the gate and re-reads storage and the launch-wait deadlines, which have no flow of their own.
        val storage = flow { while (true) { cache.refreshIfStale(); emit(storageLow() to launchFallbacks()); delay(tickMillis) } }.distinctUntilChanged()
        emitAll(combine(active.onEach { cache.refreshIfStale() }, cache.status, connection.distinctUntilChanged(), storage) { items, gate, conn, (low, fallbacks) ->
            items.mapIndexed { i, item -> QueueEntry(item, i, etaFor(item, gate, i, connection = conn, storageLow = low, launchFallbackAt = fallbacks[item.packageName])) }
        })
    }
}

/** Room holds the queue, WorkManager holds one unique job per package. Reads [graph] at call time so tests can swap parts. */
class WorkScheduler(private val context: Context, private val graph: AppGraph) : Scheduler {
    private val queueDao get() = graph.database.queue()
    private val work get() = WorkManager.getInstance(context)

    /** ETAs read this cache, so a slow or disconnected TV never stalls the queue flow. */
    private val gateCache by lazy {
        GateCache(graph.clock, CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
            // The gate reads over ADB: skip it while the TV is unreachable instead of waiting on a connect.
            if (graph.connectionMonitor.state.first() == ConnectionState.Connected) graph.gate.status() else null
        }
    }

    override val queue: Flow<List<QueueEntry>> get() = queueWithEta(queueDao.active(), graph.connectionMonitor.state, gateCache, ::storageLow, ::launchFallbacks)

    private suspend fun launchFallbacks() = queueDao.activeNow().filter { it.failureReason == NO_PROFILE_YET }
        .mapNotNull { i -> launchFallbackAt(graph, i.packageName)?.let { i.packageName to Instant.ofEpochMilli(it) } }.toMap()

    /** Reads the same sticky broadcast WorkManager's storage-not-low constraint follows. */
    @Suppress("DEPRECATION")
    private fun storageLow(): Boolean {
        val filter = IntentFilter().apply { addAction(Intent.ACTION_DEVICE_STORAGE_LOW); addAction(Intent.ACTION_DEVICE_STORAGE_OK) }
        return context.registerReceiver(null, filter)?.action == Intent.ACTION_DEVICE_STORAGE_LOW
    }

    override val history: Flow<List<QueueItem>> get() = queueDao.history()

    /** One lock for every mutation: concurrent callers would both miss [QueueDao.activeFor] and insert twice. */
    private val lock = Mutex()

    override suspend fun watch(packageName: String) = lock.withLock {
        val fingerprint = graph.packageSystem.installed().firstOrNull { it.packageName == packageName }?.fingerprint ?: ""
        graph.database.watchedApps().upsert(WatchedApp(packageName, fingerprint))
        doEnqueue(packageName, QueueSource.AUTO)
    }

    override suspend fun unwatch(packageName: String): Unit = lock.withLock {
        graph.database.watchedApps().delete(packageName)
        resetConnectionTries(graph, packageName)
        dropRefresh(graph, packageName)
        queueDao.activeFor(packageName)?.let { doCancel(it.id) } ?: Unit
    }

    override suspend fun enqueue(packageName: String, source: QueueSource) = lock.withLock { doEnqueue(packageName, source) }

    private suspend fun doEnqueue(packageName: String, source: QueueSource) {
        val existing = queueDao.activeFor(packageName)
        if (existing == null) resetConnectionTries(graph, packageName) // a new job starts with its full tries
        // shortcut: an update that lands during a running compile is not re-queued (REPLACE would kill the worker); the weekly verify catches it.
        if (existing != null && (existing.state == QueueState.RUNNING || existing.runNow)) return
        // The row says RUNNING only once the worker holds runLock; REPLACE on a started worker could cancel its compile.
        if (existing != null && work.getWorkInfosForUniqueWorkFlow(workName(packageName)).first().any { it.state == WorkInfo.State.RUNNING }) return
        schedule(existing ?: insert(packageName, source, runNow = false), debounce = existing?.state != QueueState.WAITING_GATE)
    }

    override suspend fun optimizeNow(packageName: String) = lock.withLock { doOptimizeNow(packageName) }

    private suspend fun doOptimizeNow(packageName: String) {
        val existing = queueDao.activeFor(packageName)
        if (existing?.state == QueueState.RUNNING) return
        resetConnectionTries(graph, packageName)
        val item = if (existing == null) insert(packageName, QueueSource.MANUAL, runNow = true)
        else existing.copy(runNow = true, state = QueueState.QUEUED, failureReason = null).also { queueDao.update(it) }
        schedule(item)
    }

    override suspend fun cancel(id: Long) = lock.withLock { doCancel(id) }

    private suspend fun doCancel(id: Long) {
        val item = queueDao.get(id)?.takeIf { it.state.isActive } ?: return
        work.cancelUniqueWork(workName(item.packageName))
        followUpWorkNames(id).forEach { work.cancelUniqueWork(it) }
        resetConnectionTries(graph, item.packageName)
        // Drop any waiting reason, so the row reads as cancelled.
        queueDao.update(item.copy(state = QueueState.SKIPPED, finishedAt = graph.clock.nowMillis(), failureReason = null))
    }

    override suspend fun retry(id: Long) = lock.withLock { doRetry(id) }

    private suspend fun doRetry(id: Long) {
        val item = queueDao.get(id) ?: return
        if (item.state != QueueState.FAILED && item.state != QueueState.SKIPPED) return
        if (queueDao.activeFor(item.packageName) != null) return
        resetConnectionTries(graph, item.packageName)
        // A new row, so the failed one stays in the history as a record.
        schedule(insert(item.packageName, item.source, item.runNow))
    }

    private suspend fun insert(packageName: String, source: QueueSource, runNow: Boolean): QueueItem {
        val item = QueueItem(packageName = packageName, source = source, enqueuedAt = graph.clock.nowMillis(), runNow = runNow)
        return item.copy(id = queueDao.insert(item))
    }

    /** REPLACE restarts the debounce for a waiting job. Never call it for a running one. */
    private fun schedule(item: QueueItem, debounce: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<OptimizeWorker>()
            .setInputData(workDataOf(KEY_ITEM_ID to item.id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
        if (item.runNow) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) // not held by low storage: runs or fails with a reason
        else {
            request.setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            if (debounce) request.setInitialDelay(DEBOUNCE_MINUTES, TimeUnit.MINUTES)
        }
        work.enqueueUniqueWork(workName(item.packageName), ExistingWorkPolicy.REPLACE, request.build())
    }
}
