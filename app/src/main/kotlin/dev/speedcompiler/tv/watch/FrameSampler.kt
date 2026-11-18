package dev.speedcompiler.tv.watch

import androidx.room.withTransaction
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.engine.TOP_RESUMED_COMMAND
import dev.speedcompiler.tv.engine.isValidPackageId
import dev.speedcompiler.tv.engine.topResumedPackage
import kotlin.coroutines.cancellation.CancellationException

/** A session shorter than this says nothing about smoothness. */
const val MIN_SESSION_FRAMES = 600

/** Reading intervals that start while the app's counters are younger than this belong to the "start" window. */
const val START_WINDOW_MS = 60_000L
/** One reading tick, with a little extra: a reading this much past the start window still ends it, one later than that does not belong to it. */
const val START_SLACK_MS = 75_000L
const val WINDOW_START = "start"
const val WINDOW_LATER = "later"

/*
 * Thresholds for telling "compiling can help" from "compiling can't help". Measured on the real TV (Android 14, 50 Hz output)
 * in an app's first 60 s after a cold start: the "Jit thread pool" thread used about 8 s of CPU while the app was uncompiled
 * (verify) and under 0.5 s once compiled (Nuvio, a Compose app). AIOStreams (a WebView app) used 0.0 to 0.1 s of JIT under every
 * filter, 13% of its slow frames were "Slow UI thread" against 86% "Slow issue draw commands", and compile filters moved its
 * slow-frame share by under 3 points. Nuvio's slow frames were 44% UI thread.
 */
/**
 * Share of wall time the JIT thread spends on the CPU, from which a start window counts as "still compiling at startup".
 * Measured in the first 60 s: Nuvio 13% uncompiled, 0.2-0.7% compiled; Nuvio Arc compiled from its shipped profile only 2.8%,
 * 0.1% after a recompile with the profile its own use had grown. 1% separates the shipped-profile case from compiled apps.
 */
const val JIT_BUSY_SHARE = 0.01
/** Janky frames a filter needs before the split of their causes is trusted. */
const val MIN_CAUSE_FRAMES = 100L
/** Below this share of UI-thread causes among janky frames the stutter is in drawing (AIOStreams 0.13, Nuvio 0.44). */
const val DRAW_BOUND_UI_SHARE = 0.25
/** A start window shorter than this says too little about JIT time to trigger a recompile. */
const val RECOMPILE_MIN_START_MS = 30_000L
/** An app is recompiled because of startup JIT at most once per version in this time. */
const val RECOMPILE_EVERY_MILLIS = 24 * 60 * 60 * 1000L
/** The kernel counts thread CPU time in 1/100 s (USER_HZ 100). */
private const val MS_PER_TICK = 10L

private const val JIT_MARKER = "--jit--"
private const val JIT_COMM = "Jit thread pool"

/** Share of [durationMs] spent by the JIT thread, or null when no time was measured. */
fun jitShare(jitMs: Long, durationMs: Long): Double? = if (durationMs > 0) jitMs.toDouble() / durationMs else null

/** gfxinfo, then the marker, then the app's pid and its JIT thread stat lines, in one shell call. */
internal fun frontCommand(pkg: String) =
    "dumpsys gfxinfo $pkg; echo $JIT_MARKER; p=\$(pidof -s $pkg) && echo \$p && grep -h \"($JIT_COMM)\" /proc/\$p/task/*/stat"

/**
 * Sum of utime + stime (fields 14 and 15 of `/proc/<pid>/task/<tid>/stat`) over the threads named exactly "Jit thread pool", in ticks.
 * Fields are counted after the last ")", since the comm holds spaces. Null when no such thread is listed.
 */
