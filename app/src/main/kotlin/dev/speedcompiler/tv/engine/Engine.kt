package dev.speedcompiler.tv.engine

data class InstalledPackage(val packageName: String, val fingerprint: String)

data class OptimizeResult(val ok: Boolean, val detail: String = "")

enum class Wakefulness { Awake, Dozing, Asleep }

interface PackageSource {
    suspend fun installed(): List<InstalledPackage>
}

interface Optimizer {
    suspend fun optimize(packageName: String): OptimizeResult
}

/** Decides whether optimizing is allowed right now (window, pause switch, device state). */
interface Gate {
    suspend fun isOpen(): Boolean
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

class FakeGate(var open: Boolean = true) : Gate {
    override suspend fun isOpen() = open
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
