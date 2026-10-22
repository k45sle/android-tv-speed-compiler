package dev.speedcompiler.tv.ui.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.speedcompiler.tv.SpeedApp
import dev.speedcompiler.tv.adb.ConnectionMonitor
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.watch.PairingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

data class SetupApp(val packageName: String, val label: String)

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
        val pm = context.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != context.packageName }
            .mapNotNull { p ->
                runCatching {
                    pm.getPackageInfo(p, 0).lastUpdateTime to SetupApp(p, pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString())
                }.getOrNull()
            }
            .sortedByDescending { it.first }.map { it.second }
    }
}

@Composable
fun rememberSetupDeps(): SetupDeps {
    val context = LocalContext.current
    return remember {
        val graph = (context.applicationContext as SpeedApp).graph
        SetupDeps(
            AndroidSetupEnv(context.applicationContext, graph.connectionMonitor),
            graph.connectionMonitor.state,
            PairingService.url,
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