fun parseJitTicks(stat: String): Long? {
    var sum = 0L
    var found = false
    for (line in stat.lineSequence()) {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open || line.substring(open + 1, close) != JIT_COMM) continue
        // After ")": field 3 (state) comes first, so utime and stime are at index 11 and 12.
        val f = line.substring(close + 1).trim().split(' ')
        val ticks = (f.getOrNull(11)?.toLongOrNull() ?: continue) + (f.getOrNull(12)?.toLongOrNull() ?: continue)
        sum += ticks
        found = true
    }
    return if (found) sum else null
}

/** Splits [frontCommand]'s output. The JIT time in ms counts only when the pid it was read from is [gfxPid]. */
internal fun splitFrontOutput(output: String): Pair<String, String> =
    output.substringBefore(JIT_MARKER) to output.substringAfter(JIT_MARKER, "")

internal fun jitMsFor(afterMarker: String, gfxPid: Int?): Long? {
    val lines = afterMarker.trim().lines()
    val pid = lines.firstOrNull()?.trim()?.toIntOrNull() ?: return null
    if (gfxPid == null || pid != gfxPid) return null
    return parseJitTicks(lines.drop(1).joinToString("\n"))?.times(MS_PER_TICK)
}

private val pidLine = Regex("""Graphics info for pid (\d+)""")
private val uptimeLine = Regex("""Uptime: (\d+)""")
private val statsSinceLine = Regex("""Stats since: (\d+)ns""")
private val totalFrames = Regex("""Total frames rendered: (\d+)""")
private val jankyFrames = Regex("""Janky frames: (\d+)""")
private val slowUiLine = Regex("""Number Slow UI thread: (\d+)""")
private val slowBitmapLine = Regex("""Number Slow bitmap uploads: (\d+)""")
private val slowDrawLine = Regex("""Number Slow issue draw commands: (\d+)""")

/** Cumulative counters of one process. [jitMs] is the CPU time the JIT thread has used, when it could be read. The cause counts only cover janky frames, and one janky frame can count under several causes. [ageMs] is the time since the counters started. */
data class GfxStats(val pid: Int?, val total: Long, val janky: Long, val slowUi: Long, val slowBitmap: Long, val slowDraw: Long, val ageMs: Long?, val jitMs: Long? = null)

private fun Regex.long(text: String): Long? = find(text)?.groupValues?.get(1)?.toLongOrNull()

/**
 * Reads `dumpsys gfxinfo <pkg>`. If the dump holds several "Graphics info for pid" blocks, only the first block is read.
 * Null when the total or "Janky frames:" line is missing (the legacy line never matches); missing cause lines count as 0.
 */
fun parseGfxinfo(dumpsys: String): GfxStats? {
    val first = pidLine.find(dumpsys)
    val block = if (first == null) dumpsys else {
        val next = pidLine.find(dumpsys, first.range.last + 1)
        dumpsys.substring(first.range.first, next?.range?.first ?: dumpsys.length)
    }
    val total = totalFrames.long(block) ?: return null
    val janky = jankyFrames.long(block) ?: return null
    val uptime = uptimeLine.long(dumpsys)
    val since = statsSinceLine.long(block)
    val age = if (uptime != null && since != null) uptime - since / 1_000_000 else null
    return GfxStats(
        pidLine.find(block)?.groupValues?.get(1)?.toIntOrNull(), total, janky,
        slowUiLine.long(block) ?: 0, slowBitmapLine.long(block) ?: 0, slowDrawLine.long(block) ?: 0, age?.takeIf { it >= 0 },
    )
}

/** Frames and slow frames, with causes, counted in one window, with the JIT thread's CPU time and the wall time of the readings. */
data class Counts(
    val frames: Long = 0, val janky: Long = 0, val slowUi: Long = 0, val slowBitmap: Long = 0, val slowDraw: Long = 0,
    val jitMs: Long = 0, val durationMs: Long = 0,
    /** Wall time of the readings where the JIT thread was actually read; [jitMs] over this is the JIT share. */
    val jitDurationMs: Long = 0,
) {
    operator fun plus(o: Counts) = Counts(
        frames + o.frames, janky + o.janky, slowUi + o.slowUi, slowBitmap + o.slowBitmap, slowDraw + o.slowDraw, jitMs + o.jitMs, durationMs + o.durationMs, jitDurationMs + o.jitDurationMs,
    )
}

