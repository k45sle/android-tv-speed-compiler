package dev.speedcompiler.tv.watch

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.speedcompiler.tv.AppGraph
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.engine.ChangeDetector
import dev.speedcompiler.tv.engine.GateStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DETECT_WORK = "detect"
private const val VERIFY_WORK = "verify"
private const val RETRY_WORK = "retry"
private const val KEY_BUILD = "build_fingerprint"
private const val KEY_PACKAGE = "package"
private const val RETRY_DELAY_HOURS = 6L
private const val MAX_CONNECTION_TRIES = 5
private val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(90)

private val Context.graph get() = (applicationContext as SpeedApp).graph

/** Starts the periodic jobs. KEEP leaves existing schedules alone. */
fun schedulePeriodicJobs(context: Context) {
    val wm = WorkManager.getInstance(context)
    wm.enqueueUniquePeriodicWork(
        DETECT_WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<DetectWorker>(30, TimeUnit.MINUTES).build(),
    )
    wm.enqueueUniquePeriodicWork(
        VERIFY_WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<VerifyWorker>(7, TimeUnit.DAYS).build(),
    )
}

/** One-time detect, for boot. */
fun enqueueDetectNow(context: Context) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork("$DETECT_WORK-once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DetectWorker>().build())
}

private fun enqueueVerifyNow(context: Context) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork("$VERIFY_WORK-once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<VerifyWorker>().build())
}

/**
 * Queues changed watched apps. Uninstalls are handled here, not by a broadcast (implicit package broadcasts
 * cannot be declared in the manifest on API 26+): a queued job whose app is gone is cancelled. The WatchedApp row stays,
 * so a reinstall is picked up as a change.
 */
suspend fun runDetect(g: AppGraph) {
    val installed = g.packageSystem.installed().map { it.packageName }.toSet()
    for (item in g.database.queue().activeNow()) {
        if (item.packageName !in installed) g.scheduler.cancel(item.id)
        // A gate that opened mid-backoff: WorkManager's linear backoff grows to 5 h and would miss short windows.
        else if (item.state == QueueState.WAITING_GATE && g.gate.status(ignorePause = item.source == QueueSource.MANUAL) == GateStatus.Open) {
            g.scheduler.enqueue(item.packageName, item.source)
        }
    }
    val watched = g.database.watchedApps()
    ChangeDetector(g.packageSystem, g.database.syncState()) { watched.list().filter { it.autoOptimize } }
        .detect { changed -> // the stored position advances only after these are persisted
            changed.filter { it in installed }.forEach { g.scheduler.enqueue(it, QueueSource.AUTO) }
        }
}

/** Queues every auto-optimized app the TV no longer reports at `speed`. */
suspend fun runVerify(g: AppGraph) {
    val cutoff = g.clock.nowMillis() - RETENTION_MILLIS
    g.database.events().pruneBefore(cutoff)
    g.database.queue().pruneFinished(cutoff)
    val installed = g.packageSystem.installed().map { it.packageName }.toSet()
    for (app in g.database.watchedApps().list()) {
        if (!app.autoOptimize || app.packageName !in installed) continue
        when (g.optimizer.verify(app.packageName)) {
            "speed" -> {}
            // Unreadable output must not re-queue every app each week.
            null -> g.database.events().insert(
                Event(timeMillis = g.clock.nowMillis(), packageName = app.packageName, kind = "verify_unknown", message = "The TV did not report this app's compiler filter."),
            )
            else -> g.scheduler.enqueue(app.packageName, QueueSource.AUTO)
        }
    }
}

/** True once per system update: the stored build fingerprint differs from [current]. The first run only stores it. */
suspend fun systemUpdated(state: SyncStateDao, current: String): Boolean {
    val stored = state.get(KEY_BUILD)
    state.put(SyncState(KEY_BUILD, current))
    return stored != null && stored != current
}

class DetectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        if (g.connectionKeeper.status.value != KeeperStatus.Connected) g.connectionKeeper.reconnect()
        runDetect(g)
        if (systemUpdated(g.database.syncState(), Build.FINGERPRINT)) enqueueVerifyNow(applicationContext)
        return Result.success()
    }
}

class VerifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        runVerify(applicationContext.graph)
        Result.success()
    } catch (e: IOException) { // the ADB link is down
        enqueueReconnect(applicationContext)
        Result.retry()
    }
}

/** Gives the app's next job its full set of connection tries again. */
suspend fun resetConnectionTries(g: AppGraph, packageName: String) = g.database.syncState().put(SyncState("lost:$packageName", "0"))

/** One later try for a failed app. Its own work name, so it never touches the optimize worker's REPLACE. */
private fun enqueueRetryLater(context: Context, packageName: String) {
    val request = OneTimeWorkRequestBuilder<RetryWorker>()
        .setInitialDelay(RETRY_DELAY_HOURS, TimeUnit.HOURS)
        .setInputData(workDataOf(KEY_PACKAGE to packageName))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("$RETRY_WORK:$packageName", ExistingWorkPolicy.REPLACE, request)
}

/** Queues the app again if it is still watched and installed. */
class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val pkg = inputData.getString(KEY_PACKAGE) ?: return Result.success()
        if (g.database.watchedApps().get(pkg)?.autoOptimize == true && g.packageSystem.fingerprint(pkg) != null) {
            g.scheduler.enqueue(pkg, QueueSource.AUTO)
        }
        return Result.success()
    }
}

