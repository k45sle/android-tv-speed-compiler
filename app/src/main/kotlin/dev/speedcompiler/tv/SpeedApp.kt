package dev.speedcompiler.tv

import android.app.Application
import android.os.PowerManager
import android.util.Log
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.watch.FrameSampler
import dev.speedcompiler.tv.watch.compileRunning
import dev.speedcompiler.tv.watch.enqueueReconnect
import dev.speedcompiler.tv.watch.schedulePeriodicJobs
import dev.speedcompiler.tv.watch.startWatchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

const val SAMPLE_TIMEOUT_MS = 20_000L

class SpeedApp : Application() {
    val graph by lazy { AppGraph(this) }

    // shortcut: session totals live in memory, so a session restarts if Android kills this process between ticks.
    // Off with the screen (isInteractive), while a compile holds the ADB link, and while not connected.
    private val sampler by lazy {
        val power = getSystemService(PowerManager::class.java)
        FrameSampler(
            graph.adbShell, { graph.optimizer.verify(it) }, { graph.packageSystem.fingerprint(it) }, { graph.database },
            { power.isInteractive && !compileRunning() && graph.connectionMonitor.state.first() == ConnectionState.Connected },
        )
    }
    private val sampling = Mutex()
    internal var sampleTick: suspend () -> Unit = { sampler.tick() } // tests swap this

    /**
     * One sampler tick for WakeReceiver; skipped while the previous one still runs. Capped well under the ~60 s a background
     * broadcast may take (a link that dropped while this process was frozen can hang), and never throws into appScope.
     */
    suspend fun sampleOnce() {
        if (!sampling.tryLock()) return
        try {
            withTimeoutOrNull(SAMPLE_TIMEOUT_MS) { sampleTick() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("SpeedApp", "sample failed", e)
        } finally {
            sampling.unlock()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":watch")) return // WatchService's process: no graph, WorkManager or ADB there
        // WorkManager may be uninitialised under test.
        runCatching { enqueueReconnect(this); schedulePeriodicJobs(this) }
        graph.appScope.launch { graph.connectionKeeper.watchKeepTrusting() }
        startWatchService(this) // its process forwards screen-off and the sampler tick to WakeReceiver here
    }
}