/**
 * One app process's readings. A session is fresh when its first reading is at most [START_WINDOW_MS] old: that reading then
 * counts from zero into "start". Otherwise the first reading is only a baseline, since the counters hold frames from before we looked.
 * [add] returns what to store per window, nothing while the session is under [MIN_SESSION_FRAMES]
 * (the frames seen so far are released in one go when it passes).
 * A reading interval belongs to "start" only in a session that began fresh, when the previous reading was younger than
 * [START_WINDOW_MS] and this one is at most [START_WINDOW_MS] + [START_SLACK_MS] old (a long gap is "later"), else to "later".
 */
class SessionTracker(
    val pid: Int? = null,
    lastTotal: Long = -1L,
    lastJanky: Long = 0L,
    lastSlowUi: Long = 0L,
    lastSlowBitmap: Long = 0L,
    lastSlowDraw: Long = 0L,
    lastAgeMs: Long? = null,
    pendingStart: Counts = Counts(),
    pendingLater: Counts = Counts(),
    released: Boolean = false,
    lastJitMs: Long? = null,
    /** The session's whole start window so far (not cleared on release) and whether it is still waiting to be judged. */
    sessionJitMs: Long = 0L,
    sessionDurationMs: Long = 0L,
    sessionJitDurationMs: Long = 0L,
    startOpen: Boolean = false,
    /** True when the first reading was fresh; only such a session has a start window. */
    beganFresh: Boolean = false,
    /** False after someone reset gfxinfo in a live process: its counters are young but the frames before were not seen. */
    private val canBeFresh: Boolean = true,
) {
    var lastTotal = lastTotal
        private set
    var lastJanky = lastJanky
        private set
    var lastSlowUi = lastSlowUi
        private set
    var lastSlowBitmap = lastSlowBitmap
        private set
    var lastSlowDraw = lastSlowDraw
        private set
    var lastAgeMs = lastAgeMs
        private set
    var pendingStart = pendingStart
        private set
    var pendingLater = pendingLater
        private set
    var released = released
        private set
    var lastJitMs = lastJitMs
        private set
    var sessionJitMs = sessionJitMs
        private set
    var sessionDurationMs = sessionDurationMs
        private set
    var sessionJitDurationMs = sessionJitDurationMs
        private set
    var startOpen = startOpen
        private set
    var beganFresh = beganFresh
        private set

    /** True after the [add] that crossed from the start window to the later one, for a session that began fresh. */
    var startJustEnded = false
        private set

    fun add(g: GfxStats): Map<String, Counts> {
        val seen = lastTotal >= 0
        val fresh = !seen && canBeFresh && g.ageMs != null && g.ageMs <= START_WINDOW_MS
        val jit = g.jitMs
        val lastJit = lastJitMs
        val jitDelta = when {
            jit == null -> 0L
            seen && lastJit != null -> (jit - lastJit).coerceAtLeast(0)
            fresh -> jit
            else -> 0L
        }
        val lastAge = lastAgeMs
        val duration = when {
            seen -> if (g.ageMs != null && lastAge != null) (g.ageMs - lastAge).coerceAtLeast(0) else 0L
            fresh -> g.ageMs ?: 0L
            else -> 0L
        }
        val jitMeasured = jit != null && ((seen && lastJit != null) || fresh)
        val jitDuration = if (jitMeasured) duration else 0L
        val delta = when {
            seen -> Counts(
                g.total - lastTotal, g.janky - lastJanky,
                (g.slowUi - lastSlowUi).coerceAtLeast(0), (g.slowBitmap - lastSlowBitmap).coerceAtLeast(0), (g.slowDraw - lastSlowDraw).coerceAtLeast(0),
                jitDelta, duration, jitDuration,
            )
            fresh -> Counts(g.total, g.janky, g.slowUi, g.slowBitmap, g.slowDraw, jitDelta, duration, jitDuration)
            else -> Counts()
        }
        if (!seen) {
            beganFresh = fresh
            startOpen = fresh
        }
        val prevAge = lastAgeMs
        val inStart = fresh || (seen && beganFresh && prevAge != null && prevAge < START_WINDOW_MS && g.ageMs != null && g.ageMs <= START_WINDOW_MS + START_SLACK_MS && g.ageMs - prevAge <= START_SLACK_MS)
        val window = if (inStart) WINDOW_START else WINDOW_LATER
        startJustEnded = seen && startOpen && window == WINDOW_LATER
        if (startJustEnded) startOpen = false
        if (window == WINDOW_START) {
            sessionJitMs += delta.jitMs
            sessionDurationMs += delta.durationMs
            sessionJitDurationMs += delta.jitDurationMs
        }
        lastJitMs = jit // null when unread: the next interval has no baseline and is not measured
        lastTotal = g.total
        lastJanky = g.janky
        lastSlowUi = g.slowUi
        lastSlowBitmap = g.slowBitmap
        lastSlowDraw = g.slowDraw
        lastAgeMs = g.ageMs
        if (released) return if (delta.frames > 0) mapOf(window to delta) else emptyMap()
        if (window == WINDOW_START) pendingStart += delta else pendingLater += delta
        if (pendingStart.frames + pendingLater.frames < MIN_SESSION_FRAMES) return emptyMap()
        released = true
        val out = mapOf(WINDOW_START to pendingStart, WINDOW_LATER to pendingLater).filterValues { it.frames > 0 }
        pendingStart = Counts()
        pendingLater = Counts()
        return out
    }
}

