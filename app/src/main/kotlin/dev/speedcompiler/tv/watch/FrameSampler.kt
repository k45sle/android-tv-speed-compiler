package dev.speedcompiler.tv.watch

import androidx.room.withTransaction
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.engine.TOP_RESUMED_COMMAND
import dev.speedcompiler.tv.engine.topResumedPackage
import kotlin.coroutines.cancellation.CancellationException

/** A session shorter than this says nothing about smoothness. */
const val MIN_SESSION_FRAMES = 600

private val totalFrames = Regex("""Total frames rendered: (\d+)""")
private val jankyFrames = Regex("""Janky frames: (\d+)""")

/** (total frames, slow frames) from `dumpsys gfxinfo <pkg>`; both counters are cumulative for the process. Null when either line is missing. */
fun parseGfxinfo(dumpsys: String): Pair<Long, Long>? {
    val total = totalFrames.find(dumpsys)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val janky = jankyFrames.find(dumpsys)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    return total to janky
}

/**
 * One app process's readings. The first reading is only a baseline, since the counters hold frames from before we looked.
 * [add] returns the frames and slow frames to store, or null while the session is under [MIN_SESSION_FRAMES]
 * (the frames seen so far are released in one go when it passes).
 */
class SessionTracker(lastTotal: Long = -1L, lastJanky: Long = 0L, frames: Long = 0L, janky: Long = 0L, released: Boolean = false) {
    var lastTotal = lastTotal
        private set
    var lastJanky = lastJanky
        private set
    var frames = frames
        private set
    var janky = janky
        private set
    var released = released
        private set

    fun add(total: Long, jankyNow: Long): Pair<Long, Long>? {
        val seen = lastTotal >= 0
        val dFrames = if (seen) total - lastTotal else 0
        val dJanky = if (seen) jankyNow - lastJanky else 0
        lastTotal = total
        lastJanky = jankyNow
        frames += dFrames
        janky += dJanky
        return when {
            released -> (dFrames to dJanky).takeIf { dFrames > 0 }
            frames >= MIN_SESSION_FRAMES -> { released = true; frames to janky }
            else -> null
        }
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
            val (total, janky) = parseGfxinfo(shell.run("dumpsys gfxinfo $front").output) ?: return
            val dao = database.frameStats()
            // A stored session from before an app update belongs to the old version: start fresh.
            var s = sessions[front] ?: dao.session(front)?.takeIf { it.fingerprint == fingerprint(front) }?.let { Session(it.filter, it.fingerprint, SessionTracker(it.lastTotal, it.lastJanky, it.frames, it.janky, it.released)) }
            // A smaller count means the app restarted (janky too: a new process may pass the old total between reads).
            if (s == null || total < s.tracker.lastTotal || janky < s.tracker.lastJanky) {
                s = Session(verify(front) ?: return, fingerprint(front) ?: return)
                sessions[front] = s
            }
            val added = s.tracker.add(total, janky)
            // One transaction, so the stored session and the stored frames never disagree.
            database.withTransaction {
                if (added != null) {
                    val old = dao.get(front, s.fingerprint, s.filter)
                    dao.put(FrameStat(front, s.fingerprint, s.filter, (old?.frames ?: 0) + added.first, (old?.janky ?: 0) + added.second))
                }
                val t = s.tracker
                dao.putSession(FrameSession(front, s.filter, s.fingerprint, t.lastTotal, t.lastJanky, t.frames, t.janky, t.released))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One missed reading costs nothing.
        }
    }
}
