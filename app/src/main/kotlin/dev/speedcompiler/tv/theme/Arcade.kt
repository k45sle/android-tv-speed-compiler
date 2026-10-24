package dev.speedcompiler.tv.theme

import androidx.compose.ui.res.stringResource
import dev.speedcompiler.tv.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

// Arcade kit: shared pieces for the retro look. Colors come from the active palette (LocalSpeedColors / ColorScheme);
// Cabinet.* colors are the fixed hardware colors. Depth is hard offsets and darker edges only.

private val ShadowColor = Color.Black.copy(alpha = 0.35f)
private fun Color.darker(f: Float = 0.35f) = lerp(this, Color.Black, f)

/** Hard (no blur) drop shadow, [d] down and right. Put it before focusFx so the focus frame stays on top. */
fun Modifier.hardShadow(d: Dp = 4.dp, color: Color = ShadowColor): Modifier =
    drawBehind { drawRect(color, Offset(d.toPx(), d.toPx()), size) }

/** Capitals in the UI locale, read from the configuration so a locale change recomposes. */
@Composable
@ReadOnlyComposable
fun String.upper(): String = uppercase(LocalConfiguration.current.locales[0])

/** VT323 text in capitals: 26 sp labels, numbers and status words. An unspecified [color] uses the content color. */
@Composable
fun PixelText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, size: TextUnit = 26.sp, maxLines: Int = 1, overflow: TextOverflow = TextOverflow.Clip) {
    Text(
        text.upper(), modifier, color, style = PixelLabel.copy(fontSize = size, letterSpacing = 1.sp),
        maxLines = maxLines, overflow = overflow,
    )
}

@Composable
fun toneColor(tone: Tone): Color {
    val c = LocalSpeedColors.current
    return when (tone) {
        Tone.Ok -> c.ok
        Tone.Warn -> c.warn
        Tone.Error -> c.error
        Tone.Info -> c.accent
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Focus state of this source, also true when [preview] forces it (the kit gallery shows focused pieces side by side). */
@Composable
private fun MutableInteractionSource.focused(preview: Boolean): Boolean {
    val real by collectIsFocusedAsState()
    return real || preview
}

private val FloorH = 40.dp
private val Confetti = List(14) { i ->
    val x = if (i % 2 == 0) 20 + i * 53 % 280 else 660 + i * 47 % 280
    Triple(x, 6 + i * 11 % 24, i)
}

/** The arcade room: stripes 48 dp wide on the wall, a floor bar with the D-pad hint ([hint] replaces the default) and a few confetti pixels. */
@Composable
fun RoomBackdrop(modifier: Modifier = Modifier, hint: String? = null, content: @Composable BoxScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val c = LocalSpeedColors.current
    val dots = listOf(c.accent, c.ok, c.warn, c.error, cs.onSurfaceVariant)
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(cs.background)
            val w = 48.dp.toPx()
            var x = w / 2
            while (x < size.width) { drawRect(cs.surfaceVariant, Offset(x, 0f), Size(w, size.height)); x += 2 * w }
            val top = size.height - FloorH.toPx()
            drawRect(cs.background, Offset(0f, top), Size(size.width, FloorH.toPx()))
            drawRect(c.hairline, Offset(0f, top), Size(size.width, 2.dp.toPx()))
            val px = 4.dp.toPx()
            for ((cx, cy, i) in Confetti) drawRect(dots[i % dots.size], Offset(cx.dp.toPx(), top + cy.dp.toPx()), Size(px, px))
        },
    ) {
        Box(Modifier.fillMaxSize().padding(bottom = FloorH), content = content)
        Box(Modifier.align(Alignment.BottomCenter).height(FloorH), Alignment.Center) {
            PixelText(hint ?: stringResource(R.string.common_footer_hint), color = cs.onSurfaceVariant)
        }
    }
}

