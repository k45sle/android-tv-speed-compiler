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
import dev.speedcompiler.tv.ui.setup.SetupScreen

object Routes {
    const val Setup = "setup"
    const val Home = "home"
    const val Apps = "apps"
    const val AppDetail = "apps/detail"
    const val Activity = "activity"
    const val Settings = "settings"
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
        composable(Routes.AppDetail) { AppDetailScreen(back) }
        composable(Routes.Activity) { ActivityScreen(back) }
        composable(Routes.Settings) { SettingsScreen(back) }
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
