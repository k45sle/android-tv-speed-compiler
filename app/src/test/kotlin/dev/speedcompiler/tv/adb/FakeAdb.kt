package dev.speedcompiler.tv.adb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAdbShell(private val responses: Map<String, ShellResult> = emptyMap()) : AdbShell {
    val commands = mutableListOf<String>()
    override suspend fun run(command: String): ShellResult {
        commands += command
        return responses[command] ?: ShellResult(0, "")
    }
}

class FakeConnectionMonitor(initial: ConnectionState = ConnectionState.Unpaired) : ConnectionMonitor {
    val flow = MutableStateFlow(initial)
    override val state: Flow<ConnectionState> = flow
    var connectNowCalls = 0
    override fun connectNow() { connectNowCalls++ }
}
