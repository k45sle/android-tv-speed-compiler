package dev.speedcompiler.tv.adb

import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** The app's ADB identity: an RSA key and self-signed certificate, generated once and kept in [dir]. */
class AdbKeys(dir: File) {
    val privateKey: PrivateKey
    val certificate: X509Certificate

    init {
        val keyFile = File(dir, "adb.key")
        val certFile = File(dir, "adb.crt")
        val loaded = runCatching {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) to
                parseCertificate(certFile.readBytes())
        }.getOrNull()
        if (loaded != null) {
            privateKey = loaded.first
            certificate = loaded.second
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            privateKey = pair.private
            certificate = parseCertificate(selfSignedCertificate(pair.public.encoded, pair.private))
            dir.mkdirs()
            writeAtomically(certFile, certificate.encoded)
            writeAtomically(keyFile, privateKey.encoded)
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(file)) { "could not save ${file.name}" }
    }
}

private fun parseCertificate(der: ByteArray) =
    CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

/** Minimal DER X.509 v3 certificate (adbd only checks the public key); avoids a Bouncy Castle compile dependency. */
internal fun selfSignedCertificate(publicKeyDer: ByteArray, key: PrivateKey): ByteArray {
    val sha256WithRsa = der(0x30, der(0x06, hex("2A864886F70D01010B")), byteArrayOf(0x05, 0x00))
    val name = der(0x30, der(0x31, der(0x30, der(0x06, hex("550403")), der(0x0C, "speed-compiler".toByteArray()))))
    val validity = der(0x30, der(0x17, "250101000000Z".toByteArray()), der(0x17, "491231235959Z".toByteArray()))
    val serial = der(0x02, BigInteger(63, SecureRandom()).add(BigInteger.ONE).toByteArray())
    val tbs = der(0x30, der(0xA0, der(0x02, byteArrayOf(2))), serial, sha256WithRsa, name, validity, name, publicKeyDer)
    val sig = Signature.getInstance("SHA256withRSA").run { initSign(key); update(tbs); sign() }
    return der(0x30, tbs, sha256WithRsa, der(0x03, byteArrayOf(0) + sig))
}

private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
    val body = parts.fold(ByteArray(0)) { acc, p -> acc + p }
    val out = ByteArrayOutputStream()
    out.write(tag)
    val n = body.size
    when {
        n < 0x80 -> out.write(n)
        n < 0x100 -> { out.write(0x81); out.write(n) }
        else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
    }
    out.write(body)
    return out.toByteArray()
}

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
