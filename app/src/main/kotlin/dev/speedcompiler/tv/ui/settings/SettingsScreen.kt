package dev.speedcompiler.tv.ui.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.engine.GateMode
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.HueSlider
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.MonoStyle
import dev.speedcompiler.tv.theme.Presets
import dev.speedcompiler.tv.theme.RowRadius
import dev.speedcompiler.tv.theme.Space
import dev.speedcompiler.tv.theme.StatusPill
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.theme.buildPalette
import dev.speedcompiler.tv.theme.focusFx
import dev.speedcompiler.tv.ui.Routes
import kotlinx.coroutines.launch

private const val STEP_MINUTES = 30
private const val DAY = 24 * 60

/** Steps from the stored value by [delta], skipping [other] so the window never has start == end. */
internal fun stepMinute(cur: Int, delta: Int, other: Int): Int {
    val n = (cur + delta + DAY) % DAY
    return if (n == other) (n + delta + DAY) % DAY else n
}

fun clockText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
fun SettingsScreen(go: (String) -> Unit, back: () -> Unit, deps: SettingsDeps = rememberSettingsDeps()) {
    val v by remember(deps) { deps.values }.collectAsState(SettingsValues())
    val status by remember(deps) { deps.status }.collectAsState(KeeperStatus.Reconnecting)
    val scope = rememberCoroutineScope()
    val set: ((SettingsValues) -> SettingsValues) -> Unit = { f -> scope.launch { deps.update(f) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(Space.x2),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall)

        Heading("When to optimize")
        Chips {
            Chip("Auto", v.mode == Mode.Auto, Modifier.focusRequester(first)) { set { it.copy(mode = Mode.Auto) } }
            Chip("Manual", v.mode == Mode.Manual) { set { it.copy(mode = Mode.Manual) } }
        }
        Chips {
            Chip("When the TV is idle", v.gateMode == GateMode.Idle) { set { it.copy(gateMode = GateMode.Idle) } }
            Chip("When nothing is playing", v.gateMode == GateMode.NothingPlaying) { set { it.copy(gateMode = GateMode.NothingPlaying) } }
            Chip("During set hours", v.gateMode == GateMode.Window) { set { it.copy(gateMode = GateMode.Window) } }
        }
        if (v.gateMode == GateMode.Window) {
            Chips {
                TimePicker("Start time", v.windowStartMinute) { d -> set { it.copy(windowStartMinute = stepMinute(it.windowStartMinute, d, it.windowEndMinute)) } }
                TimePicker("End time", v.windowEndMinute) { d -> set { it.copy(windowEndMinute = stepMinute(it.windowEndMinute, d, it.windowStartMinute)) } }
            }
        }

        Heading("Pause")
        SwitchRow("Pause auto-optimize", "No automatic jobs until you turn this off", v.paused) { on -> set { it.copy(paused = on) } }

        Heading("Appearance")
        Chips {
            ThemeChoice.entries.forEach { t -> Chip(t.name, v.theme == t) { set { it.copy(theme = t) } } }
        }
        Chips {
            Presets.forEachIndexed { i, p ->
                Swatch(p.name, p.seed, v.customHue == null && v.accentPreset == i) { set { it.copy(accentPreset = i, customHue = null) } }
            }
            Chip("Custom", v.customHue != null) { set { it.copy(customHue = it.customHue ?: 150f) } }
        }
        v.customHue?.let { hue ->
            HueSlider(hue, { h -> val d = (h - hue + 540f) % 360f - 180f; set { it.copy(customHue = ((it.customHue ?: hue) + d + 360f) % 360f) } }, Modifier.semantics { contentDescription = "Custom accent hue, ${hue.toInt()} degrees" })
        }

        Heading("Connection")
        val (text, tone) = when (status) {
            KeeperStatus.Connected -> "Connected" to Tone.Ok
            KeeperStatus.Reconnecting -> "Connecting" to Tone.Info
            is KeeperStatus.Failed -> "Not connected" to Tone.Error
        }
        StatusPill(text, tone)
        SwitchRow("Keep this TV trusting the app", "Stops the TV asking to allow debugging again", v.keepTrusting) { on -> set { it.copy(keepTrusting = on) } }
        Chips {
            Button({ go(Routes.Reconnect) }) { Text("Reconnect") }
            Button({ go(Routes.QuickRepair) }) { Text("Pair again") }
        }

        Heading("About and help")
        Text("Version ${deps.version}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Chips {
            Button({ go(Routes.Diagnostics) }) { Text("Export diagnostics") }
            Button({ go(Routes.Help) }) { Text("Help") }
            Button(back) { Text("Back") }
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Space.x1))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x2)) { content() }

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalSpeedColors.current
    val shape = RoundedCornerShape(RowRadius)
    Box(
        modifier.focusFx(1.05f, RowRadius, onClick).semantics { role = Role.RadioButton; this.selected = selected }
            .clip(shape).background(if (selected) c.accent else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, c.hairline, shape).padding(horizontal = Space.x2, vertical = Space.x1 + 4.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.onAccent else MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun Swatch(name: String, seed: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalSpeedColors.current
    val color = remember(seed, c.dark) { Color(buildPalette(seed, c.dark).accent) }
    Box(
        Modifier.size(Space.x4 + Space.x1).focusFx(1.15f, 20.dp, onClick)
            .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = name }
            .background(color, CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else c.hairline, CircleShape),
    )
}

/** D-pad left/right moves by 30 minutes, wrapping around midnight. */
@Composable
private fun TimePicker(label: String, minute: Int, onStep: (Int) -> Unit) {
    val c = LocalSpeedColors.current
    val shape = RoundedCornerShape(RowRadius)
    Row(
        Modifier.focusFx(1.05f, RowRadius)
            .semantics { contentDescription = "$label, ${clockText(minute)}. Press left or right to change" }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> { onStep(-STEP_MINUTES); true }
                    Key.DirectionRight -> { onStep(STEP_MINUTES); true }
                    else -> false
                }
            }
            .clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, c.hairline, shape)
            .padding(horizontal = Space.x2, vertical = Space.x1 + 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text("‹ ${clockText(minute)} ›", style = MonoStyle)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    ListRow(
        title, subtitle = subtitle, onClick = { onChange(!checked) },
        modifier = Modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(checked) },
    ) { Switch(checked, null) }
