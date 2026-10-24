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
            return finish(null, false, DID_NOT_FINISH + INVALID_APP_TAIL, "optimize_failed")
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
        if (!isValidPackageId(packageName)) return finish(null, false, DID_NOT_RESET + INVALID_APP_TAIL, "reset_failed")
        val res = try {
            withTimeout(timeoutMillis) { shell.run(resetCommand(packageName)) }
        } catch (e: Exception) {
            return adbFailed(packageName, e, DID_NOT_RESET, DID_NOT_RESET + TOOK_TOO_LONG_RESET_TAIL, "reset_failed")
        } finally {
            synchronized(dumpLock) { // the reset changes what a dump says
                dumpGeneration++
                dump = null
            }
        }
        if (res.exitCode > 0 || !compileSucceeded(res.output)) {
            val said = scrub(res.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim())?.take(120)
            return finish(packageName, false, withSaid(DID_NOT_RESET + REFUSED_RESET_TAIL, said), "reset_failed")
        }
        return finish(packageName, true, RESET_DONE, "reset_done")
    }

    private suspend fun compile(pkg: String): OptimizeResult {
        val res = try {
            withTimeout(timeoutMillis) { shell.run(compileCommand(pkg)) }
        } catch (e: Exception) {
            return adbFailed(pkg, e, DID_NOT_FINISH, tookMinutes(timeoutMillis / 60_000), "optimize_failed")
        } finally {
            synchronized(dumpLock) { // the compile changes what a dump says
                dumpGeneration++
                dump = null
            }
        }
        // exitCode -1 means the TV gave no code; the word Success then decides alone.
        if (res.exitCode > 0 || !compileSucceeded(res.output)) {
            val said = scrub(res.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim())?.take(120)
            return finish(pkg, false, withSaid(DID_NOT_FINISH + REFUSED_FINISH_TAIL, said), "optimize_failed", refused = true)
        }
        val filter = try {
            verify(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when (filter) {
            null -> finish(pkg, true, OPTIMIZED_UNVERIFIED, "optimize_unverified")
            "speed" -> finish(pkg, true, OPTIMIZED_CHECKED, "optimize_verified")
            else -> finish(pkg, false, notSpeed(filter), "optimize_failed")
        }
    }

    /** One ladder for reset and compile; a user-fixable failure is terminal, a dropped link is connectionLost. */
    private suspend fun adbFailed(pkg: String, e: Exception, prefix: String, timeoutText: String, kind: String): OptimizeResult = when (e) {
        is TimeoutCancellationException -> finish(pkg, false, timeoutText, kind)
        is CancellationException -> throw e
        is AdbFailure.NotPaired -> finish(pkg, false, prefix + NOT_PAIRED_TAIL, kind, terminal = true) // retrying cannot help: the user has to act
        is AdbFailure.NotAuthorized -> finish(pkg, false, prefix + NOT_ALLOWED_TAIL, kind, terminal = true)
        else -> finish(pkg, false, prefix + LOST_TAIL, kind, connectionLost = true)
    }

    private suspend fun finish(pkg: String?, ok: Boolean, detail: String, kind: String, connectionLost: Boolean = false, terminal: Boolean = false, refused: Boolean = false): OptimizeResult {
        events.insert(Event(timeMillis = clock.nowMillis(), packageName = pkg, kind = kind, message = detail))
        return OptimizeResult(ok, detail, connectionLost, terminal, refused)
    }
}
