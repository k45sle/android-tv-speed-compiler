package dev.speedcompiler.tv.theme

import androidx.annotation.StringRes
import dev.speedcompiler.tv.R
import com.materialkolor.hct.Hct
import kotlin.math.max
import kotlin.math.pow

/** [dark] and [light] are fixed palettes; when absent both are derived from [seed]. */
class Preset(val name: String, @StringRes val label: Int, val seed: Int, private val dark: Palette? = null, private val light: Palette? = null) {
    fun palette(isDark: Boolean): Palette = (if (isDark) dark else light) ?: buildPalette(seed, isDark)
}

val Presets = listOf(
    Preset("Retro", R.string.preset_retro, Retro.GOLD, Retro.Dark, Retro.Light),
    Preset("Retro neon", R.string.preset_retro_neon, Retro.NeonDark.accent, Retro.NeonDark, Retro.NeonLight),
    Preset("Phthalo green", R.string.preset_phthalo_green, 0xFF123524.toInt()),
    Preset("Ink indigo", R.string.preset_ink_indigo, 0xFF262C6B.toInt()),
    Preset("Kiln clay", R.string.preset_kiln_clay, 0xFF6E2E14.toInt()),
    Preset("Amber ochre", R.string.preset_amber_ochre, 0xFF8A5A00.toInt()),
    Preset("Plum", R.string.preset_plum, 0xFF5B2A6E.toInt()),
    Preset("Lagoon teal", R.string.preset_lagoon_teal, 0xFF0F5257.toInt()),
    Preset("Rose madder", R.string.preset_rose_madder, 0xFF8C2F4B.toInt()),
    Preset("Slate steel", R.string.preset_slate_steel, 0xFF3A4A5E.toInt()),
)

/** Colors as ARGB ints so the contrast math is testable without Compose. */
data class Palette(
    val accent: Int, val onAccent: Int,
    val container: Int, val onContainer: Int,
    val background: Int, val surface: Int, val surfaceVariant: Int,
    val onSurface: Int, val onSurfaceVariant: Int,
    val line: Int, val ok: Int, val warn: Int, val error: Int,
) {
    fun all() = listOf(accent, onAccent, container, onContainer, background, surface, surfaceVariant, onSurface, onSurfaceVariant, line, ok, warn, error)
}

/** Seed for the Custom preset: one hue, fixed chroma and tone. */
fun seedForHue(hue: Float): Int = Hct.from(hue.toDouble().mod(360.0), 48.0, 50.0).toInt()

private fun lin(c: Int): Double {
    val v = c / 255.0
    return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private fun luminance(argb: Int) =
    0.2126 * lin(argb shr 16 and 255) + 0.7152 * lin(argb shr 8 and 255) + 0.0722 * lin(argb and 255)

/** WCAG contrast ratio, 1..21. */
fun contrast(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

fun buildPalette(seed: Int, dark: Boolean): Palette {
    val s = Hct.fromInt(seed)
    val hue = s.hue
    val chroma = max(s.chroma, 36.0)
    val onAccent = if (dark) Hct.from(hue, 8.0, 10.0).toInt() else 0xFFFFFFFF.toInt()
    var tone = if (dark) 82.0 else 47.0
    var accent = Hct.from(hue, chroma, tone).toInt()
    val container = Hct.from(hue, chroma, if (dark) 30.0 else 90.0).toInt()
    // Contrast guard: walk lightness away from the text color until accent text and accent-on-container read at 4.5:1.
    while ((contrast(onAccent, accent) < 4.5 || contrast(accent, container) < 4.5) && tone in 1.0..99.0) {
        tone += if (dark) 1 else -1
        accent = Hct.from(hue, chroma, tone).toInt()
    }
    fun n(t: Double) = Hct.from(hue, 4.0, t).toInt() // neutrals: seed hue, very low chroma
    val status = arrayOf(statusArgb(145.0, dark), statusArgb(75.0, dark), statusArgb(25.0, dark))
    return if (dark) Palette(
        accent, onAccent, container, Hct.from(hue, 16.0, 90.0).toInt(),
        n(6.0), n(10.0), n(17.0), n(92.0), n(75.0), n(30.0), status[0], status[1], status[2],
    ) else Palette(
        accent, onAccent, container, Hct.from(hue, 16.0, 10.0).toInt(),
        n(98.0), n(96.0), n(90.0), n(10.0), n(35.0), n(78.0), status[0], status[1], status[2],
    )
}

/** Hue-matched status color used for pill text, dot and 12% tint. */
fun statusArgb(hue: Double, dark: Boolean): Int = Hct.from(hue, 60.0, if (dark) 80.0 else 38.0).toInt()

/** Fixed retro colors, taken from the pixel logo. the XPM files in tools/pixel may only use colors from [RetroColors]. */
object Retro {
    const val LOGO_BASE = 0xFF153C29.toInt()
    const val CREAM = 0xFFF2E6C4.toInt()
    const val GOLD = 0xFFE8A33A.toInt()

    // line, ok, warn, error per mode; every text color passes 4.5:1 on background, surface and container (RetroTest).
    val Dark = Palette(
        accent = GOLD, onAccent = 0xFF0B2017.toInt(),
        container = 0xFF1C4A33.toInt(), onContainer = CREAM,
        background = 0xFF0B2017.toInt(), surface = 0xFF0B2017.toInt(), surfaceVariant = 0xFF123524.toInt(),
        onSurface = CREAM, onSurfaceVariant = 0xFF9DBFA9.toInt(),
        line = 0xFF2E6B4A.toInt(), ok = 0xFF6FD08C.toInt(), warn = 0xFFF2C14E.toInt(), error = 0xFFFF9585.toInt(),
    )
    val Light = Palette(
        accent = 0xFF824A05.toInt(), onAccent = 0xFFFFF6DC.toInt(),
        container = 0xFFEADBB0.toInt(), onContainer = 0xFF123524.toInt(),
        background = CREAM, surface = CREAM, surfaceVariant = 0xFFFFF6DC.toInt(),
        onSurface = 0xFF123524.toInt(), onSurfaceVariant = 0xFF3F6450.toInt(),
        line = 0xFFB89F63.toInt(), ok = 0xFF1A6232.toInt(), warn = 0xFF7A4F00.toInt(), error = 0xFFA8231A.toInt(),
    )
    val NeonDark = Dark.copy(accent = 0xFF3EE6D0.toInt())
    val NeonLight = Light.copy(accent = 0xFF00685E.toInt())
}

val RetroColors: Set<Int> = setOf(Retro.LOGO_BASE, Retro.CREAM, Retro.GOLD) +
    listOf(Retro.Dark, Retro.Light, Retro.NeonDark, Retro.NeonLight).flatMap { it.all() }
