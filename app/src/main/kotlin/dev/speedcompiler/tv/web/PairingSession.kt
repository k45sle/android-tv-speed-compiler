package dev.speedcompiler.tv.web

import dev.speedcompiler.tv.engine.Clock
import java.security.MessageDigest
import java.security.SecureRandom

/** Token, expiry and attempt budget for one pairing run. No Android classes. */
class PairingSession(
    private val clock: Clock,
    val token: String = newToken(),
    private val ttlMillis: Long = TTL_MILLIS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) {
    private val startedAt = clock.nowMillis()
    private var attempts = 0

    fun expired() = clock.nowMillis() - startedAt >= ttlMillis

    /** Constant-time comparison of the URL token. */
    fun tokenMatches(candidate: String) =
        MessageDigest.isEqual(token.toByteArray(), candidate.toByteArray())

    /** Spends one attempt; false when expired or the budget is used up (locked until a new session). */
    @Synchronized
    fun tryAttempt(): Boolean {
        if (expired() || attempts >= maxAttempts) return false
        attempts++
        return true
    }

    companion object {
        const val TTL_MILLIS = 10 * 60 * 1000L
        const val MAX_ATTEMPTS = 5

        fun newToken(): String =
            ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Keeps digits only; null unless exactly 6 remain. */
        fun cleanCode(raw: String?): String? = raw?.filter { it in '0'..'9' }?.takeIf { it.length == 6 }
    }
}
