package dev.speedcompiler.tv

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.BannerCard
import dev.speedcompiler.tv.theme.ListRow
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.Presets
import dev.speedcompiler.tv.theme.QrCard
import dev.speedcompiler.tv.theme.SeedChoice
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.theme.StatusPill
import dev.speedcompiler.tv.theme.StepHeader
import dev.speedcompiler.tv.theme.Tone
import dev.speedcompiler.tv.theme.buildPalette
import dev.speedcompiler.tv.theme.contrast
import dev.speedcompiler.tv.theme.seedForHue
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

class ContrastTest {
    @Test
    fun onAccentIsAtLeast4_5ForPresetsAndRandomHues() {
        val rnd = Random(42)
        val seeds = Presets.map { it.seed } + List(50) { seedForHue(rnd.nextFloat() * 360f) }
        for (seed in seeds) for (dark in listOf(true, false)) {
            val p = buildPalette(seed, dark)
            val cr = contrast(p.onAccent, p.accent)
            assertTrue("seed %08x dark=$dark contrast $cr".format(seed), cr >= 4.5)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeShotTest {
    // No ui-test-manifest in the catalog, so register the host activity by hand before the rule launches it.
    init {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }

    @get:Rule val rule = createComposeRule()

    @Test
    fun rendersThreeSeedsInBothThemes() {
        var cfg by mutableStateOf(true to 0)
        var seen = 0
        rule.setContent {
            val (dark, i) = cfg
            SpeedTheme(if (dark) ThemeChoice.Dark else ThemeChoice.Light, SeedChoice.Preset(i)) {
                seen = LocalSpeedColors.current.accent.toArgb()
                Column(Modifier.padding(24.dp)) {
                    StepHeader(2, 4, "Pair your TV")
                    StatusPill("Ready", Tone.Ok)
                    BannerCard("Optimize now", body = "12 apps queued", onClick = {})
                    ListRow("Streaming app", subtitle = "v1.2.3", onClick = {})
                    QrCard { Box(Modifier.size(48.dp).background(Color.Black)) }
                }
            }
        }
        for (dark in listOf(true, false)) for (i in 0..2) {
            cfg = dark to i
            rule.waitForIdle()
            val name = "${if (dark) "dark" else "light"}_${Presets[i].name.replace(' ', '_').lowercase()}"
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/theme_$name.png")
            assertEquals(name, buildPalette(Presets[i].seed, dark).accent, seen)
        }
    }
}
