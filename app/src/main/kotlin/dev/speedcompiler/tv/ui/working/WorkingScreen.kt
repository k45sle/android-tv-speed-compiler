package dev.speedcompiler.tv.ui.working

import androidx.compose.runtime.Composable
import dev.speedcompiler.tv.ui.StubScreen

/** Shown after Optimize now (chunk 12b fills it in: animation, elapsed time, mini-games). */
@Composable
fun WorkingScreen(pkg: String, back: () -> Unit) = StubScreen("Optimizing $pkg", listOf("Back" to back))
