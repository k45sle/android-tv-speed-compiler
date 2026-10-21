package dev.speedcompiler.tv.engine

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class GateLogicTest {
    private val utc = ZoneId.of("UTC")
    private val overnight = GateWindow(startMinute = 22 * 60, endMinute = 6 * 60)
    private val run = GateDecision.Run
    private val playback = GateDecision.Wait(WaitReason.Playback)
    private val notIdle = GateDecision.Wait(WaitReason.NotIdle)
    private val paused = GateDecision.Wait(WaitReason.Paused)

    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 1, 1, hour, minute, 0, 0, utc)

    private fun decideAt(
        now: ZonedDateTime = at(12),
        music: Boolean = false,
        wake: Wakefulness? = Wakefulness.Awake,
        mode: GateMode,
        window: GateWindow = overnight,
        manual: Boolean = false,
        paused: Boolean = false,
    ) = decide(now, music, wake, mode, window, manual, paused)

    @Test
    fun idleRunsOnlyWhenNothingPlayingAndScreenNotAwake() {
        val cases = listOf(
            Triple(false, Wakefulness.Awake, notIdle),
            Triple(false, Wakefulness.Dozing, run),
            Triple(false, Wakefulness.Asleep, run),
            Triple(false, null, run),
            Triple(true, Wakefulness.Dozing, playback),
            Triple(true, Wakefulness.Awake, playback),
            Triple(true, null, playback),
        )
        for ((music, wake, expected) in cases) {
            assertEquals("music=$music wake=$wake", expected, decideAt(music = music, wake = wake, mode = GateMode.Idle))
        }
    }

    @Test
    fun nothingPlayingRunsRegardlessOfScreenWhileBrowsing() {
        for (wake in listOf(Wakefulness.Awake, Wakefulness.Dozing, Wakefulness.Asleep, null)) {
            assertEquals("wake=$wake", run, decideAt(wake = wake, mode = GateMode.NothingPlaying))
            assertEquals("wake=$wake", playback, decideAt(music = true, wake = wake, mode = GateMode.NothingPlaying))
        }
    }

    @Test
    fun windowRunsInsideHoursAndIgnoresScreenAndMusicOnlyForPlayback() {
        assertEquals(run, decideAt(now = at(23), mode = GateMode.Window))
        assertEquals(run, decideAt(now = at(5, 59), mode = GateMode.Window))
        assertEquals(run, decideAt(now = at(23), wake = Wakefulness.Awake, mode = GateMode.Window))
        assertEquals(playback, decideAt(now = at(23), music = true, mode = GateMode.Window))
    }

    @Test
    fun overnightWindowIsStartInclusiveEndExclusive() {
        assertEquals(GateDecision.Wait(WaitReason.OutsideWindow(at(22).toInstant())), decideAt(now = at(21, 59), mode = GateMode.Window))
        assertEquals(run, decideAt(now = at(22), mode = GateMode.Window))
        assertEquals(run, decideAt(now = at(6, 0).minusMinutes(1), mode = GateMode.Window))
        assertEquals(
            GateDecision.Wait(WaitReason.OutsideWindow(at(22).toInstant())),
            decideAt(now = at(6), mode = GateMode.Window),
        )
    }

    @Test
    fun daytimeWindowOpensTodayOrTomorrow() {
        val daytime = GateWindow(startMinute = 2 * 60, endMinute = 6 * 60)
        assertEquals(run, decideAt(now = at(2), mode = GateMode.Window, window = daytime))
        assertEquals(
            GateDecision.Wait(WaitReason.OutsideWindow(at(2).toInstant())),
            decideAt(now = at(1, 59), mode = GateMode.Window, window = daytime),
        )
        val afterClose = decideAt(now = at(6), mode = GateMode.Window, window = daytime)
        assertEquals(GateDecision.Wait(WaitReason.OutsideWindow(at(2).plusDays(1).toInstant())), afterClose)
    }

    @Test
    fun pauseHoldsEveryAutomaticModeButManualStillRuns() {
        for (mode in GateMode.entries) {
            assertEquals("mode=$mode", paused, decideAt(now = at(23), mode = mode, paused = true))
            assertEquals("mode=$mode", run, decideAt(now = at(23), music = true, mode = mode, paused = true, manual = true))
        }
    }

    @Test
    fun manualAlwaysRunsWhateverTheSignals() {
        for (mode in GateMode.entries) for (music in listOf(false, true)) for (wake in listOf(Wakefulness.Awake, null)) {
            assertEquals(run, decideAt(now = at(12), music = music, wake = wake, mode = mode, manual = true))
        }
    }

    @Test
    fun outsideWindowRecordsTheInstantTheWindowOpens() {
        val opensAt = nextWindowStart(at(12), overnight)
        assertEquals(Instant.parse("2026-01-01T22:00:00Z"), opensAt)
    }
}
