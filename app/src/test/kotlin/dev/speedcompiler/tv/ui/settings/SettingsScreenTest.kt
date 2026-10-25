package dev.speedcompiler.tv.ui.settings

import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.adb.Fix
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.engine.GateMode
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.Routes
import dev.speedcompiler.tv.ui.home.fixRoute
import dev.speedcompiler.tv.ui.home.fixText
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import dev.speedcompiler.tv.theme.Presets
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest : ComposeTestBase() {
    private val state = MutableStateFlow(SettingsValues())
    private val status = MutableStateFlow<KeeperStatus>(KeeperStatus.Connected)
    private val gone = mutableListOf<String>()
    private val deps = SettingsDeps(state, { f -> state.value = f(state.value) }, status, "0.1.0")

    private fun show() {
        rule.setContent { SpeedTheme(state.collectAsState().value.theme) { SettingsScreen({ gone += it }, {}, deps) } }
        // A Robolectric window starts in touch mode, where nothing can take focus; a TV has none.
        rule.runOnUiThread {
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(rule.activity.window.decorView)!!
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.java).apply { isAccessible = true }.invoke(root, false)
        }
        rule.waitForIdle()
    }

    // TV buttons react to OK, not touch, so invoke the click action directly.
    private fun click(text: String) {
        rule.onNodeWithText(text, ignoreCase = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun clickDesc(desc: String) {
        rule.onNodeWithContentDescription(desc).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun press(descStart: String, key: Key) {
        val node = rule.onNode(hasContentDescription(descStart, substring = true) or hasText(descStart, ignoreCase = true))
        node.requestFocus()
        node.performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun downFromThemeLandsOnThePickedSwatch() {
        state.value = SettingsValues(accentPreset = Presets.indexOfFirst { it.name == "Plum" })
        show()
        press("Theme,", Key.DirectionDown)
        rule.onNodeWithContentDescription("Plum").assertIsFocused()
    }

    @Test
    fun gateChangesPersist() {
        show()
        click("Run jobs")
        assertEquals(GateMode.NothingPlaying, state.value.gateMode)
        rule.onNodeWithText("Start time").assertDoesNotExist()
    }

    @Test
    fun autoSwitchTogglesPauseAndRestoresAutoMode() {
        state.value = SettingsValues(mode = Mode.Manual)
        show()
        rule.onNodeWithText("Mode").assertDoesNotExist()
        click("Auto-optimize")
        assertEquals(Mode.Auto, state.value.mode)
        assertEquals(false, state.value.paused)
        click("Auto-optimize")
        assertTrue(state.value.paused)
    }

    @Test
    fun failedConnectionShowsFixAndRoutesByIt() {
        status.value = KeeperStatus.Failed(RuntimeException(), Fix.Retry)
        show()
        Fix.entries.forEach { fix ->
            status.value = KeeperStatus.Failed(RuntimeException(), fix)
            gone.clear()
            rule.waitForIdle()
            rule.onNodeWithText("Not connected", ignoreCase = true).assertExists()
            rule.onNodeWithText(fixText(ApplicationProvider.getApplicationContext<Context>().resources, fix)).assertExists()
            if (fix == Fix.QuickRepair) {
                rule.onNodeWithText(fixText(ApplicationProvider.getApplicationContext<Context>().resources, fix)).performScrollTo()
                rule.onRoot().captureRoboImage("build/outputs/roborazzi/settings_failed.png")
            }
            click("Fix connection")
            assertEquals(listOf(fixRoute(fix)), gone)
        }
    }

    @Test
    fun windowTimesStepWithDpadAndWrap() {
        show()
        click("Run jobs"); click("Run jobs")
        assertEquals(GateMode.Window, state.value.gateMode)
        press("Start time", Key.DirectionRight)
        assertEquals(150, state.value.windowStartMinute)
        press("End time", Key.DirectionLeft)
        assertEquals(330, state.value.windowEndMinute)
        state.value = state.value.copy(windowStartMinute = 0)
        rule.waitForIdle()
        press("Start time", Key.DirectionLeft)
        assertEquals(23 * 60 + 30, state.value.windowStartMinute)
    }

    @Test
    fun quickStepsAllLand() {
        show()
        click("Run jobs"); click("Run jobs")
        rule.onNodeWithText("Start time").requestFocus()
        rule.onNodeWithText("Start time").performKeyInput {
            repeat(3) { pressKey(Key.DirectionRight) }
        }
        rule.waitForIdle()
        assertEquals(120 + 90, state.value.windowStartMinute)
    }

    @Test
    fun steppingOntoTheOtherEndSkipsIt() {
        state.value = SettingsValues(gateMode = GateMode.Window, windowStartMinute = 120, windowEndMinute = 150)
        show()
        press("Start time", Key.DirectionRight)
        assertEquals(180, state.value.windowStartMinute)
    }

    @Test
    fun appearancePersists() {
        show()
        click("Theme"); click("Theme")
        clickDesc("Plum")
        assertEquals(ThemeChoice.Dark, state.value.theme)
        assertEquals(Presets.indexOfFirst { it.name == "Plum" }, state.value.accentPreset)
        assertEquals(null, state.value.customHue)
        click("Custom")
        assertEquals(150f, state.value.customHue)
        press("Custom accent hue", Key.DirectionRight)
        assertEquals(165f, state.value.customHue)
        clickDesc("Plum")
        assertEquals(null, state.value.customHue)
    }

    @Test
    fun howToOptimizeCyclesBetweenAutomaticAndFull() {
        show()
        rule.onNodeWithText("Automatic (recommended)", substring = true, ignoreCase = true).assertExists()
        click("How to optimize")
        assertEquals(dev.speedcompiler.tv.engine.OptimizeHow.Full, state.value.optimizeHow)
        rule.onNodeWithText("Full compile", substring = true, ignoreCase = true).assertExists()
        click("How to optimize")
        assertEquals(dev.speedcompiler.tv.engine.OptimizeHow.Automatic, state.value.optimizeHow)
    }

    @Test
    fun buttonsNavigate() {
        show()
        click("Fix connection"); click("Export diagnostics"); click("Help")
        assertEquals(listOf(Routes.Reconnect, Routes.Diagnostics, Routes.Help), gone)
        click("Keep this TV trusting the app")
        assertEquals(false, state.value.keepTrusting)
        click("Keep Wireless debugging on")
        click("Cancel")
        assertEquals(true, state.value.keepWifiDebugging)
        click("Keep Wireless debugging on")
        click("OK")
        assertEquals(false, state.value.keepWifiDebugging)
        click("Keep Wireless debugging on")
        assertEquals(true, state.value.keepWifiDebugging)
    }

    @Test
    fun everyFocusableIsLabelledAndScreenshots() {
        state.value = SettingsValues(gateMode = GateMode.Window, customHue = 200f, theme = ThemeChoice.Dark)
        show()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/settings_dark.png")
        state.value = state.value.copy(theme = ThemeChoice.Light)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/settings_light.png")
        rule.onNodeWithText("Version 0.1.0", ignoreCase = true).assertExists()
        rule.onAllNodes(isFocusable()).fetchSemanticsNodes().forEach {
            val c = it.config
            assertTrue("unlabelled focusable: $c", SemanticsProperties.Text in c || SemanticsProperties.ContentDescription in c)
        }
    }
}
