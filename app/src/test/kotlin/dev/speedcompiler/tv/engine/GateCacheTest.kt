package dev.speedcompiler.tv.engine

import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.adb.FakeConnectionMonitor
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.queueWithEta
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCacheTest {
    private val scope = CoroutineScope(Dispatchers.Default)

    @Test
    fun slowSignalsNeverBlockTheCaller() = runBlocking {
        val cache = GateCache(FakeClock(), scope) { delay(10_000); GateStatus.Open }
        val start = System.nanoTime()
        cache.refreshIfStale()
        assertNull(cache.status.value)
        assertEquals(null, withTimeout(100) { cache.status.first() })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 100)
    }

    @Test
    fun refreshesAtMostOncePer30Seconds() = runBlocking {
        val clock = FakeClock(1_000)
        var loads = 0
        val cache = GateCache(clock, scope) { loads++; GateStatus.Paused }
        suspend fun refresh() { cache.refreshIfStale(); delay(50) }
        refresh(); refresh()
        assertEquals(1, loads)
        assertEquals(GateStatus.Paused, cache.status.value)
        clock.now += 29_999; refresh()
        assertEquals(1, loads)
        clock.now += 1; refresh()
        assertEquals(2, loads)
    }

    @Test
    fun failedLoadIsUnknown() = runBlocking {
        val cache = GateCache(FakeClock(), scope) { error("adb down") }
        cache.refreshIfStale(); delay(50)
        assertNull(cache.status.value)
    }

    private val items = MutableStateFlow(listOf(QueueItem(1, "a.alpha", QueueSource.AUTO, enqueuedAt = 0)))

    private fun queueOver(signals: GateSignals, monitor: FakeConnectionMonitor): kotlinx.coroutines.flow.Flow<List<dev.speedcompiler.tv.watch.QueueEntry>> {
        val gate = GateImpl(FakeClock(), signals, { GateConfig(GateMode.Idle, GateWindow(0, 0), false) }, ZoneId.of("UTC"))
        val cache = GateCache(FakeClock(), scope) { if (monitor.flow.value == ConnectionState.Connected) gate.status() else null }
        return queueWithEta(items, monitor.state, cache)
    }

    @Test
    fun queueFlowEmitsWhileSignalsSleep() = runBlocking {
        val slow = object : GateSignals {
            override suspend fun musicActive() = false
            override suspend fun wakefulness(): Wakefulness? { delay(10_000); return Wakefulness.Dozing }
        }
        val q = withTimeout(100) { queueOver(slow, FakeConnectionMonitor(ConnectionState.Connected)).first() }
        assertEquals(Eta.Checking, q.single().eta)
    }

    @Test
    fun disconnectedQueueWaitsForConnectionThenFollowsReconnect() = runBlocking {
        val idle = object : GateSignals {
            override suspend fun musicActive() = false
            override suspend fun wakefulness() = Wakefulness.Dozing
        }
        val monitor = FakeConnectionMonitor(ConnectionState.Disconnected)
        val q = queueOver(idle, monitor)
        assertEquals(Eta.WaitingForConnection, q.first().single().eta)
        monitor.flow.value = ConnectionState.Connected
        assertEquals(Eta.InQueue(0, 0), withTimeout(2_000) { q.first { it.single().eta == Eta.InQueue(0, 0) } }.single().eta)
    }
}
