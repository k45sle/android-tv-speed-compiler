package dev.speedcompiler.tv.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StoredTextTest {
    // Every literal the engine (OptimizerImpl, Engine, Jobs) can write, with sample dynamic parts.
    private val stored = listOf(
        "Didn't finish: that is not a valid app name.",
        "Didn't reset: that is not a valid app name.",
        "Didn't reset: the TV took too long.",
        "Didn't reset: the TV no longer trusts this app. Choose Fix connection in Settings.",
        "Didn't reset: the TV has not allowed this app. Accept the debugging prompt on the TV.",
        "Didn't reset: lost the connection to the TV.",
        "Didn't reset: the TV refused.",
        "Didn't reset: the TV refused. It said: Failure [x]",
        "Didn't reset: not supported here.",
        "Reset done. The TV chooses how to run this app again.",
        "Didn't finish: the TV took more than 30 minutes.",
        "Didn't finish: the TV no longer trusts this app. Choose Fix connection in Settings.",
        "Didn't finish: the TV has not allowed this app. Accept the debugging prompt on the TV.",
        "Didn't finish: lost the connection to the TV.",
        "Didn't finish: the TV refused to optimize this app.",
        "Didn't finish: the TV refused to optimize this app. It said: Error: 100% bad",
        "Optimized (this TV doesn't report the result)",
        "Optimized (checked)",
        "Didn't finish: speed-profile, not speed.",
        "Didn't finish: unexpected error (IllegalStateException).",
        "The TV did not report this app's compiler filter.",
        "Not installed",
    )

    @Test fun everyStoredMessageRendersAsItself() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        for (s in stored) assertEquals(s, storedText(res, s))
    }

    @Test fun unknownTextIsUnchanged() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        for (s in listOf("Blocked", "Didn't finish: something older.", "")) assertEquals(s, storedText(res, s))
    }
}
