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

private class FakeSettings(keep: Boolean = true) : Settings {
    val flow = MutableStateFlow(SettingsValues(keepTrusting = keep))
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
        connect: suspend () -> Unit = {},
    ) =
        ConnectionKeeper(connect, shell, settings, "dev.test", scope)

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
}