/** Sign with a dashed accent border; the dashes chase around it. Big has the logo tile, compact has a "◀ BACK" chip. */
@Composable
fun Marquee(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    compact: Boolean = false,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val c = LocalSpeedColors.current
    // Read only inside the draw lambda below, so the chase never recomposes.
    val phase: State<Float> = rememberInfiniteTransition(label = "bulbs")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(600, easing = LinearEasing)), label = "phase")
    Row(modifier.fillMaxWidth().height(if (compact) 88.dp else 112.dp), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            Box(
                Modifier.focusFx(1.05f, RowRadius, onClick = onBack).background(cs.background).border(2.dp, c.hairline)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) { PixelText(stringResource(R.string.common_back_arrow), color = cs.onSurface) }
        } else {
            Box(Modifier.size(80.dp, 52.dp).background(cs.primaryContainer).border(2.dp, c.hairline), Alignment.Center) {
                PixelBanner(stopped = true, size = 64.dp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.weight(1f).fillMaxHeight().background(cs.background).drawBehind {
                val sw = 4.dp.toPx()
                val period = 12.dp.toPx()
                drawRect(
                    c.accent, Offset(sw, sw), Size(size.width - 2 * sw, size.height - 2 * sw),
                    style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw, period - sw), phase.value * period)),
                )
            }.padding(horizontal = 28.dp),
        ) {
            Column(Modifier.align(Alignment.CenterStart)) {
                PixelText(title, color = c.accent, size = if (compact) 52.sp else 56.sp)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/** Status lamp, 12 dp. [waiting] blinks it at 1 Hz (the blink is read in the draw phase only). */
@Composable
fun Lamp(color: Color, modifier: Modifier = Modifier, waiting: Boolean = false) {
    val blink: State<Float>? = if (waiting) {
        rememberInfiniteTransition(label = "lamp").animateFloat(
            1f, 1f, infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 0; 1f at 499; 0.15f at 500; 0.15f at 999 }), label = "blink",
        )
    } else null
    Box(
        modifier.size(12.dp).drawBehind {
            val e = 2.dp.toPx()
            drawRect(color.copy(alpha = blink?.value ?: 1f))
            drawRect(Color.Black.copy(alpha = 0.4f), Offset(0f, size.height - e), Size(size.width, e))
        },
    )
}

/**
 * Handheld-style game cartridge around [content] (an app's banner as the label): grey shell with the top-right corner cut,
 * a raised blank plate with grip ridges either side at the top, the label in a recess, an arrow under it. Draw only, no state.
 */
@Composable
fun Cartridge(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shell = Color(Cabinet.GREY)
    val ink = Color(Cabinet.INK)
    Box(
        modifier.drawBehind {
            val u = 1.dp.toPx()
            val w = size.width
            val h = size.height
            val cut = 8 * u
            fun outline(dx: Float) = Path().apply {
                moveTo(dx, dx); lineTo(w - cut + dx, dx); lineTo(w + dx, cut + dx); lineTo(w + dx, h + dx); lineTo(dx, h + dx); close()
            }
            drawPath(outline(2 * u), ShadowColor)
            drawPath(outline(0f), shell)
            drawRect(shell.darker(0.25f), Offset(0f, h - 2 * u), Size(w, 2 * u))
            // Raised plate, then three grip ridges each side of it; the right ones stop short of the cut corner.
            val plateL = w * 0.3f
            val plateR = w * 0.7f
            drawRoundRect(lerp(shell, Color.White, 0.3f), Offset(plateL, 4 * u), Size(plateR - plateL, 8 * u), CornerRadius(4 * u))
            drawRoundRect(shell.darker(0.2f), Offset(plateL, 4 * u), Size(plateR - plateL, 8 * u), CornerRadius(4 * u), style = Stroke(u))
            for (y in listOf(5 * u, 8 * u, 11 * u)) {
                drawRect(shell.darker(), Offset(3 * u, y), Size(plateL - 6 * u, u))
                val end = minOf(w - 3 * u, w - cut + y - 2 * u)
                drawRect(shell.darker(), Offset(plateR + 3 * u, y), Size(end - plateR - 3 * u, u))
            }
            // Label recess: a slightly darker sunken panel 2 dp around the label's ink frame.
            drawRect(shell.darker(0.15f), Offset(4 * u, 14 * u), Size(w - 8 * u, h - 26 * u))
            val tri = Path().apply {
                moveTo(w / 2 - 4 * u, h - 10 * u); lineTo(w / 2 + 4 * u, h - 10 * u); lineTo(w / 2, h - 6 * u); close()
            }
            drawPath(tri, shell.darker())
        }.padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 14.dp),
    ) {
        // An ink backing, not a border: it frames the label without covering it and fills the banner's rounded corners.
        Box(Modifier.background(ink).padding(2.dp)) { content() }
    }
}

