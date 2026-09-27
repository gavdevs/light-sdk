package com.gav.lightvidious.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.gav.lightvidious.data.MediaStream
import com.gav.lightvidious.data.VideoPlaybackSource
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Sideload-only video surface. The surrounding navigation and controls stay in
 * the Light SDK vocabulary; Media3 owns decoding and network playback.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VideoPlaybackScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val author: String,
    private val playbackSource: VideoPlaybackSource,
) : SimpleLightScreen<Unit>(sealedActivity) {
    private var player: ExoPlayer? = null

    override fun willHide() {
        player?.pause()
    }

    override fun onAppPause() {
        player?.pause()
    }

    override fun onScreenDestroy() {
        releasePlayer()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        var playing by remember { mutableStateOf(false) }
        var ready by remember { mutableStateOf(false) }
        var positionMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(C.TIME_UNSET) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val listener = remember {
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    ready = playbackState == Player.STATE_READY
                    player?.let { durationMs = it.duration }
                }

                override fun onPlayerError(error: PlaybackException) {
                    errorMessage = "Playback failed: ${PlaybackException.getErrorCodeName(error.errorCode)}"
                }
            }
        }

        DisposableEffect(playbackSource) {
            onDispose { releasePlayer() }
        }

        LaunchedEffect(ready) {
            while (ready && isActive) {
                player?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0L)
                    durationMs = it.duration
                }
                delay(500)
            }
        }

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Watch"),
                )

                AndroidView(
                    factory = { context ->
                        SurfaceView(context).also { surface ->
                            surface.keepScreenOn = true
                            player = ExoPlayer.Builder(context).build().apply {
                                setHandleAudioBecomingNoisy(true)
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(C.USAGE_MEDIA)
                                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                        .build(),
                                    true,
                                )
                                setWakeMode(C.WAKE_MODE_NETWORK)
                                setVideoSurfaceView(surface)
                                addListener(listener)
                                when (val source = playbackSource) {
                                    is VideoPlaybackSource.Single ->
                                        setMediaItem(source.stream.toMediaItem())
                                    is VideoPlaybackSource.Separate ->
                                        setMediaSource(source.toMergedMediaSource())
                                }
                                prepare()
                                playWhenReady = true
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                )

                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = title,
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = author,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                    )
                    LightText(
                        text = playbackTimeLabel(positionMs, durationMs),
                        variant = LightTextVariant.Fine,
                        monospace = true,
                        modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                    )
                    errorMessage?.let { message ->
                        LightText(
                            text = message,
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                            onClick = { seekBy(-15_000L) }.takeIf { ready },
                            contentDescription = "Back 15 seconds",
                        ),
                        LightBarButton.LightIcon(
                            icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                            onClick = ::togglePlayback,
                            contentDescription = if (playing) "Pause" else "Play",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                            onClick = { seekBy(15_000L) }.takeIf { ready },
                            contentDescription = "Forward 15 seconds",
                        ),
                    ),
                )
            }
        }
    }

    private fun togglePlayback() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun seekBy(deltaMs: Long) {
        player?.let {
            val upperBound = it.duration.takeIf { duration -> duration > 0L } ?: Long.MAX_VALUE
            it.seekTo((it.currentPosition + deltaMs).coerceIn(0L, upperBound))
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun VideoPlaybackSource.Separate.toMergedMediaSource(): MediaSource {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent("Lightious/0.3.2")
    val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
    return MergingMediaSource(
        true,
        true,
        mediaSourceFactory.createMediaSource(video.toMediaItem()),
        mediaSourceFactory.createMediaSource(audio.toMediaItem()),
    )
}

private fun MediaStream.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .apply { mimeType?.let(::setMimeType) }
    .build()

internal fun playbackTimeLabel(positionMs: Long, durationMs: Long): String =
    if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
        "${formatPlaybackTime(positionMs)}  /  LIVE"
    } else {
        "${formatPlaybackTime(positionMs)}  /  ${formatPlaybackTime(durationMs)}"
    }

internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
