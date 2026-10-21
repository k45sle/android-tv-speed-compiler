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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.ui.apps.AppDetailScreen
import dev.speedcompiler.tv.ui.apps.AppsScreen
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
    NavHost(nav, startDestination = Routes.Home) {
        composable(Routes.Setup) { SetupScreen(back) }
        composable(Routes.Home) { HomeScreen(go) }
        composable(Routes.Apps) { AppsScreen(go, back) }
        composable(Routes.AppDetail) { AppDetailScreen(it.arguments?.getString("pkg").orEmpty(), go, back) }
        composable(Routes.Working) { WorkingScreen(it.arguments?.getString("pkg").orEmpty(), back) }
        composable(Routes.Activity) { ActivityScreen(back) }
        composable(Routes.Settings) { SettingsScreen(go, back) }
        composable(Routes.Reconnect) { ReconnectScreen(back) }
        composable(Routes.QuickRepair) { QuickRepairScreen(back) }
    }
}

/** Empty placeholder screen: a title and focusable buttons, so D-pad navigation can be exercised. */
@Composable
fun StubScreen(title: String, links: List<Pair<String, () -> Unit>>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        links.forEachIndexed { i, (label, action) ->
            Button(onClick = action, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                Text(label)
            }
        }
    }
}
