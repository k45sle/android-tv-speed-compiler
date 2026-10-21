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
    fun defaultsThenPersistsUpdates() = runBlocking {
        val store = PreferenceDataStoreFactory.create { tmp.newFile("s.preferences_pb").also { it.delete() } }
        val settings = DataStoreSettings(store)
        assertEquals(SettingsValues(), settings.values.first())

        settings.update { it.copy(mode = Mode.Manual, paused = true, theme = ThemeChoice.Dark, accentPreset = 3, customHue = 200f) }
        val v = settings.values.first()
        assertEquals(3, v.accentPreset)
        assertEquals(200f, v.customHue)
        settings.update { it.copy(customHue = null) }
        assertEquals(null, settings.values.first().customHue)
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
