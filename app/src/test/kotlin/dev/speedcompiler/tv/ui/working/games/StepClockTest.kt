package dev.speedcompiler.tv.ui.working.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepClockTest {
    private val vsync = 16_666_667L

    @Test
    fun at60HzStepsOnEverySecondVsync() {
        val c = StepClock()
        val due = (0..120).map { c.due(it * vsync) }
        assertEquals(60, due.sum())
        assertTrue(due.drop(1).chunked(2).all { it.sum() == 1 })
    }

    @Test
    fun missedVsyncDoesNotChangeSpeed() {
        val c = StepClock()
        var steps = 0
        for (i in 0..120) if (i != 41) steps += c.due(i * vsync) // vsync 41 never delivered
        assertEquals(60, steps)
    }

    @Test
    fun longStallIsCappedAndThenRecovers() {
        val c = StepClock()
        c.due(0)
        assertEquals(4, c.due(5_000_000_000))
        assertEquals(0, c.due(5_000_000_000 + vsync))
        assertEquals(1, c.due(5_000_000_000 + 2 * vsync))
    }

    @Test
    fun slowDisplayStepsEveryVsync() {
        val c = StepClock()
        assertEquals(0, c.due(0))
        assertEquals(List(5) { 1 }, (1..5).map { c.due(it * 33_333_333L) })
    }
}
