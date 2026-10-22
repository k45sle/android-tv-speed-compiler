package dev.speedcompiler.tv

import dev.speedcompiler.tv.theme.Palette
import dev.speedcompiler.tv.theme.LOGO_FRAMES
import dev.speedcompiler.tv.theme.Presets
import dev.speedcompiler.tv.theme.Retro
import dev.speedcompiler.tv.theme.RetroColors
import dev.speedcompiler.tv.theme.contrast
import dev.speedcompiler.tv.ui.working.games.GameColors
import androidx.compose.ui.graphics.toArgb
import android.graphics.BitmapFactory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetroTest {
    private val pixelDir = File(System.getProperty("pixelDir") ?: error("pixelDir not set by Gradle"))

    /** Parses an XPM written one character per pixel: returns the color map and the rows. */
    private fun xpm(name: String): Pair<Map<Char, Int>, List<String>> {
        val strings = Regex("\"([^\"]*)\"").findAll(File(pixelDir, name).readText()).map { it.groupValues[1] }.toList()
        val (w, h, n) = strings[0].trim().split(Regex("\\s+")).map { it.toInt() }
        val colors = strings.subList(1, 1 + n).associate { it[0] to (0xFF000000.toInt() or it.substringAfter("c #").toInt(16)) }
        val rows = strings.subList(1 + n, 1 + n + h)
        assertTrue("$name rows", rows.all { it.length == w })
        return colors to rows
    }

    @Test fun everyTextColorReadsOnEveryBackground() {
        for (p in listOf(Retro.Dark, Retro.Light, Retro.NeonDark, Retro.NeonLight)) {
            val texts = listOf(p.onSurface, p.onSurfaceVariant, p.accent, p.ok, p.warn, p.error)
            for (bg in listOf(p.background, p.surface, p.surfaceVariant, p.container)) for (t in texts) {
                val cr = contrast(t, bg)
                assertTrue("%08x on %08x is %.2f".format(t, bg, cr), cr >= 4.5)
            }
            assertTrue("onAccent", contrast(p.onAccent, p.accent) >= 4.5)
            assertTrue("onContainer", contrast(p.onContainer, p.container) >= 4.5)
        }
    }

    @Test fun retroPresetsComeFirstWithFixedPalettes() {
        assertEquals(Retro.Dark, Presets[0].palette(true))
        assertEquals(Retro.Light, Presets[0].palette(false))
        assertEquals(Retro.NeonDark, Presets[1].palette(true))
    }

    @Test fun pixelSourcesUseOnlyPaletteColors() {
        val files = pixelDir.listFiles { f -> f.extension == "xpm" }!!
        assertTrue("no XPM files in $pixelDir", files.isNotEmpty())
        val allowed = RetroColors + GameColors.all.map { it.toArgb() }
        for (f in files) for ((ch, c) in xpm(f.name).first) assertTrue("${f.name} '$ch' %08x not in the palette".format(c), c in allowed)
    }

    @Test fun iconAndBannerSourcesAreTheLogoSize() {
        assertEquals(27, xpm("icon.xpm").second.size)
        assertEquals(36, xpm("banner.xpm").second.size)
        assertEquals(Retro.LOGO_BASE, xpm("banner.xpm").first['.'])
    }

    @Test fun bannerSheetHoldsTheLoopAndFrameZeroMatchesTheStaticBanner() {
        val sheet = BitmapFactory.decodeFile(File(pixelDir, "../../app/src/main/res/drawable-nodpi/logo_banner.png").path)
        assertEquals(64 * LOGO_FRAMES to 36, sheet.width to sheet.height)
        val (colors, rows) = xpm("banner.xpm")
        // Frame 0 is the key frame; only the wordmark streak differs, so compare the logo half (x < 30) exactly.
        for (y in rows.indices) for (x in 0 until 30)
            assertEquals("pixel $x,$y", colors[rows[y][x]], sheet.getPixel(x, y))
    }
}
