package dev.speedcompiler.tv.ui.working.games

import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameArtTest {
    private val artDir = File(System.getProperty("gameArtDir") ?: error("gameArtDir not set by Gradle"))

    // The unit-test classpath compiles against android.jar, which has no javax.imageio / java.awt; the JVM has both at run time.
    private class Png(val w: Int, val h: Int, val argb: (Int, Int) -> Int)

    private fun read(f: File): Png {
        val im = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, f)!!
        val c = im.javaClass
        val get = c.getMethod("getRGB", Int::class.java, Int::class.java)
        return Png(c.getMethod("getWidth").invoke(im) as Int, c.getMethod("getHeight").invoke(im) as Int) { x, y -> get.invoke(im, x, y) as Int }
    }

    @Test
    fun paletteHas32DistinctColors() = assertEquals(32, GameColors.all.map { it.toArgb() }.toSet().size)

    @Test
    fun everyGameSheetUsesOnlyPaletteColorsAndHardAlpha() {
        val allowed = GameColors.all.map { it.toArgb() }.toSet()
        val sheets = artDir.listFiles { f -> f.name.startsWith("game_") && f.extension == "png" }!!.sortedBy { it.name }
        assertTrue("no game_*.png in $artDir", sheets.isNotEmpty())
        for (f in sheets) {
            val png = read(f)
            for (y in 0 until png.h) for (x in 0 until png.w) {
                val argb = png.argb(x, y)
                val a = argb ushr 24
                assertTrue("${f.name} ($x,$y) alpha $a", a == 0 || a == 255)
                if (a == 255) assertTrue("${f.name} ($x,$y) #%06x".format(argb and 0xFFFFFF), argb in allowed)
            }
        }
    }
}
