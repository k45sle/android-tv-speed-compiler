package dev.speedcompiler.tv

import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.adb.ShellResult
import dev.speedcompiler.tv.engine.FakeClock
import dev.speedcompiler.tv.engine.OptimizerImpl
import dev.speedcompiler.tv.engine.compileCommand
import dev.speedcompiler.tv.engine.parseCompilerFilter
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.EventDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    ) : AdbShell {
        var running = 0
        var maxRunning = 0
        override suspend fun run(command: String): ShellResult = when {
            command.startsWith("cmd package compile") -> {
                running++
                maxRunning = maxOf(maxRunning, running)
                try { delay(compileDelay) } finally { running-- }
                ShellResult(0, compile)
            }
            command.startsWith("pm art dump") -> ShellResult(0, art)
            else -> ShellResult(0, dexopt)
        }
    }

    private fun impl(shell: AdbShell, ev: Events, timeout: Long = 600_000) = OptimizerImpl(shell, ev, FakeClock(5), timeout)

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
        assertEquals(0, shell.maxRunning)
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

    @Test fun concurrentCallsRunOneAfterTheOther() = runTest {
        val shell = Shell(art = art, compileDelay = 100)
        val o = impl(shell, Events())
        awaitAll(async { o.optimize("com.nuvio.tv") }, async { o.optimize("com.nuvio.tv") })
        assertEquals(1, shell.maxRunning)
        assertEquals(200, testScheduler.currentTime)
    }
}
