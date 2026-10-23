package dev.speedcompiler.tv.adb

import java.io.File
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun mismatchedCertIsRebuiltFromTheKeyAndCorruptKeyRegenerates() {
        val dir = tmp.newFolder()
        val first = AdbKeys(dir)
        // A crash between the two writes of a regeneration leaves a new key beside an old cert.
        File(dir, "adb.crt").writeBytes(AdbKeys(tmp.newFolder()).certificate.encoded)
        val second = AdbKeys(dir)
        assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded) // identity (and pairing) kept
        assertEquals((first.certificate.publicKey as RSAPublicKey).modulus, (second.certificate.publicKey as RSAPublicKey).modulus)

        File(dir, "adb.key").writeBytes(byteArrayOf(1, 2, 3))
        assertNotEquals(first.privateKey.encoded.toList(), AdbKeys(dir).privateKey.encoded.toList())
    }
}
