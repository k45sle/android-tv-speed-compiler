package dev.speedcompiler.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.theme.seedChoice
import dev.speedcompiler.tv.ui.AppNavHost
import dev.speedcompiler.tv.watch.enqueueReconnect
import dev.speedcompiler.tv.watch.startWatchService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by remember { (application as SpeedApp).graph.settings.values }.collectAsState(SettingsValues())
            SpeedTheme(settings.theme, settings.seedChoice()) { AppNavHost() }
        }
    }

    // The process may outlive a boot job that gave up, so opening the app tries again. The same goes for the watcher: a process
    // a background job started could not start it, and without it nothing samples frames or sees screen-off and app updates.
    override fun onStart() {
        super.onStart()
        runCatching { enqueueReconnect(this) }
        startWatchService(this)
    }
}
