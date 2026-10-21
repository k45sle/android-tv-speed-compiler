package dev.speedcompiler.tv.adb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

data class ShellResult(val exitCode: Int, val output: String)

interface AdbShell {
    suspend fun run(command: String): ShellResult
}

interface Pairing {
    /** Pairs with the TV's own wireless-debugging endpoint using the on-screen code. */
    suspend fun pair(host: String, port: Int, code: String): Boolean
}

enum class ConnectionState { Unpaired, Disconnected, Connecting, Connected }

interface ConnectionMonitor {
    val state: Flow<ConnectionState>
}

class FakeAdbShell(private val responses: Map<String, ShellResult> = emptyMap()) : AdbShell {
    val commands = mutableListOf<String>()
    override suspend fun run(command: String): ShellResult {
        commands += command
        return responses[command] ?: ShellResult(0, "")
    }
}

class FakePairing(var succeeds: Boolean = true) : Pairing {
    override suspend fun pair(host: String, port: Int, code: String) = succeeds
}

class FakeConnectionMonitor(initial: ConnectionState = ConnectionState.Unpaired) : ConnectionMonitor {
    val flow = MutableStateFlow(initial)
    override val state: Flow<ConnectionState> = flow
}
