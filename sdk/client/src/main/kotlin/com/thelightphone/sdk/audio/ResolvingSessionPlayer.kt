package com.thelightphone.sdk.audio

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Publishes a logical queue while ExoPlayer decodes only its current, freshly resolved source. */
internal class ResolvingSessionPlayer(
    private val output: ExoPlayer,
    resolver: LightAudioQueueResolver,
    private val onActivityChanged: () -> Unit,
) : SimpleBasePlayer(output.applicationLooper) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(output.applicationLooper)
    private var snapshot = LightAudioQueueSnapshot()
    private var playlistItems: List<LightAudioQueueItem>? = null
    private var playlistIndex = -1
    private var playlistDurationMs = 0L
    private var playlist = emptyList<MediaItemData>()
    private var disposed = false
    private val invalidate = Runnable { if (!disposed) invalidateState() }
    private val ticker = object : Runnable {
        override fun run() {
            if (disposed) return
            reportOutput()
            if (output.isPlaying) handler.postDelayed(this, 500L)
        }
    }

    val queue = ResolvingAudioQueue(
        output = object : QueueAudioOutput {
            override fun load(
                item: LightAudioQueueItem,
                source: LightAudioResolvedSource,
                playWhenReady: Boolean,
            ) {
                output.setMediaItem(
                    item.mediaItem().buildUpon()
                        .setUri(source.source.uriString())
                        .setMimeType(source.mimeType)
                        .build()
                )
                output.prepare()
                output.playWhenReady = playWhenReady
            }
            override fun play() { output.play() }
            override fun pause() { output.pause() }
            override fun seekTo(positionMs: Long) { output.seekTo(positionMs) }
            override fun stop() {
                output.stop()
                output.clearMediaItems()
            }
            override fun setRepeatOne(enabled: Boolean) {
                output.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
        },
        resolver = resolver,
        scope = scope,
        publish = { next ->
            snapshot = next
            LightAudioQueueSessions.publish(next)
            handler.removeCallbacks(invalidate)
            handler.post(invalidate)
            onActivityChanged()
        },
    )

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && currentSourceMatches()) queue.onEnded()
        }
        override fun onPlayerError(error: PlaybackException) {
            if (currentSourceMatches()) queue.onError(error.toLightAudioError(output))
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && (
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
                    )
            ) {
                queue.control(LightAudioQueueCommand.Pause)
            }
        }
        override fun onEvents(player: Player, events: Player.Events) {
            reportOutput()
            handler.removeCallbacks(ticker)
            if (output.isPlaying) handler.postDelayed(ticker, 500L)
            invalidateState()
            onActivityChanged()
        }
    }

    init {
        output.addListener(listener)
    }

    private fun currentSourceMatches(): Boolean = !snapshot.isLoading &&
        snapshot.items.getOrNull(snapshot.currentIndex)?.id == output.currentMediaItem?.mediaId

    private fun reportOutput() {
        if (!currentSourceMatches() || snapshot.items.isEmpty()) return
        queue.onPlaybackChanged(
            output.isPlaying,
            output.playWhenReady,
            output.currentPosition.coerceAtLeast(0L),
            output.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
        )
    }

    fun clearQueue() {
        queue.start(emptyList(), 0, null, false)
    }

    override fun getState(): State {
        val s = snapshot
        if (playlistItems !== s.items || playlistIndex != s.currentIndex || playlistDurationMs != s.durationMs) {
            playlistItems = s.items
            playlistIndex = s.currentIndex
            playlistDurationMs = s.durationMs
            playlist = s.items.mapIndexed { index, item ->
                val duration = if (index == s.currentIndex && s.durationMs > 0) s.durationMs else item.metadata.durationMs
                MediaItemData.Builder(item.id)
                    .setMediaItem(item.mediaItem())
                    .setIsSeekable(true)
                    .setDurationUs(duration?.takeIf { it > 0 }?.let { it * 1_000L } ?: C.TIME_UNSET)
                    .build()
            }
        }
        val error = s.error?.let {
            PlaybackException(it.diagnostic, null, when (it.kind) {
                LightAudioErrorKind.Source -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                LightAudioErrorKind.Unsupported -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                LightAudioErrorKind.Output -> PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                LightAudioErrorKind.Unknown -> PlaybackException.ERROR_CODE_UNSPECIFIED
            })
        }
        val playbackState = when {
            s.items.isEmpty() || error != null -> Player.STATE_IDLE
            s.isLoading -> Player.STATE_BUFFERING
            else -> output.playbackState
        }
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(s.currentIndex)
            .setPlaylistMetadata(MediaMetadata.Builder().setTitle(s.sourceName).build())
            .setPlayWhenReady(s.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setPlaybackSuppressionReason(output.playbackSuppressionReason)
            .setPlayerError(error)
            .setIsLoading(
                playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED &&
                    (s.isLoading || output.isLoading)
            )
            .setRepeatMode(when (s.repeatMode) {
                LightAudioRepeatMode.Off -> Player.REPEAT_MODE_OFF
                LightAudioRepeatMode.All -> Player.REPEAT_MODE_ALL
                LightAudioRepeatMode.One -> Player.REPEAT_MODE_ONE
            })
            .setShuffleModeEnabled(s.shuffle)
            .setContentPositionMs(s.positionMs)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(
                if (s.isLoading) s.positionMs else output.bufferedPosition.coerceAtLeast(0L)
            ))
            .setTotalBufferedDurationMs(PositionSupplier.getConstant(output.totalBufferedDuration))
            .setAudioAttributes(output.audioAttributes)
            .setVolume(output.volume)
            .setMaxSeekToPreviousPositionMs(5_000L)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = completed {
        queue.control(if (playWhenReady) LightAudioQueueCommand.Play else LightAudioQueueCommand.Pause)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = completed {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> queue.control(LightAudioQueueCommand.Next)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> queue.control(LightAudioQueueCommand.Previous)
            else -> if (mediaItemIndex != snapshot.currentIndex) {
                queue.seekToItem(mediaItemIndex, positionMs.takeIf { it != C.TIME_UNSET } ?: 0L)
            } else {
                queue.control(LightAudioQueueCommand.Seek, positionMs.takeIf { it != C.TIME_UNSET } ?: 0L)
            }
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = completed {
        if (queue.snapshot.items.isEmpty()) return@completed
        val target = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> LightAudioRepeatMode.One
            Player.REPEAT_MODE_ALL -> LightAudioRepeatMode.All
            else -> LightAudioRepeatMode.Off
        }
        while (queue.snapshot.repeatMode != target) queue.control(LightAudioQueueCommand.CycleRepeat)
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> = completed {
        if (queue.snapshot.shuffle != shuffleModeEnabled) queue.control(LightAudioQueueCommand.ToggleShuffle)
    }

    override fun handleStop(): ListenableFuture<*> = completed { clearQueue() }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> = completed { output.volume = volume }

    override fun handleRelease(): ListenableFuture<*> {
        disposed = true
        handler.removeCallbacksAndMessages(null)
        output.removeListener(listener)
        queue.release()
        scope.cancel()
        LightAudioQueueSessions.detach(this)
        output.release()
        return Futures.immediateVoidFuture()
    }

    private inline fun completed(action: () -> Unit): ListenableFuture<*> {
        action()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
        ).build()
    }
}

private fun LightAudioQueueItem.mediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(MediaMetadata.Builder()
        .setTitle(metadata.title)
        .setArtist(metadata.artist)
        .setAlbumTitle(metadata.album)
        .setDurationMs(metadata.durationMs)
        .build())
    .build()
