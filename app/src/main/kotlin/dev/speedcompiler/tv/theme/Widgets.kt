package dev.speedcompiler.tv.theme

import dev.speedcompiler.tv.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

// Retro look: near-square corners everywhere.
val CardRadius = 2.dp
val RowRadius = 2.dp

/** The QR code must stay white in both themes so scanners can read it. */
val QrWhite = Color.White

/**
 * Focusable with a scale, a 2 dp accent outline drawn outside the bounds.
 * Shape and size change, so focus never relies on color alone. [onClick] null means not clickable.
 */
@Composable
fun Modifier.focusFx(scale: Float, radius: Dp, onClick: (() -> Unit)? = null): Modifier = focusFx(scale, radius, remember { MutableInteractionSource() }, false, onClick)

/** [focusFx] with the interaction source exposed (for pieces that restyle on focus) and [forceFocus] for the kit gallery. */
@Composable
fun Modifier.focusFx(
    scale: Float, radius: Dp,
    src: MutableInteractionSource, forceFocus: Boolean,
    onClick: (() -> Unit)? = null,
): Modifier {
    val real by src.collectIsFocusedAsState()
    val focused = real || forceFocus
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    val accent = LocalSpeedColors.current.accent
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .drawWithContent {
            if (focused) {
                val r = radius.toPx()
                val px = 1.dp.toPx()
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

/** Lamp plus a VT323 status word; Info blinks (it means waiting). */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = toneColor(tone)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Lamp(color, waiting = tone == Tone.Info)
        Spacer(Modifier.width(8.dp))
        PixelText(text, color = color)
    }
}

/** Arcade cabinet: [title] on the name strip, [trailing] in the screen bezel, [body] as the status word; focusable when [onClick] is given. */
@Composable
fun BannerCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    onClick: (() -> Unit)? = null,
    tone: Tone = Tone.Neutral,
    trailing: @Composable (() -> Unit)? = null,
) = CabinetCard(title, modifier, body, tone, onClick) { trailing?.invoke() }

/** One list row: title, optional subtitle, optional trailing slot. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) = SelectRow(title, modifier, subtitle, onClick, trailing = trailing)

/** "STEP 2 OF 4", the stage meter, then the heading. */
@Composable
fun StepHeader(step: Int, total: Int, title: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.x1 / 2)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            PixelText(stringResource(R.string.setup_step_of, step, total), color = LocalSpeedColors.current.accent)
            StageMeter(step, total)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Cabinet screen around a QR code: line-colored bezel, hard shadow, and a white panel so scanners can read it. */
@Composable
fun QrCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.hardShadow(6.dp).background(QrWhite).border(6.dp, LocalSpeedColors.current.hairline).padding(Space.x2 + 6.dp)) { content() }
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
        Box(Modifier.fillMaxWidth().height(Space.x2).align(Alignment.Center).background(track).border(2.dp, c.hairline))
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = (width - Space.x3) * (hue / 360f) )
                .size(Space.x3).background(Color(seedForHue(hue))).border(2.dp, MaterialTheme.colorScheme.onSurface),
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

/** Arcade button; [secondary] uses the container color instead of the accent. Text content is set in VT323 (use ArcadeButton(label) for capitals). */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (secondary) ArcadeButton(onClick, modifier, enabled, cs.primaryContainer, cs.onPrimaryContainer, content = content)
    else ArcadeButton(onClick, modifier, enabled, content = content)
}
