package dev.speedcompiler.tv.engine


class FakeOptimizer(var result: OptimizeResult = OptimizeResult(true)) : Optimizer {
    val optimized = mutableListOf<String>()
    val filters = mutableMapOf<String, String?>()
    /** Runs at the start of each optimize, so a test can block it or change the world mid-compile. */
    var before: suspend (String) -> Unit = {}
    override suspend fun verify(packageName: String) = filters[packageName]
    val usedFilters = mutableListOf<String>()
    val refreshed = mutableListOf<String>()
    override suspend fun refresh(packageName: String) { refreshed += packageName }
    /** Overrides [result] per call when set. */
    var resultFor: (String) -> OptimizeResult = { result }
    override suspend fun optimize(packageName: String, filter: String): OptimizeResult {
        before(packageName)
        optimized += packageName
        usedFilters += filter
        return resultFor(filter)
    }
}

class FakeGate(var status: GateStatus = GateStatus.Open) : Gate {
    override suspend fun status(ignorePause: Boolean) = if (ignorePause && status == GateStatus.Paused) GateStatus.Open else status
}

class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis() = now
}
