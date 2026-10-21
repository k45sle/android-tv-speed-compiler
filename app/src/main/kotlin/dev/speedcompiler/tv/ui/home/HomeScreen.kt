package dev.speedcompiler.tv.ui.home

import androidx.compose.runtime.Composable
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.ui.StubScreen

@Composable
fun HomeScreen(go: (String) -> Unit) = StubScreen(
    "Home",
    listOf(
        "Apps" to { go(Routes.Apps) },
        "Activity" to { go(Routes.Activity) },
        "Settings" to { go(Routes.Settings) },
        "Setup" to { go(Routes.Setup) },
    ),
)

@Composable
fun ActivityScreen(onBack: () -> Unit) = StubScreen("Activity", listOf("Back" to onBack))
