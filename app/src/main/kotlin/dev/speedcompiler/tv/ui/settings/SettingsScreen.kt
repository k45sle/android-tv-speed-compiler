package dev.speedcompiler.tv.ui.settings

import dev.speedcompiler.tv.theme.scrollbar
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.engine.GateMode
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.ArcadeButton
import dev.speedcompiler.tv.theme.DipSwitch
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.OperatorRow
import dev.speedcompiler.tv.theme.PixelText
import dev.speedcompiler.tv.theme.Preset
import dev.speedcompiler.tv.theme.Presets
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.RowRadius
import dev.speedcompiler.tv.theme.Selector
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.StatusPill
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.theme.focusFx
import dev.speedcompiler.tv.theme.upper
import dev.speedcompiler.tv.ui.apps.SimpleDialog
import dev.speedcompiler.tv.theme.rememberFocusMemory
import dev.speedcompiler.tv.theme.remembersFocus
import dev.speedcompiler.tv.theme.seedForHue
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.ui.home.fixRoute
import dev.speedcompiler.tv.ui.home.fixText
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.speedcompiler.tv.R
import kotlinx.coroutines.launch

private const val STEP_MINUTES = 30
private const val DAY = 24 * 60

/** Steps from the stored value by [delta], skipping [other] so the window never has start == end. */
internal fun stepMinute(cur: Int, delta: Int, other: Int): Int {
    val n = (cur + delta + DAY) % DAY
    return if (n == other) (n + delta + DAY) % DAY else n
}

fun clockText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

private fun <T> List<T>.cycle(cur: T, d: Int) = this[(indexOf(cur) + d + size) % size]

@Composable
private fun gateLabel(g: GateMode) = stringResource(
    when (g) {
        GateMode.Idle -> R.string.settings_gate_idle
        GateMode.NothingPlaying -> R.string.settings_gate_nothing_playing
        GateMode.Window -> R.string.settings_gate_window
    },
)

