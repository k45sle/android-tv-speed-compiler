package io.github.muntashirakon.adb

import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PubkeyEncodingTest {
    @Test fun roundTripsThroughLibadbsDecoder() {
        repeat(3) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pub = pair.public as RSAPublicKey
            val b64 = adbPublicKeyBase64(pub)
            assertEquals(700, b64.length)
            assertFalse(b64.contains(' '))
            assertFalse(b64.contains('\n'))
            val decoded = AndroidPubkey.decode(java.util.Base64.getDecoder().decode(b64))
            assertEquals(pub.modulus, decoded.modulus)
            assertEquals(pub.publicExponent, decoded.publicExponent)
        }
    }
}
