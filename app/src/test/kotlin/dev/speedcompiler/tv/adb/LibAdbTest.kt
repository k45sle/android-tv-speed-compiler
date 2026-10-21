package dev.speedcompiler.tv.adb

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class LibAdbTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun markerPresent() = assertEquals(ShellResult(0, "uid=2000(shell)\n"), parseShellOutput("uid=2000(shell)\n__rc=0\n"))

    @Test fun markerMissingIsFailure() = assertEquals(ShellResult(MISSING_RC, "partial out"), parseShellOutput("partial out"))

    @Test fun multiLineOutput() = assertEquals(ShellResult(0, "a\nb\n\nc\n"), parseShellOutput("a\nb\n\nc\n__rc=0\n"))

    @Test fun nonZeroRc() = assertEquals(ShellResult(127, "sh: nope: not found\n"), parseShellOutput("sh: nope: not found\n__rc=127\n"))

    @Test fun outputContainingMarkerKeepsIt() =
        assertEquals(ShellResult(1, "Success\n__rc=0\necho __rc=5\n"), parseShellOutput("Success\n__rc=0\necho __rc=5\n__rc=1\n"))

    @Test fun markerOnlyMidOutputIsMissing() =
        assertEquals(MISSING_RC, parseShellOutput("__rc=0\nmore output after\n").exitCode)

    @Test fun markerAfterUnterminatedLineAndCrlf() = assertEquals(ShellResult(0, "no newline"), parseShellOutput("no newline__rc=0\r\n"))

    @Test fun errorMapping() {
        assertTrue(mapAdbError(AdbPairingRequiredException("x")) is AdbFailure.NotPaired)
        assertTrue(mapAdbError(AdbAuthenticationFailedException()) is AdbFailure.NotAuthorized)
        assertTrue(mapAdbError(SocketTimeoutException()) is AdbFailure.Timeout)
        assertTrue(mapAdbError(ConnectException("Connection refused")) is AdbFailure.DebuggingOff)
        val streamRejected = ConnectException("Stream open actively rejected by remote peer.")
        assertSame(streamRejected, mapAdbError(streamRejected))
        val other = IOException("boom")
        assertSame(other, mapAdbError(other))
        val typed = AdbFailure.Timeout()
        assertSame(typed, mapAdbError(typed))
    }

    @Test fun keysAreGeneratedOnceThenReused() {
        val first = AdbKeys(tmp.root)
        first.certificate.verify(first.certificate.publicKey) // well-formed and self-signed
        val second = AdbKeys(tmp.root)
        assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
    }
}
