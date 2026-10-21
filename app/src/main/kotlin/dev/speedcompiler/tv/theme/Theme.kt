package dev.speedcompiler.tv.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.darkColorScheme

@Composable
fun SpeedTheme(content: @Composable () -> Unit) {
    // Surface supplies LocalContentColor (onSurface); without it tv Text defaults to black.
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}
