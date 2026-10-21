package dev.speedcompiler.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.AppNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpeedTheme { AppNavHost() } }
    }
}
