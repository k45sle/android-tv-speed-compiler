package dev.speedcompiler.tv.watch

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watched_app")
data class WatchedApp(
    @PrimaryKey val packageName: String,
    val fingerprint: String,
    val autoOptimize: Boolean = true,
    val lastResult: String? = null,
    val lastOptimizedAt: Long? = null,
    /** Compile this app with `speed` (Smoother mode), now and after its updates. */
    @ColumnInfo(defaultValue = "0") val useSpeed: Boolean = false,
)

/**
 * Frames drawn and slow frames counted while [packageName] was in front, per app version, compiler filter and [window]
 * ("start": the app's first minute or two after launch, "later": the rest). The cause columns only count janky frames.
 * Rows from before schema 5 never held a session's first reading, so they are mostly post-start frames and migrate to "later".
 */
@Entity(tableName = "frame_stat", primaryKeys = ["packageName", "fingerprint", "filter", "window"])
data class FrameStat(
    val packageName: String,
    val fingerprint: String,
    val filter: String,
    val frames: Long,
    val janky: Long,
    @ColumnInfo(defaultValue = "'later'") val window: String = WINDOW_LATER,
    @ColumnInfo(defaultValue = "0") val slowUi: Long = 0,
    @ColumnInfo(defaultValue = "0") val slowBitmap: Long = 0,
    @ColumnInfo(defaultValue = "0") val slowDraw: Long = 0,
    /** CPU time of the app's JIT thread and the wall time of the readings, in this window. */
    @ColumnInfo(defaultValue = "0") val jitMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
)

/**
 * The sampler's running session for one app, kept so a killed process does not lose its baseline or unreleased frames.
 * [frames] and [janky] are the pending "later" counts (the only pending ones before schema 5).
 */
@Entity(tableName = "frame_session")
data class FrameSession(
    @PrimaryKey val packageName: String,
    val filter: String,
    val fingerprint: String,
    val lastTotal: Long,
    val lastJanky: Long,
    val frames: Long,
    val janky: Long,
    val released: Boolean,
    val pid: Int? = null,
    val lastAgeMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val lastSlowUi: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastSlowBitmap: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastSlowDraw: Long = 0,
    @ColumnInfo(defaultValue = "0") val startFrames: Long = 0,
    @ColumnInfo(defaultValue = "0") val startJanky: Long = 0,
    @ColumnInfo(defaultValue = "0") val startSlowUi: Long = 0,
    @ColumnInfo(defaultValue = "0") val startSlowBitmap: Long = 0,
    @ColumnInfo(defaultValue = "0") val startSlowDraw: Long = 0,
    @ColumnInfo(defaultValue = "0") val laterSlowUi: Long = 0,
    @ColumnInfo(defaultValue = "0") val laterSlowBitmap: Long = 0,
    @ColumnInfo(defaultValue = "0") val laterSlowDraw: Long = 0,
    val lastJitMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val startJitMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val startDurationMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val laterJitMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val laterDurationMs: Long = 0,
    /** The session's whole start window so far, kept apart from the pending counts, which are released and cleared. */
    @ColumnInfo(defaultValue = "0") val sessionJitMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val sessionDurationMs: Long = 0,
    /** True from a fresh session's first reading until its start window has been judged. */
    @ColumnInfo(defaultValue = "0") val startOpen: Boolean = false,
    /** True when the session's first reading was inside the start window, so its start window was seen from the beginning. */
    @ColumnInfo(defaultValue = "0") val beganFresh: Boolean = false,
)

@Entity(tableName = "event")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val packageName: String?,
    val kind: String,
    val message: String,
)

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val key: String,
    val value: String,
)

enum class QueueSource { AUTO, MANUAL, UPDATE }

enum class QueueState { QUEUED, WAITING_GATE, RUNNING, DONE, FAILED, SKIPPED }

/** One optimize job. The queue runs one job at a time; [runNow] jobs skip the gate and go to the front. */
@Entity(tableName = "queue_item")
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val source: QueueSource,
    val state: QueueState = QueueState.QUEUED,
    val enqueuedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val failureReason: String? = null,
    val runNow: Boolean = false,
)

@Dao
interface QueueDao {
    /** Unfinished jobs in run order: the running job, then run-now jobs, then first come first served. */
    @Query(
        "SELECT * FROM queue_item WHERE state IN ('QUEUED', 'WAITING_GATE', 'RUNNING') " +
            "ORDER BY state = 'RUNNING' DESC, runNow DESC, enqueuedAt, id",
    )
    fun active(): Flow<List<QueueItem>>

