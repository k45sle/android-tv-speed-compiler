package dev.speedcompiler.tv.adb

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val K = "QUFBQUtFWQ+/="

// Android 12 shape: the keystore value is binary XML, so the key sits after a NUL-ish prefix and before more bytes.
private val DUMP = "ADB MANAGER STATE (dumpsys adb):\n{\n  debugging_manager={\n    connected_to_adb=true\n" +
    "    last_key_received=AB:CD\n    user_keys=OTHER host@pc\nOTHER2 tv\n\n" +
    "    keystore=ABX\u0001keyStore\u0002adbKey key=" + K + " speed-compiler\u0003lastConnection\n  }\n}\n"

private class ThrowingShell : AdbShell {
    override suspend fun run(command: String): ShellResult = throw IOException("no adb")
}

class KeyCheckTest {
    @Test fun parseFindsTheKeystoreCopy() {
        val places = parseDumpsysAdb(DUMP, K)
        assertEquals(KeyPlaces(inUserKeys = false, inKeystore = true), places)
    }

    @Test fun parseFindsTheUserKeysCopy() {
        val dump = DUMP.replace("OTHER host@pc", "$K speed-compiler")
        val places = parseDumpsysAdb(dump, K)
        assertEquals(true, places?.inUserKeys)
    }

    @Test fun parseWithoutUserKeysFieldIsNotInUserKeys() {
        val dump = DUMP.replace("    user_keys=OTHER host@pc\nOTHER2 tv\n", "")
        assertEquals(false, parseDumpsysAdb(dump, K)?.inUserKeys)
    }

    @Test fun parseWithoutDebuggingManagerIsNull() {
        assertNull(parseDumpsysAdb("Can't find service: adb", K))
    }

    @Test fun classifyRefusedByPlaces() {
        assertEquals(KeyCheck.Unknown, classifyRefused(null))
        assertEquals(KeyCheck.AllowedOnce, classifyRefused(KeyPlaces(false, false)))
        // In the file adbd trusts: the save landed, just after the probes.
        assertEquals(KeyCheck.Saved, classifyRefused(KeyPlaces(true, false)))
        // Only in the framework's list: the file write failed, so adbd will ask again.
        assertEquals(KeyCheck.NotSaved, classifyRefused(KeyPlaces(false, true)))
        assertEquals(KeyCheck.Saved, classifyRefused(KeyPlaces(true, true)))
    }

    @Test fun classifyRefusedWithoutPlacesIsUnknown() {
        assertEquals(KeyCheck.Unknown, classifyRefused(null))
    }

