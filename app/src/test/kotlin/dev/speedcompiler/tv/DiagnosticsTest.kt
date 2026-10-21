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
import dev.speedcompiler.tv.web.buildDiagnosticsJson
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
            ),
        )
        val out = buildDiagnosticsJson(input)
        for (secret in listOf("MIIEvQ", "BEGIN", "123456", token, "192.168", "5555", "tv.local")) {
            assertFalse("leaked $secret", out.contains(secret))
        }
        assertTrue(out.contains("lost the connection"))
        assertTrue(out.contains("com.example.app"))
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        try { responseCode } finally { disconnect() }
    }

    @Test fun downloadServesOnceThenGone() {
        val s = DiagnosticsServer("{}", PairingSession(FakeClock(1_000L))).also { it.start(); server = it }
        val base = "http://127.0.0.1:${s.port}"
        assertEquals(404, get("$base/wrong"))
        assertEquals(200, get("$base/${s.token}"))
        assertEquals(410, get("$base/${s.token}"))
    }

    @Test fun expiredDownloadIs404() {
        val clock = FakeClock(1_000L)
        val s = DiagnosticsServer("{}", PairingSession(clock)).also { it.start(); server = it }
        clock.now += PairingSession.TTL_MILLIS
        assertEquals(404, get("http://127.0.0.1:${s.port}/${s.token}"))
    }
}
