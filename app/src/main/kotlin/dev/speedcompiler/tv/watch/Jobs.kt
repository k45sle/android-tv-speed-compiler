package dev.speedcompiler.tv.watch

import dev.speedcompiler.tv.engine.NOT_INSTALLED
import dev.speedcompiler.tv.engine.DEBUG_BUILD
import dev.speedcompiler.tv.engine.NO_PROFILE_YET
import dev.speedcompiler.tv.engine.OptimizeHow
import dev.speedcompiler.tv.engine.VERIFY_UNKNOWN
import dev.speedcompiler.tv.engine.unexpectedError
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
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
import dev.speedcompiler.tv.KEY_SELF_COMPILED
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DETECT_WORK = "detect"
private const val VERIFY_WORK = "verify"
private const val RETRY_WORK = "retry"
private const val OPEN_GATES_WORK = "open-gates"
private const val OPEN_GATES_SETTLE_SECONDS = 5L
private const val KEY_BUILD = "build_fingerprint"
private const val KEY_PACKAGE = "package"
private const val RETRY_DELAY_HOURS = 6L
private const val REFUSED_WORK = "refused"
private const val REFUSED_RETRY_MINUTES = 15L
private const val LAUNCH_WORK = "launch"
private const val LAUNCH_RETRY_MINUTES = 60L
private const val NO_PROFILE_FALLBACK_MILLIS = 2 * 24 * 60 * 60 * 1000L
private const val MIN_FREE_BYTES = 1L shl 30
private const val REFRESH_WINDOW_MILLIS = 7 * 24 * 60 * 60 * 1000L
private const val REFRESH_EVERY_MILLIS = 24 * 60 * 60 * 1000L
private const val REFRESH_PREFIX = "refresh:"
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

/**
 * Screen off or screensaver start: check the gates in a few seconds. The delay lets dumpsys power report the new
 * wakefulness; going through WorkManager keeps the process alive until the check ran.
 */
fun enqueueOpenGatesCheck(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        OPEN_GATES_WORK, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<OpenGatesWorker>().setInitialDelay(OPEN_GATES_SETTLE_SECONDS, TimeUnit.SECONDS).build(),
    )
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
    }
    restartOpenedGates(g, installed)
    val watched = g.database.watchedApps()
    ChangeDetector(g.packageSystem, g.database.syncState()) { watched.list().filter { it.autoOptimize } }
        .detect { changed -> // the stored position advances only after these are persisted
            changed.filter { it in installed }.forEach { g.scheduler.enqueue(it, QueueSource.AUTO) }
        }
}

/** When an app still waiting for use gets the plain `speed` compile: two days after the wait began. Null when unknown. */
suspend fun launchFallbackAt(g: AppGraph, pkg: String): Long? =
    g.database.syncState().get(noProfileKey(pkg))?.split('|')?.getOrNull(1)?.toLongOrNull()?.plus(NO_PROFILE_FALLBACK_MILLIS)

@VisibleForTesting internal var freeBytes: () -> Long = { StatFs(Environment.getDataDirectory().path).availableBytes }

/**
 * Whether a stored `fingerprint|firstCompile|lastRefresh` record is due: same build of the app, first compiled less than
 * 7 days ago, and not refreshed in the last 24 h.
 */
internal fun refreshDue(record: String, fingerprint: String, now: Long): Boolean {
    val p = record.split('|')
    val first = p.getOrNull(1)?.toLongOrNull() ?: return false
    val last = p.getOrNull(2)?.toLongOrNull() ?: return false
    return p[0] == fingerprint && now - first < REFRESH_WINDOW_MILLIS && now - last >= REFRESH_EVERY_MILLIS
}

