package dev.speedcompiler.tv.adb

import kotlinx.coroutines.CancellationException

/** [block]'s value, or null when it throws anything but cancellation (Kotlin's runCatching would swallow that too). */
inline fun <T> tryOrNull(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
