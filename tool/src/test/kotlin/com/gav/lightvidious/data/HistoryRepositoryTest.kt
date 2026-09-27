package com.gav.lightvidious.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryRepositoryTest {
    @Test
    fun `search history deduplicates normalized queries and orders newest first`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 100L
        val repository = HistoryRepository(dao, now = { clock })

        repository.recordSearch("  Kotlin   Coroutines  ")
        clock = 200L
        repository.recordSearch("another query")
        clock = 300L
        repository.recordSearch("KOTLIN COROUTINES")

        val history = repository.searchHistory()

        assertEquals(listOf("KOTLIN COROUTINES", "another query"), history.map { it.query })
        assertEquals(2, history.first().useCount)
        assertEquals(300L, history.first().lastSearchedAt)
    }

    @Test
    fun `search history excludes direct YouTube video URLs`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, now = { 100L })

        repository.recordSearch("https://youtu.be/dQw4w9WgXcQ?t=12")
        repository.recordSearch("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertTrue(repository.searchHistory().isEmpty())
    }

    @Test
    fun `watch history upserts metadata and moves a rewatch to newest`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 100L
        val repository = HistoryRepository(dao, now = { clock })

        repository.recordWatch(video(videoId = "first-video", title = "Original title"))
        clock = 200L
        repository.recordWatch(video(videoId = "second-video", title = "Second video"))
        clock = 300L
        repository.recordWatch(video(videoId = "first-video", title = "Updated title"))

        val history = repository.watchHistory()

        assertEquals(listOf("first-video", "second-video"), history.map { it.video.videoId })
        assertEquals("Updated title", history.first().video.title)
        assertEquals(300L, history.first().lastWatchedAt)
    }

    @Test
    fun `history syncer respects local and account history toggles`() = runTest {
        val dao = FakeHistoryDao()
        val repository = HistoryRepository(dao, now = { 100L })
        val syncer = HistorySyncer(repository)
        val account = AccountSession(
            instanceUrl = "https://invidious.example",
            token = "token",
            accountKey = "account",
        )

        syncer.recordPlayback(
            video = video(videoId = "disabled-video", title = "Disabled"),
            settings = ClientSettings(
                saveWatchHistory = false,
                syncAccountHistory = false,
            ),
            account = account,
        )

        assertTrue(repository.watchHistory().isEmpty())
        assertTrue(repository.pendingServerWatches(account.accountKey).isEmpty())

        syncer.recordPlayback(
            video = video(videoId = "local-video", title = "Local only"),
            settings = ClientSettings(
                saveWatchHistory = true,
                syncAccountHistory = false,
            ),
            account = account,
        )

        assertEquals(listOf("local-video"), repository.watchHistory().map { it.video.videoId })
        assertTrue(repository.pendingServerWatches(account.accountKey).isEmpty())
    }

    @Test
    fun `outbox is account scoped deduplicated and completed in queue order`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 100L
        val repository = HistoryRepository(dao, now = { clock })

        repository.enqueueServerWatch("account-a", "video-one")
        clock = 200L
        repository.enqueueServerWatch("account-b", "other-account-video")
        clock = 300L
        repository.enqueueServerWatch("account-a", "video-two")
        clock = 400L
        repository.enqueueServerWatch("account-a", "video-one")

        assertEquals(
            listOf("video-two", "video-one"),
            repository.pendingServerWatches("account-a"),
        )
        assertEquals(
            listOf("other-account-video"),
            repository.pendingServerWatches("account-b"),
        )

        repository.completeServerWatch("account-a", "video-two")
        assertEquals(listOf("video-one"), repository.pendingServerWatches("account-a"))

        repository.clearPendingServerWatches("account-a")
        assertTrue(repository.pendingServerWatches("account-a").isEmpty())
        assertEquals(
            listOf("other-account-video"),
            repository.pendingServerWatches("account-b"),
        )
    }

    @Test
    fun `outbox returns at most one sync batch`() = runTest {
        val dao = FakeHistoryDao()
        var clock = 0L
        val repository = HistoryRepository(dao, now = { clock++ })

        repeat(30) { index ->
            repository.enqueueServerWatch("account", "video-$index")
        }

        assertEquals(
            (0 until 25).map { "video-$it" },
            repository.pendingServerWatches("account"),
        )
    }

    private fun video(videoId: String, title: String): VideoSummary = VideoSummary(
        videoId = videoId,
        title = title,
        author = "Author",
        lengthSeconds = 120L,
        viewCount = 1_000L,
        publishedText = "today",
        liveNow = false,
        thumbnailUrl = "https://example.test/$videoId.jpg",
    )

    private class FakeHistoryDao : HistoryDao {
        private val searches = mutableMapOf<String, SearchHistoryEntity>()
        private val watches = mutableMapOf<String, WatchHistoryEntity>()
        private val outbox = mutableMapOf<Pair<String, String>, ServerHistoryOutboxEntity>()

        override suspend fun listSearchHistory(limit: Int): List<SearchHistoryEntity> = searches.values
            .sortedByDescending(SearchHistoryEntity::lastSearchedAt)
            .take(limit)

        override suspend fun findSearch(normalizedQuery: String): SearchHistoryEntity? =
            searches[normalizedQuery]

        override suspend fun upsertSearch(entity: SearchHistoryEntity) {
            searches[entity.normalizedQuery] = entity
        }

        override suspend fun pruneSearchHistory(limit: Int) {
            val retained = listSearchHistory(limit).mapTo(mutableSetOf(), SearchHistoryEntity::normalizedQuery)
            searches.keys.retainAll(retained)
        }

        override suspend fun clearSearchHistory() {
            searches.clear()
        }

        override suspend fun listWatchHistory(limit: Int): List<WatchHistoryEntity> = watches.values
            .sortedByDescending(WatchHistoryEntity::lastWatchedAt)
            .take(limit)

        override suspend fun upsertWatch(entity: WatchHistoryEntity) {
            watches[entity.videoId] = entity
        }

        override suspend fun pruneWatchHistory(limit: Int) {
            val retained = listWatchHistory(limit).mapTo(mutableSetOf(), WatchHistoryEntity::videoId)
            watches.keys.retainAll(retained)
        }

        override suspend fun clearWatchHistory() {
            watches.clear()
        }

        override suspend fun enqueueServerWatch(entity: ServerHistoryOutboxEntity) {
            outbox[entity.accountKey to entity.videoId] = entity
        }

        override suspend fun listPendingServerWatches(
            accountKey: String,
            limit: Int,
        ): List<ServerHistoryOutboxEntity> = outbox.values
            .asSequence()
            .filter { it.accountKey == accountKey }
            .sortedBy(ServerHistoryOutboxEntity::queuedAt)
            .take(limit)
            .toList()

        override suspend fun deletePendingServerWatch(accountKey: String, videoId: String) {
            outbox.remove(accountKey to videoId)
        }

        override suspend fun clearPendingServerWatches(accountKey: String) {
            outbox.entries.removeAll { it.value.accountKey == accountKey }
        }
    }
}
