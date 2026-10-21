package dev.speedcompiler.tv.watch

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watched_app")
data class WatchedApp(
    @PrimaryKey val packageName: String,
    val fingerprint: String,
    val autoOptimize: Boolean = true,
    val lastResult: String? = null,
    val lastOptimizedAt: Long? = null,
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

@Dao
interface WatchedAppDao {
    @Query("SELECT * FROM watched_app ORDER BY packageName")
    fun all(): Flow<List<WatchedApp>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: WatchedApp)

    @Query("DELETE FROM watched_app WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM event ORDER BY timeMillis DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Event>>

    @Insert
    suspend fun insert(event: Event)
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncState)
}

@Database(entities = [WatchedApp::class, Event::class, SyncState::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchedApps(): WatchedAppDao
    abstract fun events(): EventDao
    abstract fun syncState(): SyncStateDao
}
