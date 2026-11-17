package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import dev.speedcompiler.tv.engine.SettingsValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
) : Settings {
    val flow = MutableStateFlow(
        SettingsValues(keepTrusting = keep, legacyAdb = legacy, legacyKeyCheck = check, legacyKeyCheckKey = key),
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
        disconnect: suspend () -> Unit = {},
        keyId: () -> String? = { null },
        connect: suspend (Boolean) -> Boolean = { true },
    ) =
        ConnectionKeeper(
            connect, shell, settings, "dev.test", scope,
            checkKey = checkKey, disconnect = disconnect, keyId = keyId,
        )

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
    fun legacyConnectStoresTheKeyCheck() = runTest(UnconfinedTestDispatcher()) {
        val shell = FakeAdbShell()
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val k = keeper(shell, settings, checkKey = { KeyCheck.NotSaved })
        k.reconnect(prompt = true)
        assertEquals(KeyCheck.NotSaved, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyChecking.value)
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
        assertEquals(false, k.keyChecking.value)
    }

    @Test
    fun noHandshakeNoCheck() = runTest(UnconfinedTestDispatcher()) {
        var checks = 0
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val k = keeper(FakeAdbShell(), settings, checkKey = { checks++; KeyCheck.NotSaved }, connect = { false })
        k.reconnect(prompt = true)
        assertEquals(0, checks)
        assertEquals(KeyCheck.Unknown, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyChecking.value)
    }

    @Test
    fun unknownResultKeepsTheOldValue() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.NotSaved)
        val k = keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Unknown })
        k.reconnect(prompt = true)
        assertEquals(KeyCheck.NotSaved, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyChecking.value)
    }

    @Test
    fun notLegacyNeverChecks() = runTest(UnconfinedTestDispatcher()) {
        var checks = 0
        val settings = FakeSettings(legacy = false)
        val k = keeper(FakeAdbShell(), settings, checkKey = { checks++; KeyCheck.Saved })
        k.reconnect(prompt = true)
        assertEquals(0, checks)
        assertEquals(false, k.keyChecking.value)
    }

    @Test
    fun quietRefusalAfterSavedMeansForgotten() = runTest(UnconfinedTestDispatcher()) {
        val check: suspend () -> KeyCheck = { KeyCheck.Unknown }

        // A quiet refusal alone is retried: only the end of the retry run says the TV dropped the key.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Saved)
        val k = keeper(FakeAdbShell(), settings, checkKey = check, connect = { throw AdbFailure.NotAuthorized() })
        val refused = k.reconnect()
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        k.quietRetriesFailed(refused)
        assertEquals(KeyCheck.Forgotten, settings.values.first().legacyKeyCheck)

        // The prompt was allowed, so the refusal alone says nothing about a restart.
        val prompted = FakeSettings(legacy = true, check = KeyCheck.Saved)
        val promptedKeeper = keeper(FakeAdbShell(), prompted, checkKey = check, connect = { throw AdbFailure.NotAuthorized() })
        promptedKeeper.reconnect(prompt = true)
        assertEquals(KeyCheck.Saved, prompted.values.first().legacyKeyCheck)

        // Nothing saved: a quiet refusal cannot mean forgotten.
        val notSaved = FakeSettings(legacy = true, check = KeyCheck.NotSaved)
        val notSavedKeeper = keeper(FakeAdbShell(), notSaved, checkKey = check, connect = { throw AdbFailure.NotAuthorized() })
        notSavedKeeper.quietRetriesFailed(notSavedKeeper.reconnect())
        assertEquals(KeyCheck.NotSaved, notSaved.values.first().legacyKeyCheck)

        // Only a refusal counts; a timeout keeps the value.
        val timeout = FakeSettings(legacy = true, check = KeyCheck.Saved)
        val timeoutKeeper = keeper(FakeAdbShell(), timeout, checkKey = check, connect = { throw AdbFailure.Timeout() })
        timeoutKeeper.quietRetriesFailed(timeoutKeeper.reconnect())
        assertEquals(KeyCheck.Saved, timeout.values.first().legacyKeyCheck)
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
        k.quietRetriesFailed(k.reconnect())
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
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
        k.quietRetriesFailed(k.reconnect())
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
        assertEquals(false, k.keyChecking.value)
        assertEquals(emptyList<Throwable>(), escaped)
    }

    @Test
    fun keyKnownSavedNeedsTheSameKey() = runTest(UnconfinedTestDispatcher()) {
        val same = FakeSettings(legacy = true, check = KeyCheck.Saved, key = "A")
        assertEquals(true, keeper(FakeAdbShell(), same, keyId = { "A" }).keyKnownSaved())

        val other = FakeSettings(legacy = true, check = KeyCheck.Saved, key = "A")
        assertEquals(false, keeper(FakeAdbShell(), other, keyId = { "B" }).keyKnownSaved())

        val notSaved = FakeSettings(legacy = true, check = KeyCheck.NotSaved, key = "A")
        assertEquals(false, keeper(FakeAdbShell(), notSaved, keyId = { "A" }).keyKnownSaved())

        val legacyOff = FakeSettings(legacy = false, check = KeyCheck.Saved, key = "A")
        assertEquals(false, keeper(FakeAdbShell(), legacyOff, keyId = { "A" }).keyKnownSaved())
    }

    @Test
    fun quietFailureWhilePromptedQueuedStaysConnecting() = runTest(UnconfinedTestDispatcher()) {
        val quietGate = CompletableDeferred<Unit>()
        val promptGate = CompletableDeferred<Unit>()
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connect = { prompt ->
                if (prompt) {
                    promptGate.await()
                    true
                } else {
                    quietGate.await()
                    throw AdbFailure.NotAuthorized()
                }
            },
        )
        val seen = mutableListOf<KeeperStatus>()
        val collect = launch(UnconfinedTestDispatcher(testScheduler)) { k.status.collect { seen += it } }

        var quietStatus: KeeperStatus? = null
        val quiet = launch { quietStatus = k.reconnect() } // holds the lock while the gate is shut
        k.connectNow() // its prompted reconnect counts as waiting while it is queued behind the quiet one
        quietGate.complete(Unit)
        quiet.join()

        assertEquals(KeeperStatus.Reconnecting, quietStatus)
        assertEquals(KeeperStatus.Reconnecting, k.status.value)
        assertTrue(seen.none { it is KeeperStatus.Failed })

        promptGate.complete(Unit)
        collect.cancel()
    }

    @Test
    fun quietFailureAfterPromptedFinishedShowsFailure() = runTest(UnconfinedTestDispatcher()) {
        val prompts = mutableListOf<Boolean>()
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connect = { prompt -> prompts += prompt; throw AdbFailure.NotAuthorized() },
        )
        // No other prompted try is queued, so the prompted failure shows.
        val prompted = k.reconnect(prompt = true)
        assertEquals(Fix.AllowPrompt, (prompted as KeeperStatus.Failed).fix)
        // The prompted try is long finished: a quiet failure must show too, not stay Connecting.
        val quiet = k.reconnect()
        assertEquals(Fix.AllowPrompt, (quiet as KeeperStatus.Failed).fix)
        assertEquals(KeeperStatus.Failed(quiet.reason, Fix.AllowPrompt), k.status.value)
        assertEquals(listOf(true, false), prompts)
    }

    @Test
    fun quietFailureWhileReallowQueuedStaysConnecting() = runTest(UnconfinedTestDispatcher()) {
        val quietGate = CompletableDeferred<Unit>()
        val reallowGate = CompletableDeferred<Unit>()
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connect = { prompt ->
                if (prompt) {
                    reallowGate.await()
                    true
                } else {
                    quietGate.await()
                    throw AdbFailure.NotAuthorized()
                }
            },
        )

        var quietStatus: KeeperStatus? = null
        val quiet = launch { quietStatus = k.reconnect() } // holds the lock while the gate is shut
        val re = launch { k.reallow() } // its prompted reconnect is queued behind the quiet one
        quietGate.complete(Unit)
        quiet.join()

        assertEquals(KeeperStatus.Reconnecting, quietStatus)
        assertEquals(KeeperStatus.Reconnecting, k.status.value)

        reallowGate.complete(Unit)
        re.join()
        assertEquals(KeeperStatus.Connected, k.status.value)
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

        // A reallow is, while its connect waits.
        val reallowGate = CompletableDeferred<Unit>()
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connect = { reallowGate.await(); true },
        )
        val job = launch { k.reallow() }
        assertEquals(true, k.keyHelpBusy.first())
        reallowGate.complete(Unit)
        job.join()
        assertEquals(false, k.keyHelpBusy.first())
    }

    @Test
    fun reallowWaitsForTheLock() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var calls = 0
        val k = keeper(
            FakeAdbShell(),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            connect = { prompt ->
                events += "connect:$prompt"
                if (calls++ == 0) gate.await()
                true
            },
            disconnect = { events += "disconnect" },
        )
        val quiet = launch { k.reconnect() } // holds the lock while the gate is shut
        assertEquals(listOf("connect:false"), events)
        val re = launch { k.reallow() }
        assertEquals(listOf("connect:false"), events) // no disconnect while the quiet connect is mid-handshake
        gate.complete(Unit)
        quiet.join()
        re.join()
        assertEquals(listOf("connect:false", "disconnect", "connect:true"), events)
    }

    @Test
    fun reallowCancelsTheRunningKeyCheckAndStartsANewOne() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val never = CompletableDeferred<Unit>()
        var checks = 0
        val k = keeper(FakeAdbShell(), settings, checkKey = {
            if (checks++ == 0) { never.await(); KeyCheck.Unknown } else KeyCheck.Saved
        })
        k.reconnect(prompt = true)
        assertEquals(1, checks)
        assertEquals(true, k.keyChecking.value)
        k.reallow()
        assertEquals(2, checks)
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
        assertEquals(false, k.keyChecking.value)
    }

    @Test
    fun reallowDisconnectsThenAsks() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val k = keeper(
            FakeAdbShell(),
            connect = { events += "connect:$it"; true },
            disconnect = { events += "disconnect" },
        )
        k.reallow()
        assertEquals(listOf("disconnect", "connect:true"), events)

        val failing = mutableListOf<String>()
        val k2 = keeper(
            FakeAdbShell(),
            connect = { failing += "connect:$it"; true },
            disconnect = { throw IOException("already gone") },
        )
        k2.reallow()
        assertEquals(listOf("connect:true"), failing)
    }
}
