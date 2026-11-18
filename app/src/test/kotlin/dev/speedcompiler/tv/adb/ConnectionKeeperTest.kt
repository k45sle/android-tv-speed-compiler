package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import dev.speedcompiler.tv.engine.SettingsValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import java.io.IOException

private class FakeSettings(
    keep: Boolean = true,
    legacy: Boolean = false,
    check: KeyCheck = KeyCheck.Unknown,
    key: String? = null,
    boot: Int? = null,
) : Settings {
    val flow = MutableStateFlow(
        SettingsValues(keepTrusting = keep, legacyAdb = legacy, legacyKeyCheck = check, legacyKeyCheckKey = key, legacyKeyCheckBoot = boot),
    )
    override val values = flow
    override suspend fun update(transform: (SettingsValues) -> SettingsValues) {
        flow.value = transform(flow.value)
    }
}

private const val GRANT = "pm grant dev.test android.permission.WRITE_SECURE_SETTINGS"
private const val ON = "settings put global adb_allowed_connection_time 0"
private const val OFF = "settings delete global adb_allowed_connection_time"

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionKeeperTest {
    private fun keeper(
        shell: AdbShell,
        settings: Settings = FakeSettings(),
        scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
        checkKey: (suspend () -> KeyCheck)? = null,
        askSeparately: suspend () -> Boolean? = { null },
        linkOpen: () -> Boolean = { false },
        keyId: () -> String? = { null },
        bootId: () -> Int? = { null },
        takeQuietHandshake: () -> Boolean = { false },
        connect: suspend (Boolean) -> Boolean = { true },
    ) =
        ConnectionKeeper(
            connect, shell, settings, "dev.test", scope,
            checkKey = checkKey,
            askSeparately = askSeparately,
            linkOpen = linkOpen,
            keyId = keyId,
            bootId = bootId,
            takeQuietHandshake = takeQuietHandshake,
        )

    @Test
    fun grantDropNoKeyCheck() = runTest(UnconfinedTestDispatcher()) {
        // The grant command is the first shell step after the handshake, and it can report the link dropping.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        var checks = 0
        val shell = object : AdbShell {
            override suspend fun run(command: String): ShellResult = throw AdbFailure.Timeout()
        }
        val k = keeper(shell, settings, checkKey = { checks++; KeyCheck.NotSaved })
        val status = k.reconnect(prompt = true)
        val failed = status as KeeperStatus.Failed
        assertEquals(Fix.Retry, failed.fix)
        assertEquals(KeeperStatus.Failed(failed.reason, Fix.Retry), k.status.value)
        assertEquals(0, checks) // the drop happened before the key check could start
        assertEquals(KeyCheck.Unknown, settings.values.first().legacyKeyCheck)
        assertEquals(null, settings.values.first().legacyKeyCheckKey)
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun grantRunsOnceAfterConnect() = runTest {
        val shell = FakeAdbShell()
        val k = keeper(shell)
        k.reconnect()
        k.reconnect()
        assertEquals(listOf(GRANT), shell.commands)
        assertEquals(KeeperStatus.Connected, k.status.value)
        assertEquals(ConnectionState.Connected, k.state.first())
    }

    @Test
    fun grantFailureIsRecordedAndRetried() = runTest {
        val shell = FakeAdbShell(mapOf(GRANT to ShellResult(1, "denied\n")))
        val k = keeper(shell)
        k.reconnect()
        assertNotNull(k.grantFailure.value)
        k.reconnect()
        assertEquals(listOf(GRANT, GRANT), shell.commands)
    }

    @Test
    fun keepTrustingToggleRunsMatchingCommands() = runTest(UnconfinedTestDispatcher()) {
        val shell = FakeAdbShell()
        val settings = FakeSettings(keep = true)
        val k = keeper(shell, settings)
        val job = launch { k.watchKeepTrusting() }
        assertEquals(emptyList<String>(), shell.commands) // not connected yet
        k.reconnect()
        assertEquals(listOf(ON), shell.commands.filter { it != GRANT })
        settings.update { it.copy(keepTrusting = false) }
        settings.update { it.copy(keepTrusting = true) }
        assertEquals(listOf(ON, OFF, ON), shell.commands.filter { it != GRANT })
        assertNull(k.trustFailure.value)
        job.cancel()
    }

    @Test
    fun retryPauseShowsConnectingAndCancelRestoresFailure() = runTest(UnconfinedTestDispatcher()) {
        val k = keeper(FakeAdbShell(), connect = { throw AdbFailure.Timeout() })
        k.reconnect()
        assertEquals(ConnectionState.Disconnected, k.state.first())
        val gate = CompletableDeferred<Unit>()
        val pause = launch { k.connectingWhile { gate.await() } }
        assertEquals(ConnectionState.Connecting, k.state.first())
        pause.cancel()
        assertEquals(ConnectionState.Disconnected, k.state.first())
        k.connectingWhile {} // finishes normally: stays Connecting until the next try reports
        assertEquals(ConnectionState.Connecting, k.state.first())
    }

    @Test
    fun cancelledConnectAfterPauseShowsDisconnected() = runTest(UnconfinedTestDispatcher()) {
        val hang = CompletableDeferred<Unit>()
        var calls = 0
        val k = keeper(FakeAdbShell(), connect = { if (calls++ == 0) throw AdbFailure.Timeout() else { hang.await(); true } })
        k.reconnect()
        k.connectingWhile {}
        val job = launch { k.reconnect() }
        assertEquals(ConnectionState.Connecting, k.state.first())
        job.cancel()
        assertEquals(ConnectionState.Disconnected, k.state.first())
    }

    @Test
    fun failuresMapToStateAndFix() = runTest {
        val cases = listOf(
            AdbFailure.NotPaired() to (Fix.QuickRepair to ConnectionState.Unpaired),
            AdbFailure.DebuggingOff() to (Fix.TurnOnWirelessDebugging to ConnectionState.Disconnected),
            AdbFailure.NotAuthorized() to (Fix.AllowPrompt to ConnectionState.Disconnected),
            AdbFailure.Timeout() to (Fix.Retry to ConnectionState.Disconnected),
        )
        for ((err, expected) in cases) {
            val shell = FakeAdbShell()
            val k = keeper(shell) { throw err }
            k.reconnect()
            val s = k.status.value as KeeperStatus.Failed
            assertEquals(expected.first, s.fix)
            assertEquals(expected.second, k.state.first())
            assertEquals(emptyList<String>(), shell.commands)
        }
    }

    @Test
    fun fixForLegacyDebuggingOff() {
        // The legacy port is opened by the plain ADB switch, not Wireless debugging.
        assertEquals(Fix.TurnOnAdbDebugging, fixFor(AdbFailure.DebuggingOff(null, legacy = true)))
        assertEquals(Fix.TurnOnWirelessDebugging, fixFor(AdbFailure.DebuggingOff()))
    }

    @Test
    fun droppedLinkDuringShellMovesStatusToFailed() = runTest {
        val k = keeper(object : AdbShell {
            override suspend fun run(command: String): ShellResult = throw AdbFailure.Timeout()
        })
        k.reconnect()
        val s = k.status.value as KeeperStatus.Failed
        assertEquals(Fix.Retry, s.fix)
        assertEquals(ConnectionState.Disconnected, k.state.first())
    }

    @Test
    fun onlyConnectNowLetsTheTvPrompt() = runTest {
        val prompts = mutableListOf<Boolean>()
        val k = keeper(FakeAdbShell(), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { prompts += it; throw AdbFailure.NotAuthorized() }
        k.reconnect()
        k.connectNow()
        assertEquals(listOf(false, true), prompts)
    }

    @Test
    fun connectNowRunsOneReconnectAndIgnoresCallsWhileRunning() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val k = keeper(FakeAdbShell(), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { calls++; gate.await(); true }
        k.connectNow()
        k.connectNow()
        assertEquals(1, calls)
        gate.complete(Unit)
        assertEquals(KeeperStatus.Connected, k.status.value)
        k.connectNow()
        assertEquals(2, calls)
    }

    @Test
    fun connectNowAndWaitReturnsItsOwnTry() = runTest(UnconfinedTestDispatcher()) {
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connect = { throw AdbFailure.NotAuthorized() },
        )
        val result = k.connectNowAndWait()
        // The prompted try's refusal is final, so Home can offer Fix connection.
        assertEquals(Fix.AllowPrompt, (result as KeeperStatus.Failed).fix)
        assertEquals(k.status.value, result)
    }

    @Test
    fun connectNowAndWaitJoinsTheRunningTry() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connect = { calls++; gate.await(); true },
        )
        k.connectNow() // the TV's prompt is up
        assertEquals(1, calls)
        val waiter = launch { assertEquals(KeeperStatus.Connected, k.connectNowAndWait()) }
        gate.complete(Unit)
        waiter.join()
        assertEquals(1, calls) // no second try, so no second prompt
    }

    @Test
    fun legacyConnectStoresTheKeyCheck() = runTest(UnconfinedTestDispatcher()) {
        val shell = FakeAdbShell()
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val k = keeper(shell, settings, checkKey = { KeyCheck.NotSaved })
        k.reconnect(prompt = true)
        assertEquals(KeyCheck.NotSaved, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyHelpBusy.first())
        assertEquals(listOf(GRANT), shell.commands)
    }

    @Test
    fun quietHandshakeStoresSavedWithoutCheck() = runTest(UnconfinedTestDispatcher()) {
        var checks = 0
        val settings = FakeSettings(legacy = true, check = KeyCheck.Forgotten)
        val k = keeper(FakeAdbShell(), settings, checkKey = { checks++; KeyCheck.NotSaved })
        k.reconnect()
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals(0, checks)
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun quietHandshakeClearsForgetsAtRestart() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.ForgetsAtRestart, key = "k1")
        keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Unknown }, keyId = { "k1" }).reconnect()
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun quietHandshakeCancelsRunningCheck() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val never = CompletableDeferred<Unit>()
        var checks = 0
        val k = keeper(
            FakeAdbShell(),
            settings,
            checkKey = { checks++; never.await(); KeyCheck.NotSaved },
            connect = { true },
        )
        k.reconnect(prompt = true) // its key check starts and never finishes
        assertEquals(1, checks)
        assertEquals(true, k.keyHelpBusy.first())
        k.reconnect() // a quiet fresh handshake already proves the key is trusted
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals(1, checks) // no second check
        assertEquals(false, k.keyHelpBusy.first())
        // The cancelled check never ran its storeKeyCheck, so Saved stays.
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun noHandshakeNoCheck() = runTest(UnconfinedTestDispatcher()) {
        var checks = 0
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val k = keeper(FakeAdbShell(), settings, checkKey = { checks++; KeyCheck.NotSaved }, connect = { false })
        k.reconnect(prompt = true)
        assertEquals(0, checks)
        assertEquals(KeyCheck.Unknown, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun noHandshakeKeepsForgotten() = runTest(UnconfinedTestDispatcher()) {
        // Without a fresh handshake nothing proves the open link is this key's: it may have come from Allow once.
        var checks = 0
        val settings = FakeSettings(legacy = true, check = KeyCheck.Forgotten)
        keeper(FakeAdbShell(), settings, checkKey = { checks++; KeyCheck.Saved }, connect = { false }).reconnect()
        assertEquals(KeyCheck.Forgotten, settings.values.first().legacyKeyCheck)
        assertEquals(0, checks)
    }

    @Test
    fun promptedUnknownReplacesAnOldResult() = runTest(UnconfinedTestDispatcher()) {
        // The person was just let in, so an older refusal must not linger even when the probe couldn't tell.
        val settings = FakeSettings(legacy = true, check = KeyCheck.NotSaved)
        val k = keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Unknown })
        k.reconnect(prompt = true)
        assertEquals(KeyCheck.Unknown, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun promptedSavedAfterForgottenIsSavedAgain() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Forgotten, key = "k1")
        keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Saved }, keyId = { "k1" }).reconnect(prompt = true)
        assertEquals(KeyCheck.SavedAgain, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun notLegacyNeverChecks() = runTest(UnconfinedTestDispatcher()) {
        var checks = 0
        val settings = FakeSettings(legacy = false)
        val k = keeper(FakeAdbShell(), settings, checkKey = { checks++; KeyCheck.Saved })
        k.reconnect(prompt = true)
        assertEquals(0, checks)
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun quietRefusalAfterSavedMeansForgotten() = runTest(UnconfinedTestDispatcher()) {
        val check: suspend () -> KeyCheck = { KeyCheck.Unknown }

        // A quiet refusal alone is retried: only the end of the retry run says the TV dropped the key.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Saved)
        val k = keeper(FakeAdbShell(), settings, checkKey = check, connect = { throw AdbFailure.NotAuthorized() })
        val refused = k.reconnect()
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        k.quietlyRefused(refused)
        assertEquals(KeyCheck.Forgotten, settings.values.first().legacyKeyCheck)

        // The prompt was allowed, so the refusal alone says nothing about a restart.
        val prompted = FakeSettings(legacy = true, check = KeyCheck.Saved)
        val promptedKeeper = keeper(FakeAdbShell(), prompted, checkKey = check, connect = { throw AdbFailure.NotAuthorized() })
        promptedKeeper.reconnect(prompt = true)
        assertEquals(KeyCheck.Saved, prompted.values.first().legacyKeyCheck)

        // Nothing saved: a quiet refusal cannot mean forgotten.
        val notSaved = FakeSettings(legacy = true, check = KeyCheck.NotSaved)
        val notSavedKeeper = keeper(FakeAdbShell(), notSaved, checkKey = check, connect = { throw AdbFailure.NotAuthorized() })
        notSavedKeeper.quietlyRefused(notSavedKeeper.reconnect())
        assertEquals(KeyCheck.NotSaved, notSaved.values.first().legacyKeyCheck)

        // Only a refusal counts; a timeout keeps the value.
        val timeout = FakeSettings(legacy = true, check = KeyCheck.Saved)
        val timeoutKeeper = keeper(FakeAdbShell(), timeout, checkKey = check, connect = { throw AdbFailure.Timeout() })
        timeoutKeeper.quietlyRefused(timeoutKeeper.reconnect())
        assertEquals(KeyCheck.Saved, timeout.values.first().legacyKeyCheck)
    }

    @Test
    fun quietRefusalAfterSavedAgainForgetsAtRestart() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.SavedAgain, key = "k1")
        val k = keeper(
            FakeAdbShell(),
            settings,
            checkKey = { KeyCheck.Unknown },
            keyId = { "k1" },
            connect = { throw AdbFailure.NotAuthorized() },
        )
        k.quietlyRefused(k.reconnect())
        assertEquals(KeyCheck.ForgetsAtRestart, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun quietHandshakeSameBootKeepsSavedAgain() = runTest(UnconfinedTestDispatcher()) {
        // The app's process restarted, not the TV: a quiet handshake now proves nothing about a restart.
        val settings = FakeSettings(legacy = true, check = KeyCheck.SavedAgain, key = "k1", boot = 5)
        keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Unknown }, keyId = { "k1" }, bootId = { 5 }).reconnect()
        assertEquals(KeyCheck.SavedAgain, settings.values.first().legacyKeyCheck)
        assertEquals(5, settings.values.first().legacyKeyCheckBoot)
    }

    @Test
    fun quietHandshakeAfterARestartStoresSaved() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.SavedAgain, key = "k1", boot = 5)
        keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Unknown }, keyId = { "k1" }, bootId = { 6 }).reconnect()
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals(6, settings.values.first().legacyKeyCheckBoot)
    }

    @Test
    fun quietHandshakeSameBootAfterForgottenIsSavedAgain() = runTest(UnconfinedTestDispatcher()) {
        // Refused earlier this boot, then let in quietly: it still has the key, so it was saved again after the drop.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Forgotten, key = "k1", boot = 5)
        keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Unknown }, keyId = { "k1" }, bootId = { 5 }).reconnect()
        assertEquals(KeyCheck.SavedAgain, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun quietHandshakeByAShellCommandStoresSaved() = runTest(UnconfinedTestDispatcher()) {
        // A shell command opened the link after a restart, so the keeper never saw that handshake itself.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Forgotten, key = "k1", boot = 5)
        keeper(
            FakeAdbShell(), settings,
            checkKey = { KeyCheck.Unknown }, keyId = { "k1" }, bootId = { 6 },
            takeQuietHandshake = { true }, connect = { false },
        ).reconnect()
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun openLinkWithoutHandshakeStoresNothing() = runTest(UnconfinedTestDispatcher()) {
        // An already open link proves nothing (it may come from an Allow-once).
        val settings = FakeSettings(legacy = true, check = KeyCheck.Forgotten, key = "k1", boot = 5)
        keeper(
            FakeAdbShell(), settings,
            checkKey = { KeyCheck.Unknown }, keyId = { "k1" }, bootId = { 6 },
            takeQuietHandshake = { false }, connect = { false },
        ).reconnect()
        assertEquals(KeyCheck.Forgotten, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun quietRefusalSameBootIsOnlyForgotten() = runTest(UnconfinedTestDispatcher()) {
        // Authorizations were revoked while the TV stayed up: not a restart, so it isn't known to forget at restarts.
        val settings = FakeSettings(legacy = true, check = KeyCheck.SavedAgain, key = "k1", boot = 5)
        val k = keeper(
            FakeAdbShell(),
            settings,
            checkKey = { KeyCheck.Unknown },
            keyId = { "k1" },
            bootId = { 5 },
            connect = { throw AdbFailure.NotAuthorized() },
        )
        k.quietlyRefused(k.reconnect())
        assertEquals(KeyCheck.Forgotten, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun quietRefusalAfterARestartForgetsAtRestart() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.SavedAgain, key = "k1", boot = 5)
        val k = keeper(
            FakeAdbShell(),
            settings,
            checkKey = { KeyCheck.Unknown },
            keyId = { "k1" },
            bootId = { 6 },
            connect = { throw AdbFailure.NotAuthorized() },
        )
        k.quietlyRefused(k.reconnect())
        assertEquals(KeyCheck.ForgetsAtRestart, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun promptedCheckStoresTheBoot() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown, key = "k1")
        keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Saved }, keyId = { "k1" }, bootId = { 7 }).reconnect(prompt = true)
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals(7, settings.values.first().legacyKeyCheckBoot)
    }

    @Test
    fun quietRefusalIgnoredOnceConnected() = runTest(UnconfinedTestDispatcher()) {
        // Someone allowed the app meanwhile: the run's refusals say nothing any more.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Saved, key = "k1")
        val k = keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Unknown }, keyId = { "k1" })
        k.reconnect()
        k.quietlyRefused(KeeperStatus.Failed(AdbFailure.NotAuthorized(), Fix.AllowPrompt))
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun storedCheckRecordsTheKey() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val k = keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.NotSaved }, keyId = { "A" })
        k.reconnect() // quiet fresh connect: the TV accepted the key
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals("A", settings.values.first().legacyKeyCheckKey)
    }

    @Test
    fun staleKeyCheckIsForgotten() = runTest(UnconfinedTestDispatcher()) {
        val stale = FakeSettings(legacy = true, check = KeyCheck.Saved, key = "A")
        keeper(FakeAdbShell(), stale, keyId = { "B" }).forgetStaleKeyCheck()
        assertEquals(KeyCheck.Unknown, stale.values.first().legacyKeyCheck)
        assertEquals(null, stale.values.first().legacyKeyCheckKey)

        val current = FakeSettings(legacy = true, check = KeyCheck.Saved, key = "A")
        keeper(FakeAdbShell(), current, keyId = { "A" }).forgetStaleKeyCheck()
        assertEquals(KeyCheck.Saved, current.values.first().legacyKeyCheck)
        assertEquals("A", current.values.first().legacyKeyCheckKey)
    }

    @Test
    fun forgottenNeedsTheSameKey() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Saved, key = "A")
        val k = keeper(
            FakeAdbShell(),
            settings,
            checkKey = { KeyCheck.Unknown },
            keyId = { "B" },
            connect = { throw AdbFailure.NotAuthorized() },
        )
        k.quietlyRefused(k.reconnect())
        // The result was found for another key, so this key has none: not Forgotten.
        assertEquals(KeyCheck.Unknown, settings.values.first().legacyKeyCheck)
        assertEquals("B", settings.values.first().legacyKeyCheckKey)
    }

    @Test
    fun noResultStoredWhileLegacyOff() = runTest(UnconfinedTestDispatcher()) {
        val saved = FakeSettings(legacy = false, check = KeyCheck.Saved, key = "A")
        val k = keeper(
            FakeAdbShell(),
            saved,
            checkKey = { KeyCheck.Unknown },
            keyId = { "A" },
            connect = { throw AdbFailure.NotAuthorized() },
        )
        k.quietlyRefused(k.reconnect())
        assertEquals(KeyCheck.Saved, saved.values.first().legacyKeyCheck)
        assertEquals("A", saved.values.first().legacyKeyCheckKey)

        val fresh = FakeSettings(legacy = false, check = KeyCheck.Unknown)
        keeper(FakeAdbShell(), fresh, checkKey = { KeyCheck.NotSaved }, keyId = { "A" }).reconnect()
        assertEquals(KeyCheck.Unknown, fresh.values.first().legacyKeyCheck)
        assertEquals(null, fresh.values.first().legacyKeyCheckKey)

        // The decision happens inside the transform: turning ADB debugging mode off meanwhile skips the write.
        val turnedOff = object : Settings {
            val flow = MutableStateFlow(SettingsValues(legacyAdb = true, legacyKeyCheck = KeyCheck.Unknown))
            override val values get() = flow
            override suspend fun update(transform: (SettingsValues) -> SettingsValues) {
                flow.value = transform(flow.value.copy(legacyAdb = false))
            }
        }
        keeper(FakeAdbShell(), turnedOff, checkKey = { KeyCheck.NotSaved }, keyId = { "A" }).reconnect()
        assertEquals(KeyCheck.Unknown, turnedOff.values.first().legacyKeyCheck)
    }

    @Test
    fun keyCheckWriteFailureDoesNotCrash() = runTest(UnconfinedTestDispatcher()) {
        val escaped = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, e -> escaped += e }
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + handler)
        val inner = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val settings = object : Settings {
            override val values get() = inner.values
            override suspend fun update(transform: (SettingsValues) -> SettingsValues) {
                if (transform(inner.values.first()) != inner.values.first()) throw IOException("settings write failed")
                inner.update(transform)
            }
        }
        val k = keeper(FakeAdbShell(), settings, scope = scope, checkKey = { KeyCheck.NotSaved })
        k.reconnect(prompt = true)
        assertEquals(KeyCheck.Unknown, inner.values.first().legacyKeyCheck)
        assertEquals(false, k.keyHelpBusy.first())
        assertEquals(emptyList<Throwable>(), escaped)
    }

    @Test
    fun knownRefusedKeyIsNotRetried() = runTest(UnconfinedTestDispatcher()) {
        // A result the TV is known not to keep makes a quiet refusal the expected answer: no retries.
        for (check in listOf(KeyCheck.AllowedOnce, KeyCheck.NotSaved)) {
            val same = FakeSettings(legacy = true, check = check, key = "A")
            assertEquals(true, keeper(FakeAdbShell(), same, keyId = { "A" }).keyKnownRefused())

            val other = FakeSettings(legacy = true, check = check, key = "A")
            assertEquals(false, keeper(FakeAdbShell(), other, keyId = { "B" }).keyKnownRefused())
        }

        // Unknown (for example after updating from 1.1.0) and Saved must still ride out the early-boot refusal.
        for (check in listOf(KeyCheck.Unknown, KeyCheck.Saved)) {
            val s = FakeSettings(legacy = true, check = check, key = "A")
            assertEquals(false, keeper(FakeAdbShell(), s, keyId = { "A" }).keyKnownRefused())
        }

        val legacyOff = FakeSettings(legacy = false, check = KeyCheck.NotSaved, key = "A")
        assertEquals(false, keeper(FakeAdbShell(), legacyOff, keyId = { "A" }).keyKnownRefused())

        val noKey = FakeSettings(legacy = true, check = KeyCheck.NotSaved, key = null)
        assertEquals(false, keeper(FakeAdbShell(), noKey, keyId = { "A" }).keyKnownRefused())
    }

    @Test
    fun forgetsAtRestartIsKnownRefused() = runTest(UnconfinedTestDispatcher()) {
        // The TV drops the key at every restart: a quiet refusal is expected there too.
        val same = FakeSettings(legacy = true, check = KeyCheck.ForgetsAtRestart, key = "A")
        assertEquals(true, keeper(FakeAdbShell(), same, keyId = { "A" }).keyKnownRefused())

        val other = FakeSettings(legacy = true, check = KeyCheck.ForgetsAtRestart, key = "A")
        assertEquals(false, keeper(FakeAdbShell(), other, keyId = { "B" }).keyKnownRefused())

        for (check in listOf(KeyCheck.SavedAgain, KeyCheck.Forgotten)) {
            val s = FakeSettings(legacy = true, check = check, key = "A")
            assertEquals(false, keeper(FakeAdbShell(), s, keyId = { "A" }).keyKnownRefused())
        }
    }

    @Test
    fun forgottenThisBootIsKnownRefused() = runTest(UnconfinedTestDispatcher()) {
        // Forgotten in this boot: an earlier run in this boot used up the early-boot grace, so retrying is pointless.
        val s = FakeSettings(legacy = true, check = KeyCheck.Forgotten, key = "A", boot = 5)
        assertEquals(true, keeper(FakeAdbShell(), s, keyId = { "A" }, bootId = { 5 }).keyKnownRefused())

        // After a restart the stored Forgotten is from an earlier boot, so that run still gets the retries.
        assertEquals(false, keeper(FakeAdbShell(), s, keyId = { "A" }, bootId = { 6 }).keyKnownRefused())

        // Only for the key the refusal was found for.
        assertEquals(false, keeper(FakeAdbShell(), s, keyId = { "B" }, bootId = { 5 }).keyKnownRefused())

        // SavedAgain says nothing about this boot.
        val saved = FakeSettings(legacy = true, check = KeyCheck.SavedAgain, key = "A", boot = 5)
        assertEquals(false, keeper(FakeAdbShell(), saved, keyId = { "A" }, bootId = { 5 }).keyKnownRefused())
    }

    @Test
    fun cancelledBeforeStartClearsBusy() = runTest {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        var calls = 0
        val k = keeper(
            FakeAdbShell(),
            settings,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            checkKey = { KeyCheck.Saved },
            connect = { calls++; if (calls > 1) throw AdbFailure.NotAuthorized() else true },
        )
        k.reconnect(prompt = true) // its key check job is queued, not started (StandardTestDispatcher)
        k.reallow() // cancels the queued job before it ever runs, then its own connect fails
        advanceUntilIdle()
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun keyHelpBusyOnlyDuringCheckOrReallow() = runTest(UnconfinedTestDispatcher()) {
        // Background retries (Connecting while a pause waits) are not the page's business.
        val gate = CompletableDeferred<Unit>()
        val retrying = keeper(FakeAdbShell(), connect = { throw AdbFailure.Timeout() })
        retrying.reconnect()
        val pause = launch { retrying.connectingWhile { gate.await() } }
        assertEquals(ConnectionState.Connecting, retrying.state.first())
        assertEquals(false, retrying.keyHelpBusy.first())
        gate.complete(Unit)
        pause.join()

        // An Allow again is, while the TV's prompt waits on the second connection.
        val reallowGate = CompletableDeferred<Unit>()
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            askSeparately = { reallowGate.await(); false },
            linkOpen = { true },
        )
        val job = launch { k.reallow() }
        assertEquals(true, k.keyHelpBusy.first())
        reallowGate.complete(Unit)
        job.join()
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun reallowWithOpenLinkAsksSeparatelyAndKeepsTheLink() = runTest(UnconfinedTestDispatcher()) {
        // A running compile carries on: no reconnect, just a second connection asking the TV.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val prompts = mutableListOf<Boolean>()
        val k = keeper(
            FakeAdbShell(),
            settings,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            checkKey = { KeyCheck.NotSaved },
            askSeparately = { true },
            linkOpen = { true },
            keyId = { "k1" },
            connect = { prompts += it; false },
        )
        assertEquals(true, k.reallow())
        assertEquals(emptyList<Boolean>(), prompts)
        assertEquals(KeyCheck.NotSaved, settings.values.first().legacyKeyCheck)
        assertEquals(true, k.lastAllow.value)
    }

    @Test
    fun reallowRefusedRunsNoCheck() = runTest(UnconfinedTestDispatcher()) {
        // The person didn't allow it, so a check would only say what the prompt already did.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Saved, key = "k1")
        var checks = 0
        val k = keeper(
            FakeAdbShell(),
            settings,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            checkKey = { checks++; KeyCheck.Unknown },
            askSeparately = { false },
            linkOpen = { true },
            keyId = { "k1" },
        )
        assertEquals(false, k.reallow())
        assertEquals(0, checks)
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.lastAllow.value)
    }

    @Test
    fun reallowWithLinkDownConnectsWithPromptAndChecksOnce() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val prompts = mutableListOf<Boolean>()
        val gate = CompletableDeferred<Unit>()
        var checks = 0
        val k = keeper(
            FakeAdbShell(),
            settings,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            checkKey = { checks++; gate.await(); KeyCheck.Saved },
            linkOpen = { false },
            keyId = { "k1" },
            connect = { prompts += it; true },
        )
        val job = launch { assertEquals(true, k.reallow()) }
        gate.complete(Unit)
        job.join()
        assertEquals(listOf(true), prompts) // the prompted connect, like Fix connection
        assertEquals(1, checks) // the reallow joins the handshake's own check
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun reallowWithoutHandshakeStillChecks() = runTest(UnconfinedTestDispatcher()) {
        // The link was already open (maybe from an Allow once), so the handshake proves nothing: check anyway.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        var checks = 0
        val k = keeper(
            FakeAdbShell(),
            settings,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            checkKey = { checks++; KeyCheck.NotSaved },
            linkOpen = { false },
            keyId = { "k1" },
            connect = { false },
        )
        assertEquals(true, k.reallow())
        assertEquals(1, checks)
        assertEquals(KeyCheck.NotSaved, settings.values.first().legacyKeyCheck)
    }

    @Test
    fun secondReallowWhileOneRunsIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Boolean>()
        var asks = 0
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            askSeparately = { asks++; gate.await() },
            linkOpen = { true },
        )
        val first = launch { assertEquals(true, k.reallow()) }
        assertEquals(1, asks)
        assertEquals(false, k.reallow()) // one prompt at a time
        gate.complete(true)
        first.join()
        assertEquals(1, asks)
        assertEquals(true, k.lastAllow.value)
    }

    @Test
    fun reallowCancelsTheRunningKeyCheckAndStartsANewOne() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val never = CompletableDeferred<Unit>()
        var checks = 0
        val k = keeper(
            FakeAdbShell(),
            settings,
            checkKey = { if (checks++ == 0) { never.await(); KeyCheck.Unknown } else KeyCheck.Saved },
            askSeparately = { true },
            linkOpen = { true },
        )
        k.reconnect(prompt = true)
        assertEquals(1, checks)
        assertEquals(true, k.keyHelpBusy.first())
        k.reallow()
        assertEquals(2, checks)
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyHelpBusy.first())
    }
}
