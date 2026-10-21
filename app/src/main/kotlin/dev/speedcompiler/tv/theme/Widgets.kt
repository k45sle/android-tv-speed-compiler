package dev.speedcompiler.tv.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

val CardRadius = 16.dp
val RowRadius = 12.dp

/** The QR code must stay white in both themes so scanners can read it. */
val QrWhite = Color.White

/**
 * Focusable with a scale, a 2 dp accent outline drawn outside the bounds and a soft glow.
 * Shape and size change, so focus never relies on color alone. [onClick] null means not clickable.
 */
@Composable
fun Modifier.focusFx(scale: Float, radius: Dp, onClick: (() -> Unit)? = null): Modifier {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    val accent = LocalSpeedColors.current.accent
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .drawWithContent {
            if (focused) {
                val r = radius.toPx()
                val px = 1.dp.toPx()
                // Glow: widening low-alpha rings, then the solid 2 dp ring just outside the edge.
                for (i in 4 downTo 1) {
                    val grow = (2 + i * 2) * px
                    drawRoundRect(
                        accent.copy(alpha = 0.16f / i), Offset(-grow, -grow),
                        Size(size.width + 2 * grow, size.height + 2 * grow),
                        CornerRadius(r + grow), Stroke(2 * px),
                    )
                }
                drawRoundRect(
                    accent, Offset(-px, -px), Size(size.width + 2 * px, size.height + 2 * px),
                    CornerRadius(r + px), Stroke(2 * px),
                )
            }
            drawContent()
        }
        .let { if (onClick != null) it.clickable(src, null, onClick = onClick) else it.focusable(true, src) }
}

enum class Tone { Ok, Warn, Error, Info, Neutral }

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = LocalSpeedColors.current
    val color = when (tone) {
        Tone.Ok -> c.ok
        Tone.Warn -> c.warn
        Tone.Error -> c.error
        Tone.Info -> c.accent
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Hairline-bordered card; focusable when [onClick] is given (1.05x on focus). */
@Composable
fun BannerCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = LocalSpeedColors.current
    val shape = RoundedCornerShape(CardRadius)
    Row(
        modifier.focusFx(1.05f, CardRadius, onClick).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, c.hairline, shape).padding(Space.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.x1 / 2)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
    }
}

/** One list row: title, optional subtitle, optional trailing slot (1.025x on focus). */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(RowRadius)
    Row(
        modifier.fillMaxWidth().focusFx(1.025f, RowRadius, onClick).clip(shape)
            .border(1.dp, LocalSpeedColors.current.hairline, shape).padding(horizontal = Space.x2, vertical = Space.x1 + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
    }
}

/** "Step 2 of 4" in mono above a heading. */
@Composable
fun StepHeader(step: Int, total: Int, title: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.x1 / 2)) {
        Text("Step $step of $total", style = MonoStyle.copy(fontSize = 14.sp), color = LocalSpeedColors.current.accent)
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** White container for a QR code, in light and dark. */
@Composable
fun QrCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(CardRadius)).background(QrWhite).padding(Space.x2)) { content() }
}

/** Hue picker for Custom: focus it and press D-pad left/right to move by 5 degrees. */
@Composable
fun HueSlider(hue: Float, onHueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val track = remember { Brush.horizontalGradient(List(13) { Color(seedForHue(it * 30f)) }) }
    val c = LocalSpeedColors.current
    val width = 480.dp
    Box(
        modifier.width(width).height(Space.x4)
            .focusFx(1.025f, 16.dp)
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> { onHueChange((hue - 5f + 360f) % 360f); true }
                    Key.DirectionRight -> { onHueChange((hue + 5f) % 360f); true }
                    else -> false
                }
            },
    ) {
        Box(Modifier.fillMaxWidth().height(Space.x2).align(Alignment.Center).clip(CircleShape).background(track).border(1.dp, c.hairline, CircleShape))
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = (width - Space.x3) * (hue / 360f) )
                .size(Space.x3).background(Color(seedForHue(hue)), CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
        )
    }
}

/** Last focused item id (saved with the back stack entry) and whether a restore is still pending for this screen entry. */
private const val RESTORE_WINDOW_MILLIS = 2_000L

class FocusMemory(val id: MutableState<String>, val touched: MutableState<Boolean>) {
    var pending by mutableStateOf(true)
}

/** [first] may change while the screen loads (an item appears or goes); until the user focuses something, it follows. */
@Composable
fun rememberFocusMemory(first: String = ""): FocusMemory {
    val id = rememberSaveable { mutableStateOf(first) }
    val touched = rememberSaveable { mutableStateOf(false) }
    if (!touched.value && id.value != first) SideEffect { id.value = first }
    val mem = remember { FocusMemory(id, touched) }
    // A target that never shows up (an app removed meanwhile) must not grab focus much later.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (windowFocused) { delay(RESTORE_WINDOW_MILLIS); mem.pending = false } }
    return mem
}

/**
 * Tracks focus into [mem] and, once per screen entry, takes focus when [mem] holds [id]: the first visit gets the
 * first item, Back gets the last one. Any item gaining focus ends the restore, so re-entering composition never steals focus.
 */
@Composable
fun Modifier.remembersFocus(mem: FocusMemory, id: String): Modifier {
    val r = remember { FocusRequester() }
    // On a cold start the request runs before the window has focus and is silently lost, so wait for window focus
    // and leave clearing pending to onFocusChanged, which only fires when focus really lands.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val target = mem.id.value == id
    LaunchedEffect(windowFocused, target) {
        if (windowFocused && mem.pending && target) runCatching { r.requestFocus() }
    }
    return focusRequester(r).onFocusChanged { if (it.hasFocus) { mem.id.value = id; mem.pending = false; mem.touched.value = true } }
}
