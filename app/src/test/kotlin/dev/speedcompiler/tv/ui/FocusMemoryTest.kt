package dev.speedcompiler.tv.ui

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.theme.rememberFocusMemory
import dev.speedcompiler.tv.theme.remembersFocus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusMemoryTest {
    init {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstVisitFocusesFirstItemAndReturnRestoresTheLastOne() {
        val tester = StateRestorationTester(rule)
        tester.setContent {
            SpeedTheme {
                val mem = rememberFocusMemory("a")
                Row {
                    Button({}, Modifier.remembersFocus(mem, "a")) { Text("A") }
                    Button({}, Modifier.remembersFocus(mem, "b")) { Text("B") }
                }
            }
        }
        rule.runOnUiThread {
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(rule.activity.window.decorView)!!
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.java).apply { isAccessible = true }.invoke(root, false)
        }
        rule.waitForIdle()
        rule.onNodeWithText("A").assertIsFocused()
        rule.onNodeWithText("B").requestFocus()
        rule.waitForIdle()
        tester.emulateSavedInstanceStateRestore() // what leaving to another screen and coming back does
        rule.waitForIdle()
        rule.onNodeWithText("B").assertIsFocused()
    }

    /** Home on a cold start: the first item is unknown until settings load, and the early guess has no button. */
    @Test
    fun firstItemThatChangesWhileLoadingStillGetsFocus() {
        val loaded = androidx.compose.runtime.mutableStateOf(false)
        // Leave touch mode first: leaving it after setContent auto-focuses the first button, which hides the bug.
        rule.runOnUiThread {
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(rule.activity.window.decorView)!!
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.java).apply { isAccessible = true }.invoke(root, false)
        }
        rule.setContent {
            SpeedTheme {
                val mem = rememberFocusMemory(if (loaded.value) "b" else "status")
                Row {
                    Button({}, Modifier.remembersFocus(mem, "a")) { Text("A") }
                    Button({}, Modifier.remembersFocus(mem, "b")) { Text("B") }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("A").assertIsNotFocused()
        rule.runOnUiThread { loaded.value = true }
        rule.waitForIdle()
        rule.onNodeWithText("B").assertIsFocused()
    }

    @Test
    fun anItemReEnteringCompositionDoesNotStealFocus() {
        val showB = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            SpeedTheme {
                val mem = rememberFocusMemory("a")
                Row {
                    Button({}, Modifier.remembersFocus(mem, "a")) { Text("A") }
                    if (showB.value) Button({}, Modifier.remembersFocus(mem, "b")) { Text("B") }
                    Button({}) { Text("X") } // not tracked, like the search box
                }
            }
        }
        rule.runOnUiThread {
            val root = android.view.View::class.java.getMethod("getViewRootImpl").invoke(rule.activity.window.decorView)!!
            root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.java).apply { isAccessible = true }.invoke(root, false)
        }
        rule.waitForIdle()
        rule.onNodeWithText("B").requestFocus()
        rule.onNodeWithText("X").requestFocus() // memory still says "b"
        rule.waitForIdle()
        rule.runOnUiThread { showB.value = false }
        rule.waitForIdle()
        rule.runOnUiThread { showB.value = true }
        rule.waitForIdle()
        rule.onNodeWithText("X").assertIsFocused()
    }
}
