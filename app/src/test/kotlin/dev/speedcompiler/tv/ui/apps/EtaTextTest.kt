package dev.speedcompiler.tv.ui.apps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.ui.home.etaText
import dev.speedcompiler.tv.watch.Eta
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class EtaTextTest {
    private val now = Instant.parse("2026-01-01T20:00:00Z").toEpochMilli()
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private fun text(e: Eta) = etaText(res, e, Instant.ofEpochMilli(now), ZoneOffset.UTC)

    @Test
    fun startTimeSaysTodayOrTomorrow() {
        assertEquals("Starts at 23:30", text(Eta.StartsAt(Instant.parse("2026-01-01T23:30:00Z"))))
        assertEquals("Starts tomorrow 02:00", text(Eta.StartsAt(Instant.parse("2026-01-02T02:00:00Z"))))
    }

    @Test
    fun otherGateReasons() {
        assertEquals("Waiting for the TV to be idle", text(Eta.WaitingForIdle))
        assertEquals("Waiting for playback to stop", text(Eta.WaitingForPlayback))
        assertEquals("Auto-optimize is off", text(Eta.Paused))
        assertEquals("Waiting for free storage", text(Eta.WaitingForStorage))
        assertEquals("Waiting for the connection", text(Eta.WaitingForConnection))
        assertEquals("Connecting to the TV", text(Eta.Connecting))
        assertEquals("Checking the TV", text(Eta.Checking))
        assertEquals("Start time unknown. Check the connection.", text(Eta.Unknown))
        assertEquals("#2 in queue, about 4 min", text(Eta.InQueue(1, 240_000)))
        assertEquals("", text(Eta.None))
    }
}
