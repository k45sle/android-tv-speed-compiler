package dev.speedcompiler.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.AppNavHost
import dev.speedcompiler.tv.watch.enqueueReconnect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpeedTheme { AppNavHost() } }
    }

    // The process may outlive a boot job that gave up, so opening the app tries again.
    override fun onStart() {
        super.onStart()
        runCatching { enqueueReconnect(this) }
    }
}
