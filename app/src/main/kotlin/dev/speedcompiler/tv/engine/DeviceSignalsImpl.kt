package dev.speedcompiler.tv.engine

import android.util.Log
import dev.speedcompiler.tv.adb.AdbShell
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DeviceSignals"
private const val DUMPSYS_TIMEOUT_MILLIS = 5_000L
private val wakefulnessLine = Regex("""(?m)^\s*mWakefulness=(\w+)\s*$""")

/**
 * Reads the single top-level `mWakefulness=` line from `dumpsys power`.
 * Dreaming is the screensaver, so it maps to Dozing. Returns null when the line is absent or conflicting.
 */
fun parseWakefulness(dumpsys: String): Wakefulness? =
    wakefulnessLine.findAll(dumpsys)
        .map { match ->
            when (match.groupValues[1]) {
                "Awake" -> Wakefulness.Awake
                "Dozing", "Dreaming" -> Wakefulness.Dozing
                "Asleep" -> Wakefulness.Asleep
                else -> null
            }
        }
        .toSet()
        .singleOrNull()

class DeviceSignalsImpl(
    private val shell: AdbShell,
    private val isMusicActive: () -> Boolean,
) : GateSignals {
    override suspend fun musicActive(): Boolean = isMusicActive()

    /** Null (Unknown) on any failure, timeout or unparseable output. Logs the reason. */
    override suspend fun wakefulness(): Wakefulness? {
        val result = try {
            withTimeoutOrNull(DUMPSYS_TIMEOUT_MILLIS) { shell.run("dumpsys power") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "dumpsys power failed: ${e.javaClass.simpleName}")
            return null
        }
        if (result == null) return unknown("dumpsys power timed out")
        if (result.exitCode != 0) return unknown("dumpsys power exited ${result.exitCode}")
        return parseWakefulness(result.output) ?: unknown("no single mWakefulness line")
    }

    private fun unknown(reason: String): Wakefulness? {
        Log.w(TAG, "screen state unknown: $reason")
        return null
    }
}
