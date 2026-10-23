package dev.speedcompiler.tv.ui.setup

import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertCountEquals
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onAllNodesWithText
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
        press(Key.DirectionDown) // Continue (2 selected), below the list
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
        rule.onNodeWithText("Continue (1 selected)", ignoreCase = true).assertIsEnabled()
        restorer.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        see("Pick apps to optimize.")
        rule.onNodeWithText("Continue (1 selected)", ignoreCase = true).assertIsEnabled()
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
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionDown); enter() // Continue
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
        tick(45_000)
        rule.onNodeWithText("Show me how").assertDoesNotExist()
        tick(45_000) // no progress twice
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

    @Test
    fun pickerRowsAreCheckboxesAndContinueSitsBelowTheList() {
        env.developerOptions = true; env.wireless = true
        monitor.flow.value = ConnectionState.Connected
        show { SetupScreen({ backs++ }, deps = deps) }
        enter(); tick()
        rule.onAllNodes(isToggleable()).assertCountEquals(3)
        see("Continue (0 selected)")
        enter() // Alpha
        see("Continue (1 selected)")
    }

    @Test
    fun finding_B08_repairBackStaysInRepair() {
        env.developerOptions = true; env.wireless = true
        show { QuickRepairScreen({ backs++ }, deps) }
        see("Use your phone.")
        url.value = pairUrl; tick()
        url.value = null; monitor.flow.value = ConnectionState.Connecting; tick()
        see("Allow this app.")
        back()
        see("Use your phone.")
        rule.onNodeWithText("Compile your apps ahead of time.").assertDoesNotExist()
    }

    @Test
    fun finding_B14_clicksAreNotFailuresButTimeoutsAre() {
        show { SetupScreen({}, deps = deps) }
        enter() // Start
        see("Turn on Developer options.")
        enter(); enter() // Open Settings, twice
        assertEquals(1, env.calls.count { it == "openSettings" }) // debounced
        rule.onNodeWithText("Show me how").assertDoesNotExist()
        tick(45_000); tick(45_000) // about 45 s without progress, twice
        see("Show me how")
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private fun setLifecycle(owner: Owner, state: Lifecycle.State) {
        rule.runOnUiThread { owner.registry.currentState = state }
        rule.waitForIdle()
    }

    private fun showInLifecycle(owner: Owner) =
        show { CompositionLocalProvider(LocalLifecycleOwner provides owner) { QuickRepairScreen({ backs++ }, deps) } }

    @Test
    fun r10_neverStartsPairingInTheBackgroundAndRestartsOnResume() {
        val owner = Owner()
        showInLifecycle(owner)
        assertEquals(1, env.calls.count { it == "startPairing" })
        url.value = pairUrl; tick()
        setLifecycle(owner, Lifecycle.State.CREATED) // user is in TV Settings
        url.value = null; tick(5000)
        assertEquals(1, env.calls.count { it == "startPairing" })
        setLifecycle(owner, Lifecycle.State.RESUMED); tick(2500)
        assertEquals(2, env.calls.count { it == "startPairing" })
    }

    @Test
    fun r10_aSessionThatEndedInTheBackgroundCountsAsPairedWhenTheKeeperConnected() {
        val owner = Owner()
        showInLifecycle(owner)
        url.value = pairUrl; tick()
        setLifecycle(owner, Lifecycle.State.CREATED)
        url.value = null; tick(500)
        monitor.flow.value = ConnectionState.Connecting; tick(500)
        setLifecycle(owner, Lifecycle.State.RESUMED); tick(500)
        see("Allow this app.")
        assertEquals(1, env.calls.count { it == "startPairing" })
    }

    @Test
    fun r11_freshInstallReachesThePairingStepWhileDisconnected() {
        env.developerOptions = true; env.wireless = true
        monitor.flow.value = ConnectionState.Disconnected // connect failed before Wireless debugging was on
        val fresh = SetupDeps(env, monitor.state, url, holdsPairing = { false })
        show { SetupScreen({ backs++ }, deps = fresh) }
        enter()
        see("Use your phone."); shot("4_pair_fresh")
        rule.onNodeWithText("Allow this app.").assertDoesNotExist()
    }

    private fun allowStep(reason: MutableStateFlow<String?>) {
        env.developerOptions = true; env.wireless = true
        monitor.flow.value = ConnectionState.Disconnected
        val d = SetupDeps(env, monitor.state, url, reason)
        show { SetupScreen({ backs++ }, deps = d) }
        enter()
        see("Allow this app.")
    }

    @Test
    fun b16_allowStepShowsDeniedAndRetries() {
        val reason = MutableStateFlow<String?>("The TV did not allow this app.")
        allowStep(reason)
        monitor.flow.value = ConnectionState.Connecting; tick()
        monitor.flow.value = ConnectionState.Disconnected; tick()
        rule.onAllNodesWithText("The TV did not allow this app.").assertCountEquals(1); shot("5_allow_denied")
        enter() // Retry, focused
        assertTrue("reconnect" in env.calls)
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun b16_allowStepShowsTimeoutAndRetries() {
        allowStep(MutableStateFlow(null))
        tick(46_000)
        see("No answer from the TV yet."); shot("5_allow_timeout")
        enter() // Retry
        rule.onNodeWithText("No answer from the TV yet.").assertDoesNotExist()
    }

    @Test
    fun b02_reconnectTimesOutWithReasonPairAgainAndHelp() {
        monitor.flow.value = ConnectionState.Disconnected
        val d = SetupDeps(env, monitor.state, url, MutableStateFlow("Wireless debugging is off."))
        show { ReconnectScreen({ backs++ }, d) }
        see("Open Developer options")
        tick(31_000)
        see("Wireless debugging is off."); see("Pair again"); see("Show me how"); shot("reconnect_timeout")
        assertEquals(0, backs)
        press(Key.DirectionRight); enter() // Pair again
        see("Use your phone.")
    }

    @Test
    fun r11_connectingDuringFailedAttemptsOnAFreshInstallDoesNotSkipPairing() {
        env.developerOptions = true
        monitor.flow.value = ConnectionState.Disconnected
        val fresh = SetupDeps(env, monitor.state, url, holdsPairing = { false })
        show { SetupScreen({ backs++ }, deps = fresh) }
        enter()
        see("Turn on Wireless debugging.")
        monitor.flow.value = ConnectionState.Connecting; tick()
        monitor.flow.value = ConnectionState.Disconnected; tick()
        env.wireless = true; tick()
        see("Use your phone.")
        rule.onNodeWithText("Allow this app.").assertDoesNotExist()
    }

    @Test
    fun r10_firstPairingStartWaitsForTheAppToBeInFront() {
        val owner = Owner().also { it.registry.currentState = Lifecycle.State.CREATED }
        showInLifecycle(owner)
        tick(3000)
        assertEquals(0, env.calls.count { it == "startPairing" })
        setLifecycle(owner, Lifecycle.State.RESUMED)
        assertEquals(1, env.calls.count { it == "startPairing" })
    }

    @Test
    fun b14_timeoutsCountOnlyWhileTheAppIsInFront() {
        val owner = Owner()
        show { CompositionLocalProvider(LocalLifecycleOwner provides owner) { SetupScreen({}, deps = deps) } }
        enter()
        see("Turn on Developer options.")
        setLifecycle(owner, Lifecycle.State.CREATED) // user is in Settings
        tick(100_000)
        setLifecycle(owner, Lifecycle.State.RESUMED)
        rule.onNodeWithText("Show me how").assertDoesNotExist()
    }

    @Test
    fun b16_allowStepTreatsConnectingThenUnpairedAsDenied() {
        allowStep(MutableStateFlow("The TV does not trust this app yet."))
        monitor.flow.value = ConnectionState.Connecting; tick()
        monitor.flow.value = ConnectionState.Unpaired; tick()
        see("The TV does not trust this app yet."); see("Retry")
    }

    @Test
    fun b02_aLateConnectAfterTheTimeoutStillLeaves() {
        monitor.flow.value = ConnectionState.Disconnected
        val d = SetupDeps(env, monitor.state, url)
        show { ReconnectScreen({ backs++ }, d) }
        tick(31_000)
        see("Try again")
        env.wireless = true; monitor.flow.value = ConnectionState.Connected; tick()
        assertEquals(1, backs)
    }
}
