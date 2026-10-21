package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** What the user has to do to get a failed connection working again. */
enum class Fix { QuickRepair, TurnOnWirelessDebugging, AllowPrompt, Retry }

sealed interface KeeperStatus {
    data object Reconnecting : KeeperStatus
    data object Connected : KeeperStatus
    data class Failed(val reason: Throwable, val fix: Fix) : KeeperStatus
}

fun fixFor(e: Throwable): Fix = when (e) {
    is AdbFailure.NotPaired -> Fix.QuickRepair
    is AdbFailure.DebuggingOff -> Fix.TurnOnWirelessDebugging
    is AdbFailure.NotAuthorized -> Fix.AllowPrompt
    else -> Fix.Retry // Timeout and anything unknown
}

/**
 * Keeps the app's ADB link usable: connects, self-grants WRITE_SECURE_SETTINGS once, and applies the
 * keep-trusting setting. [connect] is the real connection call (for example `LibAdb::connect`).
 * SYSTEM_ALERT_WINDOW via appops is deliberately not done: it waits on a device test.
 */
class ConnectionKeeper(
    private val connect: suspend () -> Unit,
    private val shell: AdbShell,
    private val settings: Settings,
    private val packageName: String,
) : ConnectionMonitor {
    private val _status = MutableStateFlow<KeeperStatus>(KeeperStatus.Reconnecting)
    val status: StateFlow<KeeperStatus> get() = _status

    /** Reasons the follow-up shell steps (grant, trust toggle) failed; null when fine. */
    val grantFailure = MutableStateFlow<String?>(null)
    val trustFailure = MutableStateFlow<String?>(null)

    private var granted = false

    override val state: Flow<ConnectionState> = _status.map {
        when (it) {
            KeeperStatus.Connected -> ConnectionState.Connected
            KeeperStatus.Reconnecting -> ConnectionState.Connecting
            is KeeperStatus.Failed ->
                if (it.reason is AdbFailure.NotPaired) ConnectionState.Unpaired else ConnectionState.Disconnected
        }
    }

    /** Connects, then grants WRITE_SECURE_SETTINGS if that has not succeeded yet. Call again to retry. */
    suspend fun reconnect() {
        _status.value = KeeperStatus.Reconnecting
        try {
            connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = mapAdbError(e)
            _status.value = KeeperStatus.Failed(err, fixFor(err))
            return
        }
        _status.value = KeeperStatus.Connected
        if (!granted) grant()
    }

    private suspend fun grant() {
        val r = runShell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
        granted = r == null
        grantFailure.value = r
    }

    /** Applies the keep-trusting flag whenever it changes while connected. Never returns; run it in a scope. */
    suspend fun watchKeepTrusting() {
        combine(_status, settings.values.map { it.keepTrusting }) { s, keep -> s to keep }
            .filter { it.first == KeeperStatus.Connected }
            .map { it.second }
            .distinctUntilChanged()
            .collect { keep ->
                trustFailure.value = runShell(
                    if (keep) "settings put global adb_allowed_connection_time 0"
                    else "settings delete global adb_allowed_connection_time",
                )
            }
    }

    /** Returns null on success, else a short reason. */
    private suspend fun runShell(command: String): String? = try {
        val r = shell.run(command)
        if (r.exitCode == 0) null else "exit ${r.exitCode}: ${r.output.trim()}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
