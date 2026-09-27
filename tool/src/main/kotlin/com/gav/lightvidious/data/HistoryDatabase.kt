package com.gav.lightvidious.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "search_history")
internal data class SearchHistoryEntity(
    @PrimaryKey val normalizedQuery: String,
    val displayQuery: String,
    val lastSearchedAt: Long,
    val useCount: Int,
)

@Entity(tableName = "watch_history")
internal data class WatchHistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val author: String,
    val lengthSeconds: Long,
    val viewCount: Long,
    val publishedText: String,
    val liveNow: Boolean,
    val thumbnailUrl: String?,
    val lastWatchedAt: Long,
)

@Entity(
    tableName = "server_history_outbox",
    primaryKeys = ["accountKey", "videoId"],
)
internal data class ServerHistoryOutboxEntity(
    val accountKey: String,
    val videoId: String,
    val queuedAt: Long,
)

@Dao
internal interface HistoryDao {
    @Query("SELECT * FROM search_history ORDER BY lastSearchedAt DESC LIMIT :limit")
    suspend fun listSearchHistory(limit: Int): List<SearchHistoryEntity>

    @Query("SELECT * FROM search_history WHERE normalizedQuery = :normalizedQuery LIMIT 1")
    suspend fun findSearch(normalizedQuery: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearch(entity: SearchHistoryEntity)

    @Query(
        "DELETE FROM search_history WHERE normalizedQuery NOT IN " +
            "(SELECT normalizedQuery FROM search_history ORDER BY lastSearchedAt DESC LIMIT :limit)",
    )
    suspend fun pruneSearchHistory(limit: Int)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()

    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC LIMIT :limit")
    suspend fun listWatchHistory(limit: Int): List<WatchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatch(entity: WatchHistoryEntity)

    @Query(
        "DELETE FROM watch_history WHERE videoId NOT IN " +
            "(SELECT videoId FROM watch_history ORDER BY lastWatchedAt DESC LIMIT :limit)",
    )
    suspend fun pruneWatchHistory(limit: Int)

    @Query("DELETE FROM watch_history")
    suspend fun clearWatchHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueServerWatch(entity: ServerHistoryOutboxEntity)

    @Query(
        "SELECT * FROM server_history_outbox WHERE accountKey = :accountKey " +
            "ORDER BY queuedAt ASC LIMIT :limit",
    )
    suspend fun listPendingServerWatches(
        accountKey: String,
        limit: Int,
    ): List<ServerHistoryOutboxEntity>

    @Query("DELETE FROM server_history_outbox WHERE accountKey = :accountKey AND videoId = :videoId")
    suspend fun deletePendingServerWatch(accountKey: String, videoId: String)

    @Query("DELETE FROM server_history_outbox WHERE accountKey = :accountKey")
    suspend fun clearPendingServerWatches(accountKey: String)

    @Transaction
    suspend fun clearWatchHistoryAndPending(accountKey: String?) {
        clearWatchHistory()
        if (accountKey != null) clearPendingServerWatches(accountKey)
    }
}

@Database(
    entities = [
        SearchHistoryEntity::class,
        WatchHistoryEntity::class,
        ServerHistoryOutboxEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class HistoryDatabase : RoomDatabase() {
    internal abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "lightvidious-history.db"
    }
}
