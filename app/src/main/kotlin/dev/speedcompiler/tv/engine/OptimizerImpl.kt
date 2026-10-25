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
private val GENERIC_FAILURE = Regex("\\bFailure\\b")
private val SPECIFIC_ERROR = Regex("not found|unknown package|error:", RegexOption.IGNORE_CASE)

/** ART's bare "Failure", as when the TV's own dexopt ran at the same moment; a named error will not pass on its own. */
private fun isGenericRefusal(output: String) = GENERIC_FAILURE.containsMatchIn(output) && !SPECIFIC_ERROR.containsMatchIn(output)

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


    override suspend fun optimize(packageName: String, filter: String): OptimizeResult {
        if (!isValidPackageId(packageName)) {
            return finish(null, false, DID_NOT_FINISH + INVALID_APP_TAIL, "optimize_failed")
        }
        return compile(packageName, filter)
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

    /** Loads the profile the app ships. Best effort: any failure just means the compile runs as before. */
    private suspend fun loadProfile(pkg: String): Boolean = try {
        hasProfile(withTimeout(timeoutMillis) { shell.run(profileInstallCommand(pkg)) }.output)
    } catch (e: CancellationException) {
        if (e is TimeoutCancellationException) false else throw e
    } catch (e: Exception) {
        false
    }

    // shortcut: no check whether the refresh actually recompiled, and its outcome is ignored; the next one is a day away.
    override suspend fun refresh(packageName: String) {
        if (!isValidPackageId(packageName)) return
        try {
            withTimeout(timeoutMillis) { shell.run(compileCommand(packageName, force = false)) }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (e: Exception) {
            // the ADB link dropped; the next pass tries again
        } finally {
            synchronized(dumpLock) { dumpGeneration++; dump = null }
        }
        events.insert(Event(timeMillis = clock.nowMillis(), packageName = packageName, kind = "refresh", message = REFRESHED))
    }

    private suspend fun compile(pkg: String, wanted: String): OptimizeResult {
        val loaded = wanted == "speed-profile" && loadProfile(pkg)
        val res = try {
            withTimeout(timeoutMillis) { shell.run(compileCommand(pkg, wanted)) }
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
            return finish(pkg, false, withSaid(DID_NOT_FINISH + REFUSED_FINISH_TAIL, said), "optimize_failed", refused = isGenericRefusal(res.output))
        }
        val filter = try {
            verify(pkg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when {
            filter == null -> finish(pkg, true, OPTIMIZED_UNVERIFIED, "optimize_unverified")
            filter == "speed" || (wanted == "speed-profile" && filter == "speed-profile") ->
                finish(pkg, true, OPTIMIZED_CHECKED, "optimize_verified", refreshable = loaded && filter == "speed-profile")
            // An empty profile leaves the app at verify; other states (run-from-apk) are real failures.
            wanted == "speed-profile" && filter.endsWith(" is verify") -> finish(pkg, false, NO_PROFILE_YET, "optimize_no_profile", noProfile = true)
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

    private suspend fun finish(pkg: String?, ok: Boolean, detail: String, kind: String, connectionLost: Boolean = false, terminal: Boolean = false, refused: Boolean = false, noProfile: Boolean = false, refreshable: Boolean = false): OptimizeResult {
        events.insert(Event(timeMillis = clock.nowMillis(), packageName = pkg, kind = kind, message = detail))
        return OptimizeResult(ok, detail, connectionLost, terminal, refused, noProfile, refreshable)
    }
}
