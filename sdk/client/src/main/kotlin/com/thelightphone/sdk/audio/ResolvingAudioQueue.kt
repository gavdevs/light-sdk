package com.thelightphone.sdk.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal interface QueueAudioOutput {
    fun load(item: LightAudioQueueItem, source: LightAudioResolvedSource, playWhenReady: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun setRepeatOne(enabled: Boolean)
}

/** All entry points, output callbacks, and the supplied scope are confined to the main thread. */
internal class ResolvingAudioQueue(
    private val output: QueueAudioOutput,
    private val resolver: LightAudioQueueResolver,
    private val scope: CoroutineScope,
    private val publish: (LightAudioQueueSnapshot) -> Unit,
) {
    var snapshot: LightAudioQueueSnapshot = LightAudioQueueSnapshot()
        private set

    private var generation = 0L
    private var work: Job? = null
    private var pending: Pending? = null
    private var released = false
    private var outputLoaded = false
    private var continuousPlayback = false
    private var sourceRecoveryUsed = false
    private var continuationAttempted = false
    private var endedHandled = false
    private var atEnd = false

    fun start(
        items: List<LightAudioQueueItem>,
        startIndex: Int,
        sourceName: String?,
        continuousPlayback: Boolean,
    ) {
        check(!released) { "Audio queue is released" }
        if (items.isEmpty()) {
            clear()
            return
        }
        require(startIndex in items.indices) { "Invalid starting queue item" }
        val requestedId = items[startIndex].id
        val unique = items.distinctBy { it.id }
        this.continuousPlayback = continuousPlayback
        snapshot = snapshot.copy(items = unique, sourceName = sourceName)
        select(unique.indexOfFirst { it.id == requestedId }, playWhenReady = true)
    }

    fun control(command: LightAudioQueueCommand, positionMs: Long? = null) {
        if (released || snapshot.currentIndex !in snapshot.items.indices) return
        when (command) {
            LightAudioQueueCommand.Play -> play()
            LightAudioQueueCommand.Pause -> pause()
            LightAudioQueueCommand.TogglePlayPause -> if (snapshot.playWhenReady) pause() else play()
            LightAudioQueueCommand.Previous -> {
                if (snapshot.positionMs > PREVIOUS_RESTART_MS || snapshot.currentIndex == 0) {
                    seek(0)
                } else {
                    select(snapshot.currentIndex - 1, playWhenReady = true)
                }
            }
            LightAudioQueueCommand.Next -> advance(playWhenReady = true)
            LightAudioQueueCommand.ToggleShuffle -> toggleShuffle()
            LightAudioQueueCommand.CycleRepeat -> cycleRepeat()
            LightAudioQueueCommand.Seek -> positionMs?.let(::seek)
        }
    }

    fun seekToItem(index: Int, positionMs: Long = 0L) {
        if (released || index !in snapshot.items.indices) return
        if (index == snapshot.currentIndex) {
            seek(positionMs)
        } else {
            select(index, snapshot.playWhenReady, positionMs)
        }
    }

    fun onPlaybackChanged(isPlaying: Boolean, playWhenReady: Boolean, positionMs: Long, durationMs: Long) {
        // Stopping the previous source while resolving must not overwrite the new play intent.
        if (released || pending != null || !outputLoaded) return
        update(snapshot.copy(
            isPlaying = isPlaying,
            playWhenReady = playWhenReady,
            positionMs = clampPosition(positionMs, durationMs),
            durationMs = durationMs.coerceAtLeast(0),
        ))
    }

    fun onEnded() {
        if (released || pending != null || !outputLoaded || endedHandled) return
        endedHandled = true
        if (snapshot.repeatMode == LightAudioRepeatMode.One) {
            seek(0)
            if (snapshot.playWhenReady) output.play()
        } else {
            advance(snapshot.playWhenReady)
        }
    }

    fun onError(error: LightAudioError) {
        if (released || pending != null || !outputLoaded) return
        if (error.kind == LightAudioErrorKind.Source && !sourceRecoveryUsed) {
            sourceRecoveryUsed = true
            resolveCurrent(snapshot.positionMs)
        } else {
            fail(error.copy(itemIndex = snapshot.currentIndex))
        }
    }

    fun release() {
        if (released) return
        released = true
        clear()
    }

    private fun clear() {
        invalidateWork()
        outputLoaded = false
        continuousPlayback = false
        sourceRecoveryUsed = false
        continuationAttempted = false
        endedHandled = false
        atEnd = false
        snapshot = LightAudioQueueSnapshot()
        output.stop()
        output.setRepeatOne(false)
        publish(snapshot)
    }

    private fun select(index: Int, playWhenReady: Boolean, positionMs: Long = 0) {
        sourceRecoveryUsed = false
        continuationAttempted = false
        endedHandled = false
        atEnd = false
        snapshot = snapshot.copy(
            currentIndex = index,
            playWhenReady = playWhenReady,
            durationMs = snapshot.items[index].metadata.durationMs?.coerceAtLeast(0) ?: 0,
            error = null,
        )
        resolveCurrent(positionMs)
    }

    private fun resolveCurrent(positionMs: Long) {
        val item = snapshot.items[snapshot.currentIndex]
        val requestGeneration = invalidateWork()
        pending = Pending.Resolution
        outputLoaded = false
        snapshot = snapshot.copy(
            positionMs = clampPosition(positionMs, snapshot.durationMs),
            isPlaying = false,
            isLoading = true,
            error = null,
        )
        output.stop()
        publish(snapshot)
        work = scope.launch(start = CoroutineStart.LAZY) {
            val source = try {
                resolver.resolve(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(requestGeneration)) fail(resolverError("SOURCE_RESOLUTION_FAILED"))
                return@launch
            }
            if (!isCurrent(requestGeneration)) return@launch
            val resumePosition = snapshot.positionMs
            pending = null
            outputLoaded = true
            snapshot = snapshot.copy(isLoading = false)
            try {
                output.load(item, source, snapshot.playWhenReady)
                if (!isCurrent(requestGeneration)) return@launch
                if (resumePosition > 0) {
                    val target = clampPosition(resumePosition, snapshot.durationMs)
                    output.seekTo(target)
                    if (!isCurrent(requestGeneration)) return@launch
                    snapshot = snapshot.copy(positionMs = target)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(requestGeneration)) {
                    fail(LightAudioError(
                        kind = LightAudioErrorKind.Unknown,
                        diagnostic = "SOURCE_PREPARATION_FAILED",
                        itemIndex = snapshot.currentIndex,
                    ))
                }
                return@launch
            }
            publish(snapshot)
        }
        work?.start()
    }

    private fun play() {
        if (pending != null) {
            update(snapshot.copy(playWhenReady = true))
        } else if (!outputLoaded || snapshot.error != null) {
            select(snapshot.currentIndex, playWhenReady = true, positionMs = snapshot.positionMs)
        } else {
            if (atEnd) seek(0)
            update(snapshot.copy(playWhenReady = true))
            output.play()
        }
    }

    private fun pause() {
        update(snapshot.copy(playWhenReady = false, isPlaying = false))
        if (pending == null && outputLoaded) output.pause()
    }

    private fun seek(positionMs: Long) {
        val target = clampPosition(positionMs, snapshot.durationMs)
        if (pending == Pending.Continuation) {
            select(snapshot.currentIndex, snapshot.playWhenReady, target)
            return
        }
        if (snapshot.durationMs == 0L || target < snapshot.durationMs) {
            endedHandled = false
            atEnd = false
            continuationAttempted = false
        }
        update(snapshot.copy(positionMs = target))
        if (pending == null && outputLoaded) output.seekTo(target)
    }

    private fun advance(playWhenReady: Boolean) {
        if (pending == Pending.Continuation) return
        when {
            snapshot.currentIndex < snapshot.items.lastIndex -> select(snapshot.currentIndex + 1, playWhenReady)
            snapshot.repeatMode == LightAudioRepeatMode.All -> select(0, playWhenReady)
            snapshot.repeatMode == LightAudioRepeatMode.One -> select(snapshot.currentIndex, playWhenReady)
            continuousPlayback && !continuationAttempted -> continueQueue(playWhenReady)
            else -> finish()
        }
    }

    private fun continueQueue(playWhenReady: Boolean) {
        val seed = snapshot.items[snapshot.currentIndex]
        val requestGeneration = invalidateWork()
        continuationAttempted = true
        pending = Pending.Continuation
        update(snapshot.copy(isPlaying = false, playWhenReady = playWhenReady, isLoading = true))
        // A manual Next at the last item also stops the old source while recommendations load.
        outputLoaded = false
        output.stop()
        work = scope.launch(start = CoroutineStart.LAZY) {
            val recommendations = try {
                resolver.continuation(seed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(requestGeneration)) fail(resolverError("QUEUE_CONTINUATION_FAILED"))
                return@launch
            }
            if (!isCurrent(requestGeneration)) return@launch
            pending = null
            val existing = snapshot.items.mapTo(HashSet()) { it.id }
            val additions = recommendations.filter { existing.add(it.id) }
            if (additions.isEmpty()) {
                finish()
            } else {
                val nextIndex = snapshot.items.size
                snapshot = snapshot.copy(items = snapshot.items + additions)
                select(nextIndex, snapshot.playWhenReady)
            }
        }
        work?.start()
    }

    private fun finish() {
        invalidateWork()
        atEnd = true
        endedHandled = true
        update(snapshot.copy(isPlaying = false, playWhenReady = false, isLoading = false))
        output.pause()
    }

    private fun fail(error: LightAudioError) {
        invalidateWork()
        outputLoaded = false
        endedHandled = true
        snapshot = snapshot.copy(isPlaying = false, playWhenReady = false, isLoading = false, error = error)
        output.stop()
        publish(snapshot)
    }

    private fun toggleShuffle() {
        if (snapshot.shuffle) {
            update(snapshot.copy(shuffle = false))
        } else {
            val current = snapshot.items[snapshot.currentIndex]
            val shuffled = listOf(current) + snapshot.items.filterNot { it.id == current.id }.shuffled()
            update(snapshot.copy(items = shuffled, currentIndex = 0, shuffle = true))
        }
    }

    private fun cycleRepeat() {
        val mode = when (snapshot.repeatMode) {
            LightAudioRepeatMode.Off -> LightAudioRepeatMode.All
            LightAudioRepeatMode.All -> LightAudioRepeatMode.One
            LightAudioRepeatMode.One -> LightAudioRepeatMode.Off
        }
        update(snapshot.copy(repeatMode = mode))
        output.setRepeatOne(mode == LightAudioRepeatMode.One)
    }

    private fun invalidateWork(): Long {
        generation++
        work?.cancel()
        work = null
        pending = null
        return generation
    }

    private fun isCurrent(requestGeneration: Long): Boolean = !released && requestGeneration == generation

    private fun resolverError(diagnostic: String): LightAudioError = LightAudioError(
        kind = LightAudioErrorKind.Source,
        diagnostic = diagnostic,
        itemIndex = snapshot.currentIndex,
    )

    private fun update(value: LightAudioQueueSnapshot) {
        snapshot = value
        publish(value)
    }

    private fun clampPosition(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0, durationMs) else positionMs.coerceAtLeast(0)

    private enum class Pending { Resolution, Continuation }

    private companion object {
        const val PREVIOUS_RESTART_MS = 5_000L
    }
}
