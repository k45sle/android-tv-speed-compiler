package io.github.muntashirakon.adb

import dev.speedcompiler.tv.adb.adbPublicKeyBase64
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PubkeyEncodingTest {
    @Test fun matchesLibadbsOwnEncoding() {
        repeat(5) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pub = pair.public as RSAPublicKey
            val expected = String(AndroidPubkey.encodeWithName(pub, "x"), Charsets.UTF_8).substringBefore(' ')
            assertEquals(expected, adbPublicKeyBase64(pub))
        }
    }
}
