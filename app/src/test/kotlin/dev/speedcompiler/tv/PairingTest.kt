package dev.speedcompiler.tv

import dev.speedcompiler.tv.adb.Pairing
import dev.speedcompiler.tv.engine.FakeClock
import dev.speedcompiler.tv.web.PairingSession
import dev.speedcompiler.tv.web.PairingWebServer
import dev.speedcompiler.tv.web.PairingDiscovery
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val clock = FakeClock(1_000L)
    private var server: PairingWebServer? = null

    @After fun tearDown() { server?.stop() }

    @Test fun tokenIs128BitHexAndUnique() {
        val t = PairingSession.newToken()
        assertTrue(Regex("[0-9a-f]{32}").matches(t))
        assertNotEquals(t, PairingSession.newToken())
    }

    @Test fun tokenMatchOnlyExact() {
        val s = PairingSession(clock, token = "abc")
        assertTrue(s.tokenMatches("abc"))
        assertFalse(s.tokenMatches("abd"))
        assertFalse(s.tokenMatches(""))
    }

    @Test fun expiresAfterTenMinutes() {
        val s = PairingSession(clock)
        clock.now += 10 * 60 * 1000L - 1
        assertFalse(s.expired())
        assertTrue(s.tryAttempt())
        clock.now += 1
        assertTrue(s.expired())
        assertFalse(s.tryAttempt())
    }

    @Test fun fiveAttemptsThenLocked() {
        val s = PairingSession(clock)
        repeat(5) { assertTrue(s.tryAttempt()) }
        assertFalse(s.tryAttempt())
    }

    @Test fun codeValidation() {
        assertEquals("123456", PairingSession.cleanCode("12 34-56"))
        assertNull(PairingSession.cleanCode("12345"))
        assertNull(PairingSession.cleanCode("1234567"))
        assertNull(PairingSession.cleanCode("abcdef"))
        assertNull(PairingSession.cleanCode(null))
    }

    private class Recorder(val ok: Boolean = true) : Pairing {
        val calls = mutableListOf<Triple<String, Int, String>>()
        override suspend fun pair(host: String, port: Int, code: String): Boolean {
            calls += Triple(host, port, code)
            return ok
        }
    }

    private fun start(p: Pairing, discovered: Int? = 4242, finished: () -> Unit = {}): PairingWebServer {
        val s = PairingWebServer(PairingSession(clock), p, { "<tv-ip>" }, { discovered }, finished)
        s.start()
        server = s
        return s
    }

    private fun http(s: PairingWebServer, path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:${s.port}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        return c.responseCode to stream.bufferedReader().readText()
    }

    @Test fun serverServesPageAndPairs() {
        val rec = Recorder()
        val finished = CountDownLatch(1)
        val s = start(rec) { finished.countDown() }
        val (code, html) = http(s, "/${s.token}")
        assertEquals(200, code)
        assertTrue(html.contains("one-time-code"))
        val (c2, _) = http(s, "/${s.token}", "POST", "code=123+456")
        assertEquals(200, c2)
        assertEquals(listOf(Triple("<tv-ip>", 4242, "123456")), rec.calls)
        assertTrue(finished.await(5, TimeUnit.SECONDS)) // the server finishes on a 500 ms timer
    }

    @Test fun manualPortOverridesDiscovery() {
        val rec = Recorder()
        val s = start(rec, discovered = null)
        assertEquals(400, http(s, "/${s.token}", "POST", "code=123456").first)
        http(s, "/${s.token}", "POST", "code=123456&port=5555")
        assertEquals(5555, rec.calls.single().second)
    }

    @Test fun wrongTokenOrMethodIs404() {
        val s = start(Recorder())
        assertEquals(404, http(s, "/wrong").first)
        assertEquals(404, http(s, "/").first)
        assertEquals(404, http(s, "/wrong", "POST", "code=123456").first)
        assertEquals(404, http(s, "/${s.token}", "DELETE").first)
        assertEquals(404, http(s, "/${s.token}", "PUT").first)
        assertEquals(404, http(s, "/${s.token}/x").first)
    }

    @Test fun badCodeRejectedAndAttemptsLock() {
        val rec = Recorder(ok = false)
        val s = start(rec)
        assertEquals(400, http(s, "/${s.token}", "POST", "code=12").first)
        repeat(5) { assertEquals(200, http(s, "/${s.token}", "POST", "code=123456").first) }
        assertEquals(403, http(s, "/${s.token}", "POST", "code=123456").first)
        assertEquals(5, rec.calls.size)
    }

    @Test fun expiredServerIs404() {
        val s = start(Recorder())
        clock.now += 10 * 60 * 1000L
        assertEquals(404, http(s, "/${s.token}").first)
    }

    private fun handlerThreads() = Thread.getAllStackTraces().keys.count { it.name.startsWith("NanoHttpd Request Processor") }

    // QA A-17
    @Test fun malformedCodeDoesNotUseAnAttempt() {
        val rec = Recorder(); val s = start(rec)
        repeat(5) { assertEquals(400, http(s, "/${s.token}", "POST", "code=abc").first) }
        assertEquals(200, http(s, "/${s.token}", "POST", "code=123456").first)
        assertEquals(1, rec.calls.size)
    }

    // QA A-18
    @Test fun discoveredPortWinsOverFormPort() {
        val rec = Recorder(); val s = start(rec)
        http(s, "/${s.token}", "POST", "code=123456&port=1")
        assertEquals(4242, rec.calls.single().second)
    }

    @Test fun formPortOutOfRangeIsIgnored() {
        val rec = Recorder(); val s = start(rec, discovered = null)
        assertEquals(400, http(s, "/${s.token}", "POST", "code=123456&port=70000").first)
        assertTrue(rec.calls.isEmpty())
    }

    private fun eventually(cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) { assertTrue("timed out", System.nanoTime() < end); LockSupport.parkNanos(10_000_000) }
    }

    // QA R-02
    @Test fun handlerThreadsAreBounded() {
        val s = start(Recorder()); val before = handlerThreads()
        val idle = List(200) { runCatching { Socket("127.0.0.1", s.port) }.getOrNull() } // extras may be refused
        eventually { handlerThreads() > before }
        assertTrue("handler threads capped", handlerThreads() - before <= 8)
        idle.forEach { it?.close() }
        eventually { runCatching { http(s, "/${s.token}").first == 200 }.getOrDefault(false) }
    }

    // QA R-06, D-06
    @Test fun oversizedBodyIs413AndNotPaired() {
        val rec = Recorder(); val s = start(rec)
        // Headers only, so the server has nothing unread and the 413 is not lost to a reset.
        val line = Socket("127.0.0.1", s.port).use { sock ->
            sock.soTimeout = 3000
            sock.getOutputStream().write("POST /${s.token} HTTP/1.1\r\nHost: x\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 5000\r\n\r\n".toByteArray())
            sock.getInputStream().bufferedReader().readLine()
        }
        assertTrue(line, line.contains(" 413 "))
        // A client that sends the whole 4 MB may see the connection reset instead; either way nothing is paired.
        runCatching { http(s, "/${s.token}", "POST", "code=123456&pad=" + "A".repeat(4_000_000)) }
        assertTrue(rec.calls.isEmpty())
    }

    @Test fun onlyOwnInterfaceAddressesAreTrusted() {
        assertTrue(PairingDiscovery.isOwnAddress(InetAddress.getLoopbackAddress()))
        assertFalse(PairingDiscovery.isOwnAddress(InetAddress.getByName("192.0.2.1")))
    }
}
