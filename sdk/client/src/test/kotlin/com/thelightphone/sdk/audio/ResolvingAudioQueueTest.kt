package com.thelightphone.sdk.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ResolvingAudioQueueTest {
    @Test
    fun deduplicationKeepsRequestedTrackAndAdvancesWithoutAController() = runTest {
        val fixture = Fixture(this)
        fixture.queue.start(listOf(a, b, a, c), 2, "Album", false)
        runCurrent()
        assertEquals(listOf(a, b, c), fixture.queue.snapshot.items)
        assertEquals(a, fixture.output.current)
        assertEquals("Album", fixture.queue.snapshot.sourceName)

        fixture.queue.onPlaybackChanged(true, true, 9_000, 10_000)
        fixture.queue.onEnded()
        runCurrent()

        assertEquals(b, fixture.output.current)
        assertEquals(1, fixture.queue.snapshot.currentIndex)
        assertTrue(fixture.output.playWhenReady)
        assertEquals(listOf("a", "b"), fixture.resolved)
    }

    @Test
    fun replacingQueueRejectsEvenNonCancellableStaleResolution() = runTest {
        val old = CompletableDeferred<LightAudioResolvedSource>()
        val fixture = Fixture(this, resolve = { item ->
            if (item == a) withContext(NonCancellable) { old.await() } else source(item)
        })
        fixture.queue.start(listOf(a), 0, "Old", false)
        runCurrent()
        fixture.queue.start(listOf(b), 0, "New", false)
        runCurrent()
        old.complete(source(a))
        runCurrent()

        assertEquals(b, fixture.output.current)
        assertEquals(listOf(b), fixture.output.loaded)
        assertEquals("New", fixture.queue.snapshot.sourceName)
    }

    @Test
    fun pauseDuringResolutionBeatsAutoplayAndStopFeedback() = runTest {
        val resolved = CompletableDeferred<LightAudioResolvedSource>()
        val fixture = Fixture(this, resolve = { resolved.await() })
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()
        assertTrue(fixture.queue.snapshot.playWhenReady)
        fixture.queue.control(LightAudioQueueCommand.Pause)
        resolved.complete(source(a))
        runCurrent()

        assertEquals(a, fixture.output.current)
        assertFalse(fixture.output.playWhenReady)
        assertFalse(fixture.queue.snapshot.playWhenReady)
        assertFalse(fixture.queue.snapshot.isLoading)
    }

    @Test
    fun delayedStoppedPlayerReportCannotClearPendingAutoplayOrPosition() = runTest {
        val resolved = CompletableDeferred<LightAudioResolvedSource>()
        val fixture = Fixture(this, resolve = { resolved.await() })
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()
        fixture.queue.onPlaybackChanged(false, false, 8_000, 9_000)
        resolved.complete(source(a))
        runCurrent()

        assertTrue(fixture.output.playWhenReady)
        assertEquals(0L, fixture.queue.snapshot.positionMs)
    }

    @Test
    fun bufferingDoesNotTurnToggleIntoPlay() = runTest {
        val fixture = Fixture(this)
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()
        fixture.queue.onPlaybackChanged(false, true, 1_000, 10_000)
        fixture.queue.control(LightAudioQueueCommand.TogglePlayPause)

        assertFalse(fixture.output.playWhenReady)
        assertFalse(fixture.queue.snapshot.playWhenReady)
    }

    @Test
    fun continuationDeduplicatesAndRetainsPauseWhileItLoads() = runTest {
        val recommendations = CompletableDeferred<List<LightAudioQueueItem>>()
        val fixture = Fixture(this, continuation = { recommendations.await() })
        fixture.queue.start(listOf(a), 0, "Radio", true)
        runCurrent()
        fixture.queue.onEnded()
        fixture.queue.onEnded()
        fixture.queue.control(LightAudioQueueCommand.Next)
        runCurrent()
        fixture.queue.control(LightAudioQueueCommand.Pause)
        recommendations.complete(listOf(a, b, b, c))
        runCurrent()

        assertEquals(listOf(a, b, c), fixture.queue.snapshot.items)
        assertEquals(b, fixture.output.current)
        assertFalse(fixture.output.playWhenReady)
        assertEquals(1, fixture.continuations)
        assertEquals("Radio", fixture.queue.snapshot.sourceName)
    }

    @Test
    fun replacingQueueRejectsStaleRecommendations() = runTest {
        val recommendations = CompletableDeferred<List<LightAudioQueueItem>>()
        val fixture = Fixture(this, continuation = {
            withContext(NonCancellable) { recommendations.await() }
        })
        fixture.queue.start(listOf(a), 0, null, true)
        runCurrent()
        fixture.queue.onEnded()
        runCurrent()
        fixture.queue.start(listOf(c), 0, null, false)
        runCurrent()
        recommendations.complete(listOf(b))
        runCurrent()

        assertEquals(listOf(c), fixture.queue.snapshot.items)
        assertEquals(c, fixture.output.current)
        assertEquals(listOf("a", "c"), fixture.resolved)
    }

    @Test
    fun emptyContinuationEndsOnceAndAllowsIdleShutdown() = runTest {
        val fixture = Fixture(this, continuation = { listOf(a) })
        fixture.queue.start(listOf(a), 0, null, true)
        runCurrent()
        fixture.queue.onEnded()
        runCurrent()
        fixture.queue.onEnded()
        fixture.queue.control(LightAudioQueueCommand.Next)
        runCurrent()

        assertEquals(1, fixture.continuations)
        assertFalse(fixture.queue.snapshot.isPlaying)
        assertFalse(fixture.queue.snapshot.playWhenReady)
        assertFalse(fixture.queue.snapshot.isLoading)
    }

    @Test
    fun restartingCurrentTrackCancelsPendingContinuation() = runTest {
        val recommendations = CompletableDeferred<List<LightAudioQueueItem>>()
        val fixture = Fixture(this, continuation = {
            withContext(NonCancellable) { recommendations.await() }
        })
        fixture.queue.start(listOf(a), 0, null, true)
        runCurrent()
        fixture.queue.onEnded()
        runCurrent()
        fixture.queue.control(LightAudioQueueCommand.Previous)
        runCurrent()
        recommendations.complete(listOf(b))
        runCurrent()

        assertEquals(listOf(a), fixture.queue.snapshot.items)
        assertEquals(a, fixture.output.current)
        assertTrue(fixture.output.playWhenReady)
        assertEquals(0L, fixture.output.positionMs)
    }

    @Test
    fun repeatAllWrapsAndRepeatOneRestartsCurrentInsteadOfAdvancing() = runTest {
        val fixture = Fixture(this)
        fixture.queue.start(listOf(a, b), 1, null, false)
        runCurrent()
        fixture.queue.control(LightAudioQueueCommand.CycleRepeat)
        fixture.queue.onEnded()
        runCurrent()
        assertEquals(a, fixture.output.current)

        fixture.queue.control(LightAudioQueueCommand.CycleRepeat)
        assertTrue(fixture.output.repeatsCurrentItem)
        fixture.queue.onPlaybackChanged(true, true, 10_000, 10_000)
        fixture.queue.onEnded()
        runCurrent()
        assertEquals(a, fixture.output.current)
        assertEquals(0L, fixture.output.positionMs)
        assertEquals(listOf("b", "a"), fixture.resolved)

        fixture.queue.control(LightAudioQueueCommand.Next)
        runCurrent()
        assertEquals(b, fixture.output.current)
        fixture.queue.control(LightAudioQueueCommand.CycleRepeat)
        fixture.queue.onEnded()
        runCurrent()
        assertFalse(fixture.output.repeatsCurrentItem)
        assertFalse(fixture.queue.snapshot.playWhenReady)
    }

    @Test
    fun shufflePreservesCurrentAndPreviousHonorsRestartThreshold() = runTest {
        val fixture = Fixture(this)
        fixture.queue.start(listOf(a, b, c), 1, null, false)
        runCurrent()
        fixture.queue.onPlaybackChanged(true, true, 5_001, 10_000)
        fixture.queue.control(LightAudioQueueCommand.Previous)
        runCurrent()
        assertEquals(b, fixture.output.current)
        assertEquals(0L, fixture.output.positionMs)
        fixture.queue.onPlaybackChanged(true, true, 5_000, 10_000)
        fixture.queue.control(LightAudioQueueCommand.Previous)
        runCurrent()
        assertEquals(a, fixture.output.current)

        fixture.queue.control(LightAudioQueueCommand.ToggleShuffle)
        assertTrue(fixture.queue.snapshot.shuffle)
        assertEquals(a, fixture.queue.snapshot.items[fixture.queue.snapshot.currentIndex])
        assertEquals(setOf(a, b, c), fixture.queue.snapshot.items.toSet())
        assertEquals(a, fixture.output.current)
        fixture.queue.control(LightAudioQueueCommand.ToggleShuffle)
        assertFalse(fixture.queue.snapshot.shuffle)
        assertEquals(a, fixture.queue.snapshot.items[fixture.queue.snapshot.currentIndex])
    }

    @Test
    fun selectingSystemQueueItemResolvesItAndPreservesPausedSeek() = runTest {
        val fixture = Fixture(this)
        fixture.queue.start(listOf(a, b), 0, null, false)
        runCurrent()
        fixture.queue.control(LightAudioQueueCommand.Pause)
        fixture.queue.seekToItem(1, 7_000)
        runCurrent()

        assertEquals(b, fixture.output.current)
        assertEquals(7_000L, fixture.output.positionMs)
        assertFalse(fixture.output.playWhenReady)
        assertEquals(1, fixture.queue.snapshot.currentIndex)
    }

    @Test
    fun seekClampsToDurationAndCanChangePendingResumePosition() = runTest {
        val retry = CompletableDeferred<LightAudioResolvedSource>()
        var calls = 0
        val fixture = Fixture(this, resolve = { if (++calls == 1) source(it) else retry.await() })
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()
        fixture.queue.onPlaybackChanged(true, true, 1_000, 10_000)
        fixture.queue.control(LightAudioQueueCommand.Seek, -5)
        assertEquals(0L, fixture.output.positionMs)
        fixture.queue.control(LightAudioQueueCommand.Seek, 50_000)
        assertEquals(10_000L, fixture.output.positionMs)
        fixture.queue.onError(error(LightAudioErrorKind.Source))
        runCurrent()
        fixture.queue.control(LightAudioQueueCommand.Seek, 4_000)
        retry.complete(source(a))
        runCurrent()
        assertEquals(4_000L, fixture.output.positionMs)
    }

    @Test
    fun sourceFailureResolvesOnceAtSamePositionAndThenStops() = runTest {
        val fixture = Fixture(this)
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()
        fixture.queue.onPlaybackChanged(true, true, 4_321, 10_000)
        fixture.queue.onError(error(LightAudioErrorKind.Source))
        runCurrent()
        assertEquals(4_321L, fixture.output.positionMs)
        assertTrue(fixture.output.playWhenReady)
        assertEquals(listOf("a", "a"), fixture.resolved)
        fixture.queue.onError(error(LightAudioErrorKind.Source))
        runCurrent()
        assertEquals(listOf("a", "a"), fixture.resolved)
        assertEquals(LightAudioErrorKind.Source, fixture.queue.snapshot.error?.kind)
        assertFalse(fixture.queue.snapshot.isLoading)
        assertFalse(fixture.queue.snapshot.playWhenReady)
        assertFalse(fixture.queue.snapshot.isPlaying)
    }

    @Test
    fun unsupportedAndOutputFailuresDoNotReresolve() = runTest {
        for (kind in listOf(LightAudioErrorKind.Unsupported, LightAudioErrorKind.Output)) {
            val fixture = Fixture(this)
            fixture.queue.start(listOf(a), 0, null, false)
            runCurrent()
            fixture.queue.onError(error(kind))
            runCurrent()
            assertEquals(listOf("a"), fixture.resolved)
            assertEquals(kind, fixture.queue.snapshot.error?.kind)
            assertFalse(fixture.queue.snapshot.playWhenReady)
            assertFalse(fixture.queue.snapshot.isLoading)
        }
    }

    @Test
    fun sourceInstallationFailureStopsWithSanitizedError() = runTest {
        val fixture = Fixture(this)
        fixture.output.loadFailure = IllegalArgumentException("https://stream.invalid/?token=private")
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()

        assertEquals(LightAudioErrorKind.Unknown, fixture.queue.snapshot.error?.kind)
        assertFalse(assertNotNull(fixture.queue.snapshot.error).diagnostic.contains("token"))
        assertFalse(fixture.queue.snapshot.isLoading)
        assertFalse(fixture.queue.snapshot.playWhenReady)
        assertFalse(fixture.output.playWhenReady)
        assertEquals(listOf("a"), fixture.resolved)
    }

    @Test
    fun resumeSeekFailureStopsInsteadOfEscapingServiceCoroutine() = runTest {
        val fixture = Fixture(this)
        fixture.queue.start(listOf(a, b), 0, null, false)
        runCurrent()
        fixture.output.seekFailure = IllegalStateException("Decoder unavailable")
        fixture.queue.seekToItem(1, 4_000)
        runCurrent()

        assertEquals(LightAudioErrorKind.Unknown, fixture.queue.snapshot.error?.kind)
        assertEquals(1, fixture.queue.snapshot.error?.itemIndex)
        assertFalse(fixture.queue.snapshot.isLoading)
        assertFalse(fixture.queue.snapshot.playWhenReady)
        assertFalse(fixture.output.playWhenReady)
        assertEquals(listOf("a", "b"), fixture.resolved)
    }

    @Test
    fun resolverAndContinuationExceptionsBecomeSanitizedErrors() = runTest {
        val secret = "https://stream.invalid/?token=private"
        val fixture = Fixture(this, resolve = { error(secret) })
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()
        val resolutionError = assertNotNull(fixture.queue.snapshot.error)
        assertEquals(LightAudioErrorKind.Source, resolutionError.kind)
        assertFalse(resolutionError.diagnostic.contains(secret))
        assertFalse(fixture.queue.snapshot.isLoading)
        assertFalse(fixture.queue.snapshot.playWhenReady)

        val radio = Fixture(this, continuation = { error(secret) })
        radio.queue.start(listOf(a), 0, null, true)
        runCurrent()
        radio.queue.onEnded()
        runCurrent()
        assertFalse(assertNotNull(radio.queue.snapshot.error).diagnostic.contains(secret))
        assertFalse(radio.queue.snapshot.playWhenReady)
    }

    @Test
    fun emptyStartCancelsPendingWorkButAllowsAnotherQueue() = runTest {
        val resolved = CompletableDeferred<LightAudioResolvedSource>()
        val fixture = Fixture(this, resolve = {
            if (it == a) withContext(NonCancellable) { resolved.await() } else source(it)
        })
        fixture.queue.start(listOf(a), 0, "Old", true)
        runCurrent()
        fixture.queue.start(emptyList(), 99, null, false)
        assertEquals(LightAudioQueueSnapshot(), fixture.queue.snapshot)
        resolved.complete(source(a))
        runCurrent()
        assertTrue(fixture.output.loaded.isEmpty())
        fixture.queue.start(listOf(b), 0, "New", false)
        runCurrent()
        assertEquals(b, fixture.output.current)
        assertTrue(fixture.output.playWhenReady)
    }

    @Test
    fun replacementCancelsResolverAndReleaseCancelsContinuation() = runTest {
        val resolution = CompletableDeferred<LightAudioResolvedSource>()
        val recommendations = CompletableDeferred<List<LightAudioQueueItem>>()
        var resolutionCancelled = false
        var continuationCancelled = false
        val fixture = Fixture(this, resolve = {
            if (it == a) {
                try {
                    resolution.await()
                } finally {
                    resolutionCancelled = true
                }
            } else {
                source(it)
            }
        }, continuation = {
            try {
                recommendations.await()
            } finally {
                continuationCancelled = true
            }
        })
        fixture.queue.start(listOf(a), 0, null, false)
        runCurrent()
        fixture.queue.start(listOf(b), 0, null, true)
        runCurrent()
        assertTrue(resolutionCancelled)
        assertEquals(b, fixture.output.current)
        fixture.queue.onEnded()
        runCurrent()
        fixture.queue.release()
        runCurrent()
        assertTrue(continuationCancelled)
        assertEquals(LightAudioQueueSnapshot(), fixture.queue.snapshot)
    }

    @Test
    fun releaseInvalidatesSuspendedWorkAndLaterPlayerEvents() = runTest {
        val resolved = CompletableDeferred<LightAudioResolvedSource>()
        val fixture = Fixture(this, resolve = { withContext(NonCancellable) { resolved.await() } })
        fixture.queue.start(listOf(a), 0, null, true)
        runCurrent()
        fixture.queue.release()
        val released = fixture.queue.snapshot
        resolved.complete(source(a))
        runCurrent()
        fixture.queue.onPlaybackChanged(true, true, 100, 200)
        fixture.queue.onEnded()
        fixture.queue.onError(error(LightAudioErrorKind.Source))
        fixture.queue.control(LightAudioQueueCommand.Play)
        runCurrent()

        assertEquals(released, fixture.queue.snapshot)
        assertNull(fixture.output.current)
        assertFalse(released.isLoading)
        assertFalse(released.playWhenReady)
    }

    private class Fixture(
        scope: TestScope,
        resolve: suspend (LightAudioQueueItem) -> LightAudioResolvedSource = { source(it) },
        continuation: suspend (LightAudioQueueItem) -> List<LightAudioQueueItem> = { emptyList() },
    ) {
        val output = FakeOutput()
        val resolved = mutableListOf<String>()
        var continuations = 0
        val queue = ResolvingAudioQueue(output, object : LightAudioQueueResolver {
            override suspend fun resolve(item: LightAudioQueueItem): LightAudioResolvedSource {
                resolved += item.id
                return resolve.invoke(item)
            }

            override suspend fun continuation(seed: LightAudioQueueItem): List<LightAudioQueueItem> {
                continuations++
                return continuation.invoke(seed)
            }
        }, scope) {}

        init {
            output.stopped = { queue.onPlaybackChanged(false, false, 0, 0) }
        }
    }

    private class FakeOutput : QueueAudioOutput {
        var current: LightAudioQueueItem? = null
        val loaded = mutableListOf<LightAudioQueueItem>()
        var playWhenReady = false
        var positionMs = 0L
        var repeatsCurrentItem = false
        var stopped: () -> Unit = {}
        var loadFailure: Exception? = null
        var seekFailure: Exception? = null

        override fun load(item: LightAudioQueueItem, source: LightAudioResolvedSource, playWhenReady: Boolean) {
            loadFailure?.let { throw it }
            current = item
            loaded += item
            this.playWhenReady = playWhenReady
            positionMs = 0
        }

        override fun play() { playWhenReady = true }
        override fun pause() { playWhenReady = false }
        override fun seekTo(positionMs: Long) {
            seekFailure?.let { throw it }
            this.positionMs = positionMs
        }
        override fun stop() {
            playWhenReady = false
            stopped()
        }
        override fun setRepeatOne(enabled: Boolean) { repeatsCurrentItem = enabled }
    }

    private companion object {
        val a = item("a")
        val b = item("b")
        val c = item("c")

        fun item(id: String) = LightAudioQueueItem(id, LightMediaMetadata(id, durationMs = 10_000))
        fun source(item: LightAudioQueueItem) = LightAudioResolvedSource(
            LightAudioSource.UrlSource("https://stream.invalid/${item.id}"),
            "audio/flac",
        )
        fun error(kind: LightAudioErrorKind) = LightAudioError(kind, "ERROR_CODE_TEST", 0)
    }
}
