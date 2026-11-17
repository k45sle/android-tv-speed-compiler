package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the user has to do to get a failed connection working again. */
enum class Fix { QuickRepair, TurnOnWirelessDebugging, TurnOnAdbDebugging, AllowPrompt, Retry }

sealed interface KeeperStatus {
    data object Reconnecting : KeeperStatus
    data object Connected : KeeperStatus
    data class Failed(val reason: Throwable, val fix: Fix) : KeeperStatus
}

fun fixFor(e: Throwable): Fix = when (e) {
    is AdbFailure.NotPaired -> Fix.QuickRepair
    is AdbFailure.DebuggingOff -> if (e.legacy) Fix.TurnOnAdbDebugging else Fix.TurnOnWirelessDebugging
    is AdbFailure.NotAuthorized -> Fix.AllowPrompt
    else -> Fix.Retry // Timeout and anything unknown
}

/**
 * Keeps the app's ADB link usable: connects, self-grants WRITE_SECURE_SETTINGS once, and applies the
 * keep-trusting setting. [connect] is the real connection call (for example `LibAdb::connect`); its argument says
 * whether the TV may show its Allow prompt, which only [connectNow] (a person on one of the app's screens) asks for.
 * [checkKey] checks whether the TV kept Always allow (ADB debugging mode); null skips it. [disconnect] drops the link,
 * for reallow.
 * SYSTEM_ALERT_WINDOW via appops is deliberately not done: it waits on a device test.
 */
class ConnectionKeeper(
    private val connect: suspend (prompt: Boolean) -> Unit,
    private val shell: AdbShell,
    private val settings: Settings,
    private val packageName: String,
    private val scope: CoroutineScope,
    private val checkKey: (suspend () -> KeyCheck)? = null,
    private val disconnect: suspend () -> Unit = {},
) : ConnectionMonitor {
    private val _status = MutableStateFlow<KeeperStatus>(KeeperStatus.Reconnecting)
    val status: StateFlow<KeeperStatus> get() = _status

    private val _keyChecking = MutableStateFlow(false)

    /** True while a key check runs. */
    val keyChecking: StateFlow<Boolean> get() = _keyChecking

    /** Reasons the follow-up shell steps (grant, trust toggle) failed; null when fine. */
    val grantFailure = MutableStateFlow<String?>(null)
    val trustFailure = MutableStateFlow<String?>(null)

    private var granted = false
    private var keyCheckJob: Job? = null
    private val lock = Mutex()
    private val inFlight = AtomicBoolean(false)

    /** Starts [reconnect] in the app scope, allowing the TV's prompt; ignored while one is already running. */
    override fun connectNow() {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                reconnect(prompt = true)
            } finally {
                inFlight.set(false)
            }
        }
    }

    override val state: Flow<ConnectionState> = _status.map {
        when (it) {
            KeeperStatus.Connected -> ConnectionState.Connected
            KeeperStatus.Reconnecting -> ConnectionState.Connecting
            is KeeperStatus.Failed ->
                if (it.reason is AdbFailure.NotPaired) ConnectionState.Unpaired else ConnectionState.Disconnected
        }
    }

    /**
     * Runs [wait] (a retry loop's pause) while showing Connecting instead of the last failure, so Home doesn't
     * offer Fix connection while an automatic retry is still coming. A cancelled wait puts the failure back.
     */
    suspend fun connectingWhile(wait: suspend () -> Unit) {
        val failed = _status.value as? KeeperStatus.Failed ?: return wait()
        _status.value = KeeperStatus.Reconnecting
        try {
            wait()
        } catch (e: CancellationException) {
            _status.compareAndSet(KeeperStatus.Reconnecting, failed)
            throw e
        }
    }

    /** Connects, then grants WRITE_SECURE_SETTINGS if that has not succeeded yet. Call again to retry. */
    /**
     * Callers queue behind one another, so the boot worker and Reconnect never overlap. Returns the new status.
     * [prompt] lets the TV show its Allow prompt for an unknown key; background callers leave it off.
     */
    suspend fun reconnect(prompt: Boolean = false): KeeperStatus = lock.withLock {
        val before = _status.value
        _status.value = KeeperStatus.Reconnecting
        try {
            connect(prompt)
        } catch (e: CancellationException) {
            // Never leave Connecting with nobody trying; before can itself be Connecting (app start, after a pause).
            _status.compareAndSet(KeeperStatus.Reconnecting, before.takeIf { it != KeeperStatus.Reconnecting } ?: KeeperStatus.Failed(AdbFailure.Timeout(), Fix.Retry))
            throw e
        } catch (e: Exception) {
            val err = mapAdbError(e)
            _status.value = KeeperStatus.Failed(err, fixFor(err))
            onLegacyRefused(prompt, err)
            return@withLock _status.value
        }
        _status.value = KeeperStatus.Connected
        if (!granted) grant()
        onLegacyConnected()
        _status.value
    }

    /**
     * ADB debugging mode, from the "How to fix" page: drops the link and connects again with the TV's prompt allowed,
     * so the person can tick Always allow; the key check then runs again. Returns the new status.
     */
    suspend fun reallow(): KeeperStatus {
        keyCheckJob?.cancelAndJoin() // the new connection gets its own check
        try {
            disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // already gone
        }
        return reconnect(prompt = true)
    }

    /** ADB debugging mode: after every connect, check (in the background) whether the TV would let this key back in after a restart. */
    private suspend fun onLegacyConnected() {
        val check = checkKey ?: return
        if (!settings.values.first().legacyAdb || keyCheckJob?.isActive == true) return
        _keyChecking.value = true
        keyCheckJob = scope.launch {
            try {
                val r = check()
                if (r != KeyCheck.Unknown) storeKeyCheck(r) // couldn't tell: keep what we knew
            } finally {
                _keyChecking.value = false
            }
        }
    }

    /** A quiet reconnect the TV refused although the key was saved: the TV dropped it (for example at a restart). */
    private suspend fun onLegacyRefused(prompt: Boolean, err: Throwable) {
        if (checkKey == null || prompt || err !is AdbFailure.NotAuthorized) return
        val s = settings.values.first()
        if (s.legacyAdb && s.legacyKeyCheck == KeyCheck.Saved) storeKeyCheck(KeyCheck.Forgotten)
    }

    private suspend fun storeKeyCheck(k: KeyCheck) {
        if (settings.values.first().legacyKeyCheck != k) settings.update { it.copy(legacyKeyCheck = k) }
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
        val err = mapAdbError(e)
        // A dropped link surfaces here as AdbFailure; show it instead of a stale Connected.
        if (err is AdbFailure) _status.value = KeeperStatus.Failed(err, fixFor(err))
        e.message ?: e.javaClass.simpleName
    }
}
