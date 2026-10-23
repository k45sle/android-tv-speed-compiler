package dev.speedcompiler.tv.engine

import dev.speedcompiler.tv.adb.AdbFailure
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.EventDao
import dev.speedcompiler.tv.web.scrub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

private const val DUMP_TTL_MILLIS = 5 * 60 * 1000L

class OptimizerImpl(
    private val shell: AdbShell,
    private val events: EventDao,
    private val clock: Clock,
    private val timeoutMillis: Long = 10 * 60 * 1000L,
) : Optimizer {
    private class Dump(val atMillis: Long, val text: String)

    // One `dumpsys package dexopt` serves every app of a verify pass (Android 11-13 has no per-app dump).
    @Volatile private var dump: Dump? = null
    private val dumpGeneration = AtomicInteger(0)


    override suspend fun optimize(packageName: String): OptimizeResult {
        if (!isValidPackageId(packageName)) {
            return finish(null, false, "Didn't finish: that is not a valid app name.", "optimize_failed")
        }
        return compile(packageName)
    }

    /** The compiler filter the TV reports for [packageName], or null if it does not report one. */
    override suspend fun verify(packageName: String): String? {
        if (!isValidPackageId(packageName)) return null
        val art = parseCompilerFilter(shell.run(artDumpCommand(packageName)).output, packageName)
        return art ?: parseCompilerFilter(dexoptDump(), packageName)
    }

    private suspend fun dexoptDump(): String {
        val now = clock.nowMillis()
        dump?.takeIf { now - it.atMillis < DUMP_TTL_MILLIS }?.let { return it.text }
        val generation = dumpGeneration.get()
        val text = shell.run(DEXOPT_DUMP_COMMAND).output
        if (generation == dumpGeneration.get()) dump = Dump(now, text) // a compile since the read makes it stale
        return text
    }

    private suspend fun compile(pkg: String): OptimizeResult {
        val res = try {
            withTimeout(timeoutMillis) { shell.run(compileCommand(pkg)) }
        } catch (e: TimeoutCancellationException) {
            return finish(pkg, false, "Didn't finish: the TV took more than ${timeoutMillis / 60_000} minutes.", "optimize_failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbFailure.NotPaired) { // retrying cannot help: the user has to act
            return finish(pkg, false, "Didn't finish: the TV no longer trusts this app. Pair again in Settings.", "optimize_failed")
        } catch (e: AdbFailure.NotAuthorized) {
            return finish(pkg, false, "Didn't finish: the TV has not allowed this app. Accept the debugging prompt on the TV.", "optimize_failed")
        } catch (e: Exception) {
            return finish(pkg, false, "Didn't finish: lost the connection to the TV.", "optimize_failed", connectionLost = true)
        } finally {
            dumpGeneration.incrementAndGet() // the compile changes what a dump says
            dump = null
        }
        // exitCode -1 means the TV gave no code; the word Success then decides alone.
        if (res.exitCode > 0 || !compileSucceeded(res.output)) {
            val said = scrub(res.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim())?.take(120)
            return finish(pkg, false, "Didn't finish: the TV refused to optimize this app." + said?.let { " It said: $it" }.orEmpty(), "optimize_failed")
        }
        val filter = try {
            verify(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when (filter) {
            null -> finish(pkg, true, "Optimized (this TV doesn't report the result)", "optimize_unverified")
            "speed" -> finish(pkg, true, "Optimized (checked)", "optimize_verified")
            else -> finish(pkg, false, "Didn't finish: the TV still reports this app as \"$filter\".", "optimize_failed")
        }
    }

    private suspend fun finish(pkg: String?, ok: Boolean, detail: String, kind: String, connectionLost: Boolean = false): OptimizeResult {
        events.insert(Event(timeMillis = clock.nowMillis(), packageName = pkg, kind = kind, message = detail))
        return OptimizeResult(ok, detail, connectionLost)
    }
}
