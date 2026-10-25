package dev.speedcompiler.tv

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import dev.speedcompiler.tv.engine.DEBUG_BUILD
import dev.speedcompiler.tv.engine.FakeClock
import dev.speedcompiler.tv.engine.FakeGate
import dev.speedcompiler.tv.engine.FakeOptimizer
import dev.speedcompiler.tv.engine.GateMode
import dev.speedcompiler.tv.engine.GateStatus
import dev.speedcompiler.tv.engine.GateWindow
import dev.speedcompiler.tv.engine.InstalledPackage
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.PackageChanges
import dev.speedcompiler.tv.engine.PackageSystem
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.toGateConfig
import dev.speedcompiler.tv.watch.OpenGatesWorker
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.runNowWaiting
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.WatchedApp
import dev.speedcompiler.tv.watch.WorkScheduler
import dev.speedcompiler.tv.watch.restartOpenedGates
import dev.speedcompiler.tv.watch.runDetect
import dev.speedcompiler.tv.watch.runVerify
import dev.speedcompiler.tv.watch.systemUpdated
import dev.speedcompiler.tv.watch.workName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLooper

private class FakeSystem(var apps: MutableMap<String, String>) : PackageSystem {
    override fun bootCount() = 1
    override fun installed() = apps.map { InstalledPackage(it.key, it.value) }
    override fun fingerprint(packageName: String) = apps[packageName]
    val debuggable = mutableSetOf<String>()
    override fun isDebuggable(packageName: String) = packageName in debuggable
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

    /** Expedited jobs have no delay to skip, but the test driver starts with the storage constraint unmet. */
    private suspend fun optimizeNow(pkg: String) {
        g.scheduler.optimizeNow(pkg)
        infos(pkg).filter { !it.state.isFinished }.forEach { driver.setAllConstraintsMet(it.id) }
    }

    /** Skips the 5 minute debounce of the newest job for [pkg]. */
    private fun skipDelay(pkg: String) = infos(pkg).last { !it.state.isFinished }.id.let {
        driver.setInitialDelayMet(it)
        driver.setAllConstraintsMet(it) // the storage constraint is unmet by default in the test driver
    }

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

