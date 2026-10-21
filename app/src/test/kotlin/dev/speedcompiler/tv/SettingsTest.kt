package dev.speedcompiler.tv

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.speedcompiler.tv.adb.FakeAdbShell
import dev.speedcompiler.tv.adb.ShellResult
import dev.speedcompiler.tv.engine.DataStoreSettings
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice
import kotlinx.coroutines.flow.first
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

        settings.update { it.copy(mode = Mode.Manual, paused = true, theme = ThemeChoice.Dark) }
        val v = settings.values.first()
        assertEquals(Mode.Manual, v.mode)
        assertEquals(true, v.paused)
        assertEquals(ThemeChoice.Dark, v.theme)
    }

    @Test
    fun fakeShellRecordsCommands() = runBlocking {
        val shell = FakeAdbShell(mapOf("echo hi" to ShellResult(0, "hi")))
        assertEquals("hi", shell.run("echo hi").output)
        assertEquals(listOf("echo hi"), shell.commands)
    }
}
