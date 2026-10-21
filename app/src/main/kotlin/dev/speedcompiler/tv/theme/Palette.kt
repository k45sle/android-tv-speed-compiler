package dev.speedcompiler.tv.theme

import com.materialkolor.hct.Hct
import kotlin.math.max
import kotlin.math.pow

class Preset(val name: String, val seed: Int)

val Presets = listOf(
    Preset("Phthalo green", 0xFF123524.toInt()),
    Preset("Ink indigo", 0xFF262C6B.toInt()),
    Preset("Kiln clay", 0xFF6E2E14.toInt()),
    Preset("Amber ochre", 0xFF8A5A00.toInt()),
    Preset("Plum", 0xFF5B2A6E.toInt()),
    Preset("Lagoon teal", 0xFF0F5257.toInt()),
    Preset("Rose madder", 0xFF8C2F4B.toInt()),
    Preset("Slate steel", 0xFF3A4A5E.toInt()),
)

/** Colors as ARGB ints so the contrast math is testable without Compose. */
class Palette(
    val accent: Int, val onAccent: Int,
    val container: Int, val onContainer: Int,
    val background: Int, val surface: Int, val surfaceVariant: Int,
    val onSurface: Int, val onSurfaceVariant: Int,
)

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
    // Contrast guard: walk lightness away from the text color until it reads at 4.5:1.
    while (contrast(onAccent, accent) < 4.5 && tone in 1.0..99.0) {
        tone += if (dark) 1 else -1
        accent = Hct.from(hue, chroma, tone).toInt()
    }
    fun n(t: Double) = Hct.from(hue, 4.0, t).toInt() // neutrals: seed hue, very low chroma
    return if (dark) Palette(
        accent, onAccent, Hct.from(hue, chroma, 30.0).toInt(), Hct.from(hue, 16.0, 90.0).toInt(),
        n(6.0), n(10.0), n(17.0), n(92.0), n(75.0),
    ) else Palette(
        accent, onAccent, Hct.from(hue, chroma, 90.0).toInt(), Hct.from(hue, 16.0, 10.0).toInt(),
        n(98.0), n(96.0), n(90.0), n(10.0), n(35.0),
    )
}

/** Hue-matched status color used for pill text, dot and 12% tint. */
fun statusArgb(hue: Double, dark: Boolean): Int = Hct.from(hue, 60.0, if (dark) 80.0 else 38.0).toInt()
