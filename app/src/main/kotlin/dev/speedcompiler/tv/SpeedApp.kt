package dev.speedcompiler.tv

import android.app.Application
import dev.speedcompiler.tv.watch.enqueueReconnect
import kotlinx.coroutines.launch

class SpeedApp : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // WorkManager may be uninitialised under test.
        runCatching { enqueueReconnect(this) }
        graph.appScope.launch { graph.connectionKeeper.watchKeepTrusting() }
    }
}
