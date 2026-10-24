package dev.speedcompiler.tv.ui.setup

import androidx.compose.ui.platform.LocalResources
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.adb.ConnectionMonitor
import dev.speedcompiler.tv.adb.AdbFailure
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.ui.apps.AppInfo
import dev.speedcompiler.tv.ui.apps.loadUserApps
import dev.speedcompiler.tv.watch.PairingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias SetupApp = AppInfo

/** Everything the setup screens need from Android that a JVM test cannot do: Settings reads and launches. */
interface SetupEnv {
    fun developerOptionsOn(): Boolean
    fun wirelessDebuggingOn(): Boolean
    /** Opens Settings at About, where Build is pressed 7 times. */
    fun openSettings()
    fun openDeveloperOptions()
    fun startPairing()
    fun stopPairing()
    /** Asks the connection layer to connect now (wireless debugging just came on). */
    fun reconnect()
    /** Launchable apps, most recently updated first. */
    suspend fun recentApps(): List<SetupApp>
}

class SetupDeps(
    val env: SetupEnv,
    val connection: Flow<ConnectionState>,
    val pairingUrl: Flow<String?>,
    /** Why the last connect failed, in words for the user; null when it did not. */
    val reason: Flow<String?> = flowOf(null),
    /** Whether this app already holds a pairing the TV trusts; a fresh install does not. */
    val holdsPairing: () -> Boolean = { true },
    /** Called when the user finishes: saves the picks and starts the run. */
    val onFinish: suspend (selected: List<String>, optimizeNow: Boolean) -> Unit = { _, _ -> },
)

class AndroidSetupEnv(private val context: Context, private val monitor: ConnectionMonitor) : SetupEnv {
    private fun global(name: String) = Settings.Global.getInt(context.contentResolver, name, 0) == 1

    override fun developerOptionsOn() = global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
    override fun wirelessDebuggingOn() = global("adb_wifi_enabled")

    override fun openSettings() = launch(Settings.ACTION_DEVICE_INFO_SETTINGS, Settings.ACTION_SETTINGS)
    override fun openDeveloperOptions() =
        launch(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_SETTINGS)

    private fun launch(vararg actions: String) {
        for (a in actions) try {
            context.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun startPairing() {
        context.startForegroundService(Intent(context, PairingService::class.java))
    }

    override fun stopPairing() {
        context.stopService(Intent(context, PairingService::class.java))
    }

    override fun reconnect() = monitor.connectNow()

    override suspend fun recentApps(): List<SetupApp> {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val packages = withContext(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(launch, 0).map { it.activityInfo.packageName }.distinct()
        }
        return loadUserApps(context, packages).sortedByDescending { it.updatedAt }
    }
}

/** The connect failure in plain words. */
fun reasonText(res: Resources, e: Throwable): String = res.getString(
    when (e) {
        is AdbFailure.NotPaired -> R.string.setup_reason_not_paired
        is AdbFailure.DebuggingOff -> R.string.setup_reason_debugging_off
        is AdbFailure.NotAuthorized -> R.string.setup_reason_not_allowed
        is AdbFailure.Timeout -> R.string.setup_reason_timeout
        else -> R.string.setup_reason_could_not_connect
    },
)

@Composable
fun rememberSetupDeps(): SetupDeps {
    val context = LocalContext.current
    val res = LocalResources.current
    return remember(res) {
        val graph = (context.applicationContext as SpeedApp).graph
        val prefs = context.applicationContext.getSharedPreferences("setup", Context.MODE_PRIVATE)
        SetupDeps(
            AndroidSetupEnv(context.applicationContext, graph.connectionMonitor),
            graph.connectionMonitor.state,
            PairingService.url,
            graph.connectionKeeper.status.map { (it as? KeeperStatus.Failed)?.let { f -> reasonText(res, f.reason) } },
            holdsPairing = { prefs.getBoolean("paired", false) }, // kept by AppGraph
            onFinish = { selected, optimizeNow ->
                // watch already queues each app; "optimize now" turns that into a run-now job
                graph.settings.update { it.copy(setupDone = true) } // awaited, so Home never sees the old value
                graph.appScope.launch {
                    selected.forEach { graph.scheduler.watch(it) }
                    if (optimizeNow) selected.forEach { graph.scheduler.optimizeNow(it) }
                }
            },
        )
    }
}
