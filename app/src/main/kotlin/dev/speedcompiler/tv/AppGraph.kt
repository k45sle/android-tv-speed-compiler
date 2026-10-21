package dev.speedcompiler.tv

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.adb.ConnectionMonitor
import dev.speedcompiler.tv.adb.ConnectionKeeper
import dev.speedcompiler.tv.adb.LibAdb
import dev.speedcompiler.tv.adb.Pairing
import dev.speedcompiler.tv.engine.Clock
import dev.speedcompiler.tv.engine.DataStoreSettings
import dev.speedcompiler.tv.engine.DeviceSignals
import dev.speedcompiler.tv.engine.FakeClock
import dev.speedcompiler.tv.engine.FakeDeviceSignals
import dev.speedcompiler.tv.engine.FakeGate
import dev.speedcompiler.tv.engine.FakeOptimizer
import dev.speedcompiler.tv.engine.FakePackageSource
import dev.speedcompiler.tv.engine.Gate
import dev.speedcompiler.tv.engine.Optimizer
import dev.speedcompiler.tv.engine.PackageSource
import dev.speedcompiler.tv.engine.Settings
import dev.speedcompiler.tv.watch.AppDatabase
import dev.speedcompiler.tv.watch.FakeScheduler
import dev.speedcompiler.tv.watch.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private val Context.settingsStore by preferencesDataStore("settings")

/** Manual binding point. Fakes for now; the wiring chunk replaces them with real implementations. */
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
    val packageSource: PackageSource = FakePackageSource()
    val optimizer: Optimizer = FakeOptimizer()
    val gate: Gate = FakeGate()
    val clock: Clock = FakeClock()
    val deviceSignals: DeviceSignals = FakeDeviceSignals()
    val scheduler: Scheduler = FakeScheduler()
    val database: AppDatabase by lazy { Room.databaseBuilder(app, AppDatabase::class.java, "speed.db").build() }
}
