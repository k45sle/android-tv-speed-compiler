package dev.speedcompiler.tv.ui.apps

import androidx.compose.runtime.Composable
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.ui.StubScreen

@Composable
fun AppsScreen(go: (String) -> Unit, onBack: () -> Unit) = StubScreen(
    "Apps",
    listOf("App detail" to { go(Routes.appDetail("com.example.app")) }, "Back" to onBack),
)

@Composable
fun AppDetailScreen(packageName: String, onBack: () -> Unit) = StubScreen(packageName, listOf("Back" to onBack))
