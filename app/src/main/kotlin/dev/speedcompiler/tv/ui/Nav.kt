package dev.speedcompiler.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.map
import dev.speedcompiler.tv.ui.home.rememberGraph
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.speedcompiler.tv.theme.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.ui.apps.AppDetailScreen
import dev.speedcompiler.tv.ui.apps.AppsScreen
import dev.speedcompiler.tv.ui.help.DiagnosticsScreen
import dev.speedcompiler.tv.ui.help.HelpScreen
import dev.speedcompiler.tv.ui.home.ActivityScreen
import dev.speedcompiler.tv.ui.home.HomeScreen
import dev.speedcompiler.tv.ui.settings.SettingsScreen
import dev.speedcompiler.tv.ui.setup.QuickRepairScreen
import dev.speedcompiler.tv.ui.setup.ReconnectScreen
import dev.speedcompiler.tv.ui.setup.SetupScreen
import dev.speedcompiler.tv.ui.working.WorkingScreen

object Routes {
    const val Setup = "setup"
    const val Home = "home"
    const val Apps = "apps"
    const val AppDetail = "apps/detail/{pkg}"
    fun appDetail(pkg: String) = "apps/detail/$pkg"
    const val Working = "working/{pkg}"
    fun working(pkg: String) = "working/$pkg"
    const val Activity = "activity"
    const val Settings = "settings"
    const val Reconnect = "reconnect"
    const val QuickRepair = "repair"
    const val Diagnostics = "diagnostics"
    const val Help = "help"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val go: (String) -> Unit = { nav.navigate(it) }
    val back: () -> Unit = { nav.popBackStack() }
    // First run starts at Setup; wait for the stored flag so Home never flashes first.
    val graph = rememberGraph()
    val setupDone by remember(graph) { graph.settings.values.map { it.setupDone } }.collectAsState(null)
    val loaded = setupDone ?: return
    val start = remember { if (loaded) Routes.Home else Routes.Setup } // fixed for the session; the flag flipping must not re-route
    NavHost(nav, startDestination = start) {
        // Setup can be the first screen, so leaving it goes Home instead of popping to nothing.
        composable(Routes.Setup) {
            SetupScreen(onBack = { if (!nav.popBackStack(Routes.Home, false)) nav.navigate(Routes.Home) { popUpTo(Routes.Setup) { inclusive = true } } })
        }
        composable(Routes.Home) { HomeScreen(go) }
        composable(Routes.Apps) { AppsScreen(go, back) }
        composable(Routes.AppDetail) { AppDetailScreen(it.arguments?.getString("pkg").orEmpty(), go, back) }
        composable(Routes.Working) { WorkingScreen(it.arguments?.getString("pkg").orEmpty(), back) }
        composable(Routes.Activity) { ActivityScreen(back) }
        composable(Routes.Settings) { SettingsScreen(go, back) }
        composable(Routes.Reconnect) { ReconnectScreen(back) }
        composable(Routes.QuickRepair) { QuickRepairScreen(back) }
        composable(Routes.Diagnostics) { DiagnosticsScreen(back) }
        composable(Routes.Help) { HelpScreen(go, back) }
    }
}
