package dev.speedcompiler.tv.engine

import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.EventDao
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class OptimizerImpl(
    private val shell: AdbShell,
    private val events: EventDao,
    private val clock: Clock,
    private val timeoutMillis: Long = 10 * 60 * 1000L,
) : Optimizer {

    override suspend fun optimize(packageName: String): OptimizeResult {
        if (!isValidPackageId(packageName)) {
            return finish(null, false, "Didn't finish: that is not a valid app name.", "optimize_failed")
        }
        // Only one compile at a time, across every instance in the process.
        return lock.withLock { compile(packageName) }
    }

    /** The compiler filter the TV reports for [packageName], or null if it does not report one. */
    suspend fun verify(packageName: String): String? {
        if (!isValidPackageId(packageName)) return null
        val art = parseCompilerFilter(shell.run(artDumpCommand(packageName)).output, packageName)
        return art ?: parseCompilerFilter(shell.run(DEXOPT_DUMP_COMMAND).output, packageName)
    }

    private suspend fun compile(pkg: String): OptimizeResult {
        val out = try {
            withTimeout(timeoutMillis) { shell.run(compileCommand(pkg)).output }
        } catch (e: TimeoutCancellationException) {
            return finish(pkg, false, "Didn't finish: the TV took more than ${timeoutMillis / 60_000} minutes.", "optimize_failed")
        } catch (e: Exception) {
            return finish(pkg, false, "Didn't finish: lost the connection to the TV.", "optimize_failed")
        }
        if (!compileSucceeded(out)) {
            return finish(pkg, false, "Didn't finish: the TV refused to optimize this app.", "optimize_failed")
        }
        val filter = try {
            verify(pkg)
        } catch (e: Exception) {
            null
        }
        return when (filter) {
            null -> finish(pkg, true, "Optimized (this TV doesn't report the result)", "optimize_unverified")
            "speed" -> finish(pkg, true, "Optimized (checked)", "optimize_verified")
            else -> finish(pkg, false, "Didn't finish: the TV still reports this app as \"$filter\".", "optimize_failed")
        }
    }

    private suspend fun finish(pkg: String?, ok: Boolean, detail: String, kind: String): OptimizeResult {
        events.insert(Event(timeMillis = clock.nowMillis(), packageName = pkg, kind = kind, message = detail))
        return OptimizeResult(ok, detail)
    }

    private companion object {
        val lock = Mutex()
    }
}
