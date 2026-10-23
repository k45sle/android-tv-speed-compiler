package dev.speedcompiler.tv.ui.working

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import dev.speedcompiler.tv.ui.working.games.StepClock
import android.app.ActivityManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.ui.working.games.GameColors
import dev.speedcompiler.tv.ui.working.games.Surface
import kotlin.math.roundToInt

private const val CAB_Y = 32
private val CAB_X = intArrayOf(40, 165, 290)
private val CAB_ART = intArrayOf(R.drawable.game_arcade_worm, R.drawable.game_arcade_run, R.drawable.game_arcade_arrow)
private const val SIGN_X = 116; private const val SIGN_Y = 3; private const val SIGN_W = 200; private const val SIGN_H = 24

/** Bulb top-lefts around the sign border in walk order: top left to right, right side, bottom right to left, left side. */
private val BULBS: List<Pair<Int, Int>> = buildList {
    val xs = (SIGN_X + 1..SIGN_X + SIGN_W - 3 step 6).toList(); val top = SIGN_Y + 1; val bottom = SIGN_Y + SIGN_H - 3
    xs.forEach { add(it to top) }
    add(SIGN_X + SIGN_W - 3 to top + 6); add(SIGN_X + SIGN_W - 3 to top + 12)
    xs.reversed().forEach { add(it to bottom) }
    add(SIGN_X + 1 to top + 12); add(SIGN_X + 1 to top + 6)
}

/** Fills the art-pixel rect with edges rounded to whole pixels, so neighbours never leave a seam. [u] is pixels per art pixel. */
private fun DrawScope.art(c: Color, x: Int, y: Int, w: Int, h: Int, u: Float) {
    val l = (x * u).roundToInt(); val t = (y * u).roundToInt()
    drawRect(c, Offset(l.toFloat(), t.toFloat()), Size(((x + w) * u).roundToInt() - l.toFloat(), ((y + h) * u).roundToInt() - t.toFloat()))
}

private fun DrawScope.artText(s: String, x: Int, y: Int, c: Color, u: Float) {
    s.forEachIndexed { n, ch ->
        val bits = Surface.glyph(ch)
        for (i in 0 until 15) if (bits and (1 shl (14 - i)) != 0) art(c, x + n * 4 + i % 3, y + i / 3, 1, 1, u)
    }
}

/** True on devices Android flags as low-RAM; the animated screens hold still there. */
@Composable
fun rememberLowRam(): Boolean = LocalContext.current.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true

/**
 * The arcade room: three cabinets at 2 dp per art pixel. The focused one is lit and plays its game in the screen on its own;
 * the others show a still frame. Select opens the game full screen through [play].
 */
@Composable
fun ArcadePicker(
    play: (GameKind) -> Unit, first: FocusRequester, firstKind: GameKind = GameKind.entries.first(),
    // Low-RAM devices get a still frame; overridable so a test can force that path. probe is a test hook told "tick", "frame" and "step".
    still: Boolean = rememberLowRam(),
    probe: (String) -> Unit = {},
) {
    var focused by remember { mutableIntStateOf(-1) }
    // Read only inside the draw lambdas, so the bulbs and the blink redraw without recomposing.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        while (true) { delay(166); tick++; probe("tick") }
    }
    val arcadeDesc = stringResource(R.string.working_arcade_desc)
    val pressSelect = stringResource(R.string.working_press_select)
    Box(Modifier.size(864.dp, 380.dp)) {
        Image(ImageBitmap.imageResource(R.drawable.game_arcade_room), arcadeDesc, Modifier.size(864.dp, 380.dp), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
        Canvas(Modifier.size(864.dp, 380.dp)) {
            val u = size.width / 432f
            for (x in CAB_X) art(GameColors.outline, x - 2, CAB_Y + 149, 108, 3, u)
            val step = tick
            BULBS.forEachIndexed { i, (bx, by) -> art(if ((i + step) % 3 == 0) GameColors.cream2 else GameColors.gold, bx, by, 2, 2, u) }
        }
        GameKind.entries.forEachIndexed { i, kind ->
            Cabinet(
                kind, CAB_X[i], CAB_ART[i], focused == i, still, probe, if (kind == firstKind) first else null,
                { if (it) focused = i else if (focused == i) focused = -1 }, { play(kind) },
            )
        }
        Canvas(Modifier.size(864.dp, 380.dp)) {
            val u = size.width / 432f
            val i = focused
            if (i < 0) return@Canvas
            val x = CAB_X[i]
            art(GameColors.gold, x - 3, CAB_Y - 3, 108, 2, u); art(GameColors.gold, x - 3, CAB_Y + 151, 108, 2, u)
            art(GameColors.gold, x - 3, CAB_Y - 3, 2, 156, u); art(GameColors.gold, x + 103, CAB_Y - 3, 2, 156, u)
            if (tick / 3 % 2 == 0) artText(pressSelect, x + 48 - (12 * 4 - 1) / 2, CAB_Y + 102, GameColors.yellow, u)
        }
    }
}

@Composable
private fun Cabinet(kind: GameKind, x: Int, artRes: Int, lit: Boolean, still: Boolean, probe: (String) -> Unit, requester: FocusRequester?, onFocus: (Boolean) -> Unit, onPlay: () -> Unit) {
    val sheet = ImageBitmap.imageResource(artRes)
    val playDesc = stringResource(R.string.working_play_game, stringResource(kind.title), stringResource(kind.hint))
    val renderer = kind.def.renderer()
    val surface = remember { Surface() }
    var game by remember(kind, lit) { mutableStateOf(kind.def.create().also { it.step(null); it.step(null) }) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(kind, lit, still) {
        if (!lit || still) return@LaunchedEffect
        val clock = StepClock()
        var sub = 0
        // Wake every 4th vsync (15 fps); the clock steps by elapsed time so the game still runs at 30 steps/s.
        while (true) {
            delay(60) // then the next vsync: 4 frames at 60 and 120 Hz, so ~15 fps
            withFrameNanos { now ->
                repeat(clock.due(now)) { if (++sub >= kind.def.ticksPerStep) { sub = 0; game.step(null); probe("step") } }
                if (game.over && game.canRestart) game = kind.def.create()
                frame++; probe("frame")
            }
        }
    }
    Box(
        Modifier.offset((x * 2).dp, (CAB_Y * 2).dp).size(204.dp, 300.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { onFocus(it.isFocused) }
            // Not clickable(): that is focusable only outside touch mode, which a test run is in.
            .onKeyEvent { e ->
                val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (select && e.type == KeyEventType.KeyUp) onPlay()
                select
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onPlay() } }
            .semantics(mergeDescendants = true) {
                role = Role.Button; contentDescription = playDesc
                onClick { onPlay(); true }
            },
    ) {
        Canvas(Modifier.offset(16.dp, 64.dp).size(160.dp, 90.dp)) {
            frame
            // Drawn on a virtual 320x180 scope scaled to fit, so the preview is the same size at any density.
            val k = size.width / Surface.W
            withTransform({ scale(k, k, Offset.Zero) }) {
                draw(this, layoutDirection, drawContext.canvas, Size(Surface.W.toFloat(), Surface.H.toFloat())) {
                    surface.frame(this, 0f) { renderer.draw(surface, game, game.score) }
                }
            }
        }
        Canvas(Modifier.size(204.dp, 300.dp)) {
            drawImage(sheet, IntOffset(if (lit) 102 else 0, 0), IntSize(102, 150), IntOffset.Zero, IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.None)
        }
    }
}
