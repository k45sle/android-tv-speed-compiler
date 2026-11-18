package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
 * ADB debugging mode only: [checkKey] checks whether the TV kept Always allow (null skips it); [askSeparately] opens a
 * second connection that may show the TV's prompt while the main link stays up (true = let in); [linkOpen] says
 * whether the main link is open. [disconnect] drops the link, only when the connection mode changes ([modeChanged]).
 * [keyId] fingerprints this app's current key, so a stored result is only trusted for the key it was found for; null
 * means unknown.
 * SYSTEM_ALERT_WINDOW via appops is deliberately not done: it waits on a device test.
 */
class ConnectionKeeper(
    private val connect: suspend (prompt: Boolean) -> Boolean,
    private val shell: AdbShell,
    private val settings: Settings,
    private val packageName: String,
    private val scope: CoroutineScope,
    private val checkKey: (suspend () -> KeyCheck)? = null,
    private val askSeparately: suspend () -> Boolean? = { null },
    private val linkOpen: () -> Boolean = { false },
    private val disconnect: suspend () -> Unit = {},
    private val keyId: () -> String? = { null },
) : ConnectionMonitor {
    private val _status = MutableStateFlow<KeeperStatus>(KeeperStatus.Reconnecting)
    val status: StateFlow<KeeperStatus> get() = _status

    private val checking = MutableStateFlow(false)
    private val reallowing = MutableStateFlow(false)

    /** True while a key check or an Allow again runs (not while background reconnects retry). */
    val keyHelpBusy: Flow<Boolean> = combine(checking, reallowing) { c, r -> c || r }

    private val _lastAllow = MutableStateFlow<Boolean?>(null)

    /** How the last Allow again ended: true = the TV let the app in, false = it didn't; null before the first, or while one runs. */
    val lastAllow: StateFlow<Boolean?> get() = _lastAllow

    /** Reasons the follow-up shell steps (grant, trust toggle) failed; null when fine. */
    val grantFailure = MutableStateFlow<String?>(null)
    val trustFailure = MutableStateFlow<String?>(null)

    private var granted = false
    private val lock = Mutex()
    private val promptLock = Any()
    private var prompted: Deferred<KeeperStatus>? = null // guarded by promptLock
    private val checkLock = Mutex()
    @Volatile private var keyCheckJob: Job? = null // written under checkLock
    private val reallowRunning = AtomicBoolean(false)

    /**
     * Starts a [reconnect] that lets the TV show its prompt, in the app scope, or returns the one already running, so the
     * app's screens never start two prompts at once.
     */
    fun connectNowAsync(): Deferred<KeeperStatus> = synchronized(promptLock) {
        prompted?.takeIf { it.isActive } ?: scope.async { reconnect(prompt = true) }.also { prompted = it }
    }

    override fun connectNow() {
        connectNowAsync()
    }

    /**
     * [connectNow] and the outcome of that very try (or of the prompted one already running). Cancelling the caller
     * doesn't cancel the try: the TV's prompt stays up either way.
     */
    suspend fun connectNowAndWait(): KeeperStatus = connectNowAsync().await()

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

    /**
     * Connects, then grants WRITE_SECURE_SETTINGS if that has not succeeded yet. Call again to retry. Callers queue
     * behind one another, so the boot worker and Reconnect never overlap. Returns the new status. [prompt] lets the TV
     * show its Allow prompt for an unknown key; background callers leave it off.
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
            _status.value = KeeperStatus.Failed(err, fixFor(err))
            return _status.value
        }
        _status.value = KeeperStatus.Connected
        if (!granted) grant()
        // grant() can flip the status to Failed when the link drops: then nothing is stored or checked.
        if (fresh && _status.value == KeeperStatus.Connected) onHandshake(prompt)
        return _status.value
    }

    /**
     * ADB debugging mode, after a new handshake. A quiet one proves the TV kept the key by itself, as it must after a
     * restart, so [KeyCheck.Saved] is stored at once (clearing any earlier result) and a running check is cancelled, so
     * its later result can't overwrite that. A prompted one proves nothing about a restart, so the full check runs. An
     * already open link proves nothing (it may come from an Allow-once), so this only runs after a handshake. A failed
     * settings read or write never fails [reconnect].
     */
    private suspend fun onHandshake(prompt: Boolean) {
        if (checkKey == null || tryOrNull { settings.values.first().legacyAdb } != true) return
        if (prompt) {
            startKeyCheck()
        } else {
            cancelKeyCheck()
            tryOrNull { storeKeyCheck { KeyCheck.Saved } }
        }
    }

    /**
     * "Allow again" on the How to fix page (ADB debugging mode): lets the person answer the TV's prompt once more, for
     * example to tick Always allow, then checks the key again. It never drops the main link, so a running compile
     * carries on. With the link open, a second connection asks; with it down, this is the same prompted connect as Fix
     * connection. Returns true when the TV let the app in; false when it didn't, or another Allow again is running.
     */
    suspend fun reallow(): Boolean {
        if (!reallowRunning.compareAndSet(false, true)) return false
        reallowing.value = true
        _lastAllow.value = null
        try {
            cancelKeyCheck() // a check from before this answer says nothing about it
            val letIn = if (linkOpen()) {
                tryOrNull { askSeparately() } == true
            } else {
                connectNowAndWait() == KeeperStatus.Connected
            }
            // A prompted handshake already started its own check: join it. Otherwise (second connection, or a link that
            // someone else reopened meanwhile) start one, so the page never keeps an answer from before.
            if (letIn) startKeyCheck()?.join()
            _lastAllow.value = letIn
            return letIn
        } finally {
            reallowing.value = false
            reallowRunning.set(false)
        }
    }

    /**
     * The connection mode changed (Setup's Wireless debugging / ADB debugging switch): the open link goes through the
     * other port, so drop it and connect again quietly through the new one. Only for that user action, because it ends
     * a running compile.
     */
    suspend fun modeChanged(): KeeperStatus = lock.withLock {
        tryOrNull { disconnect() } // already gone is fine
        reconnectLocked(prompt = false)
    }

    /** Starts the key check unless one is running, and returns the running one; null without [checkKey]. */
    private suspend fun startKeyCheck(): Job? {
        val check = checkKey ?: return null
        return checkLock.withLock {
            keyCheckJob?.takeIf { it.isActive } ?: run {
                // Lazy start: the completion handler is registered before the job can run, so an old job's completion
                // (which only clears the flag for its own job) can't hide the new job's busy flag.
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    // A failed check or settings write keeps the old result and must not crash the app.
                    val found = tryOrNull { check() } ?: return@launch
                    tryOrNull { storeKeyCheck { stored -> afterPromptedCheck(stored, found) } }
                }
                keyCheckJob = job
                checking.value = true
                job.invokeOnCompletion { if (keyCheckJob === job) checking.value = false }
                job.start()
                job
            }
        }
    }

    /** Cancels a running key check and waits until it has stopped. */
    private suspend fun cancelKeyCheck() {
        checkLock.withLock { keyCheckJob?.cancelAndJoin() }
    }

    /**
     * ReconnectWorker's run ended on a refusal ([last]) and every quiet try of it was refused: a key the TV had saved is
     * no longer accepted, so [afterQuietRefusal] moves Saved to Forgotten and a key saved again to ForgetsAtRestart.
     * Skipped once the app is connected again meanwhile (someone just allowed it).
     */
    suspend fun quietlyRefused(last: KeeperStatus) {
        val failed = last as? KeeperStatus.Failed ?: return
        if (checkKey == null || failed.reason !is AdbFailure.NotAuthorized) return
        if (_status.value == KeeperStatus.Connected || !settings.values.first().legacyAdb) return
        storeKeyCheck(::afterQuietRefusal)
    }

    /**
     * True when the TV is known not to let this app's key back in by itself (legacy ADB debugging mode, result found
     * for this key): it never saved it, or it forgets it at every restart. A quiet refusal is then the expected answer,
     * so retrying it only keeps Home on Connecting; one quiet try still runs, and its success stores Saved.
     */
    suspend fun keyKnownRefused(): Boolean {
        val s = settings.values.first()
        // Cheap fields first: keyId() may load or generate the RSA key, so only ask it once the result could match.
        return s.legacyAdb &&
            (s.legacyKeyCheck == KeyCheck.AllowedOnce || s.legacyKeyCheck == KeyCheck.NotSaved ||
                s.legacyKeyCheck == KeyCheck.ForgetsAtRestart) &&
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

    /**
     * Stores [next] of the result kept for this key (Unknown when nothing, or a result for another key, is stored).
     * Decided inside the transform, so concurrent writers each build on the latest result; never while ADB debugging
     * mode is off.
     */
    private suspend fun storeKeyCheck(next: (KeyCheck) -> KeyCheck) {
        val id = keyId()
        settings.update {
            if (!it.legacyAdb) return@update it
            val k = next(if (it.legacyKeyCheckKey == id) it.legacyKeyCheck else KeyCheck.Unknown)
            if (k == it.legacyKeyCheck && id == it.legacyKeyCheckKey) it else it.copy(legacyKeyCheck = k, legacyKeyCheckKey = id)
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
