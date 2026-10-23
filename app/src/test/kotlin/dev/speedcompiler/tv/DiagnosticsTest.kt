package dev.speedcompiler.tv

import dev.speedcompiler.tv.engine.FakeClock
import dev.speedcompiler.tv.engine.SettingsValues
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.QueueItem
import dev.speedcompiler.tv.watch.QueueSource
import dev.speedcompiler.tv.watch.WatchedApp
import dev.speedcompiler.tv.web.DiagnosticsInput
import dev.speedcompiler.tv.web.DiagnosticsServer
import dev.speedcompiler.tv.web.PairingSession
import dev.speedcompiler.tv.web.buildCappedJson
import dev.speedcompiler.tv.web.buildDiagnosticsJson
import dev.speedcompiler.tv.web.scrub
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class) // org.json is only real under Robolectric
@ConscryptMode(ConscryptMode.Mode.OFF)
class DiagnosticsTest {
    private var server: DiagnosticsServer? = null

    @After fun tearDown() { server?.stop() }

    @Test fun exportHasNoSecrets() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----"
        val token = "0123456789abcdef0123456789abcdef"
        val input = DiagnosticsInput(
            "0.1.0", 34, SettingsValues(), "Connected",
            listOf(WatchedApp("com.example.app", "fp", lastResult = "ok from 192.168.1.50")),
            listOf(QueueItem(1, "com.example.app", QueueSource.AUTO, enqueuedAt = 1, failureReason = "key $pem")),
            emptyList(),
            listOf(
                Event(1, 1, null, "optimize_failed", "pair code 123456 token $token at 192.168.1.50:5555 tv.local"),
                Event(2, 2, "com.example.app", "optimize_failed", "Didn't finish: lost the connection to the TV."),
                Event(3, 3, null, "kind-789-012", "v6 fe80::1ff:fe23:4567 code 123 456 or 654-321 key AAAAC3Nz user@Living-Room port 37123 " +
                    "emulator-5554 serial AB12CD34EF named Living Room TV"),
            ),
        )
        val out = buildDiagnosticsJson(input, known = listOf("Living Room TV"))
        for (secret in listOf(
            "MIIEvQ", "BEGIN", "123456", token, "192.168", "5555", "tv.local", "fe80", "123 456", "654-321", "789-012",
            "user@", "37123", "emulator", "AB12CD34EF", "Living Room",
        )) {
            assertFalse("leaked $secret", out.contains(secret))
        }
        assertTrue(out.contains("lost the connection"))
        assertTrue(out.contains("com.example.app"))
    }

    @Test fun scrubHandlesMacsClockTimesAndNames() {
        val out = scrub(
            "mac AA-BB-CC-DD-EE-FF and aa:bb:cc:dd:ee:ff finished at 12:30:45 or 9:05, ssid \"HomeNet\" " +
                "build google/codename/codename:14/AP1A/1234:user/release-keys on port 5555 host:7777 pkg com.example.app",
        )!!
        for (secret in listOf("AA-BB", "aa:bb", "HomeNet", "codename", "5555", "7777")) assertFalse("leaked $secret in $out", out.contains(secret))
        assertTrue(out, out.contains("12:30:45") && out.contains("9:05") && out.contains("com.example.app"))
    }

    @Test fun cappedJsonTrimsOldEventsAndSaysSo() {
        val events = List(500) { Event(it.toLong(), it.toLong(), null, "k", "message number $it with some padding text") }
        val input = DiagnosticsInput("0.1.0", 34, SettingsValues(), "Connected", emptyList(), emptyList(), emptyList(), events)
        val out = buildCappedJson(input, emptyList(), 5_000)
        assertTrue(out.length <= 5_000)
        assertTrue(out.contains("trimmed, use the QR download"))
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        try { responseCode } finally { disconnect() }
    }

    @Test fun downloadServesOnceThenGone() {
        val s = DiagnosticsServer("{}", PairingSession(FakeClock(1_000L))).also { it.start(); server = it }
        val base = "http://127.0.0.1:${s.port}"
        assertEquals(404, get("$base/wrong"))
        assertEquals(200, get("$base/${s.token}"))
        // The server stops right after the body is sent, so a second try is refused; if it races in first, it gets 410.
        val second = runCatching { get("$base/${s.token}") }
        assertTrue(second.isFailure || second.getOrNull() == 410)
    }

    @Test fun expiredDownloadIs404() {
        val clock = FakeClock(1_000L)
        val s = DiagnosticsServer("{}", PairingSession(clock)).also { it.start(); server = it }
        clock.now += PairingSession.TTL_MILLIS
        assertEquals(404, get("http://127.0.0.1:${s.port}/${s.token}"))
    }
}
