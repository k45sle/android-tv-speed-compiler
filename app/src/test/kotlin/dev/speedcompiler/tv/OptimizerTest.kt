package dev.speedcompiler.tv

import dev.speedcompiler.tv.adb.AdbFailure
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.adb.ShellResult
import dev.speedcompiler.tv.engine.FakeClock
import dev.speedcompiler.tv.engine.OptimizerImpl
import dev.speedcompiler.tv.engine.compileCommand
import dev.speedcompiler.tv.engine.parseCompilerFilter
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.EventDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class OptimizerTest {
    private val v1 = javaClass.getResource("/fixtures/compilation.txt")!!.readText()
    private val art = javaClass.getResource("/fixtures/pm-art-dump.txt")!!.readText()

    private class Events : EventDao {
        val rows = mutableListOf<Event>()
        override fun recent(limit: Int): Flow<List<Event>> = emptyFlow()
        override suspend fun insert(event: Event) { rows += event }
    }

    private class Shell(
        val compile: String = "Success\n__rc=0\n",
        val art: String = "",
        val dexopt: String = "",
        val compileDelay: Long = 0,
        val compileFails: Throwable? = null,
        /** Answers the first dexopt dump, letting a test finish a compile while that read is in flight. */
        val firstDexopt: CompletableDeferred<String>? = null,
    ) : AdbShell {
        var compiles = 0
        var artDumps = 0
        var dexoptDumps = 0
        override suspend fun run(command: String): ShellResult = when {
            command.startsWith("cmd package compile") -> {
                compiles++
                compileFails?.let { throw it }
                delay(compileDelay)
                ShellResult(0, compile)
            }
            command.startsWith("pm art dump") -> {
                artDumps++
                ShellResult(0, art)
            }
            else -> {
                dexoptDumps++
                ShellResult(0, if (dexoptDumps == 1 && firstDexopt != null) firstDexopt.await() else dexopt)
            }
        }
    }

    private fun impl(shell: AdbShell, ev: Events, timeout: Long = 600_000, sdk: Int = 34) =
        OptimizerImpl(shell, ev, FakeClock(5), timeout, sdk)

    @Test fun verifiedViaArtDump() = runTest {
        val ev = Events()
        val r = impl(Shell(art = art), ev).optimize("com.nuvio.tv")
        assertTrue(r.ok)
        assertEquals("Optimized (checked)", r.detail)
        assertEquals("optimize_verified", ev.rows.single().kind)
    }

    @Test fun verifiedViaDexoptFallback() = runTest {
        val r = impl(Shell(dexopt = v1), Events()).optimize("com.example.stream")
        assertEquals("Optimized (checked)", r.detail)
    }

    @Test fun unverifiedWhenTvDoesNotReport() = runTest {
        val ev = Events()
        val r = impl(Shell(art = "Unknown command", dexopt = "nothing"), ev).optimize("com.nuvio.tv")
        assertTrue(r.ok)
        assertEquals("Optimized (this TV doesn't report the result)", r.detail)
        assertEquals("optimize_unverified", ev.rows.single().kind)
    }

    @Test fun failedCompileAndWrongFilterAndBadId() = runTest {
        val ev = Events()
        assertFalse(impl(Shell(compile = "Failure\n__rc=1\n"), ev).optimize("com.nuvio.tv").ok)
        assertFalse(impl(Shell(dexopt = v1), ev).optimize("com.example.other").ok)
        val shell = Shell()
        assertFalse(impl(shell, ev).optimize("com.x; rm -rf /").ok)
        assertEquals(0, shell.compiles)
        assertEquals(3, ev.rows.size)
        assertTrue(ev.rows.all { it.kind == "optimize_failed" })
    }

    @Test fun exactPackageMatching() {
        assertEquals("speed", parseCompilerFilter(art, "com.nuvio.tv"))
        assertEquals("verify", parseCompilerFilter(art, "com.nuvio.tv.test"))
        assertNull(parseCompilerFilter(art, "com.nuvio"))
        assertEquals("speed", parseCompilerFilter(v1, "com.example.stream"))
        assertNull(parseCompilerFilter("[com.nuvio.tv.test]\n  arm: [status=speed]\n", "com.nuvio.tv"))
    }

    @Test fun rejectsBadIdInCommand() {
        assertEquals("cmd package compile -m speed -f com.a_b", compileCommand("com.a_b"))
        assertTrue(runCatching { compileCommand("a b") }.isFailure)
    }

    @Test fun timesOut() = runTest {
        val ev = Events()
        val r = impl(Shell(compileDelay = 1_000), ev, timeout = 100).optimize("com.nuvio.tv")
        assertFalse(r.ok)
        assertTrue(r.detail.startsWith("Didn't finish"))
        assertEquals(100, testScheduler.currentTime)
        assertEquals("optimize_failed", ev.rows.single().kind)
    }

    @Test fun notPairedAndNotAuthorizedAreTerminalWithTheFix() = runTest {
        for ((failure, fix) in listOf(AdbFailure.NotPaired() to "Pair again", AdbFailure.NotAuthorized() to "Accept the debugging prompt")) {
            val r = impl(Shell(compileFails = failure), Events()).optimize("com.nuvio.tv")
            assertFalse(r.ok)
            assertFalse(r.connectionLost)
            assertTrue(r.terminal)
            assertTrue(r.detail, fix in r.detail)
        }
    }

    @Test fun ioAndTimeoutFailuresStayRetryable() = runTest {
        for (failure in listOf(IOException("reset"), AdbFailure.Timeout(), AdbFailure.DebuggingOff())) {
            val ev = Events()
            val r = impl(Shell(compileFails = failure), ev).optimize("com.nuvio.tv")
            assertFalse(r.ok)
            assertTrue(failure.toString(), r.connectionLost)
            assertFalse(r.terminal)
            assertEquals("optimize_failed", ev.rows.single().kind)
        }
    }

    @Test fun failureDetailKeepsTheScrubbedFirstLine() = runTest {
        val said = "Error: pairing code 123456 rejected by emulator-5554 " + "word ".repeat(40)
        val r = impl(Shell(compile = "\n$said\nsecond line\n__rc=1\n"), Events()).optimize("com.nuvio.tv")
        assertTrue(r.detail, r.detail.startsWith("Didn't finish: the TV refused to optimize this app. It said: Error: pairing"))
        assertFalse(r.detail.contains("123456") || r.detail.contains("emulator-5554") || r.detail.contains("second line"))
        assertEquals(120, r.detail.substringAfter("It said: ").length) // cut after scrubbing
        assertEquals("Didn't finish: the TV refused to optimize this app.", impl(Shell(compile = "\n  \n"), Events()).optimize("com.nuvio.tv").detail)
    }

    @Test fun verifyReadsTheDexoptDumpOncePerPassAndAgainAfterACompile() = runTest {
        val shell = Shell(dexopt = v1)
        val optimizer = impl(shell, Events())
        assertEquals("speed", optimizer.verify("com.example.stream"))
        optimizer.verify("com.example.other")
        optimizer.verify("com.example.stream")
        assertEquals(1, shell.dexoptDumps)
        optimizer.optimize("com.example.stream") // the compile makes the cached dump stale
        assertEquals(2, shell.dexoptDumps)
    }

    @Test fun aDumpReadBeforeACompileIsNotCachedAfterIt() = runTest {
        val first = CompletableDeferred<String>()
        val shell = Shell(dexopt = v1, firstDexopt = first)
        val optimizer = impl(shell, Events())
        val early = async { optimizer.verify("com.example.stream") } // blocks inside its dexopt read
        runCurrent()
        assertEquals("Optimized (checked)", optimizer.optimize("com.example.stream").detail)
        first.complete("nothing") // the pre-compile answer arrives late
        assertNull(early.await())
        assertEquals("speed", optimizer.verify("com.example.stream"))
        assertEquals(2, shell.dexoptDumps)
    }

    @Test fun beforeAndroid14VerifyNeverAsksForAnArtDump() = runTest {
        val old = Shell(art = art, dexopt = v1)
        val oldImpl = impl(old, Events(), sdk = 33)
        assertEquals("speed", oldImpl.verify("com.example.stream"))
        oldImpl.verify("com.example.other")
        assertEquals(0, old.artDumps)
        assertEquals(1, old.dexoptDumps)
        val new = Shell(art = art)
        assertEquals("speed", impl(new, Events(), sdk = 34).verify("com.nuvio.tv"))
        assertEquals(1, new.artDumps)
        assertEquals(0, new.dexoptDumps)
    }
}
