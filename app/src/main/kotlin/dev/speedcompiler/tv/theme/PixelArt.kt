package dev.speedcompiler.tv.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.speedcompiler.tv.R
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

const val LOGO_FRAMES = 36
const val BANNER_W = 64
const val BANNER_H = 36

/** The animated logo and wordmark: the 36-frame 64x36 sheet (tools/pixel/logo/gen.py) at 12 fps, drawn at a whole-number scale. [stopped] shows the key frame (frame 0). */
@Composable
fun PixelBanner(stopped: Boolean, modifier: Modifier = Modifier, size: Dp = 192.dp) {
    val sheet = ImageBitmap.imageResource(R.drawable.logo_banner)
    val frame by produceState(0, stopped) {
        if (stopped) value = 0 else while (true) { delay(1000L / 12); value = (value + 1) % LOGO_FRAMES }
    }
    Canvas(modifier.size(size, size * BANNER_H / BANNER_W)) {
        val k = maxOf(1, floor(min(this.size.width / BANNER_W, this.size.height / BANNER_H)).toInt())
        val dst = IntSize(BANNER_W * k, BANNER_H * k)
        drawImage(
            sheet, IntOffset(frame * BANNER_W, 0), IntSize(BANNER_W, BANNER_H),
            IntOffset(((this.size.width - dst.width) / 2).roundToInt(), ((this.size.height - dst.height) / 2).roundToInt()), dst,
            filterQuality = FilterQuality.None,
        )
    }
}
