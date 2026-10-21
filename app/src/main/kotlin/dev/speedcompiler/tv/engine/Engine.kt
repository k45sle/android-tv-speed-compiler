package dev.speedcompiler.tv.engine

import java.time.Instant

data class InstalledPackage(val packageName: String, val fingerprint: String)

data class OptimizeResult(val ok: Boolean, val detail: String = "")

enum class Wakefulness { Awake, Dozing, Asleep }

interface PackageSource {
    suspend fun installed(): List<InstalledPackage>
}

interface Optimizer {
    suspend fun optimize(packageName: String): OptimizeResult
}

/** Why the gate is open or closed. Only [OpensAt] carries a clock time, taken from the user's set hours. */
sealed interface GateStatus {
    data object Open : GateStatus
    data object WaitingForPlayback : GateStatus
    data object WaitingForIdle : GateStatus
    data class OpensAt(val at: Instant) : GateStatus
}

/** Decides whether optimizing is allowed right now (window, pause switch, device state). */
interface Gate {
    suspend fun status(): GateStatus
    suspend fun isOpen(): Boolean = status() == GateStatus.Open
}

interface Clock {
    fun nowMillis(): Long
}

interface DeviceSignals {
    suspend fun musicActive(): Boolean
    suspend fun wakefulness(): Wakefulness
}

class FakePackageSource(var packages: List<InstalledPackage> = emptyList()) : PackageSource {
    override suspend fun installed() = packages
}

class FakeOptimizer(var result: OptimizeResult = OptimizeResult(true)) : Optimizer {
    val optimized = mutableListOf<String>()
    override suspend fun optimize(packageName: String): OptimizeResult {
        optimized += packageName
        return result
    }
}

class FakeGate(var status: GateStatus = GateStatus.Open) : Gate {
    override suspend fun status() = status
}

class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis() = now
}

class FakeDeviceSignals(
    var music: Boolean = false,
    var wakefulness: Wakefulness = Wakefulness.Awake,
) : DeviceSignals {
    override suspend fun musicActive() = music
    override suspend fun wakefulness() = wakefulness
}
