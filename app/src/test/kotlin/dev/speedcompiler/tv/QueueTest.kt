package dev.speedcompiler.tv

import androidx.room.Room
import dev.speedcompiler.tv.engine.GateStatus
import dev.speedcompiler.tv.watch.AppDatabase
import dev.speedcompiler.tv.watch.Eta
import dev.speedcompiler.tv.watch.FakeScheduler
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.QueueState
import dev.speedcompiler.tv.watch.etaFor
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
// The app's conscrypt-android has no JVM native lib, so Robolectric must not install Conscrypt.
@ConscryptMode(ConscryptMode.Mode.OFF)
class QueueTest {
    private val opensAt = GateStatus.OpensAt(Instant.parse("2026-01-01T02:00:00Z"))
    private val allGates = listOf(GateStatus.Open, GateStatus.WaitingForIdle, GateStatus.WaitingForPlayback, opensAt)

    private fun item(state: QueueState = QueueState.QUEUED, runNow: Boolean = false) =
        QueueItem(packageName = "p", source = QueueSource.AUTO, state = state, enqueuedAt = 0, runNow = runNow)

    @Test
    fun finishedOrRunningJobsHaveNoEta() {
        for (state in listOf(QueueState.RUNNING, QueueState.DONE, QueueState.FAILED, QueueState.SKIPPED)) {
            for (gate in allGates) assertEquals(Eta.None, etaFor(item(state), gate, 0))
        }
    }

    @Test
    fun openGateGivesQueuePositionAndApproxWait() {
        assertEquals(Eta.InQueue(0, 0), etaFor(item(), GateStatus.Open, 0))
        assertEquals(Eta.InQueue(3, 360_000), etaFor(item(QueueState.WAITING_GATE), GateStatus.Open, 3))
        assertEquals(Eta.InQueue(2, 60_000), etaFor(item(), GateStatus.Open, 2, avgDurationMillis = 30_000))
    }

    @Test
    fun closedGateReportsWhatItWaitsFor() {
        assertEquals(Eta.WaitingForIdle, etaFor(item(), GateStatus.WaitingForIdle, 1))
        assertEquals(Eta.WaitingForPlayback, etaFor(item(), GateStatus.WaitingForPlayback, 1))
    }

    @Test
    fun setHoursGiveAStartTimeOffsetByJobsAhead() {
        assertEquals(Eta.StartsAt(opensAt.at), etaFor(item(), opensAt, 0))
        assertEquals(Eta.StartsAt(opensAt.at.plusSeconds(240)), etaFor(item(), opensAt, 2))
    }

    @Test
    fun runNowSkipsTheGate() {
        for (gate in allGates) assertEquals(Eta.InQueue(1, 120_000), etaFor(item(runNow = true), gate, 1))
    }

    @Test
    fun onlySetHoursProduceAClockTime() {
        for (state in QueueState.entries) for (runNow in listOf(false, true)) {
            for (gate in allGates - opensAt) assertFalse(etaFor(item(state, runNow), gate, 2) is Eta.StartsAt)
        }
    }

    @Test
    fun fakeSchedulerPutsOptimizeNowFirstAndDropsCancelled() = runBlocking {
        val s = FakeScheduler(GateStatus.WaitingForIdle)
        s.enqueue("a")
        s.enqueue("b")
        s.optimizeNow("c")
        s.cancel(1)
        val q = s.queue.first()
        assertEquals(listOf("c", "b"), q.map { it.item.packageName })
        assertEquals(listOf(0, 1), q.map { it.position })
        assertEquals(listOf(Eta.InQueue(0, 0), Eta.WaitingForIdle), q.map { it.eta })
        s.retry(1)
        assertEquals(listOf("c", "a", "b"), s.queue.first().map { it.item.packageName })
    }

    @Test
    fun daoListsActiveJobsInRunOrder() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val dao = db.queue()
        fun job(pkg: String, at: Long, state: QueueState = QueueState.QUEUED, runNow: Boolean = false) =
            QueueItem(packageName = pkg, source = QueueSource.MANUAL, state = state, enqueuedAt = at, runNow = runNow)
        dao.insert(job("done", 0, QueueState.DONE))
        dao.insert(job("late", 5))
        dao.insert(job("early", 1, QueueState.WAITING_GATE))
        dao.insert(job("now", 9, runNow = true))
        dao.insert(job("running", 7, QueueState.RUNNING))
        assertEquals(listOf("running", "now", "early", "late"), dao.active().first().map { it.packageName })
        db.close()
    }
}
