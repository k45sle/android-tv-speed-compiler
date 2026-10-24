package dev.speedcompiler.tv

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import android.media.AudioManager
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.adb.ConnectionMonitor
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.adb.ConnectionKeeper
import dev.speedcompiler.tv.adb.LibAdb
import dev.speedcompiler.tv.adb.Pairing
import dev.speedcompiler.tv.engine.AndroidPackageSystem
import dev.speedcompiler.tv.engine.Clock
import dev.speedcompiler.tv.engine.DataStoreSettings
import dev.speedcompiler.tv.engine.DeviceSignalsImpl
import dev.speedcompiler.tv.engine.Gate
import dev.speedcompiler.tv.engine.GateConfigProvider
import dev.speedcompiler.tv.engine.GateImpl
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.Optimizer
import dev.speedcompiler.tv.engine.OptimizerImpl
import dev.speedcompiler.tv.engine.PackageSystem
import dev.speedcompiler.tv.engine.Settings
import dev.speedcompiler.tv.engine.SystemClock
import dev.speedcompiler.tv.engine.toGateConfig
import dev.speedcompiler.tv.watch.AppDatabase
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.Scheduler
import dev.speedcompiler.tv.watch.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.settingsStore by preferencesDataStore("settings")

/** Manual binding point. The `var`s let tests swap in fakes. */
class AppGraph(context: Context) {
    private val app = context.applicationContext

    /** Lives as long as the process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val libAdb = LibAdb(app)
    val adbShell: AdbShell = libAdb
    val pairing: Pairing = libAdb
    val settings: Settings = DataStoreSettings(app.settingsStore)
    val connectionKeeper = ConnectionKeeper({ libAdb.connect() }, libAdb, settings, app.packageName, appScope)
    val connectionMonitor: ConnectionMonitor = connectionKeeper
    var database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "speed.db").build()
    var clock: Clock = SystemClock
    var packageSystem: PackageSystem = AndroidPackageSystem(app)
    var optimizer: Optimizer = OptimizerImpl(libAdb, database.events(), clock)
    var gate: Gate = GateImpl(
        clock,
        DeviceSignalsImpl(libAdb) { app.getSystemService(AudioManager::class.java).isMusicActive },
        GateConfigProvider { settings.values.first().toGateConfig() },
    )
    var scheduler: Scheduler = WorkScheduler(app, this)

    /** The TV keeps the trust, so this flag (read by Setup) is the only local trace that we hold a pairing. */
    init {
        val prefs = app.getSharedPreferences("setup", Context.MODE_PRIVATE)
        appScope.launch {
            connectionMonitor.state.collect {
                if (it == ConnectionState.Connected) {
                    prefs.edit().putBoolean("paired", true).apply()
                    launch { runCatching { queueSelf() } } // best effort; must not end this collector
                } else if (it == ConnectionState.Unpaired) prefs.edit().putBoolean("paired", false).apply()
            }
        }
    }

    /**
     * Queues this app for its own speed compile until one succeeds for the installed version (OptimizeWorker
     * records it): a forced compile cut screen-change hitches. Not while paused or manual, where it would sit forever.
     */
    private suspend fun queueSelf() {
        val s = settings.values.first()
        if (s.paused || s.mode != Mode.Auto) return
        val self = packageSystem.fingerprint(app.packageName) ?: return
        if (database.syncState().get(KEY_SELF_COMPILED) == self) return
        if (database.queue().activeFor(app.packageName) != null) return // re-enqueueing would restart its debounce
        scheduler.enqueue(app.packageName, QueueSource.AUTO)
    }
}

const val KEY_SELF_COMPILED = "self_compiled"
