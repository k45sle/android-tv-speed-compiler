package dev.speedcompiler.tv.engine

import android.os.Build
import dev.speedcompiler.tv.adb.AdbFailure
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.watch.Event
import dev.speedcompiler.tv.watch.EventDao
import dev.speedcompiler.tv.web.scrub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val DUMP_TTL_MILLIS = 5 * 60 * 1000L

class OptimizerImpl(
    private val shell: AdbShell,
    private val events: EventDao,
    private val clock: Clock,
    private val timeoutMillis: Long = 10 * 60 * 1000L,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : Optimizer {
    private class Dump(val atMillis: Long, val text: String)

    // One `dumpsys package dexopt` serves every app of a verify pass (Android 11-13 has no per-app dump).
    private val dumpLock = Any()
    private var dump: Dump? = null // guarded by dumpLock, with dumpGeneration
    private var dumpGeneration = 0


    override suspend fun optimize(packageName: String): OptimizeResult {
        if (!isValidPackageId(packageName)) {
            return finish(null, false, "Didn't finish: that is not a valid app name.", "optimize_failed")
        }
        return compile(packageName)
    }

    /** The compiler filter the TV reports for [packageName], or null if it does not report one. */
    override suspend fun verify(packageName: String): String? {
        if (!isValidPackageId(packageName)) return null
        // Android 14+ answers per app; before that the one shared dexopt dump is the only source.
        val art = if (sdkInt >= 34) parseCompilerFilter(shell.run(artDumpCommand(packageName)).output, packageName) else null
        return art ?: parseCompilerFilter(dexoptDump(), packageName)
    }

    private suspend fun dexoptDump(): String {
        val now = clock.nowMillis()
        val generation = synchronized(dumpLock) {
            dump?.takeIf { now - it.atMillis < DUMP_TTL_MILLIS }?.let { return it.text }
            dumpGeneration
        }
        val text = shell.run(DEXOPT_DUMP_COMMAND).output
        synchronized(dumpLock) { if (generation == dumpGeneration) dump = Dump(now, text) } // a compile since the read makes it stale
        return text
    }

    override suspend fun reset(packageName: String): OptimizeResult {
        if (!isValidPackageId(packageName)) return finish(null, false, "Didn't reset: that is not a valid app name.", "reset_failed")
        val res = try {
            withTimeout(timeoutMillis) { shell.run(resetCommand(packageName)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbFailure.NotPaired) {
            return finish(packageName, false, "Didn't reset: the TV no longer trusts this app. Choose Fix connection in Settings.", "reset_failed", terminal = true)
        } catch (e: AdbFailure.NotAuthorized) {
            return finish(packageName, false, "Didn't reset: the TV has not allowed this app. Accept the debugging prompt on the TV.", "reset_failed", terminal = true)
        } catch (e: Exception) {
            return finish(packageName, false, "Didn't reset: lost the connection to the TV.", "reset_failed", connectionLost = true)
        } finally {
            synchronized(dumpLock) { // the reset changes what a dump says
                dumpGeneration++
                dump = null
            }
        }
        if (res.exitCode > 0 || !compileSucceeded(res.output)) {
            val said = scrub(res.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim())?.take(120)
            return finish(packageName, false, "Didn't reset: the TV refused." + said?.let { " It said: $it" }.orEmpty(), "reset_failed")
        }
        return finish(packageName, true, "Reset done. The TV chooses how to run this app again.", "reset_done")
    }

    private suspend fun compile(pkg: String): OptimizeResult {
        val res = try {
            withTimeout(timeoutMillis) { shell.run(compileCommand(pkg)) }
        } catch (e: TimeoutCancellationException) {
            return finish(pkg, false, "Didn't finish: the TV took more than ${timeoutMillis / 60_000} minutes.", "optimize_failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbFailure.NotPaired) { // retrying cannot help: the user has to act
            return finish(pkg, false, "Didn't finish: the TV no longer trusts this app. Choose Fix connection in Settings.", "optimize_failed", terminal = true)
        } catch (e: AdbFailure.NotAuthorized) {
            return finish(pkg, false, "Didn't finish: the TV has not allowed this app. Accept the debugging prompt on the TV.", "optimize_failed", terminal = true)
        } catch (e: Exception) {
            return finish(pkg, false, "Didn't finish: lost the connection to the TV.", "optimize_failed", connectionLost = true)
        } finally {
            synchronized(dumpLock) { // the compile changes what a dump says
                dumpGeneration++
                dump = null
            }
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
            else -> finish(pkg, false, "Didn't finish: ${filter.removePrefix("not fully compiled: ")}, not speed.", "optimize_failed")
        }
    }

    private suspend fun finish(pkg: String?, ok: Boolean, detail: String, kind: String, connectionLost: Boolean = false, terminal: Boolean = false): OptimizeResult {
        events.insert(Event(timeMillis = clock.nowMillis(), packageName = pkg, kind = kind, message = detail))
        return OptimizeResult(ok, detail, connectionLost, terminal)
    }
}