/**
 * Cabinet for one item: name strip, [banner] in a bezel, grey control strip with a lamp and [status].
 * Unlit until focused; focused it lights the name, scales up and shows "▶ PRESS SELECT" under it.
 */
@Composable
fun CabinetCard(
    name: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    tone: Tone = Tone.Neutral,
    onClick: (() -> Unit)? = null,
    preview: Boolean = false,
    screenHeight: Dp = 84.dp,
    banner: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val c = LocalSpeedColors.current
    val src = remember { MutableInteractionSource() }
    val lit = src.focused(preview)
    Column(modifier.widthIn(min = 200.dp)) {
        Column(Modifier.hardShadow(4.dp).focusFx(1.05f, CardRadius, src, preview, onClick)) {
            Box(Modifier.fillMaxWidth().height(36.dp).background(cs.background), Alignment.Center) {
                PixelText(name, color = if (lit) c.accent else cs.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().background(cs.primaryContainer).padding(12.dp)) {
                Box(Modifier.fillMaxWidth().height(screenHeight).background(cs.background).border(3.dp, c.hairline), Alignment.Center, content = banner)
            }
            Row(
                Modifier.fillMaxWidth().height(36.dp).background(Color(Cabinet.GREY)).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Lamp(toneColor(tone))
                if (status != null) { Spacer(Modifier.width(8.dp)); PixelText(status, color = Color(Cabinet.INK)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(40.dp).padding(top = 4.dp), Alignment.CenterStart) {
            if (lit) PixelText(stringResource(R.string.common_press_select), color = c.accent)
        }
    }
}

private fun Modifier.keys(onLeft: (() -> Unit)?, onRight: (() -> Unit)?) = onKeyEvent {
    if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
    when (it.key) {
        Key.DirectionLeft -> onLeft?.let { f -> f(); true } ?: false
        Key.DirectionRight -> onRight?.let { f -> f(); true } ?: false
        else -> false
    }
}

@Composable
private fun SelectRowImpl(
    title: String,
    modifier: Modifier,
    subtitle: String?,
    onClick: (() -> Unit)?,
    preview: Boolean,
    actions: (@Composable RowScope.() -> Unit)?,
    trailing: (@Composable (Boolean) -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val c = LocalSpeedColors.current
    val src = remember { MutableInteractionSource() }
    val focused = src.focused(preview)
    Row(
        modifier.fillMaxWidth().focusFx(1.025f, RowRadius, src, preview, onClick)
            .background(if (focused) cs.primaryContainer else Color.Transparent)
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp)) { if (focused) PixelText("▶", color = c.accent) }
        if (leading != null) Box(Modifier.padding(end = 16.dp)) { leading() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = if (focused) c.accent else cs.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (trailing != null) trailing(focused)
        if (focused && actions != null) Row(Modifier.padding(start = 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically, actions)
    }
}

/** List row with an accent ▶ cursor and container fill when focused; [actions] (inline buttons) show on the focused row only. */
@Composable
fun SelectRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    preview: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) = SelectRowImpl(title, modifier, subtitle, onClick, preview, actions, trailing?.let { t -> { _: Boolean -> t() } }, leading)

/** A SelectRow with a control on the right: [control] gets the focused state. Left and right keys call [onLeft] and [onRight]. */
@Composable
fun OperatorRow(
    label: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    onClick: (() -> Unit)? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    preview: Boolean = false,
    control: @Composable (focused: Boolean) -> Unit,
) = SelectRowImpl(label, modifier.keys(onLeft, onRight), sublabel, onClick, preview, null, control)

/** "‹ VALUE ›", accent while the row is focused. */
@Composable
fun Selector(value: String, focused: Boolean, modifier: Modifier = Modifier) {
    PixelText("‹ $value ›", modifier, if (focused) LocalSpeedColors.current.accent else MaterialTheme.colorScheme.onSurface)
}

/** ON/OFF switch: the active cell is filled (accent for ON, container for OFF) inside a line-colored frame. */
@Composable
fun DipSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val c = LocalSpeedColors.current
    Row(modifier.border(2.dp, c.hairline)) {
        listOf(true, false).forEach { cell ->
            val active = cell == on
            val fill = if (!active) Color.Transparent else if (cell) c.accent else cs.primaryContainer
            val text = if (!active) cs.onSurfaceVariant else if (cell) c.onAccent else cs.onPrimaryContainer
            Box(Modifier.size(56.dp, 36.dp).background(fill), Alignment.Center) { PixelText(if (cell) "on" else "off", color = text) }
        }
    }
}

/** Primary (accent) or secondary (container) button: 6 dp darker bottom edge, accent frame on focus, edge drops to 2 dp when pressed. */
@Composable
fun ArcadeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fill: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    preview: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalSpeedColors.current
    val face = fill.takeIf { it != Color.Unspecified } ?: c.accent
    val ink = contentColor.takeIf { it != Color.Unspecified } ?: c.onAccent
    val src = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    val depth = 6.dp
    val d = if (pressed) 2.dp else depth
    // Not clickable(): that is focusable only outside touch mode, so a focus request (remembersFocus) would be refused in a test run.
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f).hardShadow(3.dp)
            .onKeyEvent { e ->
                val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (select && enabled) when (e.type) {
                    KeyEventType.KeyDown -> pressed = true
                    KeyEventType.KeyUp -> if (pressed) { pressed = false; onClick() } // ignore a release whose press landed elsewhere
                }
                select
            }
            .onFocusChanged { if (!it.isFocused) pressed = false }
            .focusFx(1.05f, RowRadius, src, preview)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() })
            }
            .semantics(mergeDescendants = true) { role = Role.Button; if (enabled) onClick { onClick(); true } else disabled() }
            .drawBehind {
                val dp = d.toPx()
                val shift = depth.toPx() - dp
                drawRect(face.darker(), Offset(0f, size.height - dp), Size(size.width, dp))
                drawRect(face, Offset(0f, shift), Size(size.width, size.height - depth.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink, LocalTextStyle provides PixelLabel) {
            Row(
                Modifier.heightIn(min = 36.dp).padding(start = 24.dp, end = 24.dp, bottom = depth).offset(y = depth - d),
                Arrangement.Center, Alignment.CenterVertically, content,
            )
        }
    }
}