    /** Finished jobs, newest first. */
    @Query("SELECT * FROM queue_item WHERE state IN ('DONE', 'FAILED', 'SKIPPED') ORDER BY finishedAt DESC, id DESC LIMIT 50")
    fun history(): Flow<List<QueueItem>>

    @Query("SELECT * FROM queue_item WHERE state IN ('QUEUED', 'WAITING_GATE', 'RUNNING')")
    suspend fun activeNow(): List<QueueItem>

    @Query("SELECT * FROM queue_item WHERE packageName = :packageName AND state IN ('QUEUED', 'WAITING_GATE', 'RUNNING') LIMIT 1")
    suspend fun activeFor(packageName: String): QueueItem?

    /** Drops finished rows that ended before [before]. */
    @Query("DELETE FROM queue_item WHERE state IN ('DONE', 'FAILED', 'SKIPPED') AND finishedAt < :before")
    suspend fun pruneFinished(before: Long)

    @Query("UPDATE queue_item SET state = 'QUEUED', startedAt = NULL WHERE id = :id AND state = 'RUNNING'")
    suspend fun requeueIfRunning(id: Long)

    /** Every RUNNING row back to QUEUED; only call it holding the run lock (see requeueStaleRunning). */
    @Query("UPDATE queue_item SET state = 'QUEUED', startedAt = NULL WHERE state = 'RUNNING'")
    suspend fun requeueAllRunning()

    @Query("SELECT * FROM queue_item WHERE id = :id")
    suspend fun get(id: Long): QueueItem?

    @Insert
    suspend fun insert(item: QueueItem): Long

    @Update
    suspend fun update(item: QueueItem)
}

@Dao
interface WatchedAppDao {
    @Query("SELECT * FROM watched_app ORDER BY packageName")
    fun all(): Flow<List<WatchedApp>>

    @Query("SELECT * FROM watched_app")
    suspend fun list(): List<WatchedApp>

    @Query("SELECT * FROM watched_app WHERE packageName = :packageName")
    suspend fun get(packageName: String): WatchedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: WatchedApp)

    /** Single-column write so it cannot clobber fields a running job updates. */
    @Query("UPDATE watched_app SET autoOptimize = :on WHERE packageName = :packageName")
    suspend fun setAutoOptimize(packageName: String, on: Boolean)

    /** Writes only the result columns, so a concurrent [setAutoOptimize] survives. */
    @Query("UPDATE watched_app SET fingerprint = :fingerprint, lastResult = :lastResult, lastOptimizedAt = :at WHERE packageName = :packageName")
    suspend fun recordResult(packageName: String, fingerprint: String, lastResult: String, at: Long)

    @Query("UPDATE watched_app SET useSpeed = :on WHERE packageName = :packageName")
    suspend fun setUseSpeed(packageName: String, on: Boolean = true)

    @Query("DELETE FROM watched_app WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface FrameStatDao {
    @Query("SELECT * FROM frame_stat")
    fun all(): Flow<List<FrameStat>>

    @Query("SELECT * FROM frame_stat WHERE packageName = :packageName AND fingerprint = :fingerprint AND filter = :filter AND `window` = :window")
    suspend fun get(packageName: String, fingerprint: String, filter: String, window: String): FrameStat?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(stat: FrameStat)

    @Query("SELECT * FROM frame_session WHERE packageName = :packageName")
    suspend fun session(packageName: String): FrameSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(session: FrameSession)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM event ORDER BY timeMillis DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Event>>

    @Query("SELECT * FROM event WHERE kind = 'reset_done'")
    fun resets(): Flow<List<Event>>

    @Insert
    suspend fun insert(event: Event)

    @Query("DELETE FROM event WHERE timeMillis < :before")
    suspend fun pruneBefore(before: Long)
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM sync_state WHERE `key` LIKE :prefix || '%'")
    suspend fun withPrefix(prefix: String): List<SyncState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncState)
}

@Database(
    entities = [WatchedApp::class, Event::class, SyncState::class, QueueItem::class, FrameStat::class, FrameSession::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queue(): QueueDao
    abstract fun watchedApps(): WatchedAppDao
    abstract fun events(): EventDao
    abstract fun syncState(): SyncStateDao
    abstract fun frameStats(): FrameStatDao
}