@Composable
fun SettingsScreen(go: (String) -> Unit, back: () -> Unit, deps: SettingsDeps = rememberSettingsDeps()) {
    val v by remember(deps) { deps.values }.collectAsState(SettingsValues())
    val status by remember(deps) { deps.status }.collectAsState(KeeperStatus.Reconnecting)
    val scope = rememberCoroutineScope()
    var confirmWifiOff by remember { mutableStateOf(false) }
    val set: ((SettingsValues) -> SettingsValues) -> Unit = { f -> scope.launch { deps.update(f) } }
    val mem = rememberFocusMemory("auto")
    val c = LocalSpeedColors.current
    val runJobs = stringResource(R.string.settings_run_jobs)
    val theme = stringResource(R.string.settings_theme)
    val themeName = when (v.theme) {
        ThemeChoice.System -> stringResource(R.string.settings_theme_system)
        ThemeChoice.Light -> stringResource(R.string.settings_theme_light)
        ThemeChoice.Dark -> stringResource(R.string.settings_theme_dark)
    }
    val scroll = rememberScrollState()
    RoomBackdrop {
        Column(Modifier.padding(horizontal = 48.dp).padding(top = 16.dp, bottom = 8.dp), Arrangement.spacedBy(Space.x1)) {
            Marquee(stringResource(R.string.nav_settings), compact = true, onBack = back) { PixelText(stringResource(R.string.settings_version, deps.version), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(
                Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background).border(2.dp, c.hairline)
                    .scrollbar(scroll).verticalScroll(scroll).padding(horizontal = Space.x2, vertical = Space.x1),
            ) {
                Heading(stringResource(R.string.settings_heading_when))
                SwitchRow(
                    stringResource(R.string.settings_auto_title), stringResource(R.string.settings_auto_subtitle), !v.paused && v.mode == Mode.Auto,
                    Modifier.remembersFocus(mem, "auto"),
                ) { on -> set { if (on) it.copy(mode = Mode.Auto, paused = false) else it.copy(paused = true) } }
                val gates = GateMode.entries
                OperatorRow(
                    runJobs, hint(runJobs, gateLabel(v.gateMode)), onClick = { set { it.copy(gateMode = gates.cycle(it.gateMode, 1)) } },
                    onLeft = { set { it.copy(gateMode = gates.cycle(it.gateMode, -1)) } },
                    onRight = { set { it.copy(gateMode = gates.cycle(it.gateMode, 1)) } },
                ) { Selector(gateLabel(v.gateMode), it) }
                if (v.gateMode == GateMode.Window) {
                    TimeRow(stringResource(R.string.settings_start_time), v.windowStartMinute) { d -> set { it.copy(windowStartMinute = stepMinute(it.windowStartMinute, d, it.windowEndMinute)) } }
                    TimeRow(stringResource(R.string.settings_end_time), v.windowEndMinute) { d -> set { it.copy(windowEndMinute = stepMinute(it.windowEndMinute, d, it.windowStartMinute)) } }
                }

                Heading(stringResource(R.string.settings_heading_appearance))
                val themes = ThemeChoice.entries
                // Down from Theme lands on the picked swatch, not the geometrically nearest one.
                val picked = remember { FocusRequester() }
                OperatorRow(
                    theme, hint(theme, themeName).focusProperties { if (v.customHue == null && v.accentPreset in Presets.indices) down = picked }, onClick = { set { it.copy(theme = themes.cycle(it.theme, 1)) } },
                    onLeft = { set { it.copy(theme = themes.cycle(it.theme, -1)) } },
                    onRight = { set { it.copy(theme = themes.cycle(it.theme, 1)) } },
                ) { Selector(themeName, it) }
                Row(Modifier.padding(horizontal = Space.x2, vertical = Space.x1), Arrangement.spacedBy(Space.x2), Alignment.CenterVertically) {
                    Presets.forEachIndexed { i, p ->
                        val sel = v.customHue == null && v.accentPreset == i
                        Swatch(p, sel, if (sel) Modifier.focusRequester(picked) else Modifier) { set { it.copy(accentPreset = i, customHue = null) } }
                    }
                    Chip(stringResource(R.string.settings_custom), v.customHue != null) { set { it.copy(customHue = it.customHue ?: 150f) } }
                }
                v.customHue?.let { hue ->
                    BlockMeter(
                        hue, { h -> val d = (h - hue + 540f) % 360f - 180f; set { it.copy(customHue = ((it.customHue ?: hue) + d + 360f) % 360f) } },
                        Modifier.padding(horizontal = Space.x2, vertical = Space.x1),
                    )
                }

                Heading(stringResource(R.string.settings_heading_connection))
                SwitchRow(stringResource(R.string.settings_keep_trusting_title), stringResource(R.string.settings_keep_trusting_subtitle), v.keepTrusting) { on -> set { it.copy(keepTrusting = on) } }
                SwitchRow(stringResource(R.string.settings_keep_wifi_title), stringResource(R.string.settings_keep_wifi_subtitle), v.keepWifiDebugging) { on -> if (on) set { it.copy(keepWifiDebugging = true) } else confirmWifiOff = true }
                if (confirmWifiOff) {
                    val ok = remember { FocusRequester() }
                    LaunchedEffect(Unit) { ok.requestFocus() }
                    SimpleDialog({ confirmWifiOff = false }) {
                        Text(stringResource(R.string.settings_keep_wifi_off_body))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.x1)) {
                            Button({ confirmWifiOff = false; set { it.copy(keepWifiDebugging = false) } }, Modifier.focusRequester(ok)) { Text(stringResource(R.string.common_ok).upper()) }
                            Button({ confirmWifiOff = false }) { Text(stringResource(R.string.common_cancel).upper()) }
                        }
                    }
                }
                val failed = status as? KeeperStatus.Failed
                val (textRes, tone) = when (status) {
                    KeeperStatus.Connected -> R.string.home_connected to Tone.Ok
                    KeeperStatus.Reconnecting -> R.string.home_connecting to Tone.Info
                    is KeeperStatus.Failed -> R.string.settings_not_connected to Tone.Error
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.x2, vertical = Space.x1), Arrangement.spacedBy(Space.x2), Alignment.CenterVertically) {
                    Column(Modifier.padding(start = 28.dp).weight(1f)) {
                        Text(stringResource(R.string.settings_status), style = MaterialTheme.typography.titleLarge)
                        failed?.let { Text(fixText(LocalResources.current, it.fix), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    StatusPill(stringResource(textRes), tone)
                    ArcadeButton(stringResource(R.string.common_fix_connection), { go(fixRoute(failed?.fix ?: Fix.Retry)) }, Modifier.remembersFocus(mem, "fix"))
                }

                Heading(stringResource(R.string.settings_heading_about))
                Row(Modifier.padding(horizontal = Space.x2, vertical = Space.x1), Arrangement.spacedBy(Space.x2)) {
                    ArcadeButton(stringResource(R.string.settings_export_diagnostics), { go(Routes.Diagnostics) }, Modifier.remembersFocus(mem, "diag"))
                    ArcadeButton(stringResource(R.string.help_title), { go(Routes.Help) }, Modifier.remembersFocus(mem, "help"))
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) =
    PixelText(stringResource(R.string.settings_heading, text), Modifier.padding(start = Space.x2, top = Space.x1, bottom = Space.x1 / 2), LocalSpeedColors.current.accent, 36.sp)

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalSpeedColors.current
    Box(
        Modifier.focusFx(1.05f, RowRadius, onClick).semantics { role = Role.RadioButton; this.selected = selected }
            .background(if (selected) c.accent else MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, c.hairline).padding(horizontal = Space.x2, vertical = Space.x1),
    ) { PixelText(label, color = if (selected) c.onAccent else MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun Swatch(preset: Preset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalSpeedColors.current
    val label = stringResource(preset.label)
    val color = remember(preset, c.dark) { Color(preset.palette(c.dark).accent) }
    Box(
        modifier.size(Space.x4 + Space.x1).focusFx(1.15f, RowRadius, onClick)
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = label }
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else c.hairline),
    )
}

/** Hue picker as a block meter: one block per 15 degrees in its own hue, the current one taller and framed. D-pad left/right moves 15 degrees. */
@Composable
private fun BlockMeter(hue: Float, onHueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSpeedColors.current
    val cur = (hue / 15f).toInt() % 24
    val hueDesc = pluralStringResource(R.plurals.settings_hue_desc, hue.toInt(), hue.toInt())
    Row(
        modifier.focusFx(1.025f, RowRadius)
            .semantics { contentDescription = hueDesc }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> { onHueChange((hue - 15f + 360f) % 360f); true }
                    Key.DirectionRight -> { onHueChange((hue + 15f) % 360f); true }
                    else -> false
                }
            }.padding(Space.x1),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
    ) {
        repeat(24) { i ->
            val on = i == cur
            Box(
                Modifier.size(16.dp, if (on) 32.dp else 20.dp).background(Color(seedForHue(i * 15f)))
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.onSurface else c.hairline),
            )
        }
    }
}

/** D-pad left/right moves by 30 minutes, wrapping around midnight. */
@Composable
private fun hint(label: String, value: String): Modifier {
    val desc = stringResource(R.string.settings_hint, label, value)
    return Modifier.semantics { contentDescription = desc }
}

@Composable
private fun TimeRow(label: String, minute: Int, onStep: (Int) -> Unit) =
    OperatorRow(label, hint(label, clockText(minute)), onClick = { onStep(STEP_MINUTES) }, onLeft = { onStep(-STEP_MINUTES) }, onRight = { onStep(STEP_MINUTES) }) { Selector(clockText(minute), it) }

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) =
    OperatorRow(
        title, modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(checked) },
        sublabel = subtitle, onClick = { onChange(!checked) },
    ) { DipSwitch(checked) }
