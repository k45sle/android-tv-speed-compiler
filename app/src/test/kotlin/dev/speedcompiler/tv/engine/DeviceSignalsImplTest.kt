package dev.speedcompiler.tv.engine

import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.adb.FakeAdbShell
import dev.speedcompiler.tv.adb.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

// Synthetic dumpsys snippet, not captured from a device.
private fun power(wakefulness: String) = """
    Power Manager State:
      mWakefulness=$wakefulness
      mWakefulnessChanging=false
      mScreenOn=true
""".trimIndent()

@RunWith(RobolectricTestRunner::class)
// The app's conscrypt-android has no JVM native lib, so Robolectric must not install Conscrypt.
@ConscryptMode(ConscryptMode.Mode.OFF)
class DeviceSignalsImplTest {
    @Test
    fun parsesEachWakefulnessAndMapsDreamingToDozing() {
        val cases = mapOf(
            "Awake" to Wakefulness.Awake,
            "Dozing" to Wakefulness.Dozing,
            "Dreaming" to Wakefulness.Dozing,
            "Asleep" to Wakefulness.Asleep,
            "Bogus" to null,
        )
        for ((word, expected) in cases) assertEquals(word, expected, parseWakefulness(power(word)))
    }

    @Test
    fun missingOrConflictingLineIsUnknown() {
        assertNull(parseWakefulness("Power Manager State:\n  mScreenOn=true"))
        assertNull(parseWakefulness("  mWakefulness=Awake\n  mWakefulness=Asleep"))
        // mWakefulnessChanging must not be read as the wakefulness line.
        assertNull(parseWakefulness("  mWakefulnessChanging=true"))
    }

    @Test
    fun shellReadsWakefulnessAndMusicFromCallbackAndShell() = runBlocking {
        val shell = FakeAdbShell(mapOf("dumpsys power" to ShellResult(0, power("Dozing"))))
        val signals = DeviceSignalsImpl(shell, isMusicActive = { true })
        assertEquals(Wakefulness.Dozing, signals.wakefulness())
        assertEquals(true, signals.musicActive())
        assertEquals(listOf("dumpsys power"), shell.commands)
    }

    @Test
    fun failedOrThrowingShellIsUnknown() = runBlocking {
        val failing = FakeAdbShell(mapOf("dumpsys power" to ShellResult(1, power("Asleep"))))
        assertNull(DeviceSignalsImpl(failing, { false }).wakefulness())
        val throwing = object : AdbShell {
            override suspend fun run(command: String): ShellResult = throw java.io.IOException("adb gone")
        }
        assertNull(DeviceSignalsImpl(throwing, { false }).wakefulness())
    }

    // Synthetic, shaped like Android 14 output.
    private fun front(components: String, top: String) =
        "$components\n  topResumedActivity=ActivityRecord{1a2b3c u0 $top t42}"

    @Test
    fun screensaverInFrontMatchesTheTopPackageAgainstTheSetting() {
        val aerial = "com.neilturner.aerialviews/.ui.screensaver.DreamActivity"
        assertEquals(true, parseScreensaverInFront(front(aerial, "com.neilturner.aerialviews/.ui.screensaver.TestActivity")))
        assertEquals(true, parseScreensaverInFront(front("a.b/.D,$aerial", "com.neilturner.aerialviews/.X")))
        assertEquals(false, parseScreensaverInFront(front(aerial, "com.nuvio.tv/.MainActivity")))
        assertEquals(false, parseScreensaverInFront(front("null", "null/.X")))
        assertEquals(false, parseScreensaverInFront(aerial))
    }

    @Test
    fun awakeWithScreensaverInFrontCountsAsDozing() = runBlocking {
        val aerial = "com.neilturner.aerialviews/.ui.screensaver.DreamActivity"
        val shell = FakeAdbShell(mapOf(
            "dumpsys power" to ShellResult(0, power("Awake")),
            SCREENSAVER_FRONT_COMMAND to ShellResult(0, front(aerial, "com.neilturner.aerialviews/.ui.screensaver.TestActivity")),
        ))
        assertEquals(Wakefulness.Dozing, DeviceSignalsImpl(shell, { false }).wakefulness())
        val failing = FakeAdbShell(mapOf(
            "dumpsys power" to ShellResult(0, power("Awake")),
            SCREENSAVER_FRONT_COMMAND to ShellResult(1, ""),
        ))
        assertEquals(Wakefulness.Awake, DeviceSignalsImpl(failing, { false }).wakefulness())
    }
}
