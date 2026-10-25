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
    @Test fun parsesTotalsAndIgnoresTheLegacyLine() {
        assertEquals(1234L to 56L, parseGfxinfo(gfx(1234, 56)))
        assertNull(parseGfxinfo("Total frames rendered: 10"))
        assertNull(parseGfxinfo(""))
    }

    @Test fun firstReadingIsABaselineAndShortSessionsAreDropped() {
        val t = SessionTracker()
        assertNull(t.add(5_000, 100)) // baseline: frames from before we looked do not count
        assertNull(t.add(5_300, 130)) // 300 frames, under the minimum
        assertEquals(700L to 80L, t.add(5_700, 180)) // passes 600 frames: everything so far in one go
        assertEquals(100L to 5L, t.add(5_800, 185))
        assertNull(t.add(5_800, 185)) // nothing drawn
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
        db.close()
    }
}
