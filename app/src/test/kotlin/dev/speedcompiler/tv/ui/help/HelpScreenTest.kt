package dev.speedcompiler.tv.ui.help

import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.home.fixRoute
import dev.speedcompiler.tv.ui.Routes
import org.junit.Assert.assertEquals
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w960dp-h2000dp")
class HelpScreenTest : ComposeTestBase() {
    @Test fun showsEntriesAndFixButtonsNavigate() {
        val went = mutableListOf<String>()
        rule.setContent { SpeedTheme { HelpContent(go = { went += it }) } }
        rule.onNodeWithText("The TV forgot this app").assertIsDisplayed()
        rule.onNodeWithText("The pairing code did not work").assertIsDisplayed()
        rule.onAllNodesWithText("Fix connection", ignoreCase = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(Routes.QuickRepair), went)
        rule.onAllNodes(hasContentDescription("Fix connection"))[0].assertIsDisplayed()
        rule.onNodeWithText("Wireless debugging may have switched off. Reconnect, and if that fails turn it on again in Developer options.").assertIsDisplayed()
    }

    @Test fun explainsWhatSpeedCompileChangesAndHowToUndoIt() {
        rule.setContent { SpeedTheme { HelpContent(go = {}) } }
        rule.onNodeWithText("What speed compile changes").assertIsDisplayed()
        rule.onNodeWithText("How to undo it").assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/help_speed_compile.png")
    }

    @Test fun everyFixRoutesToItsDestination() {
        assertEquals(Routes.QuickRepair, fixRoute(Fix.QuickRepair))
        listOf(Fix.TurnOnWirelessDebugging, Fix.AllowPrompt, Fix.Retry).forEach { assertEquals(Routes.Reconnect, fixRoute(it)) }
        assertEquals(setOf(Fix.QuickRepair, Fix.TurnOnWirelessDebugging, Fix.AllowPrompt, Fix.Retry), helpEntries.mapNotNull { it.fix }.toSet())
    }

    @Test fun shots() {
        var light by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { SpeedTheme(if (light) dev.speedcompiler.tv.engine.ThemeChoice.Light else dev.speedcompiler.tv.engine.ThemeChoice.Dark) { HelpContent(go = {}) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/help_dark.png")
        light = true
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/help_light.png")
    }
}
