package dev.speedcompiler.tv.engine

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GateImplTest {
    private val utc = ZoneId.of("UTC")
    private val overnight = GateWindow(startMinute = 22 * 60, endMinute = 6 * 60)

    private class Signals(var music: Boolean = false, var wake: Wakefulness? = Wakefulness.Dozing) : GateSignals {
        override suspend fun musicActive() = music
        override suspend fun wakefulness() = wake
    }

    private fun gate(
        clock: FakeClock,
        signals: Signals,
        mode: GateMode,
        paused: Boolean = false,
    ) = GateImpl(clock, signals, { GateConfig(mode, overnight, paused) }, utc)

    private fun millisAt(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun idleMapsSignalsToStatus() = runBlocking {
        val clock = FakeClock(millisAt("2026-01-01T12:00:00Z"))
        val signals = Signals(wake = Wakefulness.Awake)
        val g = gate(clock, signals, GateMode.Idle)
        assertEquals(GateStatus.WaitingForIdle, g.status())
        signals.wake = Wakefulness.Dozing
        assertEquals(GateStatus.Open, g.status())
        signals.music = true
        assertEquals(GateStatus.WaitingForPlayback, g.status())
    }

    @Test
    fun unknownScreenStateNeverBlocksIdleMode() = runBlocking {
        val g = gate(FakeClock(millisAt("2026-01-01T12:00:00Z")), Signals(wake = null), GateMode.Idle)
        assertEquals(GateStatus.Open, g.status())
    }

    @Test
    fun windowReportsTheClockTimeItOpens() = runBlocking {
        val clock = FakeClock(millisAt("2026-01-01T12:00:00Z"))
        val g = gate(clock, Signals(), GateMode.Window)
        assertEquals(GateStatus.OpensAt(Instant.parse("2026-01-01T22:00:00Z")), g.status())
        clock.now = millisAt("2026-01-01T23:00:00Z")
        assertEquals(GateStatus.Open, g.status())
    }

    @Test
    fun nonWindowModesNeverPromiseAClockTime() = runBlocking {
        val clock = FakeClock(millisAt("2026-01-01T12:00:00Z"))
        for (mode in listOf(GateMode.Idle, GateMode.NothingPlaying)) {
            val status = gate(clock, Signals(wake = Wakefulness.Awake), mode).status()
            assertEquals("mode=$mode", false, status is GateStatus.OpensAt)
        }
    }

    @Test
    fun pauseHoldsTheGateInEveryMode() = runBlocking {
        val clock = FakeClock(millisAt("2026-01-01T23:00:00Z"))
        for (mode in GateMode.entries) {
            assertEquals("mode=$mode", GateStatus.Paused, gate(clock, Signals(), mode, paused = true).status())
        }
    }
}
