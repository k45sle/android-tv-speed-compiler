package dev.speedcompiler.tv.adb

import dev.speedcompiler.tv.engine.Settings
import dev.speedcompiler.tv.engine.SettingsValues
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
    private fun keeper(shell: AdbShell, settings: Settings = FakeSettings(), connect: suspend () -> Unit = {}) =
        ConnectionKeeper(connect, shell, settings, "dev.test")

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
}
