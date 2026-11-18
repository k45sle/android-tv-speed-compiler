package dev.speedcompiler.tv.adb

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec

/** The app's ADB identity: an RSA key and self-signed certificate, generated once and kept in [dir]. */
class AdbKeys(dir: File, log: (String) -> Unit = {}, decode: (ByteArray) -> PrivateKey = ::decodePkcs8) {
    val privateKey: PrivateKey
    val certificate: X509Certificate

    init {
        val keyFile = File(dir, "adb.key")
        val certFile = File(dir, "adb.crt")
        dir.mkdirs()
        // A read failure (EIO, storage not ready at boot) says nothing about the content: let the IOException
        // propagate, so the caller's lazy retries instead of replacing a key that is still fine on disk.
        val keyBytes = if (keyFile.exists()) keyFile.readBytes() else null
        val key = keyBytes?.let { loadRsaKey(it, decode) }
        if (key is RSAPrivateCrtKey) {
            // The key is the identity the TV trusts. A missing, corrupt or mismatched cert (a crash between the two writes)
            // is rebuilt from the key's own public half, so pairing survives.
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(key.modulus, key.publicExponent))
            val certBytes = if (certFile.exists()) certFile.readBytes() else null
            val saved = certBytes?.let { runCatching { parseCertificate(it) }.getOrNull() }
            privateKey = key
            certificate = saved?.takeIf { (it.publicKey as? RSAPublicKey)?.modulus == key.modulus }
                ?: parseCertificate(selfSignedCertificate(publicKey.encoded, key)).also { writeAtomically(certFile, it.encoded) }
        } else {
            // No usable key (first run, or the file is corrupt): a new identity, so the TV has to be paired again.
            if (keyFile.exists()) {
                // Never drop an identity silently: keep the unreadable file for diagnosis and say so in the log.
                // Earlier copies (likely the key the TV still trusts) are never overwritten.
                val what = keepBadCopy(keyFile)
                log("adb.key unreadable (${keyBytes?.size ?: -1} bytes), $what; the TV will ask to allow the new key")
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            privateKey = pair.private
            certificate = parseCertificate(selfSignedCertificate(pair.public.encoded, pair.private))
            writeAtomically(keyFile, privateKey.encoded) // key first: a crash leaves a key and a stale cert, which the load above repairs
            writeAtomically(certFile, certificate.encoded)
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() } // on disk before the rename, so a power cut can't leave an empty key
        check(tmp.renameTo(file)) { "could not save ${file.name}" }
    }

    /**
     * Keeps the unreadable copy under the first free of `<name>.bad`, `<name>.bad.1` and `<name>.bad.2`, so up to
     * three earlier copies (likely the key the TV still trusts) survive. With all three taken, [file] is deleted
     * instead. Returns what the log should say about the copy.
     */
    private fun keepBadCopy(file: File): String {
        val names = listOf(".bad", ".bad.1", ".bad.2")
        val name = names.firstOrNull { !File(file.parentFile, file.name + it).exists() }
        if (name == null) {
            file.delete()
            return "the copy limit of ${names.size} is reached, so it was deleted"
        }
        val bad = File(file.parentFile, file.name + name)
        if (file.renameTo(bad)) return "kept as ${bad.name}"
        // The move can fail (a locked file): a copy still keeps the evidence.
        return try {
            file.copyTo(bad, overwrite = false)
            "kept as ${bad.name}"
        } catch (e: Exception) {
            "couldn't keep a copy"
        }
    }
}

private fun decodePkcs8(bytes: ByteArray): PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))

/**
 * [pkcs8] as an [RSAPrivateCrtKey], or null if it isn't a usable RSA key. Android 12's KeyFactory returns a plain
 * RSAPrivateKey for a PKCS#8 key (it drops the CRT fields), so then the key is rebuilt from the fields in the file.
 */
internal fun loadRsaKey(pkcs8: ByteArray, decode: (ByteArray) -> PrivateKey = ::decodePkcs8): RSAPrivateCrtKey? {
    val decoded = runCatching { decode(pkcs8) }.getOrNull() as? RSAPrivateKey ?: return null
    if (decoded is RSAPrivateCrtKey) return decoded
    val rebuilt = runCatching { KeyFactory.getInstance("RSA").generatePrivate(rsaPrivateCrtKeySpec(pkcs8)) }.getOrNull() as? RSAPrivateCrtKey
    return rebuilt?.takeIf { it.modulus == decoded.modulus && it.privateExponent == decoded.privateExponent }
}

/** The RSA fields of a PKCS#8 PrivateKeyInfo: SEQUENCE { version, algorithm, OCTET STRING { RSAPrivateKey } }. */
internal fun rsaPrivateCrtKeySpec(pkcs8: ByteArray): RSAPrivateCrtKeySpec {
    val info = DerReader(DerReader(pkcs8).next(0x30))
    info.next(0x02) // version
    info.next(0x30) // AlgorithmIdentifier
    val rsa = DerReader(DerReader(info.next(0x04)).next(0x30))
    rsa.next(0x02) // version
    val v = List(8) { BigInteger(rsa.next(0x02)) } // n, e, d, p, q, dP, dQ, qInv
    return RSAPrivateCrtKeySpec(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
}

/** Just enough DER for [rsaPrivateCrtKeySpec]: [next] returns the contents of the next element, which must have [tag]. */
private class DerReader(private val bytes: ByteArray) {
    private var pos = 0

    fun next(tag: Int): ByteArray {
        check((bytes[pos++].toInt() and 0xFF) == tag) { "unexpected DER tag" }
        var len = bytes[pos++].toInt() and 0xFF
        if (len >= 0x80) {
            val n = len and 0x7F
            check(n in 1..3) { "bad DER length" }
            len = 0
            repeat(n) { len = (len shl 8) or (bytes[pos++].toInt() and 0xFF) }
        }
        check(len <= bytes.size - pos) { "truncated DER" }
        return bytes.copyOfRange(pos, pos + len).also { pos += len }
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
