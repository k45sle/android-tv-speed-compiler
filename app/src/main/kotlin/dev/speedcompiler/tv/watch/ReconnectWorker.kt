package dev.speedcompiler.tv.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.adb.ConnectionKeeper
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.adb.KeeperStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

const val RECONNECT_WORK = "reconnect"
private const val NETWORK_WAIT_MS = 60_000L
private val BACKOFF_MS = listOf(5_000L, 10_000L, 20_000L, 40_000L)

/** The one entry point for boot, app open and the periodic job. Does nothing if a reconnect is already queued. */
fun enqueueReconnect(context: Context) {
    val request = OneTimeWorkRequestBuilder<ReconnectWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(RECONNECT_WORK, ExistingWorkPolicy.KEEP, request)
}

/**
 * Waits for the network, then tries [reconnect] up to `backoff.size + 1` times, pausing between tries.
 * Stops early once connected or when the user has to act. Without a network it tries anyway.
 * With [retryNotAllowed] (the legacy port) a refused key is retried too: these tries never show the TV's prompt,
 * and right after boot the TV may refuse a key it accepts a few seconds later. It is left off when the check knows
 * the TV won't keep the key, so those runs stop at once and Home offers Fix connection.
 * Calls [onGaveUp] with the last status only when every try was refused ([Fix.AllowPrompt]) and no try connected
 * or stopped early, never on an early stop. A run where the port was closed for some tries, or timed out,
 * says nothing about the key.
 * Calls [enableWifiAdb] only when [turnOnWifiAdb] (the Keep Wireless debugging on setting).
 */
suspend fun reconnectWithRetry(
    turnOnWifiAdb: Boolean,
    waitForNetwork: suspend () -> Boolean,
    enableWifiAdb: () -> Unit,
    reconnect: suspend () -> KeeperStatus,
    pause: suspend (Long) -> Unit = { delay(it) },
    backoff: List<Long> = BACKOFF_MS,
    retryNotAllowed: Boolean = false,
    onGaveUp: suspend (KeeperStatus) -> Unit = {},
) {
    waitForNetwork()
    if (turnOnWifiAdb) enableWifiAdb()
    var last: KeeperStatus = KeeperStatus.Reconnecting
    var allRefused = true
    for (i in 0..backoff.size) {
        val s = reconnect()
        last = s
        if (s == KeeperStatus.Connected) return
        if (s !is KeeperStatus.Failed || s.fix != Fix.AllowPrompt) allRefused = false
        if (s is KeeperStatus.Failed && (s.fix == Fix.QuickRepair || (s.fix == Fix.AllowPrompt && !retryNotAllowed))) return
        if (i < backoff.size) pause(backoff[i])
    }
    if (allRefused) onGaveUp(last)
}

/**
 * Turns the debugging the app connects through back on: Wireless debugging, or ADB debugging with [legacy]
 * (the older port-5555 connection). Needs the self-granted WRITE_SECURE_SETTINGS; without it, carry on.
 */
fun enableWifiAdb(context: Context, legacy: Boolean) {
    try {
        Settings.Global.putInt(context.contentResolver, if (legacy) Settings.Global.ADB_ENABLED else "adb_wifi_enabled", 1)
    } catch (_: SecurityException) {
    }
}

/** Reconnects the app's ADB link; the keeper's status carries any failure to the UI. */
class ReconnectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as SpeedApp).graph
        val s = graph.settings.values.first()
        reconnectWithRetry(
            turnOnWifiAdb = s.keepWifiDebugging,
            waitForNetwork = { awaitNetwork(applicationContext, NETWORK_WAIT_MS) },
            enableWifiAdb = { enableWifiAdb(applicationContext, s.legacyAdb) },
            reconnect = graph.connectionKeeper::reconnect,
            pause = { ms -> graph.connectionKeeper.connectingWhile { delay(ms) } },
            // Retry a refused key quietly unless the TV is known not to keep it (Unknown, e.g. after updating from
            // 1.1.0, must still ride out the early-boot refusal).
            retryNotAllowed = s.legacyAdb && !keyKnownRefused(graph.connectionKeeper),
            onGaveUp = { last -> quietRetriesFailed(graph.connectionKeeper, last) },
        )
        return Result.success()
    }

    /** Needed for expedited work below API 31. */
    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, 2, "reconnect", applicationContext.getString(dev.speedcompiler.tv.R.string.notif_reconnecting))
}

/**
 * [ConnectionKeeper.keyKnownRefused] with a settings or key-load error counting as "not known", so it never fails the
 * worker before the tries; cancelling still cancels.
 */
private suspend fun keyKnownRefused(keeper: ConnectionKeeper): Boolean = try {
    keeper.keyKnownRefused()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    false
}

/**
 * [ConnectionKeeper.quietRetriesFailed] after the tries, with a settings or key-load error ignored: the status already
 * carries the failure, and the worker must not fail on bookkeeping. Cancelling still cancels.
 */
private suspend fun quietRetriesFailed(keeper: ConnectionKeeper, last: KeeperStatus) {
    try {
        keeper.quietRetriesFailed(last)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // ignored: a bookkeeping error must not fail the worker
    }
}

/** Notification for expedited work below API 31. */
fun foregroundInfo(context: Context, id: Int, channel: String, title: String): ForegroundInfo {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(channel, title, NotificationManager.IMPORTANCE_LOW),
    )
    return ForegroundInfo(
        id,
        Notification.Builder(context, channel).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).build(),
    )
}

/** True once a default network is up, false after [timeoutMs] without one. */
private suspend fun awaitNetwork(context: Context, timeoutMs: Long): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    if (cm.activeNetwork != null) return true
    return withTimeoutOrNull(timeoutMs) {
        var cb: ConnectivityManager.NetworkCallback? = null
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }.also { cm.registerDefaultNetworkCallback(it) }
            }
        } finally {
            cb?.let { cm.unregisterNetworkCallback(it) } // the app may hold at most 100 callbacks
        }
        true
    } ?: false
}
