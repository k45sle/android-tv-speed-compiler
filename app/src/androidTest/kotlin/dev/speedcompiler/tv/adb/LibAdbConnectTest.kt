package dev.speedcompiler.tv.adb

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connects to the emulator's own adbd over plain TCP. Needs `adb tcpip 5555` first.
 * On a secure (user) image the first connection raises the "Allow debugging?" prompt; the test accepts it
 * through UiAutomation while [LibAdb.connect] waits, ticking "Always allow" so later runs connect straight away.
 */
@RunWith(AndroidJUnit4::class)
class LibAdbConnectTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun connectsAndRunsShellCommands() = runBlocking {
        val adb = LibAdb(
            instrumentation.targetContext, direct = "127.0.0.1" to 5555,
            timeoutMillis = 30_000, connectTimeoutMillis = 30_000, promptForApproval = true,
        )
        val connecting = async(Dispatchers.IO) { adb.connect() }
        while (connecting.isActive) {
            acceptDebuggingPrompt()
            delay(500)
        }
        connecting.await()

        val id = adb.run("id")
        assertEquals(id.output, 0, id.exitCode)
        assertTrue(id.output, id.output.startsWith("uid=2000(shell)"))

        val sdk = adb.run("getprop ro.build.version.sdk")
        assertEquals(ShellResult(0, "${android.os.Build.VERSION.SDK_INT}\n"), sdk)

        assertEquals(3, adb.run("sh -c 'exit 3'").exitCode)
        // `exit` skips the appended echo, so the marker never arrives
        assertEquals(MISSING_RC, adb.run("exit 3").exitCode)
        adb.disconnect()
    }

    /** Clicks "Allow" (with "Always allow" ticked) on adbd's prompt if it is showing; already-known keys never see one. */
    private fun acceptDebuggingPrompt() {
        val ui = instrumentation.getUiAutomation()
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val roots = ui.windows.mapNotNull { it.root }
        val allow = roots.firstNotNullOfOrNull { it.find("Allow") } ?: return
        roots.firstNotNullOfOrNull { it.find("Always allow", contains = true) }
            ?.takeIf { !it.isChecked }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun AccessibilityNodeInfo.find(text: String, contains: Boolean = false): AccessibilityNodeInfo? =
        findAccessibilityNodeInfosByText(text).firstOrNull {
            val t = it.text?.toString() ?: return@firstOrNull false
            (if (contains) t.contains(text, ignoreCase = true) else t.equals(text, ignoreCase = true)) && it.isEnabled
        }
}
