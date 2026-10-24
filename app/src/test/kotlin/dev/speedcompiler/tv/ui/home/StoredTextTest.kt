package dev.speedcompiler.tv.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.engine.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        "Didn't finish: the TV no longer trusts this app. Choose Fix connection in Settings.",
        "Didn't finish: the TV has not allowed this app. Accept the debugging prompt on the TV.",
        "Didn't finish: lost the connection to the TV.",
        "Optimized (this TV doesn't report the result)",
        "Optimized (checked)",
        "Not installed",
    )

    @Test fun everyStoredMessageRendersAsItself() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        for (s in stored) assertEquals(s, storedText(res, s))
    }

    // Built from the engine's own constants and templates, so rewording one there without the registry fails here.
    // Literals pin the wording already stored in Room rows: rewording a constant must fail here.
    @Test fun everyMessageTheEngineCanWriteMapsToAResource() {
        val all = mapOf(
            DID_NOT_FINISH + INVALID_APP_TAIL to "Didn't finish: that is not a valid app name.",
            DID_NOT_RESET + INVALID_APP_TAIL to "Didn't reset: that is not a valid app name.",
            DID_NOT_RESET + TOOK_TOO_LONG_RESET_TAIL to "Didn't reset: the TV took too long.",
            DID_NOT_RESET + NOT_PAIRED_TAIL to "Didn't reset: the TV no longer trusts this app. Choose Fix connection in Settings.",
            DID_NOT_RESET + NOT_ALLOWED_TAIL to "Didn't reset: the TV has not allowed this app. Accept the debugging prompt on the TV.",
            DID_NOT_RESET + LOST_TAIL to "Didn't reset: lost the connection to the TV.",
            DID_NOT_RESET + REFUSED_RESET_TAIL to "Didn't reset: the TV refused.",
            withSaid(DID_NOT_RESET + REFUSED_RESET_TAIL, "x") to "Didn't reset: the TV refused. It said: x",
            RESET_UNSUPPORTED to "Didn't reset: not supported here.",
            RESET_DONE to "Reset done. The TV chooses how to run this app again.",
            DID_NOT_FINISH + NOT_PAIRED_TAIL to "Didn't finish: the TV no longer trusts this app. Choose Fix connection in Settings.",
            DID_NOT_FINISH + NOT_ALLOWED_TAIL to "Didn't finish: the TV has not allowed this app. Accept the debugging prompt on the TV.",
            DID_NOT_FINISH + LOST_TAIL to "Didn't finish: lost the connection to the TV.",
            DID_NOT_FINISH + REFUSED_FINISH_TAIL to "Didn't finish: the TV refused to optimize this app.",
            withSaid(DID_NOT_FINISH + REFUSED_FINISH_TAIL, "x") to "Didn't finish: the TV refused to optimize this app. It said: x",
            tookMinutes(10) to "Didn't finish: the TV took more than 10 minutes.",
            unexpectedError("IllegalStateException") to "Didn't finish: unexpected error (IllegalStateException).",
            notSpeed("speed-profile") to "Didn't finish: speed-profile, not speed.",
            notSpeed("not fully compiled: verify") to "Didn't finish: verify, not speed.",
            OPTIMIZED_UNVERIFIED to "Optimized (this TV doesn't report the result)",
            OPTIMIZED_CHECKED to "Optimized (checked)",
            VERIFY_UNKNOWN to "The TV did not report this app's compiler filter.",
            NOT_INSTALLED to "Not installed",
            NO_PROFILE_YET to "Waiting for the app's first launch.",
            DEBUG_BUILD to "Didn't finish: this is a debug build, and Android never compiles debug builds ahead of time.",
        )
        for ((built, stored) in all) {
            assertEquals(stored, built)
            assertNotNull(stored, storedResource(stored))
        }
    }

    @Test fun reworkedMessagesShowNextSteps() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val retry = "Try again when the TV is idle."
        mapOf(
            "Didn't finish: the TV took more than 30 minutes." to "Didn't finish: the TV took too long. Try again when nothing is playing.",
            "Didn't finish: the TV refused to optimize this app." to "Didn't finish: the TV refused to optimize this app. $retry",
            "Didn't finish: the TV refused to optimize this app. It said: Error: 100% bad" to
                "Didn't finish: the TV refused to optimize this app. $retry It said: Error: 100% bad",
            "Didn't finish: speed-profile, not speed." to "Didn't finish: the TV only partly optimized this app. $retry",
            "Didn't finish: unexpected error (IllegalStateException)." to "Didn't finish: something went wrong. Try again from the app.",
            "The TV did not report this app's compiler filter." to "The TV did not report the result for this app.",
        ).forEach { (stored, shown) -> assertEquals(shown, storedText(res, stored)) }
    }

    @Test fun unknownTextIsUnchanged() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        for (s in listOf("Blocked", "Didn't finish: something older.", "")) assertEquals(s, storedText(res, s))
    }
}
