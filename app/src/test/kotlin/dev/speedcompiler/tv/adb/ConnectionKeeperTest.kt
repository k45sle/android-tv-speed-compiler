package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import dev.speedcompiler.tv.engine.SettingsValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

import java.io.IOException

private class FakeSettings(keep: Boolean = true, legacy: Boolean = false, check: KeyCheck = KeyCheck.Unknown) : Settings {
    val flow = MutableStateFlow(SettingsValues(keepTrusting = keep, legacyAdb = legacy, legacyKeyCheck = check))
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
        connect: suspend (Boolean) -> Unit = {},
    ) =
        ConnectionKeeper(connect, shell, settings, "dev.test", scope, checkKey = checkKey, disconnect = disconnect)

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
        val k = keeper(FakeAdbShell(), connect = { if (calls++ == 0) throw AdbFailure.Timeout() else hang.await() })
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
        val k = keeper(FakeAdbShell(), scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) { calls++; gate.await() }
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
    fun quietConnectAlsoChecks() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettings(legacy = true, check = KeyCheck.Unknown)
        val k = keeper(FakeAdbShell(), settings, checkKey = { KeyCheck.Saved })
        k.reconnect()
        assertEquals(KeyCheck.Saved, settings.values.first().legacyKeyCheck)
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

        // A quiet refusal after Saved: the TV dropped the key.
        val settings = FakeSettings(legacy = true, check = KeyCheck.Saved)
        keeper(FakeAdbShell(), settings, checkKey = check, connect = { throw AdbFailure.NotAuthorized() }).reconnect()
        assertEquals(KeyCheck.Forgotten, settings.values.first().legacyKeyCheck)

        // The prompt was allowed, so the refusal says nothing about a restart.
        val prompted = FakeSettings(legacy = true, check = KeyCheck.Saved)
        keeper(FakeAdbShell(), prompted, checkKey = check, connect = { throw AdbFailure.NotAuthorized() }).reconnect(prompt = true)
        assertEquals(KeyCheck.Saved, prompted.values.first().legacyKeyCheck)

        // Nothing saved: a quiet refusal cannot mean forgotten.
        val notSaved = FakeSettings(legacy = true, check = KeyCheck.NotSaved)
        keeper(FakeAdbShell(), notSaved, checkKey = check, connect = { throw AdbFailure.NotAuthorized() }).reconnect()
        assertEquals(KeyCheck.NotSaved, notSaved.values.first().legacyKeyCheck)

        // Only a refusal counts; a timeout keeps the value.
        val timeout = FakeSettings(legacy = true, check = KeyCheck.Saved)
        keeper(FakeAdbShell(), timeout, checkKey = check, connect = { throw AdbFailure.Timeout() }).reconnect()
        assertEquals(KeyCheck.Saved, timeout.values.first().legacyKeyCheck)
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
            connect = { events += "connect:$it" },
            disconnect = { events += "disconnect" },
        )
        k.reallow()
        assertEquals(listOf("disconnect", "connect:true"), events)

        val failing = mutableListOf<String>()
        val k2 = keeper(
            FakeAdbShell(),
            connect = { failing += "connect:$it" },
            disconnect = { throw IOException("already gone") },
        )
        k2.reallow()
        assertEquals(listOf("connect:true"), failing)
    }
}
