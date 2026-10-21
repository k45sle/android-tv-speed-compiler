package dev.speedcompiler.tv

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.adb.ConnectionMonitor
import dev.speedcompiler.tv.adb.FakeAdbShell
import dev.speedcompiler.tv.adb.FakeConnectionMonitor
import dev.speedcompiler.tv.adb.FakePairing
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

private val Context.settingsStore by preferencesDataStore("settings")

/** Manual binding point. Fakes for now; the wiring chunk replaces them with real implementations. */
class AppGraph(context: Context) {
    private val app = context.applicationContext

    val adbShell: AdbShell = FakeAdbShell()
    val pairing: Pairing = FakePairing()
    val connectionMonitor: ConnectionMonitor = FakeConnectionMonitor()
    val packageSource: PackageSource = FakePackageSource()
    val optimizer: Optimizer = FakeOptimizer()
    val gate: Gate = FakeGate()
    val clock: Clock = FakeClock()
    val deviceSignals: DeviceSignals = FakeDeviceSignals()
    val scheduler: Scheduler = FakeScheduler()
    val settings: Settings = DataStoreSettings(app.settingsStore)
    val database: AppDatabase by lazy { Room.databaseBuilder(app, AppDatabase::class.java, "speed.db").build() }
}
