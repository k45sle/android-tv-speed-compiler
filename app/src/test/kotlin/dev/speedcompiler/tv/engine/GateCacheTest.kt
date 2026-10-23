package dev.speedcompiler.tv.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
}
