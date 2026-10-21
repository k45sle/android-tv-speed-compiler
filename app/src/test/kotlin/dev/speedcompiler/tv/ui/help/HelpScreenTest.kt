package dev.speedcompiler.tv.ui.help

import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.speedcompiler.tv.theme.SpeedTheme
import dev.speedcompiler.tv.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(qualifiers = "w960dp-h2000dp")
class HelpScreenTest {
    init {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun showsEntriesAndFixButtonsNavigate() {
        val went = mutableListOf<String>()
        rule.setContent { SpeedTheme { HelpContent { went += it } } }
        rule.onNodeWithText("The TV forgot this app").assertIsDisplayed()
        rule.onNodeWithText("The pairing code did not work").assertIsDisplayed()
        rule.onNodeWithText("Repair").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(Routes.QuickRepair), went)
        rule.onNode(hasContentDescription("Repair")).assertIsDisplayed()
        rule.onNodeWithText("Wireless debugging may have switched off. Reconnect, and if that fails turn it on again in Developer options.").assertIsDisplayed()
    }
}
