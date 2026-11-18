package dev.speedcompiler.tv.watch

import androidx.room.withTransaction
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.engine.TOP_RESUMED_COMMAND
import dev.speedcompiler.tv.engine.topResumedPackage
import kotlin.coroutines.cancellation.CancellationException

/** A session shorter than this says nothing about smoothness. */
const val MIN_SESSION_FRAMES = 600

/** Reading intervals that start while the app's counters are younger than this belong to the "start" window. */
const val START_WINDOW_MS = 60_000L
const val WINDOW_START = "start"
const val WINDOW_LATER = "later"

private val pidLine = Regex("""Graphics info for pid (\d+)""")
private val uptimeLine = Regex("""Uptime: (\d+)""")
private val statsSinceLine = Regex("""Stats since: (\d+)ns""")
private val totalFrames = Regex("""Total frames rendered: (\d+)""")
private val jankyFrames = Regex("""Janky frames: (\d+)""")
private val slowUiLine = Regex("""Number Slow UI thread: (\d+)""")
private val slowBitmapLine = Regex("""Number Slow bitmap uploads: (\d+)""")
private val slowDrawLine = Regex("""Number Slow issue draw commands: (\d+)""")

/** Cumulative counters of one process. The cause counts only cover janky frames, and one janky frame can count under several causes. [ageMs] is the time since the counters started. */
data class GfxStats(val pid: Int?, val total: Long, val janky: Long, val slowUi: Long, val slowBitmap: Long, val slowDraw: Long, val ageMs: Long?)

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

/** Frames and slow frames, with causes, counted in one window. */
data class Counts(val frames: Long = 0, val janky: Long = 0, val slowUi: Long = 0, val slowBitmap: Long = 0, val slowDraw: Long = 0) {
    operator fun plus(o: Counts) = Counts(frames + o.frames, janky + o.janky, slowUi + o.slowUi, slowBitmap + o.slowBitmap, slowDraw + o.slowDraw)
}

/**
 * One app process's readings. A session is fresh when its first reading is at most [START_WINDOW_MS] old: that reading then
 * counts from zero into "start". Otherwise the first reading is only a baseline, since the counters hold frames from before we looked.
 * [add] returns what to store per window, nothing while the session is under [MIN_SESSION_FRAMES]
 * (the frames seen so far are released in one go when it passes).
 * A reading interval belongs to "start" when the previous reading was younger than [START_WINDOW_MS], else to "later".
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

    fun add(g: GfxStats): Map<String, Counts> {
        val seen = lastTotal >= 0
        val fresh = !seen && canBeFresh && g.ageMs != null && g.ageMs <= START_WINDOW_MS
        val delta = when {
            seen -> Counts(
                g.total - lastTotal, g.janky - lastJanky,
                (g.slowUi - lastSlowUi).coerceAtLeast(0), (g.slowBitmap - lastSlowBitmap).coerceAtLeast(0), (g.slowDraw - lastSlowDraw).coerceAtLeast(0),
            )
            fresh -> Counts(g.total, g.janky, g.slowUi, g.slowBitmap, g.slowDraw)
            else -> Counts()
        }
        val window = if (fresh || lastAgeMs.let { it != null && it < START_WINDOW_MS }) WINDOW_START else WINDOW_LATER
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
) {
    private class Session(val filter: String, val fingerprint: String, val tracker: SessionTracker = SessionTracker())

    private val sessions = mutableMapOf<String, Session>()

    suspend fun tick() {
        try {
            if (!ready()) return
            val front = topResumedPackage(shell.run(TOP_RESUMED_COMMAND).output.lines()) ?: return
            val database = db()
            if (database.watchedApps().get(front)?.autoOptimize != true) return
            val g = parseGfxinfo(shell.run("dumpsys gfxinfo $front").output) ?: return
            val dao = database.frameStats()
            // A stored session from before an app update belongs to the old version: start fresh.
            var s = sessions[front] ?: dao.session(front)?.takeIf { it.fingerprint == fingerprint(front) }?.let {
                Session(
                    it.filter, it.fingerprint,
                    SessionTracker(
                        it.pid, it.lastTotal, it.lastJanky, it.lastSlowUi, it.lastSlowBitmap, it.lastSlowDraw, it.lastAgeMs,
                        Counts(it.startFrames, it.startJanky, it.startSlowUi, it.startSlowBitmap, it.startSlowDraw),
                        Counts(it.frames, it.janky, it.laterSlowUi, it.laterSlowBitmap, it.laterSlowDraw), it.released,
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
            // One transaction, so the stored session and the stored frames never disagree.
            database.withTransaction {
                for ((window, c) in added) {
                    val old = dao.get(front, s.fingerprint, s.filter, window)
                    dao.put(
                        FrameStat(
                            front, s.fingerprint, s.filter, (old?.frames ?: 0) + c.frames, (old?.janky ?: 0) + c.janky, window,
                            (old?.slowUi ?: 0) + c.slowUi, (old?.slowBitmap ?: 0) + c.slowBitmap, (old?.slowDraw ?: 0) + c.slowDraw,
                        ),
                    )
                }
                val t = s.tracker
                dao.putSession(
                    FrameSession(
                        front, s.filter, s.fingerprint, t.lastTotal, t.lastJanky, t.pendingLater.frames, t.pendingLater.janky, t.released,
                        t.pid, t.lastAgeMs, t.lastSlowUi, t.lastSlowBitmap, t.lastSlowDraw,
                        t.pendingStart.frames, t.pendingStart.janky, t.pendingStart.slowUi, t.pendingStart.slowBitmap, t.pendingStart.slowDraw,
                        t.pendingLater.slowUi, t.pendingLater.slowBitmap, t.pendingLater.slowDraw,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One missed reading costs nothing.
        }
    }
}
