package com.gav.lightvidious.data

import java.util.Locale

class HistoryRepository internal constructor(
    private val dao: HistoryDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun searchHistory(): List<SearchHistoryEntry> =
        dao.listSearchHistory(MAX_SEARCH_HISTORY).map { entity ->
            SearchHistoryEntry(
                query = entity.displayQuery,
                lastSearchedAt = entity.lastSearchedAt,
                useCount = entity.useCount,
            )
        }

    suspend fun recordSearch(query: String) {
        val display = query.trim().replace(Regex("\\s+"), " ")
        if (display.isEmpty() || extractYouTubeVideoId(display) != null) return
        val normalized = display.lowercase(Locale.ROOT)
        val existing = dao.findSearch(normalized)
        dao.upsertSearch(
            SearchHistoryEntity(
                normalizedQuery = normalized,
                displayQuery = display,
                lastSearchedAt = now(),
                useCount = (existing?.useCount ?: 0) + 1,
            ),
        )
        dao.pruneSearchHistory(MAX_SEARCH_HISTORY)
    }

    suspend fun clearSearchHistory() = dao.clearSearchHistory()

    suspend fun watchHistory(): List<WatchHistoryEntry> =
        dao.listWatchHistory(MAX_WATCH_HISTORY).map { entity ->
            WatchHistoryEntry(
                video = VideoSummary(
                    videoId = entity.videoId,
                    title = entity.title,
                    author = entity.author,
                    lengthSeconds = entity.lengthSeconds,
                    viewCount = entity.viewCount,
                    publishedText = entity.publishedText,
                    liveNow = entity.liveNow,
                    thumbnailUrl = entity.thumbnailUrl,
                ),
                lastWatchedAt = entity.lastWatchedAt,
            )
        }

    suspend fun recordWatch(video: VideoSummary) {
        dao.upsertWatch(
            WatchHistoryEntity(
                videoId = video.videoId,
                title = video.title,
                author = video.author,
                lengthSeconds = video.lengthSeconds,
                viewCount = video.viewCount,
                publishedText = video.publishedText,
                liveNow = video.liveNow,
                thumbnailUrl = video.thumbnailUrl,
                lastWatchedAt = now(),
            ),
        )
        dao.pruneWatchHistory(MAX_WATCH_HISTORY)
    }

    suspend fun clearWatchHistory(accountKey: String? = null) =
        dao.clearWatchHistoryAndPending(accountKey)

    suspend fun enqueueServerWatch(accountKey: String, videoId: String) {
        dao.enqueueServerWatch(
            ServerHistoryOutboxEntity(
                accountKey = accountKey,
                videoId = videoId,
                queuedAt = now(),
            ),
        )
    }

    internal suspend fun pendingServerWatches(accountKey: String): List<String> =
        dao.listPendingServerWatches(accountKey, MAX_OUTBOX_BATCH).map { it.videoId }

    internal suspend fun completeServerWatch(accountKey: String, videoId: String) {
        dao.deletePendingServerWatch(accountKey, videoId)
    }

    suspend fun clearPendingServerWatches(accountKey: String) {
        dao.clearPendingServerWatches(accountKey)
    }

    private companion object {
        const val MAX_SEARCH_HISTORY = 30
        const val MAX_WATCH_HISTORY = 500
        const val MAX_OUTBOX_BATCH = 25
    }
}

class HistorySyncer(
    private val history: HistoryRepository,
) {
    suspend fun recordPlayback(
        video: VideoSummary,
        settings: ClientSettings,
        account: AccountSession?,
    ) {
        if (settings.saveWatchHistory) {
            history.recordWatch(video)
        }
        if (settings.syncAccountHistory && account != null) {
            history.enqueueServerWatch(account.accountKey, video.videoId)
            flush(account, settings.proxyMedia)
        }
    }

    suspend fun flush(account: AccountSession, proxyMedia: Boolean) {
        val pending = history.pendingServerWatches(account.accountKey)
        if (pending.isEmpty()) return
        InvidiousApi(account.instanceUrl, proxyMedia).use { api ->
            for (videoId in pending) {
                val result = api.markWatched(account.token, videoId)
                if (result.isFailure) break
                history.completeServerWatch(account.accountKey, videoId)
            }
        }
    }
}
