package dev.speedcompiler.tv.engine

import dev.speedcompiler.tv.watch.SyncState
import dev.speedcompiler.tv.watch.SyncStateDao
import dev.speedcompiler.tv.watch.WatchedApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSystem : PackageSystem {
    var boot = 1
    var seq = 0
    val prints = mutableMapOf<String, String>()
    val touched = mutableListOf<String>()
    var requested = -1
    override fun bootCount() = boot
    override fun installed() = prints.map { InstalledPackage(it.key, it.value) }
    override fun fingerprint(packageName: String) = prints[packageName]
    override fun isDebuggable(packageName: String) = false
    override fun changedSince(sequence: Int): PackageChanges? {
        requested = sequence
        return if (touched.isEmpty()) null else PackageChanges(seq, touched.toList())
    }
}

private class FakeState : SyncStateDao {
    val map = mutableMapOf<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun withPrefix(prefix: String) = map.filterKeys { it.startsWith(prefix) }.map { SyncState(it.key, it.value) }
    override suspend fun put(state: SyncState) {
        map[state.key] = state.value
    }
}

class ChangeDetectorTest {
    private val system = FakeSystem().apply { prints["a"] = "1:10"; prints["b"] = "1:10"; prints["other"] = "1:10" }
    private val state = FakeState()
    private val watched = listOf(WatchedApp("a", "1:10"), WatchedApp("b", "1:10"))
    private val detector = ChangeDetector(system, state) { watched }

    @Test fun noChangeIsEmpty() = runTest {
        assertEquals(emptyList<String>(), detector.detect())
        assertEquals(emptyList<String>(), detector.detect())
    }

    @Test fun sameVersionReinstallIsDetected() = runTest {
        detector.detect()
        system.prints["a"] = fingerprintOf(1, 20)
        system.touched += "a"
        system.seq = 5
        assertEquals(listOf("a"), detector.detect())
        assertEquals("5", state.map["pkg_sequence"])
    }

    @Test fun incrementalUsesStoredSequence() = runTest {
        system.seq = 5
        system.touched += "other"
        detector.detect()
        detector.detect()
        assertEquals(5, system.requested)
    }

    @Test fun rebootForcesFullCompareDespiteSequenceReset() = runTest {
        system.seq = 50
        system.touched += "other"
        detector.detect()
        system.boot = 2
        system.seq = 1
        system.touched.clear()
        system.prints["b"] = "2:10" // changed while off; the new boot's sequence knows nothing of it
        assertEquals(listOf("b"), detector.detect())
        assertEquals("2", state.map["pkg_boot_count"])
    }

    @Test fun uninstallIsReported() = runTest {
        detector.detect()
        system.prints.remove("b")
        system.touched += "b"
        assertEquals(listOf("b"), detector.detect())
    }

    @Test fun unwatchedAppsAreIgnored() = runTest {
        detector.detect()
        system.prints["other"] = "9:99"
        system.touched += "other"
        assertEquals(emptyList<String>(), detector.detect())
    }

    @Test fun positionAdvancesOnlyAfterTheCallbackReturns() = runTest {
        system.prints["a"] = "2:10"
        system.touched += "a"
        system.seq = 7
        val seen = mutableListOf<String>()
        detector.detect { seen += it; assertNull(state.map["pkg_sequence"]) }
        assertEquals(listOf("a"), seen)
        assertEquals("7", state.map["pkg_sequence"])
    }

    @Test fun aFailedCallbackLeavesTheChangeToBeFoundAgain() = runTest {
        detector.detect()
        system.prints["a"] = "2:10"
        system.touched += "a"
        system.seq = 7
        assertTrue(runCatching { detector.detect { error("crash while queueing") } }.isFailure)
        assertEquals("0", state.map["pkg_sequence"])
        assertEquals(listOf("a"), detector.detect())
    }
}