    @Test fun detectReschedulesAWaitingItemOnceTheGateOpens() = runBlocking {
        gate.status = GateStatus.WaitingForIdle
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("waiting") { state(1) == QueueState.WAITING_GATE }
        await("backoff") { infos("a").single().let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 } }
        runDetect(g)
        val backedOff = infos("a").single()
        assertEquals(1, backedOff.runAttemptCount) // gate still closed: left in its backoff
        gate.status = GateStatus.Open
        runDetect(g)
        driver.setAllConstraintsMet(infos("a").single().id)
        await("done") { state(1) == QueueState.DONE }
        val fresh = infos("a").single() // REPLACE dropped the backed-off request
        assertTrue(fresh.id != backedOff.id)
        assertEquals(1, fresh.runAttemptCount) // its first run, not the backoff's second
        assertEquals(0L, fresh.initialDelayMillis) // no second debounce
        assertEquals(listOf("a"), optimizer.optimized)
    }

    @Test fun screenOffRestartsAWaitingItemOnlyOnceTheGateOpens() = runBlocking {
        gate.status = GateStatus.WaitingForIdle
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("backoff") { infos("a").single().let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 } }
        val backedOff = infos("a").single()
        restartOpenedGates(g) // screen off, but the gate still says not idle
        assertEquals(backedOff.id, infos("a").single().id)
        gate.status = GateStatus.Open
        restartOpenedGates(g)
        val fresh = infos("a").single()
        assertTrue(fresh.id != backedOff.id)
        assertEquals(0L, fresh.initialDelayMillis) // runs now, not after the backoff or a debounce
        driver.setAllConstraintsMet(fresh.id)
        await("done") { state(1) == QueueState.DONE }
        assertEquals(listOf("a"), optimizer.optimized)
    }

    private suspend fun assertBroadcastRestartsAWaitingItem(vararg actions: String) {
        gate.status = GateStatus.WaitingForIdle
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("backoff") { infos("a").single().let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 } }
        val backedOff = infos("a").single()
        gate.status = GateStatus.Open
        actions.forEach { ctx.sendBroadcast(Intent(it)) }
        ShadowLooper.idleMainLooper()
        val check = wm.getWorkInfosByTag(OpenGatesWorker::class.java.name).get().single() // two broadcasts share one check
        assertEquals(5_000L, check.initialDelayMillis)
        assertEquals(backedOff.id, infos("a").single().id) // nothing happens before the settle delay
        driver.setInitialDelayMet(check.id)
        await("restarted") { infos("a").any { it.id != backedOff.id } }
        driver.setAllConstraintsMet(infos("a").last { !it.state.isFinished }.id)
        await("done") { state(1) == QueueState.DONE }
        assertEquals(listOf("a"), optimizer.optimized)
    }

    @Test fun screenOffRestartsAWaitingItemAfterTheSettleDelay() = runBlocking {
        assertBroadcastRestartsAWaitingItem(Intent.ACTION_SCREEN_OFF)
    }

    @Test fun screensaverStartRestartsAWaitingItemAfterTheSettleDelay() = runBlocking {
        assertBroadcastRestartsAWaitingItem(Intent.ACTION_DREAMING_STARTED)
    }

    @Test fun screenOffThenScreensaverQueuesOneCheck() = runBlocking {
        assertBroadcastRestartsAWaitingItem(Intent.ACTION_SCREEN_OFF, Intent.ACTION_DREAMING_STARTED)
    }

    @Test fun screenOffLeavesARowWaitingForTheFirstLaunchAlone() = runBlocking {
        val retry = waitForLaunch()
        gate.status = GateStatus.Open
        restartOpenedGates(g)
        assertEquals(QueueState.WAITING_GATE, state(1))
        assertEquals(retry, wm.getWorkInfosForUniqueWork("launch:1").get().single().id)
        assertTrue(infos("a").none { !it.state.isFinished })
        assertEquals(1, optimizer.usedFilters.size)
    }

    @Test fun detectLeavesARunningItemAlone() = runBlocking {
        val release = CompletableDeferred<Unit>()
        optimizer.before = { release.await() }
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("running") { state(1) == QueueState.RUNNING }
        runDetect(g)
        assertEquals(listOf(WorkInfo.State.RUNNING), infos("a").map { it.state })
        release.complete(Unit)
        await("done") { state(1) == QueueState.DONE }
        assertEquals(listOf("a"), optimizer.optimized)
    }

    @Test fun detectLeavesAStartedWorkerAloneWhileItsRowStillSaysWaiting() = runBlocking {
        val release = CompletableDeferred<Unit>()
        optimizer.before = { release.await() }
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("running") { state(1) == QueueState.RUNNING }
        val id = infos("a").single().id
        // The window between a retry worker starting and it writing RUNNING.
        g.database.queue().update(g.database.queue().get(1)!!.copy(state = QueueState.WAITING_GATE))
        runDetect(g)
        assertEquals(listOf(id to WorkInfo.State.RUNNING), infos("a").map { it.id to it.state })
        release.complete(Unit)
        await("done") { state(1) == QueueState.DONE }
        assertEquals(listOf("a"), optimizer.optimized)
    }

    @Test fun weeklyVerifyPrunesOldEventsAndFinishedRows() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        (g.clock as FakeClock).now = 200 * day
        val events = g.database.events()
        events.insert(dev.speedcompiler.tv.watch.Event(timeMillis = 10 * day, packageName = null, kind = "old", message = "m"))
        events.insert(dev.speedcompiler.tv.watch.Event(timeMillis = 190 * day, packageName = null, kind = "new", message = "m"))
        val q = g.database.queue()
        fun row(state: QueueState, finished: Long?) = dev.speedcompiler.tv.watch.QueueItem(
            packageName = "a", source = QueueSource.AUTO, state = state, enqueuedAt = 1, finishedAt = finished,
        )
        q.insert(row(QueueState.DONE, 10 * day))
        q.insert(row(QueueState.DONE, 190 * day))
        q.insert(row(QueueState.QUEUED, null))
        runVerify(g)
        assertEquals(listOf("new"), g.database.events().recent(10).first().map { it.kind })
        assertEquals(listOf(2L, 3L), (q.history().first() + q.activeNow()).map { it.id }.sorted())
    }

    @Test fun optimizeWorkRequiresStorageNotLow() = runBlocking {
        g.scheduler.enqueue("a")
        assertTrue(infos("a").single().constraints.requiresStorageNotLow())
    }

    @Test fun optimizeNowIsNotHeldByLowStorage() = runBlocking {
        g.scheduler.optimizeNow("a")
        assertFalse(infos("a").single().constraints.requiresStorageNotLow())
    }

    @Test fun aStoppedWorkerDoesNotLeaveTheRowRunning() = runBlocking {
        optimizer.before = { CompletableDeferred<Unit>().await() }
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("running") { state(1) == QueueState.RUNNING }
        wm.cancelUniqueWork(workName("a")).result.get() // what Android does when constraints are lost
        await("reset") { state(1) == QueueState.QUEUED }
        assertEquals(null, g.database.queue().get(1)!!.startedAt)
    }

    @Test fun requeueLeavesARowThatWasCancelledMeanwhile() = runBlocking {
        val dao = g.database.queue()
        val id = dao.insert(QueueItem(packageName = "a", source = QueueSource.MANUAL, state = QueueState.SKIPPED, enqueuedAt = 1, finishedAt = 2))
        dao.requeueIfRunning(id)
        assertEquals(QueueState.SKIPPED, dao.get(id)!!.state)
    }

    @Test fun weeklyVerifyRequeuesADowngradedApp() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "1"))
        g.database.watchedApps().upsert(WatchedApp("b", "1"))
        optimizer.filters["a"] = "speed"
        optimizer.filters["b"] = "verify"
        runVerify(g)
        assertEquals(listOf("b"), g.database.queue().activeNow().map { it.packageName })
    }

    @Test fun weeklyVerifySkipsADebuggableApp() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "1"))
        optimizer.filters["a"] = "verify"
        system.debuggable += "a"
        runVerify(g)
        assertTrue(g.database.queue().activeNow().isEmpty())
    }

    @Test fun aDebuggableAppFailsWithoutCompiling() = runBlocking {
        system.debuggable += "a"
        optimizeNow("a")
        await("failed") { state(1) == QueueState.FAILED }
        assertEquals(DEBUG_BUILD, g.database.queue().get(1)!!.failureReason)
        assertTrue(optimizer.optimized.isEmpty())
    }

    @Test fun pauseStopsAutomaticJobsButNotOptimizeNow() = runBlocking {
        gate.status = GateStatus.Paused
        g.scheduler.enqueue("a", QueueSource.AUTO)
        skipDelay("a")
        await("paused") { g.database.queue().get(1)!!.failureReason == "Paused" }
        optimizeNow("b")
        await("b done") { state(2) == QueueState.DONE }
        assertEquals(listOf("b"), optimizer.optimized)
    }

    @Test fun aFailedJobCanBeRetried() = runBlocking {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "nope")
        optimizeNow("a")
        await("failed") { state(1) == QueueState.FAILED }
        assertEquals("nope", g.scheduler.history.first().single().failureReason)
        g.scheduler.retry(1)
        infos("a").filter { !it.state.isFinished }.forEach { driver.setAllConstraintsMet(it.id) }
        await("second run") { optimizer.optimized.size == 2 }
        assertEquals(QueueState.FAILED, state(1)) // the failed row stays in the history
        assertEquals("a", g.database.queue().get(2)!!.packageName)
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

    @Test fun addToQueueIgnoresThePauseSwitch() = runBlocking {
        gate.status = GateStatus.Paused
        g.scheduler.enqueue("a", QueueSource.MANUAL)
        skipDelay("a")
        await("done") { state(1) == QueueState.DONE }
    }

    @Test fun optimizeNowGoesBeforeAWaitingAutoJob() = runBlocking {
        system.apps["c"] = "1"
        val release = CompletableDeferred<Unit>()
        optimizer.before = { if (it == "a") release.await() }
        g.scheduler.enqueue("a", QueueSource.AUTO)
        skipDelay("a")
        await("a compiling") { state(1) == QueueState.RUNNING }
        g.scheduler.enqueue("b", QueueSource.AUTO)
        skipDelay("b")
        optimizeNow("c")
        await("c waiting to go first") { runNowWaiting.get() == 1 }
        release.complete(Unit)
        await("all done") { state(1) == QueueState.DONE && state(2) == QueueState.DONE && state(3) == QueueState.DONE }
        assertEquals(listOf("a", "c", "b"), optimizer.optimized)
    }

    @Test fun concurrentEnqueuesKeepOneRow() = runBlocking {
        (1..20).map { async(Dispatchers.Default) { g.scheduler.enqueue("a", QueueSource.AUTO) } }.awaitAll()
        assertEquals(1, g.database.queue().activeNow().size)
    }

    @Test fun anUpdateDuringTheCompileStillDiffersAfterwards() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "1"))
        optimizer.before = { system.apps["a"] = "2" }
        optimizeNow("a")
        await("done") { state(1) == QueueState.DONE }
        assertEquals("1", g.database.watchedApps().get("a")!!.fingerprint) // the pre-compile value
        runDetect(g)
        assertEquals(listOf("a"), g.database.queue().activeNow().map { it.packageName })
    }

    @Test fun verifyIgnoresAnUnreadableFilterAndLogsIt() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "1"))
        runVerify(g) // no filter known for "a"
        assertTrue(g.database.queue().activeNow().isEmpty())
        assertEquals("verify_unknown", g.database.events().recent(5).first().first().kind)
    }

    @Test fun lostConnectionIsRetriedFiveTimesThenFails() = runBlocking {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "lost", connectionLost = true)
        g.scheduler.enqueue("a")
        skipDelay("a")
        for (n in 1..4) {
            await("try $n") { infos("a").single().let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == n } }
            assertEquals(QueueState.WAITING_GATE, state(1))
            driver.setAllConstraintsMet(infos("a").single().id)
        }
        await("failed") { state(1) == QueueState.FAILED }
        assertEquals(5, optimizer.optimized.size)
        assertEquals("lost", g.database.queue().get(1)!!.failureReason)
    }

    private suspend fun refuseOnce(): java.util.UUID {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "refused", refused = true)
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("requeued") { wm.getWorkInfosForUniqueWork("refused:1").get().isNotEmpty() }
        assertEquals(QueueState.WAITING_GATE, state(1))
        assertEquals(1, optimizer.optimized.size)
        val retry = wm.getWorkInfosForUniqueWork("refused:1").get().single()
        assertEquals(15 * 60_000L, retry.initialDelayMillis) // fixed, not WorkManager's growing backoff
        return retry.id
    }

    private fun runRefusedRetry(id: java.util.UUID) {
        driver.setInitialDelayMet(id)
        driver.setAllConstraintsMet(id)
    }

    @Test fun aRefusedCompileIsRequeuedOnceThenFails() = runBlocking {
        runRefusedRetry(refuseOnce())
        await("failed") { state(1) == QueueState.FAILED }
        assertEquals(2, optimizer.optimized.size)
        assertEquals("refused", g.database.queue().get(1)!!.failureReason)
    }

    @Test fun aSuccessAfterARefusalClearsTheRefusalCount() = runBlocking {
        val id = refuseOnce()
        assertEquals("1", g.database.syncState().get("refused:a"))
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(true)
        runRefusedRetry(id)
        await("done") { state(1) == QueueState.DONE }
        assertEquals("0", g.database.syncState().get("refused:a"))
    }

    private val noProfile = dev.speedcompiler.tv.engine.OptimizeResult(false, dev.speedcompiler.tv.engine.NO_PROFILE_YET, noProfile = true)

    private suspend fun waitForLaunch(): java.util.UUID {
        optimizer.resultFor = { if (it == "speed") dev.speedcompiler.tv.engine.OptimizeResult(true) else noProfile }
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("requeued") { wm.getWorkInfosForUniqueWork("launch:1").get().isNotEmpty() }
        assertEquals(QueueState.WAITING_GATE, state(1))
        assertEquals(dev.speedcompiler.tv.engine.NO_PROFILE_YET, g.database.queue().get(1)!!.failureReason)
        val retry = wm.getWorkInfosForUniqueWork("launch:1").get().single()
        assertEquals(60 * 60_000L, retry.initialDelayMillis)
        return retry.id
    }

    @Test fun noProfileWaitsAndRequeuesTheSameRow() = runBlocking {
        val id = waitForLaunch()
        assertEquals(listOf("speed-profile"), optimizer.usedFilters)
        assertEquals("1|1000", g.database.syncState().get("noprofile:a"))
        runRefusedRetry(id)
        await("second try") { optimizer.usedFilters.size == 2 }
        assertEquals(listOf("speed-profile", "speed-profile"), optimizer.usedFilters)
    }

    @Test fun afterSevenDaysNoProfileCompilesWithSpeed() = runBlocking {
        val id = waitForLaunch()
        (g.clock as FakeClock).now += 7 * 24 * 60 * 60 * 1000L
        runRefusedRetry(id)
        await("done") { state(1) == QueueState.DONE }
        assertEquals(listOf("speed-profile", "speed-profile", "speed"), optimizer.usedFilters)
        assertEquals("", g.database.syncState().get("noprofile:a"))
    }

    @Test fun aNewFingerprintRestartsTheSevenDayClock() = runBlocking {
        val id = waitForLaunch()
        (g.clock as FakeClock).now += 8 * 24 * 60 * 60 * 1000L
        g.database.syncState().put(dev.speedcompiler.tv.watch.SyncState("noprofile:a", "old|0"))
        runRefusedRetry(id)
        await("second try") { optimizer.usedFilters.size == 2 }
        assertEquals("speed-profile", optimizer.usedFilters[1])
        // The row is written after the compile returns, so wait for it instead of reading it at once.
        await("waiting again") { state(1) == QueueState.WAITING_GATE && g.database.syncState().get("noprofile:a") != "old|0" }
        assertEquals("1|${g.clock.nowMillis()}", g.database.syncState().get("noprofile:a"))
    }

    @Test fun detectLeavesALaunchWaitAlone() = runBlocking {
        waitForLaunch()
        gate.status = GateStatus.Open
        runDetect(g)
        assertTrue(infos("a").all { it.state.isFinished })
        assertEquals(1, optimizer.usedFilters.size)
    }

    @Test fun cancelStopsThePendingLaunchTry() = runBlocking {
        waitForLaunch()
        g.scheduler.cancel(1)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork("launch:1").get().single().state)
        assertEquals(QueueState.SKIPPED, state(1))
    }

    @Test fun optimizeNowOnAWaitingRowKeepsTheSevenDayClock() = runBlocking {
        waitForLaunch()
        (g.clock as FakeClock).now += 60_000
        optimizeNow("a")
        await("second try") { optimizer.usedFilters.size == 2 }
        await("waiting again") { state(1) == QueueState.WAITING_GATE }
        assertEquals("1|1000", g.database.syncState().get("noprofile:a"))
    }

    @Test fun aSuccessClearsTheNoProfileKey() = runBlocking {
        val id = waitForLaunch()
        optimizer.resultFor = { dev.speedcompiler.tv.engine.OptimizeResult(true) }
        runRefusedRetry(id)
        await("done") { state(1) == QueueState.DONE }
        assertEquals("", g.database.syncState().get("noprofile:a"))
    }

    @Test fun aNonGenericFailureIsNotRequeued() = runBlocking {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "Package not found")
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("failed") { state(1) == QueueState.FAILED }
        assertTrue(wm.getWorkInfosForUniqueWork("refused:1").get().isEmpty())
        assertEquals(1, optimizer.optimized.size)
    }

    @Test fun aFailedCompileQueuesOneRetrySixHoursLater() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "1"))
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "nope")
        g.scheduler.enqueue("a", QueueSource.AUTO)
        skipDelay("a")
        await("failed") { state(1) == QueueState.FAILED }
        await("retry scheduled") { wm.getWorkInfosForUniqueWork("retry:a").get().isNotEmpty() }
        val retry = wm.getWorkInfosForUniqueWork("retry:a").get().single()
        assertEquals(WorkInfo.State.ENQUEUED, retry.state)
        assertEquals(6 * 3_600_000L, retry.initialDelayMillis)
        driver.setInitialDelayMet(retry.id)
        await("queued again") { g.database.queue().activeNow().any { it.packageName == "a" } }
        skipDelay("a")
        await("failed again") { optimizer.optimized.size == 2 && g.database.queue().activeNow().isEmpty() }
        assertTrue(wm.getWorkInfosForUniqueWork("retry:a").get().none { it.state == WorkInfo.State.ENQUEUED }) // only one retry
    }

    @Test fun aLaterSuccessCancelsThePendingRetry() = runBlocking {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "nope")
        g.scheduler.enqueue("a", QueueSource.AUTO)
        skipDelay("a")
        await("retry scheduled") { wm.getWorkInfosForUniqueWork("retry:a").get().isNotEmpty() }
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(true)
        await("failed") { state(1) == QueueState.FAILED }
        g.scheduler.retry(1)
        skipDelay("a")
        await("done") { state(2) == QueueState.DONE }
        await("retry cancelled") { wm.getWorkInfosForUniqueWork("retry:a").get().single().state == WorkInfo.State.CANCELLED }
    }

    @Test fun aFailureThatNeedsTheUserGetsNoDelayedRetry() = runBlocking {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "pair again", terminal = true)
        g.scheduler.enqueue("a", QueueSource.AUTO)
        skipDelay("a")
        await("failed") { state(1) == QueueState.FAILED }
        await("worker finished") { infos("a").single().state == WorkInfo.State.SUCCEEDED }
        assertTrue(wm.getWorkInfosForUniqueWork("retry:a").get().isEmpty())
    }

    @Test fun aCancelGivesTheNextJobItsFullConnectionTries() = runBlocking {
        optimizer.result = dev.speedcompiler.tv.engine.OptimizeResult(false, "lost", connectionLost = true)
        g.scheduler.enqueue("a")
        skipDelay("a")
        await("one try counted") { g.database.syncState().get("lost:a") == "1" }
        await("waiting with a reason") { g.database.queue().get(1)?.failureReason != null }
        g.scheduler.cancel(1)
        assertEquals("0", g.database.syncState().get("lost:a"))
        assertEquals(QueueState.SKIPPED, state(1))
        assertNull(g.database.queue().get(1)!!.failureReason) // reads as cancelled, not as the old waiting reason
    }

    @Test fun anExceptionInTheRunMarksTheRowFailed() = runBlocking {
        g.database.syncState().put(dev.speedcompiler.tv.watch.SyncState("lost:a", "3"))
        optimizer.before = { throw IllegalStateException("boom") }
        optimizeNow("a")
        await("failed") { state(1) == QueueState.FAILED }
        assertTrue(g.database.queue().get(1)!!.failureReason!!.contains("IllegalStateException"))
        assertEquals("0", g.database.syncState().get("lost:a"))
    }

    @Test fun recordingAResultKeepsAConcurrentAutoOptimizeToggle() = runBlocking {
        g.database.watchedApps().upsert(WatchedApp("a", "1"))
        optimizer.before = { g.database.watchedApps().setAutoOptimize("a", false) } // the user flips it mid-compile
        optimizeNow("a")
        await("done") { state(1) == QueueState.DONE }
        val app = g.database.watchedApps().get("a")!!
        assertFalse(app.autoOptimize)
        assertEquals(1_000L, app.lastOptimizedAt)
        assertEquals("1", app.fingerprint)
    }
}
