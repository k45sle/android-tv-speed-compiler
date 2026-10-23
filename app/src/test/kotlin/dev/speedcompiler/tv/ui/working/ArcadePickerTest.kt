package dev.speedcompiler.tv.ui.working

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.speedcompiler.tv.ComposeTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadePickerTest : ComposeTestBase() {
    private fun run(still: Boolean, ms: Long): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val first = FocusRequester()
        rule.mainClock.autoAdvance = false
        rule.setContent { ArcadePicker({}, first, still = still, probe = { counts.merge(it, 1, Int::plus) }) }
        rule.runOnIdle { first.requestFocus() }
        rule.mainClock.advanceTimeBy(100)
        counts.clear()
        rule.mainClock.advanceTimeBy(ms)
        println("still=$still over ${ms}ms: $counts")
        return counts
    }

    @Test fun litCabinetRedrawsAt15AndBulbsAtMostSeven() {
        val c = run(still = false, ms = 1000)
        assertTrue("frames=$c", c["frame"] in 13..16)
        assertTrue("ticks=$c", c["tick"] in 1..7)
        assertTrue("steps=$c", c["step"]!! >= 20) // 30 steps/s, divided by ticksPerStep
    }

    @Test fun lowRamShowsStillFrame() {
        assertEquals(emptyMap<String, Int>(), run(still = true, ms = 2000))
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/arcade_picker_still.png")
    }
}
