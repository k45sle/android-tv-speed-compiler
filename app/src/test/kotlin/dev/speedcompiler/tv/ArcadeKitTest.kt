package dev.speedcompiler.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.ArcadeButton
import dev.speedcompiler.tv.theme.Cabinet
import dev.speedcompiler.tv.theme.CabinetCard
import dev.speedcompiler.tv.theme.DipSwitch
import dev.speedcompiler.tv.theme.Lamp
import dev.speedcompiler.tv.theme.LocalSpeedColors
import dev.speedcompiler.tv.theme.Marquee
import dev.speedcompiler.tv.theme.OperatorRow
import dev.speedcompiler.tv.theme.QrCard
import dev.speedcompiler.tv.theme.RoomBackdrop
import dev.speedcompiler.tv.theme.ScoreRow
import dev.speedcompiler.tv.theme.ScoreTable
import dev.speedcompiler.tv.theme.SelectRow
import dev.speedcompiler.tv.theme.Selector
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.theme.StageMeter
import dev.speedcompiler.tv.theme.StatusPill
import dev.speedcompiler.tv.theme.Tone
import org.junit.Test
import org.robolectric.annotation.Config

/** Renders every arcade kit piece, unfocused and focused, in dark and light into build/outputs/roborazzi/. */
@Config(qualifiers = "w960dp-h920dp")
class ArcadeKitTest : ComposeTestBase() {
    @Test
    fun kitGallery() {
        var dark by mutableStateOf(true)
        rule.setContent {
                SpeedTheme(if (dark) ThemeChoice.Dark else ThemeChoice.Light) {
                    val c = LocalSpeedColors.current
                    RoomBackdrop(Modifier.requiredSize(960.dp, 920.dp)) {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), Arrangement.spacedBy(10.dp)) {
                            Marquee("Auto-optimize is on", subtitle = "2 waiting") { StatusPill("Connected", Tone.Ok) }
                            Marquee("Settings", compact = true, onBack = {}) { Text("Version 1.0") }
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                CabinetCard("StreamBox", Modifier.width(200.dp), "Optimized", Tone.Ok, preview = true) { Text("S") }
                                CabinetCard("Retro Arcade", Modifier.width(200.dp), "Needs a retry", Tone.Error, onClick = {}) { Text("R") }
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    ArcadeButton("Reconnect", {}, preview = true)
                                    ArcadeButton("Setup", {}, fill = Color(Cabinet.BLUE), contentColor = Color(Cabinet.CREAM))
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Lamp(c.ok); Lamp(c.warn, waiting = true); Lamp(c.error)
                                    }
                                    StageMeter(3, 5)
                                    StatusPill("Waiting for you", Tone.Info)
                                }
                            }
                            SelectRow("Run jobs", subtitle = "Choose when", preview = true, onClick = {},
                                actions = { ArcadeButton("Reconnect", {}) }, trailing = { Selector("When the TV is idle", true) })
                            OperatorRow("Start time", onClick = {}) { Selector("02:00", it) }
                            OperatorRow("Pause auto-optimize", sublabel = "No automatic jobs until you turn this off", onClick = {}, preview = true) { DipSwitch(false) }
                            OperatorRow("Keep this TV trusting the app", onClick = {}) { DipSwitch(true) }
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                ScoreTable(
                                    "Recent activity",
                                    listOf(ScoreRow("01-01 00:10", "StreamBox", "Optimized", Tone.Ok), ScoreRow("12-31 21:30", "Retro Arcade", "Failed", Tone.Error)),
                                    Modifier.width(560.dp),
                                )
                                QrCard { Box(Modifier.size(96.dp).background(Color.Black)) }
                            }
                        }
                    }
                }
        }
        for (d in listOf(true, false)) {
            dark = d
            rule.waitForIdle()
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/arcade_kit_${if (d) "dark" else "light"}.png")
        }
    }
}
