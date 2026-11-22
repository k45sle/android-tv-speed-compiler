package dev.speedcompiler.tv.ui.setup

import dev.speedcompiler.tv.ComposeTestBase
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.adb.AdbFailure
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
    private val deps = SetupDeps(env, monitor.state, url, onFinish = { sel, now -> finished = sel to now })
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
        see("Optimize your apps ahead of time."); shot("1_intro")
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
        see("Optimize your apps ahead of time.")
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
        rule.onNodeWithText("Open Developer options", ignoreCase = true).assertIsFocused() // focus stays on an action
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
            if (it == "connect") {
                monitor.flow.value = ConnectionState.Connected
                env.answer.complete(null)
            }
        }
        show { ReconnectScreen({ backs++ }, deps) }
        see("Reconnect"); shot("reconnect")
        enter() // Open Developer options; the fake turns Wireless debugging on
        tick()
        assertEquals(1, backs)
        assertTrue("$presses key presses", presses <= 4)
        assertEquals(listOf("openDeveloperOptions", "connect"), env.calls)
    }

    @Test
    fun reconnectSkipsTheButtonWhenWirelessDebuggingIsAlreadyOn() {
        monitor.flow.value = ConnectionState.Disconnected
        env.wireless = true
        env.hook = { if (it == "connect") env.answer.complete(null) }
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
        rule.onNodeWithText("Optimize your apps ahead of time.").assertDoesNotExist()
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

    private fun allowStep() {
        env.developerOptions = true; env.wireless = true
        monitor.flow.value = ConnectionState.Disconnected
        val d = SetupDeps(env, monitor.state, url)
        show { SetupScreen({ backs++ }, deps = d) }
        enter()
        see("Allow this app.")
    }

    @Test
    fun allowStepAdvancesOnItsOwnSuccess() {
        // The step's own try connected, the shared status may still say Disconnected (a later drop could flip it first).
        allowStep()
        env.answer.complete(null)
        tick()
        see("Pick apps to optimize.")
    }

    @Test
    fun b16_allowStepShowsDeniedAndRetries() {
        allowStep()
        env.answer.complete(AdbFailure.NotAuthorized())
        tick()
        rule.onAllNodesWithText("The TV did not allow this app.").assertCountEquals(1); shot("5_allow_denied")
        assertEquals(1, env.calls.count { it == "connect" })
        enter() // Retry, focused
        tick()
        assertEquals(2, env.calls.count { it == "connect" })
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun b16_allowStepShowsTimeoutAndRetries() {
        allowStep()
        env.answer.complete(AdbFailure.Timeout())
        tick()
        see("The TV did not answer in time."); shot("5_allow_timeout")
        enter() // Retry
        tick()
        rule.onNodeWithText("The TV did not answer in time.").assertDoesNotExist()
    }

    @Test
    fun b02_reconnectTimesOutWithReasonPairAgainAndHelp() {
        monitor.flow.value = ConnectionState.Disconnected
        val d = SetupDeps(env, monitor.state, url)
        show { ReconnectScreen({ backs++ }, d) }
        see("Open Developer options")
        tick(31_000)
        see("Wireless debugging is off."); see("Pair again"); see("Show me how"); shot("reconnect_timeout")
        assertEquals(0, backs)
        press(Key.DirectionRight); enter() // Pair again
        see("Use your phone.")
    }

    @Test
    fun reconnectShowsItsOwnFailure() {
        monitor.flow.value = ConnectionState.Disconnected
        env.wireless = true
        show { ReconnectScreen({ backs++ }, deps) }
        env.answer.complete(AdbFailure.NotAuthorized())
        tick()
        see("The TV did not allow this app."); see("Try again")
        assertEquals(0, backs)
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
        allowStep()
        env.answer.complete(AdbFailure.NotPaired())
        tick()
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

    private val legacy = MutableStateFlow(false)
    private fun legacyDeps() =
        SetupDeps(env, monitor.state, url, holdsPairing = { false }, onFinish = { sel, now -> finished = sel to now }, legacy = legacy, setLegacy = { legacy.value = it })

    private fun click(text: String) { rule.onNodeWithText(text, ignoreCase = true).performClick(); rule.waitForIdle() }

    @Test
    fun alreadyOnSkipsDeveloperOptionsTheTvNeverReports() {
        show { SetupScreen({ backs++ }, deps = deps) }
        enter()
        see("Turn on Developer options.")
        click("It's already on")
        see("Turn on Wireless debugging.")
        assertTrue("openSettings" !in env.calls)
    }

    @Test
    fun fireTvStartsOnAdbDebuggingWithItsOwnMenuNames() {
        env.fireTv = true
        show { SetupScreen({ backs++ }, deps = legacyDeps()) }
        assertEquals(true, legacy.value)
        enter()
        see("Open Settings > My Fire TV > About, and press your Fire TV's name 7 times. If My Fire TV already lists Developer options, it's on.")
        shot("2_developer_options_fire_tv")
        click("It's already on")
        see("Turn on ADB debugging.")
    }

    @Test
    fun fireTvRepairKeepsTheChosenConnection() {
        env.fireTv = true
        show { SetupScreen({ backs++ }, SetupMode.Repair, deps = legacyDeps()) }
        tick()
        assertEquals(false, legacy.value)
    }

    @Test
    fun legacyWalksFromNoWirelessDebuggingToAllowWithoutPairing() {
        env.developerOptions = true
        show { SetupScreen({ backs++ }, deps = legacyDeps()) }
        enter()
        see("Turn on Wireless debugging.")
        click("No Wireless debugging?")
        assertEquals(true, legacy.value)
        see("Turn on ADB debugging."); see("Step 2 of 5"); shot("3_adb_debugging")
        env.adbDebugging = true; tick(4000)
        see("ADB debugging is on, but this TV doesn't accept the connection on port 5555. Speed Compile needs Wireless debugging on this TV."); shot("3_adb_port_closed")
        env.portOpen = true; tick()
        see("Allow this app.")
        assertTrue("connect" in env.calls)
        assertTrue("startPairing" !in env.calls)
        monitor.flow.value = ConnectionState.Connected; tick()
        see("Pick apps to optimize.")
    }

    @Test
    fun legacyNoPortClosedMessageWhileAdbDebuggingIsOff() {
        env.developerOptions = true; legacy.value = true
        show { SetupScreen({ backs++ }, deps = legacyDeps()) }
        enter()
        see("Turn on ADB debugging.")
        tick(10_000)
        rule.onNodeWithText("ADB debugging is on, but", substring = true).assertDoesNotExist()
    }

    @Test
    fun legacySwitchesBackToWirelessDebugging() {
        env.developerOptions = true; legacy.value = true
        show { SetupScreen({ backs++ }, deps = legacyDeps()) }
        enter()
        see("Turn on ADB debugging.")
        click("Use Wireless debugging")
        assertEquals(false, legacy.value)
        see("Turn on Wireless debugging.")
        env.wireless = true; tick()
        see("Use your phone.")
    }

    @Test
    fun legacyBackgroundFailureDoesNotDenyTheStep() {
        // A background try failing says nothing about the step's own prompted try, which is still pending.
        env.developerOptions = true; legacy.value = true; env.portOpen = true
        show { SetupScreen({ backs++ }, deps = legacyDeps()) }
        enter()
        see("Allow this app.")
        monitor.flow.value = ConnectionState.Connecting; tick()
        monitor.flow.value = ConnectionState.Disconnected; tick()
        rule.onNodeWithText("The TV did not allow this app.").assertDoesNotExist()
        rule.onNodeWithText("Retry").assertDoesNotExist()
        see("Connecting")
    }

    @Test
    fun legacyAllowStepWaitsForThePrompt() {
        env.developerOptions = true; legacy.value = true; env.portOpen = true
        show { SetupScreen({ backs++ }, deps = legacyDeps()) }
        enter()
        see("Allow this app.")
        tick(59_000)
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun legacyPromptedRefusalShowsTheNotAllowedReason() {
        env.developerOptions = true; legacy.value = true; env.portOpen = true
        show { SetupScreen({ backs++ }, deps = legacyDeps()) }
        enter()
        see("Allow this app.")
        env.answer.complete(AdbFailure.NotAuthorized())
        tick()
        see("The TV did not allow this app."); see("Retry")
    }

    @Test
    fun legacyReconnectWaitsForThePortAndHidesPairAgain() {
        legacy.value = true
        monitor.flow.value = ConnectionState.Disconnected
        show { ReconnectScreen({ backs++ }, legacyDeps()) }
        see("Turn ADB debugging back on and this app reconnects by itself.")
        tick(31_000)
        see("ADB debugging is off, or the TV doesn't accept the connection on port 5555."); shot("reconnect_adb_timeout")
        rule.onNodeWithText("Pair again").assertDoesNotExist()
        assertTrue("connect" !in env.calls)
        env.portOpen = true; tick()
        assertTrue("connect" in env.calls)
        monitor.flow.value = ConnectionState.Connected; tick()
        assertEquals(1, backs)
    }
}