/** [ArcadeButton] with an uppercased text label. */
@Composable
fun ArcadeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fill: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    preview: Boolean = false,
) = ArcadeButton(onClick, modifier, enabled, fill, contentColor, preview) { Text(label.upper()) }

/** Progress blocks: [step] of [total] filled, the rest hollow. */
@Composable
fun StageMeter(step: Int, total: Int, modifier: Modifier = Modifier) {
    val c = LocalSpeedColors.current
    Row(modifier, Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(Modifier.size(28.dp, 14.dp).then(if (i < step) Modifier.background(c.accent) else Modifier.border(2.dp, c.hairline)))
        }
    }
}

class ScoreRow(val time: String, val name: String, val status: String, val tone: Tone)

/** Bordered panel of rows: VT323 time, Geist name, status word on the right. */
@Composable
fun ScoreTable(title: String, rows: List<ScoreRow>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val c = LocalSpeedColors.current
    Column(modifier.background(cs.background).border(2.dp, c.hairline).padding(16.dp), Arrangement.spacedBy(4.dp)) {
        PixelText(title, color = c.accent)
        rows.forEach {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelText(it.time, Modifier.width(140.dp), c.accent)
                Text(it.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1)
                PixelText(it.status, color = toneColor(it.tone))
            }
        }
    }
}

/** Arcade hardware colors, identical in every preset and mode. Cream is large-text only (3:1) on the four bodies; ink is text on grey. */
object Cabinet {
    const val BLUE = 0xFF3B6FA8.toInt()
    const val ORANGE = 0xFFB5543A.toInt()
    const val PLUM = 0xFF5A4A7A.toInt()
    const val GREEN = 0xFF2E6B4A.toInt()
    const val LOGO = Retro.LOGO_BASE
    const val CREAM = Retro.CREAM
    const val GREY = 0xFFC7CCD1.toInt()
    const val INK = 0xFF0B2017.toInt()
}
