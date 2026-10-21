package dev.speedcompiler.tv.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Draws a character grid as square cells; colors come from [colors], unknown characters stay clear. */
@Composable
fun PixelArt(rows: List<String>, colors: Map<Char, Color>, modifier: Modifier = Modifier, cell: Dp = 5.dp) {
    Canvas(modifier.size(cell * rows[0].length, cell * rows.size)) {
        // Whole-pixel cells so neighbours never blur into each other.
        val c = cell.roundToPx().toFloat()
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch -> colors[ch]?.let { drawRect(it, Offset(x * c, y * c), Size(c, c)) } }
        }
    }
}

/** The pixel logo looping at 10 frames a second on its own green tile (cream vanishes on light themes); [stopped] shows the whole logo still. */
@Composable
fun PixelLogoSprite(stopped: Boolean, modifier: Modifier = Modifier, cell: Dp = 5.dp) {
    val frame by produceState(0, stopped) {
        if (stopped) value = LOGO_FRAMES - 1 else while (true) { delay(100); value = (value + 1) % LOGO_FRAMES }
    }
    PixelArt(pixelLogoFrame(frame), mapOf('c' to Color(Retro.CREAM), 'g' to Color(Retro.GOLD)), modifier.background(Color(Retro.LOGO_BASE)).padding(cell), cell)
}
