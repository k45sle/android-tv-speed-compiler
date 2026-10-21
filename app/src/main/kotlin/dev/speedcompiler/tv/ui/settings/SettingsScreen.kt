package dev.speedcompiler.tv.ui.settings

import androidx.compose.runtime.Composable
import dev.speedcompiler.tv.ui.StubScreen

@Composable
fun SettingsScreen(onBack: () -> Unit) = StubScreen("Settings", listOf("Back" to onBack))
