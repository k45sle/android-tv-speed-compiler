package dev.speedcompiler.tv.ui.settings

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.adb.KeeperStatus
import dev.speedcompiler.tv.engine.GateMode
import dev.speedcompiler.tv.engine.Mode
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.engine.ThemeChoice
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    init {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val state = MutableStateFlow(SettingsValues())
    private val status = MutableStateFlow<KeeperStatus>(KeeperStatus.Connected)
    private val gone = mutableListOf<String>()
    private val deps = SettingsDeps(state, { f -> state.value = f(state.value) }, status, "0.1.0")

    private fun show() {
        rule.setContent { SpeedTheme { SettingsScreen({ gone += it }, {}, deps) } }
        // A Robolectric window starts in touch mode, where nothing can take focus; a TV has none.
        rule.runOnUiThread {
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(rule.activity.window.decorView)!!
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.java).apply { isAccessible = true }.invoke(root, false)
        }
        rule.waitForIdle()
    }

    // TV buttons react to OK, not touch, so invoke the click action directly.
    private fun click(text: String) {
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun clickDesc(desc: String) {
        rule.onNodeWithContentDescription(desc).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun press(descStart: String, key: Key) {
        rule.onNode(hasContentDescription(descStart, substring = true)).requestFocus()
        rule.onNode(hasContentDescription(descStart, substring = true)).performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun modeAndGateChangesPersist() {
        show()
        click("Manual")
        click("When nothing is playing")
        assertEquals(Mode.Manual, state.value.mode)
        assertEquals(GateMode.NothingPlaying, state.value.gateMode)
        rule.onNodeWithText("Start time").assertDoesNotExist()
    }

    @Test
    fun pauseSwitchToggles() {
        show()
        click("Pause auto-optimize")
        assertTrue(state.value.paused)
        click("Pause auto-optimize")
        assertEquals(false, state.value.paused)
    }

    @Test
    fun windowTimesStepWithDpadAndWrap() {
        show()
        click("During set hours")
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
        click("During set hours")
        rule.onNode(hasContentDescription("Start time", substring = true)).requestFocus()
        rule.onNode(hasContentDescription("Start time", substring = true)).performKeyInput {
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
        click("Dark")
        clickDesc("Plum")
        assertEquals(ThemeChoice.Dark, state.value.theme)
        assertEquals(4, state.value.accentPreset)
        assertEquals(null, state.value.customHue)
        click("Custom")
        assertEquals(150f, state.value.customHue)
        press("Custom accent hue", Key.DirectionRight)
        assertEquals(155f, state.value.customHue)
        clickDesc("Plum")
        assertEquals(null, state.value.customHue)
    }

    @Test
    fun buttonsNavigate() {
        show()
        click("Reconnect"); click("Pair again"); click("Export diagnostics"); click("Help")
        assertEquals(listOf(Routes.Reconnect, Routes.QuickRepair, Routes.Diagnostics, Routes.Help), gone)
        click("Keep this TV trusting the app")
        assertEquals(false, state.value.keepTrusting)
    }

    @Test
    fun everyFocusableIsLabelledAndScreenshots() {
        state.value = SettingsValues(gateMode = GateMode.Window, customHue = 200f)
        show()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/settings.png")
        rule.onNodeWithText("Version 0.1.0").assertExists()
        rule.onAllNodes(isFocusable()).fetchSemanticsNodes().forEach {
            val c = it.config
            assertTrue("unlabelled focusable: $c", SemanticsProperties.Text in c || SemanticsProperties.ContentDescription in c)
        }
    }
}
