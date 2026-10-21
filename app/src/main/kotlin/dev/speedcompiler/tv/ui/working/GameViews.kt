package dev.speedcompiler.tv.ui.working

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.theme.LocalSpeedColors
import kotlinx.coroutines.delay

enum class GameKind(val title: String, val hint: String) {
    Snake("Snake", "Eat, grow, don't crash"),
    Dino("Dino runner", "Jump the cacti"),
    Flappy("Flappy", "Flap through the gaps"),
}

private fun mapKey(key: Key): Input? = when (key) {
    Key.DirectionUp -> Input.UP
    Key.DirectionDown -> Input.DOWN
    Key.DirectionLeft -> Input.LEFT
    Key.DirectionRight -> Input.RIGHT
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> Input.SELECT
    else -> null
}

private fun newGame(kind: GameKind): Game = when (kind) {
    GameKind.Snake -> SnakeGame()
    GameKind.Dino -> DinoGame()
    GameKind.Flappy -> FlappyGame()
}

/** Plays [kind] on a Canvas. [paused] freezes the loop and ignores input (the job ended). Back calls [onExit]. */
@Composable
fun GamePlayer(kind: GameKind, paused: Boolean, onExit: () -> Unit, modifier: Modifier = Modifier) {
    var game by remember(kind) { mutableStateOf(newGame(kind)) }
    var frame by remember(kind) { mutableIntStateOf(0) }
    var pending by remember(kind) { mutableStateOf<Input?>(null) }
    val focus = remember { FocusRequester() }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val colors = LocalSpeedColors.current
    LaunchedEffect(kind) { focus.requestFocus() }
    LaunchedEffect(kind, paused) {
        while (!paused) {
            delay(if (kind == GameKind.Snake) 150L else 50L)
            game.step(pending)
            pending = null
            frame++
        }
    }
    Box(
        modifier.size(480.dp, 270.dp).clip(RoundedCornerShape(12.dp))
            .border(2.dp, if (focused) colors.accent else colors.hairline, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "${kind.title} game. Arrow keys move, Select to act or restart, Back to stop playing." }
            .focusRequester(focus).focusable(interactionSource = src)
            .onKeyEvent { e ->
                // Only KeyDown is handled and consumed; KeyUp passes through.
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (e.key == Key.Back) { onExit(); return@onKeyEvent true }
                val input = mapKey(e.key) ?: return@onKeyEvent false
                if (!paused) {
                    if (game.over && input == Input.SELECT) game = newGame(kind) else pending = input
                }
                true
            },
    ) {
        Canvas(Modifier.size(480.dp, 270.dp)) {
            frame // read so each step redraws
            drawRect(colors.hairline.copy(alpha = 0.3f))
            when (val g = game) {
                is SnakeGame -> drawSnake(g, colors.accent, colors.error)
                is DinoGame -> drawDino(g, colors.accent, colors.error)
                is FlappyGame -> drawFlappy(g, colors.accent, colors.error)
            }
        }
    }
}

private fun DrawScope.drawSnake(g: SnakeGame, fg: Color, food: Color) {
    val c = size.width / g.cols
    g.body.forEach { drawRect(fg, Offset(it.x * c + 1, it.y * c + 1), Size(c - 2, c - 2)) }
    drawRect(food, Offset(g.food.x * c + 3, g.food.y * c + 3), Size(c - 6, c - 6))
}

private fun DrawScope.drawDino(g: DinoGame, fg: Color, bad: Color) {
    val u = size.width / DinoGame.WIDTH
    val ground = size.height * 0.8f
    drawRect(fg, Offset(0f, ground), Size(size.width, 2f))
    drawRect(fg, Offset(DinoGame.X * u, ground - (g.y + DinoGame.SIZE) * u), Size(DinoGame.SIZE * u, DinoGame.SIZE * u))
    g.cacti.forEach { drawRect(bad, Offset(it.x * u, ground - DinoGame.CACTUS_H * u), Size(DinoGame.CACTUS_W * u, DinoGame.CACTUS_H * u)) }
}

private fun DrawScope.drawFlappy(g: FlappyGame, fg: Color, pipe: Color) {
    val u = size.width / FlappyGame.W
    drawRect(fg, Offset(FlappyGame.BX * u, g.y * u), Size(FlappyGame.SIZE * u, FlappyGame.SIZE * u))
    g.pipes.forEach {
        drawRect(pipe, Offset(it.x * u, 0f), Size(FlappyGame.PIPE_W * u, it.gapTop * u))
        val bottom = (it.gapTop + FlappyGame.GAP) * u
        drawRect(pipe, Offset(it.x * u, bottom), Size(FlappyGame.PIPE_W * u, size.height - bottom))
    }
}
