package dev.speedcompiler.tv.watch

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.speedcompiler.tv.adb.AdbShell
import dev.speedcompiler.tv.adb.ShellResult
import dev.speedcompiler.tv.engine.TOP_RESUMED_COMMAND
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

// Synthetic dumpsys snippets, not captured from a device.
private fun gfx(total: Long, janky: Long) = "Stats since: 1ns\nTotal frames rendered: $total\nJanky frames: $janky (3.00%)\nJanky frames (legacy): 9 (1.00%)\n"
private fun top(pkg: String) = "  topResumedActivity=ActivityRecord{1a2b u0 $pkg/.Main t5}"

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FrameSamplerTest {
    private val real = """
Applications Graphics Acceleration Info:
Uptime: 237305290 Realtime: 237305290
** Graphics info for pid 17090 [dev.speedcompiler.tv] **
Stats since: 234723287527046ns
Total frames rendered: 114727
Janky frames: 56 (0.05%)
Janky frames (legacy): 866 (0.75%)
50th percentile: 20ms
Number Missed Vsync: 27
Number High input latency: 229341
Number Slow UI thread: 52
Number Slow bitmap uploads: 3
Number Slow issue draw commands: 54
Number Frame deadline missed: 56
Number Frame deadline missed (legacy): 57
HISTOGRAM: 5ms=0 6ms=0
"""

    @Test fun parsesTheRealFormat() {
        assertEquals(GfxStats(17090, 114727, 56, 52, 3, 54, 2_582_003), parseGfxinfo(real))
    }

    @Test fun parserHandlesMissingLinesAndOnlyReadsTheFirstBlock() {
        assertEquals(GfxStats(null, 1234, 56, 0, 0, 0, null), parseGfxinfo(gfx(1234, 56)))
        assertNull(parseGfxinfo("Total frames rendered: 10"))
        assertNull(parseGfxinfo("Total frames rendered: 10\nJanky frames (legacy): 9 (1.00%)"))
        assertNull(parseGfxinfo(""))
        // Stats since later than uptime: no age.
        assertNull(parseGfxinfo(real.replace("Uptime: 237305290", "Uptime: 1"))!!.ageMs)
        val two = real + "** Graphics info for pid 99 [other] **\nTotal frames rendered: 5\nJanky frames: 1 (1%)\n"
        assertEquals(17090, parseGfxinfo(two)!!.pid)
        assertEquals(114727L, parseGfxinfo(two)!!.total)
    }

    /** Android 12 TV emulator, TV Settings in front: the process-wide set (since process start) comes first, then one per window. */
    @Test fun android12ReadsTheProcessSetBeforeTheWindowSet() {
        val a12 = """
Applications Graphics Acceleration Info:
Uptime: 74285 Realtime: 74285

** Graphics info for pid 835 [com.android.tv.settings] **

Stats since: 6321041127ns
Total frames rendered: 62
Janky frames: 0 (0.00%)
Janky frames (legacy): 52 (83.87%)
Number Slow UI thread: 0
Number Slow bitmap uploads: 0
Number Slow issue draw commands: 0
Number Frame deadline missed: 0

Stats since: 66978914572ns
Total frames rendered: 61
Janky frames: 0 (0.00%)
Janky frames (legacy): 51 (83.61%)
"""
        assertEquals(GfxStats(835, 62, 0, 0, 0, 0, 74285 - 6321), parseGfxinfo(a12))
    }

    private fun g(total: Long, janky: Long, age: Long?, pid: Int? = 1, ui: Long = 0, bmp: Long = 0, draw: Long = 0) = GfxStats(pid, total, janky, ui, bmp, draw, age)

    @Test fun aFreshSessionCountsItsFirstReadingIntoStart() {
        val t = SessionTracker()
        assertEquals(emptyMap<String, Counts>(), t.add(g(300, 30, 20_000, ui = 20, bmp = 1, draw = 25)))
        val out = t.add(g(700, 40, 80_000, ui = 25, bmp = 1, draw = 30))
        assertEquals(mapOf(WINDOW_START to Counts(700, 40, 25, 1, 30)), out)
        assertEquals(mapOf(WINDOW_LATER to Counts(100, 5, 0, 0, 0)), t.add(g(800, 45, 140_000, ui = 25, bmp = 1, draw = 30)))
    }

    @Test fun anOldProcessIsABaseline() {
        val t = SessionTracker()
        assertEquals(emptyMap<String, Counts>(), t.add(g(5_000, 100, START_WINDOW_MS + 1)))
        assertEquals(mapOf(WINDOW_LATER to Counts(700, 80)), t.add(g(5_700, 180, 200_000)))
    }

    @Test fun anIntervalBelongsToStartWhenThePreviousReadingWasYoung() {
        val t = SessionTracker()
        t.add(g(100, 0, START_WINDOW_MS)) // exactly the limit still counts as fresh
        t.add(g(200, 0, START_WINDOW_MS - 1_000)) // previous age was the limit: later
        assertEquals(Counts(100, 0), t.pendingStart)
        assertEquals(Counts(100, 0), t.pendingLater)
    }

    @Test fun theGateReleasesPendingCountsPerWindow() {
        val u = SessionTracker()
        assertEquals(emptyMap<String, Counts>(), u.add(g(400, 4, 59_000)))
        assertEquals(emptyMap<String, Counts>(), u.add(g(500, 5, 119_000))) // start interval, 100 more
        assertEquals(emptyMap<String, Counts>(), u.add(g(550, 6, 179_000))) // previous age 119 s: later
        assertEquals(mapOf(WINDOW_START to Counts(500, 5), WINDOW_LATER to Counts(500, 3)), u.add(g(1_000, 8, 239_000)))
    }

    @Test fun aSessionSurvivesARoundTrip() = runBlocking {
        val t = SessionTracker(7)
        t.add(g(400, 4, 30_000, pid = 7, ui = 2))
        t.add(g(450, 5, 90_000, pid = 7, ui = 3))
        val s = FrameSession(
            "a.app", "f", "fp", t.lastTotal, t.lastJanky, t.pendingLater.frames, t.pendingLater.janky, t.released, t.pid, t.lastAgeMs, t.lastSlowUi, t.lastSlowBitmap, t.lastSlowDraw,
            t.pendingStart.frames, t.pendingStart.janky, t.pendingStart.slowUi, t.pendingStart.slowBitmap, t.pendingStart.slowDraw,
            t.pendingLater.slowUi, t.pendingLater.slowBitmap, t.pendingLater.slowDraw,
        )
        val f = Fixture()
        f.db.frameStats().putSession(s)
        assertEquals(s, f.db.frameStats().session("a.app"))
        f.db.close()
    }

    private class Fixture(var counters: String = gfx(0, 0), var ready: Boolean = true) {
        val shell = object : AdbShell {
            override suspend fun run(command: String) = ShellResult(0, if (command == TOP_RESUMED_COMMAND) top("a.app") else counters)
        }
        var filter = "speed-profile"
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        val sampler = FrameSampler(shell, { filter }, { "fp1" }, { db }, { ready })
        suspend fun sample(total: Long, janky: Long) { counters = gfx(total, janky); sampler.tick() }
        suspend fun stats() = db.frameStats().all().first()
    }

    @Test fun storesDeltasPerFilterAndStartsANewSessionWhenTheTotalDrops() = runBlocking {
        val f = Fixture()
        f.db.watchedApps().upsert(WatchedApp("a.app", "fp1"))
        f.sample(1_000, 10)
        f.sample(1_700, 80) // 700 frames, 70 slow
        assertEquals(listOf(FrameStat("a.app", "fp1", "speed-profile", 700, 70)), f.stats())
        f.sample(2_000, 90)
        assertEquals(1_000L to 80L, f.stats().single().let { it.frames to it.janky })
        // Process restarted and the app is now at speed: counters start over, the filter is read again.
        f.filter = "speed"
        f.sample(50, 5)
        assertEquals(1, f.stats().size) // a baseline only
        f.sample(800, 15) // 750 frames
        assertEquals(setOf("speed-profile" to 1_000L, "speed" to 750L), f.stats().map { it.filter to it.frames }.toSet())
        f.db.close()
    }

    @Test fun aSmallerJankyCountAlsoStartsANewSession() = runBlocking {
        val f = Fixture()
        f.db.watchedApps().upsert(WatchedApp("a.app", "fp1"))
        f.sample(1_000, 500)
        f.sample(1_700, 520)
        // Restarted and already past the old total between two reads: only the janky count shows it.
        f.sample(2_500, 10)
        f.sample(3_200, 40)
        assertEquals(listOf(FrameStat("a.app", "fp1", "speed-profile", 1_400, 50)), f.stats())
        f.db.close()
    }

    @Test fun aNewSamplerOnTheSameDatabaseContinuesTheSession() = runBlocking {
        val f = Fixture()
        f.db.watchedApps().upsert(WatchedApp("a.app", "fp1"))
        f.sample(1_000, 10) // baseline
        f.sample(1_300, 40) // 300 frames, not released yet
        // The process dies and comes back with an empty memory.
        val again = FrameSampler(f.shell, { "other" }, { "fp1" }, { f.db }, { true })
        f.counters = gfx(1_700, 80) // 700 frames since the baseline
        again.tick()
        assertEquals(listOf(FrameStat("a.app", "fp1", "speed-profile", 700, 70)), f.stats())
        f.counters = gfx(1_800, 85)
        again.tick() // after release, deltas only: nothing counted twice
        assertEquals(800L to 75L, f.stats().single().let { it.frames to it.janky })
        f.db.close()
    }

    @Test fun aStoredSessionFromAnOlderVersionIsDropped() = runBlocking {
        val f = Fixture()
        f.db.watchedApps().upsert(WatchedApp("a.app", "fp1"))
        f.sample(1_000, 10)
        f.sample(1_300, 40)
        // The app updated while the process was dead: its counts start a new session as a baseline.
        val again = FrameSampler(f.shell, { "speed-profile" }, { "fp2" }, { f.db }, { true })
        f.counters = gfx(1_700, 80)
        again.tick()
        f.counters = gfx(2_200, 90)
        again.tick()
        assertEquals(emptyList<FrameStat>(), f.stats())
        f.db.close()
    }

    @Test fun skipsUnwatchedAutoOffAndNotReady() = runBlocking {
        val f = Fixture()
        f.sample(1_000, 0); f.sample(3_000, 0)
        f.db.watchedApps().upsert(WatchedApp("a.app", "fp1", autoOptimize = false))
        f.sample(1_000, 0); f.sample(3_000, 0)
        f.db.watchedApps().upsert(WatchedApp("a.app", "fp1"))
        f.ready = false
        f.sample(1_000, 0); f.sample(3_000, 0)
        assertTrue(f.stats().isEmpty())
        f.db.close()
    }

    private fun gfxFull(pid: Int, total: Long, janky: Long, age: Long, ui: Long = 0) =
        "Uptime: 1000000000 Realtime: 1\n** Graphics info for pid $pid [a.app] **\nStats since: ${(1_000_000_000L - age) * 1_000_000}ns\n" +
            "Total frames rendered: $total\nJanky frames: $janky (1.00%)\nNumber Slow UI thread: $ui\n"

    @Test fun storesBothWindowsAndTreatsASamePidResetAsABaseline() = runBlocking {
        val f = Fixture()
        f.db.watchedApps().upsert(WatchedApp("a.app", "fp1"))
        f.counters = gfxFull(10, 400, 40, 30_000, ui = 30); f.sampler.tick() // fresh: counts from zero
        f.counters = gfxFull(10, 800, 60, 90_000, ui = 40); f.sampler.tick() // start interval, gate passed
        f.counters = gfxFull(10, 1_000, 70, 150_000, ui = 45); f.sampler.tick() // later
        assertEquals(
            setOf(FrameStat("a.app", "fp1", "speed-profile", 800, 60, "start", 40), FrameStat("a.app", "fp1", "speed-profile", 200, 10, "later", 5)),
            f.stats().toSet(),
        )
        // Same pid, smaller counters and young age: gfxinfo was reset, so this is a baseline, not a fresh start.
        f.counters = gfxFull(10, 50, 1, 5_000); f.sampler.tick()
        assertEquals(2, f.stats().size)
        assertEquals(800L, f.stats().first { it.window == "start" }.frames)
        // A different pid with young counters is fresh.
        f.counters = gfxFull(11, 700, 7, 20_000); f.sampler.tick()
        assertEquals(1_500L, f.stats().first { it.window == "start" }.frames)
        f.db.close()
    }

    @Test fun migratesVersion4KeepingOldRowsAsLater() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("migrate4.db")
        // Version 4 tables as in schemas/dev.speedcompiler.tv.watch.AppDatabase/4.json.
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("migrate4.db"), null).use {
            it.execSQL("CREATE TABLE `watched_app` (`packageName` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `autoOptimize` INTEGER NOT NULL, `lastResult` TEXT, `lastOptimizedAt` INTEGER, `useSpeed` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`packageName`))")
            it.execSQL("CREATE TABLE `event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMillis` INTEGER NOT NULL, `packageName` TEXT, `kind` TEXT NOT NULL, `message` TEXT NOT NULL)")
            it.execSQL("CREATE TABLE `sync_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            it.execSQL("CREATE TABLE `queue_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `source` TEXT NOT NULL, `state` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL, `startedAt` INTEGER, `finishedAt` INTEGER, `failureReason` TEXT, `runNow` INTEGER NOT NULL)")
            it.execSQL("CREATE TABLE `frame_stat` (`packageName` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `filter` TEXT NOT NULL, `frames` INTEGER NOT NULL, `janky` INTEGER NOT NULL, PRIMARY KEY(`packageName`, `fingerprint`, `filter`))")
            it.execSQL("CREATE TABLE `frame_session` (`packageName` TEXT NOT NULL, `filter` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `lastTotal` INTEGER NOT NULL, `lastJanky` INTEGER NOT NULL, `frames` INTEGER NOT NULL, `janky` INTEGER NOT NULL, `released` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
            it.execSQL("INSERT INTO frame_stat VALUES ('a.app', 'fp1', 'speed', 900, 90)")
            it.execSQL("INSERT INTO frame_session VALUES ('a.app', 'speed', 'fp1', 5, 1, 300, 3, 0)")
            it.version = 4
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migrate4.db").allowMainThreadQueries().build()
        assertEquals(listOf(FrameStat("a.app", "fp1", "speed", 900, 90, "later", 0, 0, 0)), db.frameStats().all().first())
        val s = db.frameStats().session("a.app")!!
        assertEquals(300L to 3L, s.frames to s.janky)
        assertNull(s.pid)
        db.frameStats().put(FrameStat("a.app", "fp1", "speed", 10, 1, "start"))
        assertEquals(2, db.frameStats().all().first().size)
        db.close()
    }

    @Test fun migratesVersion2WithoutLosingRows() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("migrate.db")
        // The version 2 tables, as in schemas/dev.speedcompiler.tv.watch.AppDatabase/2.json.
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("migrate.db"), null).use {
            it.execSQL("CREATE TABLE `watched_app` (`packageName` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `autoOptimize` INTEGER NOT NULL, `lastResult` TEXT, `lastOptimizedAt` INTEGER, PRIMARY KEY(`packageName`))")
            it.execSQL("CREATE TABLE `event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMillis` INTEGER NOT NULL, `packageName` TEXT, `kind` TEXT NOT NULL, `message` TEXT NOT NULL)")
            it.execSQL("CREATE TABLE `sync_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            it.execSQL("CREATE TABLE `queue_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `source` TEXT NOT NULL, `state` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL, `startedAt` INTEGER, `finishedAt` INTEGER, `failureReason` TEXT, `runNow` INTEGER NOT NULL)")
            it.execSQL("INSERT INTO watched_app VALUES ('a.app', 'fp1', 1, 'Optimized (checked)', 5)")
            it.version = 2
        }
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migrate.db").allowMainThreadQueries().build()
        val app = db.watchedApps().get("a.app")!!
        assertEquals("Optimized (checked)", app.lastResult)
        assertEquals(false, app.useSpeed)
        db.frameStats().put(FrameStat("a.app", "fp1", "speed", 1, 0))
        assertEquals(1, db.frameStats().all().first().size)
        db.frameStats().putSession(FrameSession("a.app", "speed", "fp1", 5, 1, 2, 0, false)) // the version 4 table
        assertEquals(5L, db.frameStats().session("a.app")!!.lastTotal)
        db.close()
    }
}
