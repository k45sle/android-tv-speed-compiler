package dev.speedcompiler.tv

import android.app.Application
import dev.speedcompiler.tv.watch.enqueueReconnect
import dev.speedcompiler.tv.watch.schedulePeriodicJobs
import kotlinx.coroutines.launch

class SpeedApp : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // WorkManager may be uninitialised under test.
        runCatching { enqueueReconnect(this); schedulePeriodicJobs(this) }
        graph.appScope.launch { graph.connectionKeeper.watchKeepTrusting() }
    }
}
