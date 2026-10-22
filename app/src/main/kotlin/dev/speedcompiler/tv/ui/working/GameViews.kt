package dev.speedcompiler.tv.ui.working

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import dev.speedcompiler.tv.ui.working.games.AlienRunDef
import dev.speedcompiler.tv.ui.working.games.FlappyDef
import dev.speedcompiler.tv.ui.working.games.Game
import dev.speedcompiler.tv.ui.working.games.GameColors
import dev.speedcompiler.tv.ui.working.games.GameDef
import dev.speedcompiler.tv.ui.working.games.Input
import dev.speedcompiler.tv.ui.working.games.SnakeDef
import dev.speedcompiler.tv.ui.working.games.StepClock
import dev.speedcompiler.tv.ui.working.games.Surface

/** The games in picker order. Each entry's logic, art and tuning live in its own file under games/. */
enum class GameKind(val def: GameDef) {
    Snake(SnakeDef),
    AlienRun(AlienRunDef),
    Flappy(FlappyDef);

    val title get() = def.title
    val hint get() = def.hint
}

private fun mapKey(key: Key): Input? = when (key) {
    Key.DirectionUp -> Input.UP
    Key.DirectionDown -> Input.DOWN
    Key.DirectionLeft -> Input.LEFT
    Key.DirectionRight -> Input.RIGHT
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> Input.SELECT
    else -> null
}

/**
 * Plays [kind] full screen on a Canvas. [paused] freezes the loop, shows DONE and ignores input (the job ended).
 * [statusHeight] is the status line the caller draws over the top edge; the HUD sits below it.
 * Back calls [onExit]. [onBest] gets the score when a run ends.
 */
@Composable
fun GamePlayer(
    kind: GameKind, paused: Boolean, best: Int, onBest: (Int) -> Unit, onExit: () -> Unit,
    modifier: Modifier = Modifier, statusHeight: Dp = 30.dp,
) {
    var game by remember(kind) { mutableStateOf(kind.def.create()) }
    // Read only inside the Canvas draw lambda, so a new frame redraws without recomposing.
    var frame by remember(kind) { mutableIntStateOf(0) }
    var pending by remember(kind) { mutableStateOf<Input?>(null) }
    val focus = remember { FocusRequester() }
    val renderer = kind.def.renderer()
    val surface = remember { Surface() }
    val top = with(LocalDensity.current) { statusHeight.toPx() }
    LaunchedEffect(kind, paused) { runCatching { focus.requestFocus() } }
    LaunchedEffect(kind, paused) {
        if (paused) return@LaunchedEffect
        val clock = StepClock()
        var sub = 0
        var reported: Game? = null
        while (true) withFrameNanos { now ->
            val due = clock.due(now)
            if (due == 0) return@withFrameNanos
            repeat(due) {
                if (++sub >= kind.def.ticksPerStep) { sub = 0; game.step(pending); pending = null }
            }
            val g = game
            if (g.over && reported !== g) { reported = g; onBest(g.score) }
            frame++
        }
    }
    Canvas(
        modifier.fillMaxSize()
            .semantics { contentDescription = "${kind.title} game. Arrow keys move, Select to act or restart, Back to stop playing." }
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                // Only KeyDown is handled and consumed; KeyUp passes through.
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (e.key == Key.Back) { onExit(); return@onKeyEvent true }
                val input = mapKey(e.key) ?: return@onKeyEvent false
                if (!paused) {
                    if (game.over && input == Input.SELECT) game = kind.def.create() else pending = input
                }
                true
            },
    ) {
        frame
        drawRect(GameColors.sky3)
        surface.frame(this, top) {
            renderer.draw(surface, game, best)
            if (paused) surface.banner("DONE", "PRESS BACK", -1, GameColors.green3)
        }
    }
}
