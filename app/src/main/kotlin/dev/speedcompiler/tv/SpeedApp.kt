package dev.speedcompiler.tv

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.watch.FrameSampler
import dev.speedcompiler.tv.watch.ScreenOffReceiver
import dev.speedcompiler.tv.watch.compileRunning
import dev.speedcompiler.tv.watch.enqueueReconnect
import dev.speedcompiler.tv.watch.schedulePeriodicJobs
import dev.speedcompiler.tv.watch.startWatchService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SpeedApp : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // WorkManager may be uninitialised under test.
        runCatching { enqueueReconnect(this); schedulePeriodicJobs(this) }
        graph.appScope.launch { graph.connectionKeeper.watchKeepTrusting() }
        // System-only broadcasts, so no export flag is needed; lives as long as the process (WatchService keeps it uncached).
        registerReceiver(ScreenOffReceiver(), IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_DREAMING_STARTED) })
        startWatchService(this) // without it Android caches and freezes this process, and the receiver and the loop below go silent
        // shortcut: a loop in this process, so sampling stops if Android kills the app and resumes when it starts again.
        // Off with the screen (isInteractive), while a compile holds the ADB link, and while not connected.
        val power = getSystemService(PowerManager::class.java)
        val sampler = FrameSampler(
            graph.adbShell, { graph.optimizer.verify(it) }, { graph.packageSystem.fingerprint(it) }, { graph.database },
            { power.isInteractive && !compileRunning() && graph.connectionMonitor.state.first() == ConnectionState.Connected },
        )
        graph.appScope.launch { while (true) { delay(60_000); sampler.tick() } }
    }
}
