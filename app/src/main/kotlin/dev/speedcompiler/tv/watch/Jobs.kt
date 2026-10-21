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
import dev.speedcompiler.tv.AppGraph
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.engine.ChangeDetector
import dev.speedcompiler.tv.engine.GateStatus
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val DETECT_WORK = "detect"
private const val VERIFY_WORK = "verify"
private const val KEY_BUILD = "build_fingerprint"

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
    val installed = g.packageSource.installed().map { it.packageName }.toSet()
    for (item in g.database.queue().activeNow()) if (item.packageName !in installed) g.scheduler.cancel(item.id)
    val watched = g.database.watchedApps()
    ChangeDetector(g.packageSystem, g.database.syncState()) { watched.list().filter { it.autoOptimize } }
        .detect().filter { it in installed }
        .forEach { g.scheduler.enqueue(it, QueueSource.AUTO) } // persisted before the next detect advances
}

/** Queues every auto-optimized app the TV no longer reports at `speed`. */
suspend fun runVerify(g: AppGraph) {
    val installed = g.packageSource.installed().map { it.packageName }.toSet()
    for (app in g.database.watchedApps().list()) {
        if (app.autoOptimize && app.packageName in installed && g.optimizer.verify(app.packageName) != "speed") {
            g.scheduler.enqueue(app.packageName, QueueSource.AUTO)
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

/** Runs one queue item: waits for the gate unless the user said "now", then compiles. */
class OptimizeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val dao = g.database.queue()
        val item = dao.get(inputData.getLong(KEY_ITEM_ID, 0))?.takeIf { it.state.isActive } ?: return Result.success()
        val pkg = item.packageName
        val fingerprint = g.packageSource.installed().firstOrNull { it.packageName == pkg }?.fingerprint
        if (fingerprint == null) {
            dao.update(item.copy(state = QueueState.SKIPPED, finishedAt = g.clock.nowMillis(), failureReason = "Not installed"))
            return Result.success()
        }
        if (!item.runNow) {
            val gate = g.gate.status()
            if (gate != GateStatus.Open) {
                dao.update(item.copy(state = QueueState.WAITING_GATE, failureReason = gate::class.simpleName))
                return Result.retry() // never re-enqueue under the same name: that would cancel this worker
            }
        }
        dao.update(item.copy(state = QueueState.RUNNING, startedAt = g.clock.nowMillis(), failureReason = null))
        val result = g.optimizer.optimize(pkg)
        if (result.connectionLost) {
            dao.update(item.copy(state = QueueState.WAITING_GATE, failureReason = result.detail))
            enqueueReconnect(applicationContext)
            return Result.retry()
        }
        val now = g.clock.nowMillis()
        dao.update(
            item.copy(
                state = if (result.ok) QueueState.DONE else QueueState.FAILED,
                finishedAt = now,
                failureReason = result.detail.takeUnless { result.ok },
            ),
        )
        // Record the fingerprint so the next detect does not queue this app again.
        val watched = g.database.watchedApps()
        watched.get(pkg)?.let { watched.upsert(it.copy(fingerprint = fingerprint, lastResult = result.detail, lastOptimizedAt = now)) }
        return Result.success()
    }

    /** Needed for expedited work below API 31. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, 3, "optimize", "Optimizing")
}
