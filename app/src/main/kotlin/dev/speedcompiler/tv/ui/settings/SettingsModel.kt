package dev.speedcompiler.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.ui.home.rememberGraph
import kotlinx.coroutines.flow.Flow

/** What the Settings screen needs, as plain values so tests can fake it. */
class SettingsDeps(
    val values: Flow<SettingsValues>,
    val update: suspend ((SettingsValues) -> SettingsValues) -> Unit,
    val status: Flow<KeeperStatus>,
    val version: String,
)

@Composable
fun rememberSettingsDeps(): SettingsDeps {
    val graph = rememberGraph()
    val context = LocalContext.current.applicationContext
    return remember(graph) {
        SettingsDeps(
            graph.settings.values, graph.settings::update, graph.connectionKeeper.status,
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: context.getString(R.string.settings_version_unknown),
        )
    }
}