/**
 * Called about once a minute while the screen is on. Reads how smoothly the front app, if watched with auto-optimize on,
 * has been drawing, and adds it to [FrameStat] under the app's current compiler filter and version.
 * [ready] says whether it is fine to use the ADB link now (screen on, connected, no compile running).
 */
class FrameSampler(
    private val shell: AdbShell,
    private val verify: suspend (String) -> String?,
    private val fingerprint: (String) -> String?,
    private val db: () -> AppDatabase,
    private val ready: suspend () -> Boolean,
    /** Whether Settings says to compile everything with `speed`: then a recompile has nothing to add. */
    private val compilesFull: suspend () -> Boolean = { false },
    /** Called with (package, fingerprint) when an optimized app spent its start window compiling code anyway. */
    private val wantRecompile: suspend (String, String) -> Unit = { _, _ -> },
    /** Called with the package in front at every tick, so work that must not touch it can start. */
    private val onFront: suspend (String) -> Unit = {},
) {
    private class Session(val filter: String, val fingerprint: String, val tracker: SessionTracker = SessionTracker())

    private val sessions = mutableMapOf<String, Session>()

    suspend fun tick() {
        try {
            if (!ready()) return
            val front = topResumedPackage(shell.run(TOP_RESUMED_COMMAND).output.lines()) ?: return
            onFront(front)
            val database = db()
            val watched = database.watchedApps().get(front)
            if (watched?.autoOptimize != true || !isValidPackageId(front)) return
            val (gfxText, jitText) = splitFrontOutput(shell.run(frontCommand(front)).output)
            var g = parseGfxinfo(gfxText) ?: return
            g = g.copy(jitMs = jitMsFor(jitText, g.pid))
            val dao = database.frameStats()
            // A stored session from before an app update belongs to the old version: start fresh.
            var s = sessions[front] ?: dao.session(front)?.takeIf { it.fingerprint == fingerprint(front) }?.let {
                Session(
                    it.filter, it.fingerprint,
                    SessionTracker(
                        it.pid, it.lastTotal, it.lastJanky, it.lastSlowUi, it.lastSlowBitmap, it.lastSlowDraw, it.lastAgeMs,
                        Counts(it.startFrames, it.startJanky, it.startSlowUi, it.startSlowBitmap, it.startSlowDraw, it.startJitMs, it.startDurationMs, it.startJitDurationMs),
                        Counts(it.frames, it.janky, it.laterSlowUi, it.laterSlowBitmap, it.laterSlowDraw, it.laterJitMs, it.laterDurationMs, it.laterJitDurationMs), it.released,
                        it.lastJitMs, it.sessionJitMs, it.sessionDurationMs, it.sessionJitDurationMs, it.startOpen, it.beganFresh,
                    ),
                )
            }
            if (s != null) {
                val t = s.tracker
                val otherProcess = t.pid != null && g.pid != null && t.pid != g.pid
                // A smaller count means the app restarted (janky too: a new process may pass the old total between reads).
                val dropped = g.total < t.lastTotal || g.janky < t.lastJanky
                if (otherProcess || dropped) {
                    // Same process with smaller counters: someone reset gfxinfo, so its young counters hide earlier frames.
                    val reset = dropped && !otherProcess && t.pid != null && t.pid == g.pid
                    s = Session(verify(front) ?: return, fingerprint(front) ?: return, SessionTracker(g.pid, canBeFresh = !reset))
                    sessions[front] = s
                }
            } else {
                s = Session(verify(front) ?: return, fingerprint(front) ?: return, SessionTracker(g.pid))
                sessions[front] = s
            }
            val added = s.tracker.add(g)
            val t = s.tracker
            try {
                // Judge before persisting: if this throws, nothing of this tick is stored, so the next tick re-reads the delta and judges again.
                val share = jitShare(t.sessionJitMs, t.sessionJitDurationMs)
                if (t.startJustEnded && s.filter == "speed-profile" && !watched.useSpeed && !compilesFull() &&
                    t.sessionJitDurationMs >= RECOMPILE_MIN_START_MS && share != null && share >= JIT_BUSY_SHARE
                ) wantRecompile(front, s.fingerprint)
                // One transaction, so the stored session and the stored frames never disagree.
                database.withTransaction {
                    for ((window, c) in added) {
                        val old = dao.get(front, s.fingerprint, s.filter, window)
                        dao.put(
                            FrameStat(
                                front, s.fingerprint, s.filter, (old?.frames ?: 0) + c.frames, (old?.janky ?: 0) + c.janky, window,
                                (old?.slowUi ?: 0) + c.slowUi, (old?.slowBitmap ?: 0) + c.slowBitmap, (old?.slowDraw ?: 0) + c.slowDraw,
                                (old?.jitMs ?: 0) + c.jitMs, (old?.durationMs ?: 0) + c.durationMs, (old?.jitDurationMs ?: 0) + c.jitDurationMs,
                            ),
                        )
                    }
                    dao.putSession(
                        FrameSession(
                            front, s.filter, s.fingerprint, t.lastTotal, t.lastJanky, t.pendingLater.frames, t.pendingLater.janky, t.released,
                            t.pid, t.lastAgeMs, t.lastSlowUi, t.lastSlowBitmap, t.lastSlowDraw,
                            t.pendingStart.frames, t.pendingStart.janky, t.pendingStart.slowUi, t.pendingStart.slowBitmap, t.pendingStart.slowDraw,
                            t.pendingLater.slowUi, t.pendingLater.slowBitmap, t.pendingLater.slowDraw,
                            t.lastJitMs, t.pendingStart.jitMs, t.pendingStart.durationMs, t.pendingLater.jitMs, t.pendingLater.durationMs,
                            t.pendingStart.jitDurationMs, t.pendingLater.jitDurationMs,
                            t.sessionJitMs, t.sessionDurationMs, t.sessionJitDurationMs, t.startOpen, t.beganFresh,
                        ),
                    )
                }
            } catch (e: Exception) {
                // The in-memory session is ahead of what is stored: drop it so the next tick reloads the stored one.
                sessions.remove(front)
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One missed reading costs nothing.
        }
    }
}
