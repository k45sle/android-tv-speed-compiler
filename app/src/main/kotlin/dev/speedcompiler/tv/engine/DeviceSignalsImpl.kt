package dev.speedcompiler.tv.engine

import android.util.Log
import dev.speedcompiler.tv.adb.AdbShell
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DeviceSignals"
private const val DUMPSYS_TIMEOUT_MILLIS = 5_000L
private val wakefulnessLine = Regex("""(?m)^\s*mWakefulness=(\w+)\s*$""")
private val topResumedLine = Regex("""topResumedActivity=ActivityRecord\{\S+ u\d+ ([\w.]+)/""")

// First line: the screensaver setting; then the top resumed activity line(s).
internal const val TOP_RESUMED_COMMAND = "dumpsys activity activities | grep topResumedActivity="
internal const val SCREENSAVER_FRONT_COMMAND =
    "settings get secure screensaver_components; $TOP_RESUMED_COMMAND"

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

/** The package of the first `topResumedActivity=` line, or null. */
fun topResumedPackage(lines: List<String>): String? = lines.firstNotNullOfOrNull { topResumedLine.find(it)?.groupValues?.get(1) }

/**
 * True when the top resumed activity belongs to a package listed in `screensaver_components`.
 * Some launchers start the screensaver as a normal activity with a bright wake lock, so Android stays Awake.
 */
fun parseScreensaverInFront(output: String): Boolean {
    val lines = output.lines()
    val packages = lines.firstOrNull().orEmpty().split(',')
        .map { it.trim().substringBefore('/') }
        .filter { it.isNotEmpty() && it != "null" }
        .toSet()
    val top = topResumedPackage(lines.drop(1))
    return top != null && top in packages
}

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
        val wake = parseWakefulness(result.output) ?: return unknown("no single mWakefulness line")
        return if (wake == Wakefulness.Awake && screensaverInFront()) Wakefulness.Dozing else wake
    }

    /** False on any failure, so an unreadable state keeps Awake. */
    private suspend fun screensaverInFront(): Boolean = try {
        withTimeoutOrNull(DUMPSYS_TIMEOUT_MILLIS) { shell.run(SCREENSAVER_FRONT_COMMAND) }
            ?.takeIf { it.exitCode == 0 }
            ?.let { parseScreensaverInFront(it.output) } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "screensaver check failed: ${e.javaClass.simpleName}")
        false
    }

    private fun unknown(reason: String): Wakefulness? {
        Log.w(TAG, "screen state unknown: $reason")
        return null
    }
}
