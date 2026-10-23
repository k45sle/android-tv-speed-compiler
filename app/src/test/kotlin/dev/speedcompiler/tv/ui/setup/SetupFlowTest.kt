package dev.speedcompiler.tv.ui.setup

import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.adb.ConnectionState
import dev.speedcompiler.tv.adb.FakeConnectionMonitor
import dev.speedcompiler.tv.theme.SpeedTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFlowTest : ComposeTestBase() {
    private val env = FakeSetupEnv(apps = listOf(SetupApp("a.alpha", "Alpha"), SetupApp("b.beta", "Beta"), SetupApp("c.gamma", "Gamma")))
    private val monitor = FakeConnectionMonitor(ConnectionState.Unpaired)
    private val url = MutableStateFlow<String?>(null)
    private var finished: Pair<List<String>, Boolean>? = null
    private var backs = 0
    private var presses = 0
    private val deps = SetupDeps(env, monitor.state, url) { sel, now -> finished = sel to now }
    private val pairUrl = "http://tv.local:8080/t0ken"

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { SpeedTheme { content() } }
        leaveTouchMode()
    }

    private fun leaveTouchMode() {
        // A TV has no touch mode, but a Robolectric window starts in it, where clickable rows cannot take focus.
        rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(decor)!!
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.java).apply { isAccessible = true }.invoke(root, false)
        }
        rule.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        presses++
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun enter() = press(Key.DirectionCenter)

    private fun tick(ms: Long = 1000) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun see(text: String) = rule.onNodeWithText(text, ignoreCase = true).assertIsDisplayed()

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/setup_$name.png")

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun walksAllSevenScreensWithDpad() {
        show { SetupScreen({ backs++ }, deps = deps) }
        see("Compile your apps ahead of time."); shot("1_intro")
        enter()
        see("Turn on Developer options."); see("Step 1 of 5"); shot("2_developer_options")
        enter()
        assertEquals(listOf("openSettings"), env.calls)
        env.developerOptions = true; tick()
        see("Turn on Wireless debugging."); see("Step 2 of 5"); shot("3_wireless_debugging")
        enter()
        assertTrue("openDeveloperOptions" in env.calls)
        env.wireless = true; tick()
        see("Use your phone."); see("Step 3 of 5")
        assertTrue("startPairing" in env.calls)
        url.value = pairUrl; tick()
        see(pairUrl); shot("4_pair")
        url.value = null; monitor.flow.value = ConnectionState.Disconnected; tick()
        see("Allow this app."); see("Step 4 of 5"); shot("5_allow")
        monitor.flow.value = ConnectionState.Connected; tick()
        see("Pick apps to optimize."); see("Step 5 of 5")
        tick()
        enter() // list starts focused on Alpha
        press(Key.DirectionDown); press(Key.DirectionDown); enter() // Gamma
        shot("6_apps")
        press(Key.DirectionUp); press(Key.DirectionUp); press(Key.DirectionUp) // Beta, Alpha, Continue
        enter()
        see("Alpha and 1 other will be optimized when nothing is playing. It takes a minute or two each.")
        shot("7_done")
        enter() // Optimize now
        assertEquals(listOf("a.alpha", "c.gamma") to true, finished)
        assertEquals(1, backs)
    }

    @Test
    fun keepsTheStepAndPicksAfterProcessDeath() {
        env.developerOptions = true; env.wireless = true
        monitor.flow.value = ConnectionState.Connected
        val restorer = StateRestorationTester(rule)
        restorer.setContent { SpeedTheme { SetupScreen({ backs++ }, deps = deps) } }
        leaveTouchMode()
        enter()
        see("Pick apps to optimize."); tick()
        enter() // picks Alpha, the focused row
        rule.onNodeWithText("Continue", ignoreCase = true).assertIsEnabled()
        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        see("Pick apps to optimize.")
        rule.onNodeWithText("Continue", ignoreCase = true).assertIsEnabled()
    }

    @Test
    fun keepsThePicksOnTheLastStepAfterProcessDeath() {
        env.developerOptions = true; env.wireless = true
        monitor.flow.value = ConnectionState.Connected
        val restorer = StateRestorationTester(rule)
        restorer.setContent { SpeedTheme { SetupScreen({ backs++ }, deps = deps) } }
        leaveTouchMode()
        enter()
        see("Pick apps to optimize."); tick()
        enter() // picks Alpha, the focused row
        press(Key.DirectionUp); enter() // Continue
        see("Setup is done.")
        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        see("Alpha will be optimized when nothing is playing. It takes a minute or two each.")
        tick(); enter() // Optimize now, focused on entry
        assertEquals(listOf("a.alpha") to true, finished)
    }

    @Test
    fun skipsStepsAlreadyDone() {
        env.developerOptions = true; env.wireless = true
        monitor.flow.value = ConnectionState.Disconnected // paired already
        show { SetupScreen({ backs++ }, deps = deps) }
        enter()
        see("Allow this app.")
        monitor.flow.value = ConnectionState.Connected; tick()
        see("Pick apps to optimize.")
    }

    @Test
    fun backStepsBackAndLeavesFromTheFirstScreen() {
        show { SetupScreen({ backs++ }, deps = deps) }
        enter()
        see("Turn on Developer options.")
        back()
        see("Compile your apps ahead of time.")
        assertEquals(0, backs)
    }

    @Test
    fun offersHelpAfterTwoFailedAttempts() {
        show { SetupScreen({ backs++ }, deps = deps) }
        enter(); enter()
        rule.onNodeWithText("Show me how").assertDoesNotExist()
        enter()
        press(Key.DirectionDown); enter()
        see("Show me how")
        see("Open Settings, go to System, then About. Find the line named Build and press OK on it 7 times. A message says you are now a developer.")
        shot("help")
        back()
        see("Turn on Developer options.")
    }

    @Test
    fun failedPairingRestartsAndOffersHelp() {
        show { QuickRepairScreen({ backs++ }, deps) }
        see("Use your phone.")
        repeat(2) {
            url.value = pairUrl; tick()
            url.value = null; tick(2500) // ended while still Unpaired
        }
        assertEquals(3, env.calls.count { it == "startPairing" })
        see("Show me how")
        rule.onNodeWithText("Show me how").assertIsFocused() // the pair page's only action
    }

    @Test
    fun expiredPairingWhileDisconnectedIsAFailure() {
        monitor.flow.value = ConnectionState.Disconnected
        show { QuickRepairScreen({ backs++ }, deps) }
        repeat(2) {
            url.value = pairUrl; tick()
            url.value = null; tick(2500)
        }
        see("Use your phone.")
        see("Show me how")
    }

    @Test
    fun quickRepairJumpsToPairingAndLeavesWhenConnected() {
        show { QuickRepairScreen({ backs++ }, deps) }
        see("Use your phone.")
        url.value = pairUrl; tick()
        url.value = null; monitor.flow.value = ConnectionState.Connecting; tick()
        see("Allow this app.")
        monitor.flow.value = ConnectionState.Connected; tick()
        assertEquals(1, backs)
    }

    @Test
    fun pairingStepRendersInLight() {
        rule.setContent { SpeedTheme(dev.speedcompiler.tv.engine.ThemeChoice.Light) { SetupScreen({ backs++ }, SetupMode.Repair, deps) } }
        url.value = pairUrl; tick()
        see("Use your phone."); see("Scan me"); see(pairUrl); shot("4_pair_light")
    }

    @Test
    fun reconnectReachesConnectedInOneKeyPress() {
        monitor.flow.value = ConnectionState.Disconnected
        env.hook = {
            if (it == "openDeveloperOptions") env.wireless = true
            if (it == "reconnect") monitor.flow.value = ConnectionState.Connected
        }
        show { ReconnectScreen({ backs++ }, deps) }
        see("Reconnect"); shot("reconnect")
        enter() // Open Developer options; the fake turns Wireless debugging on
        tick()
        assertEquals(1, backs)
        assertTrue("$presses key presses", presses <= 4)
        assertEquals(listOf("openDeveloperOptions", "reconnect"), env.calls)
    }

    @Test
    fun reconnectSkipsTheButtonWhenWirelessDebuggingIsAlreadyOn() {
        monitor.flow.value = ConnectionState.Disconnected
        env.wireless = true
        env.hook = { if (it == "reconnect") monitor.flow.value = ConnectionState.Connected }
        show { ReconnectScreen({ backs++ }, deps) }
        tick()
        assertEquals(1, backs)
        assertEquals(0, presses)
    }
}
