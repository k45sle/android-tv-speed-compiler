package dev.speedcompiler.tv

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import dev.speedcompiler.tv.watch.ScreenOffReceiver
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
        // System-only broadcasts, so no export flag is needed; lives as long as the process that holds the TV link.
        registerReceiver(ScreenOffReceiver(), IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_DREAMING_STARTED) })
    }
}