/** While the gate is open, re-runs a plain speed-profile compile for apps compiled from their shipped profile in the last 7 days. */
private suspend fun refreshProfiles(g: AppGraph, installed: Set<String>) {
    val sync = g.database.syncState()
    if (sync.withPrefix(REFRESH_PREFIX).none { it.value.isNotEmpty() } || g.gate.status() != GateStatus.Open || !runLock.tryLock()) return
    try {
        if (g.settings.values.first().optimizeHow == OptimizeHow.Full) return
        // Read again under the lock: a compile that finished meanwhile may have written a fresh record.
        val records = sync.withPrefix(REFRESH_PREFIX).filter { it.value.isNotEmpty() }
        val now = g.clock.nowMillis()
        for (key in records.map { it.key }) {
            // Fresh per app: a Reset or unwatch during an earlier refresh drops the record without the lock.
            val value = sync.get(key).orEmpty().ifEmpty { continue }
            val r = SyncState(key, value)
            val pkg = key.removePrefix(REFRESH_PREFIX)
            val fingerprint = g.packageSystem.fingerprint(pkg)
            val p = value.split('|')
            val expired = fingerprint == null || p[0] != fingerprint || now - (p.getOrNull(1)?.toLongOrNull() ?: 0) >= REFRESH_WINDOW_MILLIS ||
                g.database.watchedApps().get(pkg)?.autoOptimize != true
            if (expired) { sync.put(SyncState(r.key, "")); continue }
            if (pkg !in installed || !refreshDue(r.value, fingerprint, now)) continue
            sync.put(SyncState(r.key, "${p[0]}|${p[1]}|$now")) // before the call, so a failure is not retried at once
            try {
                g.optimizer.refresh(pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("refreshProfiles", "refresh failed: ${e.javaClass.simpleName}")
            }
        }
    } finally {
        runLock.unlock()
    }
}

/** Stops the after-use refreshes, as after a Reset. */
suspend fun dropRefresh(g: AppGraph, packageName: String) = g.database.syncState().put(SyncState(REFRESH_PREFIX + packageName, ""))

/**
 * Runs rows whose gate opened mid-backoff: WorkManager's linear backoff grows to 5 h and would miss short windows.
 * Called by every detect and when the screen turns off, so the idle gate starts its jobs within seconds.
 * Also the moment for the cheap profile refreshes.
 */
suspend fun restartOpenedGates(g: AppGraph, installed: Set<String> = g.packageSystem.installed().map { it.packageName }.toSet()) {
    for (item in g.database.queue().activeNow()) {
        // A row waiting for the app's first launch has its own hourly try; restarting it here would compile every 30 min.
        if (item.packageName in installed && item.state == QueueState.WAITING_GATE && item.failureReason != NO_PROFILE_YET &&
            g.gate.status(ignorePause = item.source == QueueSource.MANUAL) == GateStatus.Open
        ) {
            g.scheduler.enqueue(item.packageName, item.source)
        }
    }
    refreshProfiles(g, installed)
}

/** Queues every auto-optimized app the TV no longer reports at `speed` or `speed-profile`. */
suspend fun runVerify(g: AppGraph) {
    val cutoff = g.clock.nowMillis() - RETENTION_MILLIS
    g.database.events().pruneBefore(cutoff)
    g.database.queue().pruneFinished(cutoff)
    val installed = g.packageSystem.installed().map { it.packageName }.toSet()
    for (app in g.database.watchedApps().list()) {
        if (!app.autoOptimize || app.packageName !in installed || g.packageSystem.isDebuggable(app.packageName)) continue
        when (g.optimizer.verify(app.packageName)) {
            "speed", "speed-profile" -> {}
            // Unreadable output must not re-queue every app each week.
            null -> g.database.events().insert(
                Event(timeMillis = g.clock.nowMillis(), packageName = app.packageName, kind = "verify_unknown", message = VERIFY_UNKNOWN),
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
        // Not gated on the keeper: it can still say Connected after Wireless debugging was switched off.
        if (g.settings.values.first().keepWifiDebugging) enableWifiAdb(applicationContext)
        if (g.connectionKeeper.status.value != KeeperStatus.Connected) g.connectionKeeper.reconnect()
        runDetect(g)
        if (systemUpdated(g.database.syncState(), Build.FINGERPRINT)) enqueueVerifyNow(applicationContext)
        return Result.success()
    }
}

class OpenGatesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            restartOpenedGates(applicationContext.graph)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // best effort: the backoff and the 30 min detect still catch it
            Log.w("OpenGatesWorker", "gate check failed: ${e.javaClass.simpleName}")
        }
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
fun lostKey(pkg: String) = "lost:$pkg"
fun retriedKey(pkg: String) = "retried:$pkg"
private fun refusedKey(pkg: String) = "refused:$pkg"
private fun noProfileKey(pkg: String) = "noprofile:$pkg"

/** Delayed reruns of one row, under their own work names; a cancel must stop them too. */
fun followUpWorkNames(itemId: Long) = listOf("$REFUSED_WORK:$itemId", "$LAUNCH_WORK:$itemId")

suspend fun resetConnectionTries(g: AppGraph, packageName: String) {
    g.database.syncState().put(SyncState(lostKey(packageName), "0"))
    g.database.syncState().put(SyncState(refusedKey(packageName), "0"))
}

/** One later try for a failed app. Its own work name, so it never touches the optimize worker's REPLACE. */
private fun enqueueRetryLater(context: Context, packageName: String) {
    val request = OneTimeWorkRequestBuilder<RetryWorker>()
        .setInitialDelay(RETRY_DELAY_HOURS, TimeUnit.HOURS)
        .setInputData(workDataOf(KEY_PACKAGE to packageName))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("$RETRY_WORK:$packageName", ExistingWorkPolicy.REPLACE, request)
}

/** Runs the same row again after a fixed delay. Its own work name, so it never touches the optimize worker's REPLACE. */
private fun enqueueRefusedRetry(context: Context, itemId: Long) {
    val request = OneTimeWorkRequestBuilder<OptimizeWorker>()
        .setInitialDelay(REFUSED_RETRY_MINUTES, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
        .setInputData(workDataOf(KEY_ITEM_ID to itemId))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("$REFUSED_WORK:$itemId", ExistingWorkPolicy.REPLACE, request)
}

/** Waits for the app's first launch: same row again after a fixed delay, under its own work name. */
private fun enqueueLaunchRetry(context: Context, itemId: Long) {
    val request = OneTimeWorkRequestBuilder<OptimizeWorker>()
        .setInitialDelay(LAUNCH_RETRY_MINUTES, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
        .setInputData(workDataOf(KEY_ITEM_ID to itemId))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("$LAUNCH_WORK:$itemId", ExistingWorkPolicy.REPLACE, request)
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

/** True while a compile or refresh holds the lock; the frame sampler stays off the ADB link then. */
internal fun compileRunning() = runLock.isLocked
@VisibleForTesting internal val runNowWaiting = AtomicInteger(0)

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
        withContext(NonCancellable) { g.database.queue().requeueIfRunning(id) } // atomic, so a concurrent cancel's SKIPPED wins
        throw e
    } catch (e: Exception) {
        val dao = g.database.queue()
        dao.get(id)?.takeIf { it.state.isActive }?.let {
            resetConnectionTries(g, it.packageName)
            dao.update(it.copy(state = QueueState.FAILED, finishedAt = g.clock.nowMillis(), failureReason = unexpectedError(e.javaClass.simpleName)))
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
            dao.update(item.copy(state = QueueState.SKIPPED, finishedAt = g.clock.nowMillis(), failureReason = NOT_INSTALLED))
            return Result.success()
        }
        if (g.packageSystem.isDebuggable(pkg)) { // ART stops at verify for these, so a compile can only fail
            resetConnectionTries(g, pkg)
            dao.update(item.copy(state = QueueState.FAILED, finishedAt = g.clock.nowMillis(), failureReason = DEBUG_BUILD))
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
        val full = g.settings.values.first().optimizeHow == OptimizeHow.Full || g.database.watchedApps().get(pkg)?.useSpeed == true
        var result = if (full) g.optimizer.optimize(pkg, "speed") else g.optimizer.optimize(pkg)
        val sync = g.database.syncState()
        if (result.noProfile) {
            // No shipped profile and none from use yet: wait for a launch, and after 2 days settle for plain speed (storage permitting).
            val key = noProfileKey(pkg)
            val now = g.clock.nowMillis()
            val stored = sync.get(key)?.split('|')
            val firstSeen = if (stored?.size == 2 && stored[0] == fingerprint) stored[1].toLongOrNull() else null
            if (firstSeen == null) sync.put(SyncState(key, "$fingerprint|$now"))
            if (now - (firstSeen ?: now) < NO_PROFILE_FALLBACK_MILLIS || freeBytes() < MIN_FREE_BYTES) {
                dao.update(item.copy(state = QueueState.WAITING_GATE, failureReason = result.detail))
                enqueueLaunchRetry(applicationContext, id)
                return Result.success()
            }
            result = g.optimizer.optimize(pkg, "speed")
        }
        val lostKey = lostKey(pkg) // counted here, not by runAttemptCount: gate waits retry too, and a detect pass starts a fresh worker
        if (result.connectionLost) {
            val tries = (sync.get(lostKey)?.toIntOrNull() ?: 0) + 1
            if (tries < MAX_CONNECTION_TRIES) {
                sync.put(SyncState(lostKey, tries.toString()))
                dao.update(item.copy(state = QueueState.WAITING_GATE, failureReason = result.detail))
                enqueueReconnect(applicationContext)
                return Result.retry()
            }
        }
        // The TV's own background dexopt can collide with ours and make it refuse: one try after a fixed 15 min (Result.retry would grow linearly), still behind the gate.
        val refusedKey = refusedKey(pkg)
        if (result.refused && sync.get(refusedKey) != "1") {
            sync.put(SyncState(refusedKey, "1"))
            dao.update(item.copy(state = QueueState.WAITING_GATE, failureReason = result.detail))
            enqueueRefusedRetry(applicationContext, id)
            return Result.success()
        }
        resetConnectionTries(g, pkg)
        // Cleared only when a compile finishes: Optimize now on a waiting row must not restart the 2-day clock.
        sync.put(SyncState(noProfileKey(pkg), ""))
        val now = g.clock.nowMillis()
        // Stores the fingerprint read BEFORE compiling, so an update that lands mid-compile still differs and the
        // next full compare (or the weekly verify, if an incremental detect already moved past it) queues it again.
        // Re-enqueueing from here is not possible: REPLACE under this worker's own name would cancel it.
        // Written while the row still says RUNNING, so an enqueue cannot REPLACE this worker before it lands.
        // A targeted UPDATE, not a read-modify-write: a toggle of autoOptimize during the compile must survive.
        g.database.watchedApps().recordResult(pkg, fingerprint, result.detail, now)
        sync.put(SyncState(REFRESH_PREFIX + pkg, if (result.ok && result.refreshable) "$fingerprint|$now|$now" else ""))
        if (result.ok && pkg == applicationContext.packageName) sync.put(SyncState(KEY_SELF_COMPILED, fingerprint))
        dao.update(
            item.copy(
                state = if (result.ok) QueueState.DONE else QueueState.FAILED,
                finishedAt = now,
                failureReason = result.detail.takeUnless { result.ok },
            ),
        )
        // The fingerprint is stored either way, so the app would wait for the weekly verify: try once more later.
        val retriedKey = retriedKey(pkg)
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
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext, 3, "optimize", applicationContext.getString(dev.speedcompiler.tv.R.string.notif_optimizing))
}
