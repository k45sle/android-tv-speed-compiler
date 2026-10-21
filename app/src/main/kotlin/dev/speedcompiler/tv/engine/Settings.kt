package dev.speedcompiler.tv.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class Mode { Auto, Manual }

enum class ThemeChoice { System, Light, Dark }

data class SettingsValues(
    val mode: Mode = Mode.Auto,
    /** Allowed optimize window as minutes since midnight; default 02:00-06:00. */
    val windowStartMinute: Int = 120,
    val windowEndMinute: Int = 360,
    val paused: Boolean = false,
    val keepTrusting: Boolean = true,
    val theme: ThemeChoice = ThemeChoice.System,
)

interface Settings {
    val values: Flow<SettingsValues>
    suspend fun update(transform: (SettingsValues) -> SettingsValues)
}

class DataStoreSettings(private val store: DataStore<Preferences>) : Settings {
    private val mode = stringPreferencesKey("mode")
    private val start = intPreferencesKey("window_start")
    private val end = intPreferencesKey("window_end")
    private val paused = booleanPreferencesKey("paused")
    private val keepTrusting = booleanPreferencesKey("keep_trusting")
    private val theme = stringPreferencesKey("theme")

    override val values: Flow<SettingsValues> = store.data.map { p ->
        val d = SettingsValues()
        SettingsValues(
            mode = p[mode]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: d.mode,
            windowStartMinute = p[start] ?: d.windowStartMinute,
            windowEndMinute = p[end] ?: d.windowEndMinute,
            paused = p[paused] ?: d.paused,
            keepTrusting = p[keepTrusting] ?: d.keepTrusting,
            theme = p[theme]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: d.theme,
        )
    }

    override suspend fun update(transform: (SettingsValues) -> SettingsValues) {
        val new = transform(values.first())
        store.edit { p ->
            p[mode] = new.mode.name
            p[start] = new.windowStartMinute
            p[end] = new.windowEndMinute
            p[paused] = new.paused
            p[keepTrusting] = new.keepTrusting
            p[theme] = new.theme.name
        }
    }
}
