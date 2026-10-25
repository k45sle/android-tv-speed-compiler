package dev.speedcompiler.tv

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.speedcompiler.tv.adb.FakeAdbShell
import dev.speedcompiler.tv.adb.ShellResult
import dev.speedcompiler.tv.engine.DataStoreSettings
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun connectionSettingsAreOnByDefault() = runBlocking {
        val store = PreferenceDataStoreFactory.create { tmp.newFile("k.preferences_pb").also { it.delete() } }
        val v = DataStoreSettings(store).values.first()
        assertEquals(true, v.keepTrusting)
        assertEquals(true, v.keepWifiDebugging)
        assertEquals(dev.speedcompiler.tv.engine.OptimizeHow.Automatic, v.optimizeHow)
    }

    @Test
    fun defaultsThenPersistsUpdates() = runBlocking {
        val store = PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb").also { it.delete() } }
        val settings = DataStoreSettings(store)
        assertEquals(SettingsValues(), settings.values.first())

        settings.update { it.copy(mode = Mode.Manual, paused = true, theme = ThemeChoice.Dark, accentPreset = 3, customHue = 200f, optimizeHow = dev.speedcompiler.tv.engine.OptimizeHow.Full) }
        val v = settings.values.first()
        assertEquals(dev.speedcompiler.tv.engine.OptimizeHow.Full, v.optimizeHow)
        assertEquals(3, v.accentPreset)
        assertEquals(200f, v.customHue)
        settings.update { it.copy(customHue = null, setupDone = true) }
        assertEquals(null, settings.values.first().customHue)
        assertEquals(true, settings.values.first().setupDone)
        assertEquals(Mode.Manual, v.mode)
        assertEquals(true, v.paused)
        assertEquals(ThemeChoice.Dark, v.theme)
    }

    @Test
    fun concurrentUpdatesKeepEveryField() = runBlocking {
        val store = PreferenceDataStoreFactory.create { tmp.newFile("c.preferences_pb").also { it.delete() } }
        val settings = DataStoreSettings(store)
        coroutineScope {
            launch(Dispatchers.Default) { settings.update { it.copy(mode = Mode.Manual) } }
            launch(Dispatchers.Default) { settings.update { it.copy(paused = true) } }
            // Read-modify-write races: any update applied to a stale read loses an increment.
            repeat(50) { launch(Dispatchers.Default) { settings.update { it.copy(windowEndMinute = it.windowEndMinute + 1) } } }
        }
        assertEquals(
            SettingsValues(mode = Mode.Manual, paused = true, windowEndMinute = 360 + 50),
            settings.values.first(),
        )
    }

    @Test
    fun fakeShellRecordsCommands() = runBlocking {
        val shell = FakeAdbShell(mapOf("echo hi" to ShellResult(0, "hi")))
        assertEquals("hi", shell.run("echo hi").output)
        assertEquals(listOf("echo hi"), shell.commands)
    }
}
