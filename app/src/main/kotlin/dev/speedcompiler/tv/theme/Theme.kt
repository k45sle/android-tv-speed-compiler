package dev.speedcompiler.tv.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import dev.speedcompiler.tv.R
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice

/** 8 dp grid. */
object Space {
    val x1 = 8.dp
    val x2 = 16.dp
    val x3 = 24.dp
    val x4 = 32.dp
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val GeistSans = FontFamily(variable(R.font.geist_sans, 400), variable(R.font.geist_sans, 500), variable(R.font.geist_sans, 600))
val GeistMono = FontFamily(variable(R.font.geist_mono, 400), variable(R.font.geist_mono, 500), variable(R.font.geist_mono, 600))

/** Codes, URLs, times, versions and output: Geist Mono with tabular figures. */
val MonoStyle = TextStyle(fontFamily = GeistMono, fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

private fun sans(size: Int, w: Int) = TextStyle(fontFamily = GeistSans, fontSize = size.sp, fontWeight = FontWeight(w))

// Scale 14/16/18/22/28/40; 600 for headings, 500 for labels.
private val SpeedTypography = Typography(
    displayLarge = sans(40, 600), displayMedium = sans(40, 600), displaySmall = sans(28, 600),
    headlineLarge = sans(40, 600), headlineMedium = sans(28, 600), headlineSmall = sans(22, 600),
    titleLarge = sans(22, 600), titleMedium = sans(18, 600), titleSmall = sans(16, 600),
    bodyLarge = sans(18, 400), bodyMedium = sans(16, 400), bodySmall = sans(14, 400),
    labelLarge = sans(18, 500), labelMedium = sans(16, 500), labelSmall = sans(14, 500),
)

/** Extra colors the TV ColorScheme has no slot for. */
@Immutable
class SpeedColors(
    val accent: Color, val onAccent: Color, val hairline: Color, val dark: Boolean,
    val ok: Color, val warn: Color, val error: Color,
)

val LocalSpeedColors = staticCompositionLocalOf<SpeedColors> { error("SpeedTheme missing") }

sealed interface SeedChoice {
    data class Preset(val index: Int) : SeedChoice
    data class Custom(val hue: Float) : SeedChoice

    fun seed(): Int = when (this) {
        is Preset -> Presets[index.coerceIn(Presets.indices)].seed
        is Custom -> seedForHue(hue)
    }
}

fun SettingsValues.seedChoice(): SeedChoice = customHue?.let { SeedChoice.Custom(it) } ?: SeedChoice.Preset(accentPreset)

private fun Int.c() = Color(this)

@Composable
fun SpeedTheme(
    themeChoice: ThemeChoice = ThemeChoice.Dark,
    seed: SeedChoice = SeedChoice.Preset(0),
    content: @Composable () -> Unit,
) {
    val dark = when (themeChoice) {
        ThemeChoice.Dark -> true
        ThemeChoice.Light -> false
        ThemeChoice.System -> isSystemInDarkTheme()
    }
    val seedArgb = seed.seed()
    val pair = remember(seedArgb, dark) {
        val p = buildPalette(seedArgb, dark)
        val hairline = p.onSurface.c().copy(alpha = 0.08f)
        val cs = (if (dark) darkColorScheme() else lightColorScheme()).copy(
            primary = p.accent.c(), onPrimary = p.onAccent.c(),
            primaryContainer = p.container.c(), onPrimaryContainer = p.onContainer.c(),
            secondary = p.accent.c(), onSecondary = p.onAccent.c(),
            background = p.background.c(), onBackground = p.onSurface.c(),
            surface = p.surface.c(), onSurface = p.onSurface.c(),
            surfaceVariant = p.surfaceVariant.c(), onSurfaceVariant = p.onSurfaceVariant.c(),
            border = hairline, borderVariant = hairline,
        )
        cs to SpeedColors(
            p.accent.c(), p.onAccent.c(), hairline, dark,
            statusArgb(145.0, dark).c(), statusArgb(75.0, dark).c(), statusArgb(25.0, dark).c(),
        )
    }
    CompositionLocalProvider(LocalSpeedColors provides pair.second) {
        MaterialTheme(colorScheme = pair.first, typography = SpeedTypography) {
            // Surface supplies LocalContentColor (onSurface); without it tv Text defaults to black.
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }
}
