package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    /** [quiet] says the failed try could not show the TV's Allow prompt, so its refusal may not be final. */
    data class Failed(val reason: Throwable, val fix: Fix, val quiet: Boolean = false) : KeeperStatus
}

fun KeeperStatus.toConnectionState(): ConnectionState = when (this) {
    KeeperStatus.Connected -> ConnectionState.Connected
    KeeperStatus.Reconnecting -> ConnectionState.Connecting
    is KeeperStatus.Failed -> if (reason is AdbFailure.NotPaired) ConnectionState.Unpaired else ConnectionState.Disconnected
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
 * It returns true when the call made a new connection (a handshake), false when one was already open.
 * [checkKey] checks whether the TV kept Always allow (ADB debugging mode); null skips it. [disconnect] drops the link,
 * for reallow. [keyId] fingerprints this app's current key, so a stored result is only trusted for the key it was found
 * for; null means unknown.
 * SYSTEM_ALERT_WINDOW via appops is deliberately not done: it waits on a device test.
 */
class ConnectionKeeper(
    private val connect: suspend (prompt: Boolean) -> Boolean,
    private val shell: AdbShell,
    private val settings: Settings,
    private val packageName: String,
    private val scope: CoroutineScope,
    private val checkKey: (suspend () -> KeyCheck)? = null,
    private val disconnect: suspend () -> Unit = {},
    private val keyId: () -> String? = { null },
) : ConnectionMonitor {
    private val _status = MutableStateFlow<KeeperStatus>(KeeperStatus.Reconnecting)
    val status: StateFlow<KeeperStatus> get() = _status

    private val _keyChecking = MutableStateFlow(false)

    /** True while a key check runs. */
    internal val keyChecking: StateFlow<Boolean> get() = _keyChecking

    private val _reallowing = MutableStateFlow(false)

    /** True while a key check or a reallow runs (not while background reconnects retry). */
    val keyHelpBusy: Flow<Boolean> = combine(_keyChecking, _reallowing) { c, r -> c || r }

    /** Reasons the follow-up shell steps (grant, trust toggle) failed; null when fine. */
    val grantFailure = MutableStateFlow<String?>(null)
    val trustFailure = MutableStateFlow<String?>(null)

    private var granted = false
    @Volatile private var keyCheckJob: Job? = null
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

    override val state: Flow<ConnectionState> = _status.map { it.toConnectionState() }

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
     * [prompt] lets the TV show its Allow prompt for an unknown key; background callers leave it off, and their
     * refusal is marked [KeeperStatus.Failed.quiet] so Setup can still wait for its own prompted try.
     */
    suspend fun reconnect(prompt: Boolean = false): KeeperStatus = lock.withLock { reconnectLocked(prompt) }

    /** [reconnect] without taking [lock]: the caller already holds it. */
    private suspend fun reconnectLocked(prompt: Boolean): KeeperStatus {
        val before = _status.value
        _status.value = KeeperStatus.Reconnecting
        val fresh = try {
            connect(prompt)
        } catch (e: CancellationException) {
            // Never leave Connecting with nobody trying; before can itself be Connecting (app start, after a pause).
            _status.compareAndSet(KeeperStatus.Reconnecting, before.takeIf { it != KeeperStatus.Reconnecting } ?: KeeperStatus.Failed(AdbFailure.Timeout(), Fix.Retry))
            throw e
        } catch (e: Exception) {
            val err = mapAdbError(e)
            _status.value = KeeperStatus.Failed(err, fixFor(err), quiet = !prompt)
            return _status.value
        }
        _status.value = KeeperStatus.Connected
        if (!granted) grant()
        // grant() can flip the status to Failed when the link drops: then nothing is stored or checked.
        if (_status.value == KeeperStatus.Connected) onLegacyConnected(prompt, fresh)
        return _status.value
    }

    /**
     * ADB debugging mode, from the "How to fix" page: drops the link and connects again with the TV's prompt allowed,
     * so the person can tick Always allow; the key check then runs again. Returns the new status.
     */
    suspend fun reallow(): KeeperStatus {
        _reallowing.value = true
        try {
            return lock.withLock {
                keyCheckJob?.cancelAndJoin() // the new connection gets its own check
                try {
                    disconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // already gone
                }
                reconnectLocked(prompt = true)
            }
        } finally {
            _reallowing.value = false
        }
    }

    /**
     * ADB debugging mode, after a connect ([fresh] says the call made a new handshake): a quiet fresh handshake proves
     * the TV trusts the key, so save [KeyCheck.Saved] directly and cancel any running check (its later result must not
     * overwrite Saved). A prompted connect proves nothing about a restart, so the full [checkKey] probe runs there
     * instead, and its result is stored even when [KeyCheck.Unknown] (the person just allowed the app, so an older
     * negative result must not linger). Without a handshake the link was already open, which proves nothing (it may
     * have come from an Allow-once), so nothing is stored then. A failed settings read or write must not fail
     * [reconnect].
     */
    private suspend fun onLegacyConnected(prompt: Boolean, fresh: Boolean) {
        val check = checkKey ?: return
        // A failed settings read or write (or a storeKeyCheck that can't load the key) must not fail reconnect().
        try {
            val s = settings.values.first()
            if (!s.legacyAdb) return
            // No handshake: the open link may have come from an Allow-once, so it says nothing about this key.
            if (!fresh) return
            if (!prompt) {
                // The quiet handshake already proves the key is trusted: cancel a running check, whose later result
                // must not overwrite Saved.
                keyCheckJob?.cancelAndJoin()
                storeKeyCheck(KeyCheck.Saved) // also clears a stale Forgotten
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        if (keyCheckJob?.isActive == true) return
        // Lazy start: the completion handler is registered before the job can run, so an old job's completion
        // (which only clears the flag for its own job) can't hide the new job's busy flag.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // a failed settings write must not crash the app
            try {
                // The person was just let in, so store even Unknown: an older negative result must not linger.
                storeKeyCheck(check())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep the old result
            }
        }
        keyCheckJob = job
        _keyChecking.value = true
        // Also clears the flag when the job is cancelled before it ever starts (its finally would never run).
        job.invokeOnCompletion { if (keyCheckJob === job) _keyChecking.value = false }
        job.start()
    }

    /**
     * Every quiet try of a retry run was refused although the key was saved: the TV dropped it (for example at a restart).
     */
    suspend fun quietRetriesFailed(last: KeeperStatus) {
        val failed = last as? KeeperStatus.Failed ?: return
        if (checkKey == null || failed.reason !is AdbFailure.NotAuthorized) return
        val s = settings.values.first()
        if (s.legacyAdb && s.legacyKeyCheck == KeyCheck.Saved && s.legacyKeyCheckKey == keyId()) {
            storeKeyCheck(KeyCheck.Forgotten)
        }
    }

    /**
     * True when the TV is known not to keep this app's key (legacy ADB debugging mode, result found for this key): a
     * quiet refusal is the expected answer, so retrying it is pointless.
     */
    suspend fun keyKnownRefused(): Boolean {
        val s = settings.values.first()
        // Cheap fields first: keyId() may load or generate the RSA key, so only ask it once the result could match.
        return s.legacyAdb &&
            (s.legacyKeyCheck == KeyCheck.AllowedOnce || s.legacyKeyCheck == KeyCheck.NotSaved) &&
            s.legacyKeyCheckKey == keyId()
    }

    /**
     * Drops a stored result found for another key (for example one an older version regenerated): it says nothing
     * about this one.
     */
    suspend fun forgetStaleKeyCheck() {
        // Nothing stored, nothing to compare: keyId() may load or generate the RSA key, so skip it then.
        if (settings.values.first().legacyKeyCheck == KeyCheck.Unknown) return
        val id = keyId()
        // Decided inside the transform: a result stored meanwhile for this key must not be wiped.
        settings.update {
            if (it.legacyKeyCheck != KeyCheck.Unknown && it.legacyKeyCheckKey != id) {
                it.copy(legacyKeyCheck = KeyCheck.Unknown, legacyKeyCheckKey = null)
            } else {
                it
            }
        }
    }

    private suspend fun storeKeyCheck(k: KeyCheck) {
        val id = keyId()
        // Decided inside the transform: never store a result while ADB debugging mode is off.
        settings.update {
            if (!it.legacyAdb || (it.legacyKeyCheck == k && it.legacyKeyCheckKey == id)) {
                it
            } else {
                it.copy(legacyKeyCheck = k, legacyKeyCheckKey = id)
            }
        }
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
