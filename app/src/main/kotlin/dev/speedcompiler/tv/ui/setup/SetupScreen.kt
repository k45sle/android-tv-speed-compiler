package dev.speedcompiler.tv.ui.setup

import androidx.compose.runtime.Composable
import dev.speedcompiler.tv.ui.StubScreen

@Composable
fun SetupScreen(onBack: () -> Unit) = StubScreen("Setup", listOf("Back" to onBack))