private val runLock = Mutex()
private val runNowWaiting = AtomicInteger(0)

/** Runs one queue item: waits for the gate unless the user said "now", then compiles. One compile at a time. */
class OptimizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val id = inputData.getLong(KEY_ITEM_ID, 0)
        val runNow = g.database.queue().get(id)?.takeIf { it.state.isActive }?.runNow ?: return Result.success()
        if (runNow) runNowWaiting.incrementAndGet()
        try {
            while (true) {
                // A waiting "Optimize now" goes first: other jobs take the lock, see it, and step aside.
                val result = runLock.withLock { if (runNow || runNowWaiting.get() == 0) run(g, id) else null }
                if (result != null) return result
                delay(1_000)
            }
        } finally {
            if (runNow) runNowWaiting.decrementAndGet()
        }
    }

    /** Called with [runLock] held. Whatever goes wrong, the row ends up finished, never stuck in RUNNING or QUEUED. */
    private suspend fun run(g: AppGraph, id: Long): Result = try {
        runItem(g, id)
    } catch (e: CancellationException) {
        // Android stopped the worker mid-compile: WorkManager reruns it later, so the row must not claim RUNNING meanwhile.
        withContext(NonCancellable) {
            val dao = g.database.queue()
            dao.get(id)?.takeIf { it.state == QueueState.RUNNING }?.let { dao.update(it.copy(state = QueueState.QUEUED, startedAt = null)) }
        }
        throw e
    } catch (e: Exception) {
        val dao = g.database.queue()
        dao.get(id)?.takeIf { it.state.isActive }?.let {
            resetConnectionTries(g, it.packageName)
            dao.update(it.copy(state = QueueState.FAILED, finishedAt = g.clock.nowMillis(), failureReason = "Didn't finish: unexpected error (${e.javaClass.simpleName})."))
        }
        Result.success()
    }

    private suspend fun runItem(g: AppGraph, id: Long): Result {
        val dao = g.database.queue()
        val item = dao.get(id)?.takeIf { it.state.isActive } ?: return Result.success()
        val pkg = item.packageName
        val fingerprint = g.packageSystem.installed().firstOrNull { it.packageName == pkg }?.fingerprint
        if (fingerprint == null) {
            resetConnectionTries(g, pkg)
            dao.update(item.copy(state = QueueState.SKIPPED, finishedAt = g.clock.nowMillis(), failureReason = "Not installed"))
            return Result.success()
        }
        if (!item.runNow) {
            // Checked after taking the lock: the window may have closed while this job waited its turn.
            val gate = g.gate.status(ignorePause = item.source == QueueSource.MANUAL)
            if (gate != GateStatus.Open) {
                dao.update(item.copy(state = QueueState.WAITING_GATE, failureReason = gate::class.simpleName))
                return Result.retry() // never re-enqueue under the same name: that would cancel this worker
            }
        }
        dao.update(item.copy(state = QueueState.RUNNING, startedAt = g.clock.nowMillis(), failureReason = null))
        val result = g.optimizer.optimize(pkg)
        val sync = g.database.syncState()
        val lostKey = "lost:$pkg" // counted here, not by runAttemptCount: gate waits retry too, and a detect pass starts a fresh worker
        if (result.connectionLost) {
            val tries = (sync.get(lostKey)?.toIntOrNull() ?: 0) + 1
            if (tries < MAX_CONNECTION_TRIES) {
                sync.put(SyncState(lostKey, tries.toString()))
                dao.update(item.copy(state = QueueState.WAITING_GATE, failureReason = result.detail))
                enqueueReconnect(applicationContext)
                return Result.retry()
            }
        }
        resetConnectionTries(g, pkg)
        val now = g.clock.nowMillis()
        // Stores the fingerprint read BEFORE compiling, so an update that lands mid-compile still differs and the
        // next full compare (or the weekly verify, if an incremental detect already moved past it) queues it again.
        // Re-enqueueing from here is not possible: REPLACE under this worker's own name would cancel it.
        // Written while the row still says RUNNING, so an enqueue cannot REPLACE this worker before it lands.
        // A targeted UPDATE, not a read-modify-write: a toggle of autoOptimize during the compile must survive.
        g.database.watchedApps().recordResult(pkg, fingerprint, result.detail, now)
        dao.update(
            item.copy(
                state = if (result.ok) QueueState.DONE else QueueState.FAILED,
                finishedAt = now,
                failureReason = result.detail.takeUnless { result.ok },
            ),
        )
        // The fingerprint is stored either way, so the app would wait for the weekly verify: try once more later.
        val retriedKey = "retried:$pkg"
        if (result.ok) {
            sync.put(SyncState(retriedKey, ""))
            WorkManager.getInstance(applicationContext).cancelUniqueWork("$RETRY_WORK:$pkg") // an app at speed needs no later try
        } else if (!result.terminal && sync.get(retriedKey) != fingerprint) {
            sync.put(SyncState(retriedKey, fingerprint))
            enqueueRetryLater(applicationContext, pkg)
        }
        return Result.success()
    }

    /** Needed for expedited work below API 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, 3, "optimize", "Optimizing")
}
