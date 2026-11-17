package dev.speedcompiler.tv.ui.setup

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import dev.speedcompiler.tv.ComposeTestBase
import dev.speedcompiler.tv.adb.KeyCheck
import dev.speedcompiler.tv.theme.SpeedTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyHelpTest : ComposeTestBase() {
    private val check = MutableStateFlow(KeyCheck.Unknown)
    private val busy = MutableStateFlow(false)
    private var allowances = 0
    private var opened = 0
    private val deps = KeyHelpDeps(check, busy, { allowances++ }, { opened++ })

    private fun show() {
        rule.setContent { SpeedTheme { KeyHelpScreen(onBack = {}, deps = deps) } }
        rule.waitForIdle()
    }

    // tv Surface ignores touch clicks in Robolectric; its OnClick action is what a D-pad press invokes.
    private fun SemanticsNodeInteraction.invokeClick() = performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun notSavedShowsTheStepsAndAllowsAgain() {
        check.value = KeyCheck.NotSaved
        show()
        rule.onNodeWithText("Revoke USB debugging authorizations", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open Developer options", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Allow again").invokeClick()
        assertEquals(1, allowances)
        assertEquals(0, opened)
    }

    @Test
    fun forgottenAlsoOffersDeveloperOptions() {
        check.value = KeyCheck.Forgotten
        show()
        rule.onNodeWithText("This TV saved Always allow", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open Developer options", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Open Developer options", ignoreCase = true).invokeClick()
        assertEquals(1, opened)
    }

    @Test
    fun busySaysItIsCheckingAndHidesAllowAgain() {
        busy.value = true
        show()
        rule.onNodeWithText("Checking with the TV", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Allow again").assertDoesNotExist()
    }

    @Test
    fun savedSaysSoAndOffersNoDeveloperOptions() {
        check.value = KeyCheck.Saved
        show()
        rule.onNodeWithText("Saved. After a restart the TV lets Speed Compile back in without asking.", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Open Developer options", ignoreCase = true).assertDoesNotExist()
        rule.onNodeWithText("Allow again").assertIsDisplayed()
    }

    @Test
    fun allowedOnceShowsTheTickStepAndNoDeveloperOptions() {
        check.value = KeyCheck.AllowedOnce
        show()
        rule.onNodeWithText("tick Always allow from this computer", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open Developer options", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun notSavedFitsTheScreen() = assertFits(KeyCheck.NotSaved, "Still not saved?")

    @Test
    fun forgottenFitsTheScreen() = assertFits(KeyCheck.Forgotten, "Forgotten again")

    /** With too much text the page runs out of room and the last paragraph is cut off: its text overflows its box. */
    private fun assertFits(state: KeyCheck, lastParagraph: String) {
        check.value = state
        show()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(lastParagraph, substring = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertFalse("last paragraph cut off", layouts.single().didOverflowHeight)
    }
}
