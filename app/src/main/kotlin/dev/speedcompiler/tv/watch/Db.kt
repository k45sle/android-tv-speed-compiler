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

/** Frames drawn and slow frames counted while [packageName] was in front, per app version and compiler filter. */
@Entity(tableName = "frame_stat", primaryKeys = ["packageName", "fingerprint", "filter"])
data class FrameStat(
    val packageName: String,
    val fingerprint: String,
    val filter: String,
    val frames: Long,
    val janky: Long,
)

/** The sampler's running session for one app, kept so a killed process does not lose its baseline or unreleased frames. */
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

enum class QueueSource { AUTO, MANUAL }

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

    @Query("SELECT * FROM frame_stat WHERE packageName = :packageName AND fingerprint = :fingerprint AND filter = :filter")
    suspend fun get(packageName: String, fingerprint: String, filter: String): FrameStat?

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
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun queue(): QueueDao
    abstract fun watchedApps(): WatchedAppDao
    abstract fun events(): EventDao
    abstract fun syncState(): SyncStateDao
    abstract fun frameStats(): FrameStatDao
}
