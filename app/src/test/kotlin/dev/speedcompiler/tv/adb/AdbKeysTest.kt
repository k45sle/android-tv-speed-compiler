package dev.speedcompiler.tv.adb

import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        val logs = mutableListOf<String>()
        assertNotEquals(first.privateKey.encoded.toList(), AdbKeys(dir, log = { logs += it }).privateKey.encoded.toList())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "adb.key.bad").readBytes()) // kept, not overwritten
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("kept as adb.key.bad")) // never claim a copy that isn't there
    }

    @Test fun badLoadsKeepUpToThreeCopies() {
        val dir = tmp.newFolder()
        File(dir, "adb.key").writeBytes(byteArrayOf(1, 2, 3))
        val first = mutableListOf<String>()
        AdbKeys(dir, log = { first += it })
        File(dir, "adb.key").writeBytes(byteArrayOf(4, 5, 6))
        val second = mutableListOf<String>()
        AdbKeys(dir, log = { second += it })
        // The first bad key may be the one the TV still trusts: a later one must not overwrite it.
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "adb.key.bad").readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), File(dir, "adb.key.bad.1").readBytes())
        assertEquals(1, first.size)
        assertTrue(first.single().contains("kept as adb.key.bad"))
        assertTrue(second.single().contains("kept as adb.key.bad.1"))

        File(dir, "adb.key").writeBytes(byteArrayOf(7, 8, 9))
        AdbKeys(dir)
        assertArrayEquals(byteArrayOf(7, 8, 9), File(dir, "adb.key.bad.2").readBytes())

        // All three names taken: the new unreadable file is dropped, the kept copies stay as they were.
        File(dir, "adb.key").writeBytes(byteArrayOf(10, 11, 12))
        val logs = mutableListOf<String>()
        AdbKeys(dir, log = { logs += it })
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, "adb.key.bad").readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), File(dir, "adb.key.bad.1").readBytes())
        assertArrayEquals(byteArrayOf(7, 8, 9), File(dir, "adb.key.bad.2").readBytes())
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("copy limit"))
    }

    @Test fun unreadableKeyFileThrowsAndLeavesTheFileAlone() {
        val dir = tmp.newFolder()
        val keyFile = File(dir, "adb.key")
        // A directory here reads like a storage error: exists, but readBytes throws.
        assertTrue(keyFile.mkdir())
        assertThrows(IOException::class.java) { AdbKeys(dir) }
        // The identity may still be fine on disk: no bad copy, no new key, no cert.
        assertTrue(keyFile.isDirectory)
        assertFalse(File(dir, "adb.key.bad").exists())
        assertFalse(File(dir, "adb.key.bad.1").exists())
        assertFalse(File(dir, "adb.crt").exists())
        assertEquals(emptyList<String>(), keyFile.list()!!.toList())
    }

    @Test fun unreadableCertFileThrowsAndKeepsTheKey() {
        val dir = tmp.newFolder()
        val first = AdbKeys(dir)
        val saved = File(dir, "adb.key").readBytes()
        val certFile = File(dir, "adb.crt")
        assertTrue(certFile.delete())
        assertTrue(certFile.mkdir())
        assertThrows(IOException::class.java) { AdbKeys(dir) }
        assertArrayEquals(saved, File(dir, "adb.key").readBytes()) // not regenerated
        assertArrayEquals(first.privateKey.encoded, saved)
        assertTrue(certFile.isDirectory)
    }

    @Test fun savedKeyIsKeptWhenKeyFactoryDropsTheCrtFields() {
        val dir = tmp.newFolder()
        val first = AdbKeys(dir)
        val saved = File(dir, "adb.key").readBytes()
        // What Android 12's KeyFactory does with the saved PKCS#8 key: a plain RSAPrivateKey, no CRT fields.
        val android12: (ByteArray) -> PrivateKey = { bytes ->
            val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes)) as RSAPrivateCrtKey
            KeyFactory.getInstance("RSA").generatePrivate(RSAPrivateKeySpec(k.modulus, k.privateExponent))
        }
        val logs = mutableListOf<String>()
        val second = AdbKeys(dir, log = { logs += it }, decode = android12)
        assertTrue(second.privateKey is RSAPrivateCrtKey)
        assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded)
        assertArrayEquals(saved, File(dir, "adb.key").readBytes())
        assertFalse(File(dir, "adb.key.bad").exists())
        assertEquals(emptyList<String>(), logs)
    }

    @Test fun aFailingDecodeOnAValidKeyKeepsIt() {
        val dir = tmp.newFolder()
        val first = AdbKeys(dir)
        // A KeyFactory failure on well-formed bytes is not corruption: the saved identity must stay.
        val logs = mutableListOf<String>()
        val second = AdbKeys(dir, log = { logs += it }, decode = { throw IllegalStateException("KeyFactory failed") })
        assertEquals((first.privateKey as RSAPrivateCrtKey).modulus, (second.privateKey as RSAPrivateCrtKey).modulus)
        assertFalse(File(dir, "adb.key.bad").exists())
        assertEquals(emptyList<String>(), logs)
    }

    @Test fun crtSpecReadsEveryFieldOfAPkcs8Key() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAPrivateCrtKey
        val spec = rsaPrivateCrtKeySpec(key.encoded)
        assertEquals(key.modulus, spec.modulus)
        assertEquals(key.publicExponent, spec.publicExponent)
        assertEquals(key.privateExponent, spec.privateExponent)
        assertEquals(key.primeP, spec.primeP)
        assertEquals(key.primeQ, spec.primeQ)
        assertEquals(key.primeExponentP, spec.primeExponentP)
        assertEquals(key.primeExponentQ, spec.primeExponentQ)
        assertEquals(key.crtCoefficient, spec.crtCoefficient)
        assertEquals(null, loadRsaKey(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)))
    }
}
