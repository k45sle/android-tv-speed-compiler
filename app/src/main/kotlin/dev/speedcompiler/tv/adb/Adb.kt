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
    /** Tries to connect now instead of waiting for the next retry. */
    fun connectNow() {}
}

class FakePairing(var succeeds: Boolean = true) : Pairing {
    override suspend fun pair(host: String, port: Int, code: String) = succeeds
}
