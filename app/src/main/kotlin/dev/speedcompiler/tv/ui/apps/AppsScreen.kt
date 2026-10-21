package dev.speedcompiler.tv.ui.apps

import androidx.compose.runtime.Composable
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.ui.StubScreen

@Composable
fun AppsScreen(go: (String) -> Unit, onBack: () -> Unit) = StubScreen(
    "Apps",
    listOf("App detail" to { go(Routes.AppDetail) }, "Back" to onBack),
)

@Composable
fun AppDetailScreen(onBack: () -> Unit) = StubScreen("App detail", listOf("Back" to onBack))