    @Test fun trustedAtOnceIsSavedWithoutShell() = runTest {
        val pauses = mutableListOf<Long>()
        val shell = FakeAdbShell()
        val check = KeyChecker({ true }, shell, { K }, pause = { pauses += it })
        assertEquals(KeyCheck.Saved, check.check())
        assertEquals(1, pauses.size)
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun trustedOnTheSecondTryIsSaved() = runTest {
        val answers = ArrayDeque(listOf(false, true))
        val shell = FakeAdbShell()
        val check = KeyChecker({ answers.removeFirst() }, shell, { K }, pause = {})
        assertEquals(KeyCheck.Saved, check.check())
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun refusedButListedInTheKeystoreIsNotSaved() = runTest {
        val shell = FakeAdbShell(mapOf("dumpsys adb" to ShellResult(0, DUMP)))
        val check = KeyChecker({ false }, shell, { K }, pause = {})
        assertEquals(KeyCheck.NotSaved, check.check())
        assertEquals(listOf("dumpsys adb"), shell.commands)
    }

    @Test fun refusedAndNowhereListedIsAllowedOnce() = runTest {
        val dump = "ADB MANAGER STATE (dumpsys adb):\n{\n  debugging_manager={\n    connected_to_adb=true\n  }\n}\n"
        val shell = FakeAdbShell(mapOf("dumpsys adb" to ShellResult(0, dump)))
        val check = KeyChecker({ false }, shell, { K }, pause = {})
        assertEquals(KeyCheck.AllowedOnce, check.check())
    }

    @Test fun unknownAnswerStopsBeforeTheShell() = runTest {
        val shell = FakeAdbShell()
        val check = KeyChecker({ null }, shell, { K }, pause = {})
        assertEquals(KeyCheck.Unknown, check.check())
        assertTrue(shell.commands.isEmpty())
    }

    @Test fun keyInUserKeysIsSaved() = runTest {
        val dump = DUMP.replace("OTHER host@pc", "$K speed-compiler")
        val shell = FakeAdbShell(mapOf("dumpsys adb" to ShellResult(0, dump)))
        val check = KeyChecker({ false }, shell, { K }, pause = {})
        assertEquals(KeyCheck.Saved, check.check())
        assertEquals(listOf("dumpsys adb"), shell.commands)
    }

    @Test fun refusedThenErrorStillClassifies() = runTest {
        // The first try was refused, the second got no answer: the refusal still asks where the TV lists the key.
        // DUMP lists the key only in the keystore, the failed-write case, so this still means NotSaved.
        val shell = FakeAdbShell(mapOf("dumpsys adb" to ShellResult(0, DUMP)))
        val answers = ArrayDeque<Boolean?>(listOf(false, null))
        val check = KeyChecker({ answers.removeFirst() }, shell, { K }, pause = {})
        assertEquals(KeyCheck.NotSaved, check.check())
        assertEquals(listOf("dumpsys adb"), shell.commands)
    }

    @Test fun refusedWithAFailingShellIsUnknown() = runTest {
        val check = KeyChecker({ false }, ThrowingShell(), { K }, pause = {})
        assertEquals(KeyCheck.Unknown, check.check())
    }

    @Test fun afterPromptedCheckKeepsWhatARestartShowed() {
        for (found in KeyCheck.entries) {
            assertEquals(KeyCheck.ForgetsAtRestart, afterPromptedCheck(KeyCheck.ForgetsAtRestart, found))
        }
        assertEquals(KeyCheck.SavedAgain, afterPromptedCheck(KeyCheck.Forgotten, KeyCheck.Saved))
        assertEquals(KeyCheck.SavedAgain, afterPromptedCheck(KeyCheck.SavedAgain, KeyCheck.Saved))
        assertEquals(KeyCheck.Forgotten, afterPromptedCheck(KeyCheck.Forgotten, KeyCheck.Unknown))
        assertEquals(KeyCheck.SavedAgain, afterPromptedCheck(KeyCheck.SavedAgain, KeyCheck.Unknown))
        assertEquals(KeyCheck.AllowedOnce, afterPromptedCheck(KeyCheck.Forgotten, KeyCheck.AllowedOnce))
        assertEquals(KeyCheck.NotSaved, afterPromptedCheck(KeyCheck.Forgotten, KeyCheck.NotSaved))
        assertEquals(KeyCheck.Unknown, afterPromptedCheck(KeyCheck.NotSaved, KeyCheck.Unknown))
        assertEquals(KeyCheck.Saved, afterPromptedCheck(KeyCheck.Unknown, KeyCheck.Saved))
        assertEquals(KeyCheck.NotSaved, afterPromptedCheck(KeyCheck.Saved, KeyCheck.NotSaved))
    }

    @Test fun afterQuietRefusalMovesSavedKeysOnly() {
        assertEquals(KeyCheck.Forgotten, afterQuietRefusal(KeyCheck.Saved))
        assertEquals(KeyCheck.ForgetsAtRestart, afterQuietRefusal(KeyCheck.SavedAgain))
        assertEquals(KeyCheck.ForgetsAtRestart, afterQuietRefusal(KeyCheck.ForgetsAtRestart))
        assertEquals(KeyCheck.Forgotten, afterQuietRefusal(KeyCheck.Forgotten))
        assertEquals(KeyCheck.Unknown, afterQuietRefusal(KeyCheck.Unknown))
        assertEquals(KeyCheck.AllowedOnce, afterQuietRefusal(KeyCheck.AllowedOnce))
        assertEquals(KeyCheck.NotSaved, afterQuietRefusal(KeyCheck.NotSaved))
    }

    @Test fun tryOrNullRethrowsCancellation() {
        assertNull(tryOrNull { throw IOException("no adb") })
        assertEquals(5, tryOrNull { 5 })
        var cancelled = false
        try {
            tryOrNull { throw kotlinx.coroutines.CancellationException("x") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
