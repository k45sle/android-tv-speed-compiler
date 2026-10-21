package dev.speedcompiler.tv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import dev.speedcompiler.tv.engine.FakeClock
import dev.speedcompiler.tv.engine.FakeGate
import dev.speedcompiler.tv.engine.FakeOptimizer
import dev.speedcompiler.tv.engine.GateMode
import dev.speedcompiler.tv.engine.GateStatus
import dev.speedcompiler.tv.engine.GateWindow
import dev.speedcompiler.tv.engine.InstalledPackage
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.PackageChanges
import dev.speedcompiler.tv.engine.PackageSourceImpl
import dev.speedcompiler.tv.engine.PackageSystem
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.toGateConfig
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.WatchedApp
import dev.speedcompiler.tv.watch.WorkScheduler
import dev.speedcompiler.tv.watch.runDetect
import dev.speedcompiler.tv.watch.runVerify
import dev.speedcompiler.tv.watch.systemUpdated
import dev.speedcompiler.tv.watch.workName
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

private class FakeSystem(var apps: MutableMap<String, String>) : PackageSystem {
    override fun bootCount() = 1
    override fun installed() = apps.map { InstalledPackage(it.key, it.value) }
    override fun fingerprint(packageName: String) = apps[packageName]
    override fun changedSince(sequence: Int): PackageChanges? = null
}

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SchedulingTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val system = FakeSystem(mutableMapOf("a" to "1", "b" to "1"))
    private val optimizer = FakeOptimizer()
    private val gate = FakeGate()
    private lateinit var g: AppGraph
    private lateinit var driver: TestDriver
    private lateinit var wm: WorkManager

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        driver = WorkManagerTestInitHelper.getTestDriver(ctx)!!
        wm = WorkManager.getInstance(ctx)
        g = (ctx as SpeedApp).graph
        g.database = Room.inMemoryDatabaseBuilder(ctx, dev.speedcompiler.tv.watch.AppDatabase::class.java)
            .allowMainThreadQueries().build()
        g.clock = FakeClock(1_000)
        g.packageSystem = system
        g.packageSource = PackageSourceImpl(system)
        g.optimizer = optimizer
        g.gate = gate
        g.scheduler = WorkScheduler(ctx, g)
    }

    @After fun tearDown() = g.database.close()

    private fun infos(pkg: String) = wm.getWorkInfosForUniqueWork(workName(pkg)).get()

    /** Workers run on Dispatchers.Default, so wait for their effect. */
    private suspend fun await(what: String, cond: suspend () -> Boolean) {
        repeat(250) { if (cond()) return else delay(20) }
        fail("timed out waiting for $what")
    }

    private suspend fun state(id: Long) = g.database.queue().get(id)!!.state

    /** Skips the 5 minute debounce of the newest job for [pkg]. */
    private fun skipDelay(pkg: String) = driver.setInitialDelayMet(infos(pkg).last { !it.state.isFinished }.id)

    @Test fun twoUpdatesProduceOneCompile() = runBlocking {
        g.scheduler.enqueue("a", QueueSource.AUTO)
        g.scheduler.enqueue("a", QueueSource.AUTO)
        assertEquals(1, g.database.queue().activeNow().size)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), infos("a").map { it.state }) // REPLACE dropped the first job
        skipDelay("a")
        await("done") { state(1) == QueueState.DONE }
        assertEquals(listOf("a"), optimizer.optimized)
    }

    @Test fun uninstallCancelsOnlyItsOwnJob() = runBlocking {
        g.scheduler.enqueue("a")
        g.scheduler.enqueue("b")
        system.apps.remove("a")
        runDetect(g)
        assertEquals(WorkInfo.State.CANCELLED, infos("a").single().state)
        assertEquals(WorkInfo.State.ENQUEUED, infos("b").single().state)
        assertEquals(listOf("b"), g.database.queue().activeNow().map { it.packageName })
        assertEquals(QueueState.SKIPPED, g.database.queue().get(1)!!.state)
    }

    @Test fun detectQueuesAChangedWatchedApp() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "0"))
        g.database.watchedApps().upsert(WatchedApp("b", "1"))
        runDetect(g)
        assertEquals(listOf("a"), g.database.queue().activeNow().map { it.packageName })
        assertEquals(QueueSource.AUTO, g.database.queue().get(1)!!.source)
    }

    @Test fun aWaitRetriesAfterTheBackoff() = runBlocking {
        gate.status = GateStatus.WaitingForIdle
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("waiting") { state(1) == QueueState.WAITING_GATE }
        assertEquals("WaitingForIdle", g.database.queue().get(1)!!.failureReason)
        await("retry scheduled") { infos("a").single().state == WorkInfo.State.ENQUEUED }
        assertEquals(1, infos("a").single().runAttemptCount)
        assertTrue(optimizer.optimized.isEmpty())
        gate.status = GateStatus.Open
        driver.setAllConstraintsMet(infos("a").single().id) // the backoff elapsing reruns the same worker
        await("done") { state(1) == QueueState.DONE }
        assertEquals(listOf("a"), optimizer.optimized)
        assertEquals(1, infos("a").size)
    }

    @Test fun weeklyVerifyRequeuesADowngradedApp() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "1"))
        g.database.watchedApps().upsert(WatchedApp("b", "1"))
        optimizer.filters["a"] = "speed"
        optimizer.filters["b"] = "verify"
        runVerify(g)
        assertEquals(listOf("b"), g.database.queue().activeNow().map { it.packageName })
    }

    @Test fun pauseStopsAutomaticJobsButNotOptimizeNow() = runBlocking {
        gate.status = GateStatus.Paused
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("paused") { g.database.queue().get(1)!!.failureReason == "Paused" }
        g.scheduler.optimizeNow("b")
        await("b done") { state(2) == QueueState.DONE }
        assertEquals(listOf("b"), optimizer.optimized)
    }

    @Test fun aFailedJobCanBeRetried() = runBlocking {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "nope")
        g.scheduler.optimizeNow("a")
        await("failed") { state(1) == QueueState.FAILED }
        assertEquals("nope", g.scheduler.history.first().single().failureReason)
        g.scheduler.retry(1)
        await("second run") { optimizer.optimized.size == 2 }
    }

    @Test fun systemUpdateIsReportedOnce() = runBlocking {
        val state = g.database.syncState()
        assertFalse(systemUpdated(state, "v1"))
        assertFalse(systemUpdated(state, "v1"))
        assertTrue(systemUpdated(state, "v2"))
        assertFalse(systemUpdated(state, "v2"))
    }

    @Test fun manualModeAndEmptyWindowMapToTheGate() {
        val manual = SettingsValues(mode = Mode.Manual, gateMode = GateMode.Window).toGateConfig()
        assertTrue(manual.paused)
        assertEquals(GateMode.Window, manual.mode)
        val empty = SettingsValues(windowStartMinute = 600, windowEndMinute = 600).toGateConfig()
        assertEquals(GateWindow(120, 360), empty.window)
        assertFalse(empty.paused)
    }
}
