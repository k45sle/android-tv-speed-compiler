package dev.speedcompiler.tv.watch

interface Scheduler {
    fun watch(packageName: String)
    fun unwatch(packageName: String)
    fun optimizeNow(packageName: String)
    fun cancel(packageName: String)
}

class FakeScheduler : Scheduler {
    val calls = mutableListOf<String>()
    override fun watch(packageName: String) { calls += "watch:$packageName" }
    override fun unwatch(packageName: String) { calls += "unwatch:$packageName" }
    override fun optimizeNow(packageName: String) { calls += "optimizeNow:$packageName" }
    override fun cancel(packageName: String) { calls += "cancel:$packageName" }
}
