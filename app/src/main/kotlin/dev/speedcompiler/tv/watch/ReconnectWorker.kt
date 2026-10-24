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
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.adb.KeeperStatus
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
 * Calls [enableWifiAdb] only when [turnOnWifiAdb] (the Keep Wireless debugging on setting).
 */
suspend fun reconnectWithRetry(
    turnOnWifiAdb: Boolean,
    waitForNetwork: suspend () -> Boolean,
    enableWifiAdb: () -> Unit,
    reconnect: suspend () -> KeeperStatus,
    pause: suspend (Long) -> Unit = { delay(it) },
    backoff: List<Long> = BACKOFF_MS,
) {
    waitForNetwork()
    if (turnOnWifiAdb) enableWifiAdb()
    for (i in 0..backoff.size) {
        val s = reconnect()
        if (s == KeeperStatus.Connected) return
        if (s is KeeperStatus.Failed && (s.fix == Fix.QuickRepair || s.fix == Fix.AllowPrompt)) return
        if (i < backoff.size) pause(backoff[i])
    }
}

/** Reconnects the app's ADB link; the keeper's status carries any failure to the UI. */
class ReconnectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as SpeedApp).graph
        reconnectWithRetry(
            turnOnWifiAdb = graph.settings.values.first().keepWifiDebugging,
            waitForNetwork = { awaitNetwork(applicationContext, NETWORK_WAIT_MS) },
            enableWifiAdb = {
                // Needs the self-granted WRITE_SECURE_SETTINGS; without it, carry on.
                try {
                    Settings.Global.putInt(applicationContext.contentResolver, "adb_wifi_enabled", 1)
                } catch (_: SecurityException) {
                }
            },
            reconnect = graph.connectionKeeper::reconnect,
        )
        return Result.success()
    }

    /** Needed for expedited work below API 31. */
    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext, 2, "reconnect", applicationContext.getString(dev.speedcompiler.tv.R.string.notif_reconnecting))
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
