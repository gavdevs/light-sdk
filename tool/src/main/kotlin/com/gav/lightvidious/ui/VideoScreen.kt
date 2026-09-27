package com.gav.lightvidious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.gav.lightvidious.LightvidiousServices
import com.gav.lightvidious.data.ClientSettings
import com.gav.lightvidious.data.InvidiousApi
import com.gav.lightvidious.data.StreamSelection
import com.gav.lightvidious.data.VideoDetails
import com.gav.lightvidious.data.VideoPlaybackSource
import com.gav.lightvidious.data.VideoSummary
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface VideoMode {
    data object Loading : VideoMode
    data class Loaded(val details: VideoDetails) : VideoMode
    data class Failed(val message: String) : VideoMode
}

data class VideoUiState(
    val mode: VideoMode = VideoMode.Loading,
    val errorMessage: String? = null,
)

class VideoViewModel(
    private val api: InvidiousApi,
    private val audio: LightAudio,
    private val initialVideo: VideoSummary,
    private val services: LightvidiousServices,
) : LightViewModel<Unit>() {
    private val player = audio.newPlayer(
        usage = LightAudioUsage.Speech,
        playback = LightAudioPlayback.Detached,
    )

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()
    private val _audioStarted = MutableStateFlow(false)
    val audioStarted: StateFlow<Boolean> = _audioStarted.asStateFlow()
    val audioPlaying = player.isPlaying
    val audioPositionMs = player.positionMs
    val audioDurationMs = player.durationMs
    val audioError = player.error
    private var playbackRecorded = false

    init {
        load()
    }

    fun load() {
        _uiState.value = VideoUiState(mode = VideoMode.Loading)
        viewModelScope.launch(Dispatchers.IO) {
            api.video(initialVideo.videoId).fold(
                onSuccess = { details ->
                    _uiState.value = VideoUiState(mode = VideoMode.Loaded(details))
                },
                onFailure = { error ->
                    _uiState.value = VideoUiState(
                        mode = VideoMode.Failed(error.message ?: "Could not load this video."),
                    )
                },
            )
        }
    }

    fun playAudio() {
        val details = (_uiState.value.mode as? VideoMode.Loaded)?.details ?: return
        val audioUrl = details.audioUrl
        if (audioUrl == null) {
            _uiState.update { it.copy(errorMessage = "This video has no compatible audio stream.") }
            return
        }

        viewModelScope.launch {
            if (!player.awaitReady()) {
                _uiState.update { it.copy(errorMessage = "Audio player is unavailable.") }
                return@launch
            }
            player.setMediaQueue(
                listOf(
                    LightAudioItem(
                        source = LightAudioSource.UrlSource(audioUrl),
                        metadata = LightMediaMetadata(
                            title = details.summary.title,
                            artist = details.summary.author,
                            album = "Lightious",
                            durationMs = details.summary.lengthSeconds
                                .takeIf { it > 0L }
                                ?.times(1_000L),
                        ),
                    ),
                ),
            )
            _audioStarted.value = true
            player.play()
            recordPlayback(details.summary)
        }
    }

    fun toggleAudio() {
        if (audioPlaying.value) player.pause() else player.play()
    }

    fun skipAudioBack() = player.skipBack()
    fun skipAudioForward() = player.skipForward()
    fun pauseForVideo() = player.pause()

    fun recordVideoPlayback() {
        val details = (_uiState.value.mode as? VideoMode.Loaded)?.details ?: return
        recordPlayback(details.summary)
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        api.close()
        player.release()
        super.onCleared()
    }

    private fun recordPlayback(video: VideoSummary) {
        if (playbackRecorded) return
        playbackRecorded = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentSettings = services.settings.load()
                val account = services.accounts.load(currentSettings.instanceUrl)
                services.historySyncer.recordPlayback(video, currentSettings, account)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Playback must remain available if local persistence or optional sync fails.
            }
        }
    }
}

class VideoScreen(
    private val sealedActivity: SealedLightActivity,
    private val settings: ClientSettings,
    private val initialVideo: VideoSummary,
    private val services: LightvidiousServices,
) : LightScreen<Unit, VideoViewModel>(sealedActivity) {
    override val viewModelClass = VideoViewModel::class.java

    override fun createViewModel() = VideoViewModel(
        api = InvidiousApi(
            settings.instanceUrl,
            settings.proxyMedia,
            settings.audioLanguage,
        ),
        audio = DefaultLightAudio(sealedActivity),
        initialVideo = initialVideo,
        services = services,
    )

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val audioStarted by viewModel.audioStarted.collectAsState()
        val audioPlaying by viewModel.audioPlaying.collectAsState()
        val audioPosition by viewModel.audioPositionMs.collectAsState()
        val audioDuration by viewModel.audioDurationMs.collectAsState()
        val audioError by viewModel.audioError.collectAsState()

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(
                            icon = LightIcons.BACK,
                            onClick = { goBack() },
                            contentDescription = "Back",
                        ),
                        center = LightTopBarCenter.Text("Video"),
                    )

                    when (val mode = state.mode) {
                        VideoMode.Loading -> VideoLoadingContent(Modifier.weight(1f))
                        is VideoMode.Failed -> VideoFailureContent(
                            message = mode.message,
                            onRetry = viewModel::load,
                            modifier = Modifier.weight(1f),
                        )
                        is VideoMode.Loaded -> {
                            VideoDetailsContent(
                                details = mode.details,
                                audioPlaying = audioPlaying,
                                audioPositionMs = audioPosition,
                                audioDurationMs = audioDuration,
                                audioError = audioError?.let { "${it.kind}: ${it.diagnostic}" },
                                modifier = Modifier.weight(1f),
                            )
                            VideoActions(
                                details = mode.details,
                                audioStarted = audioStarted,
                                audioPlaying = audioPlaying,
                                onWatch = { source ->
                                    viewModel.pauseForVideo()
                                    viewModel.recordVideoPlayback()
                                    navigateTo(
                                        screenFactory = { activity ->
                                            VideoPlaybackScreen(
                                                sealedActivity = activity,
                                                title = mode.details.summary.title,
                                                author = mode.details.summary.author,
                                                playbackSource = source,
                                            )
                                        },
                                    )
                                },
                                onListen = viewModel::playAudio,
                                onToggleAudio = viewModel::toggleAudio,
                                onSkipBack = viewModel::skipAudioBack,
                                onSkipForward = viewModel::skipAudioForward,
                            )
                        }
                    }
                }

                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun VideoLoadingContent(modifier: Modifier = Modifier) {
    LightText(
        text = "Loading video…",
        variant = LightTextVariant.Copy,
        modifier = modifier
            .fillMaxWidth()
            .padding(1f.gridUnitsAsDp()),
    )
}

@Composable
private fun VideoFailureContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
        )
        LightText(
            text = "RETRY",
            variant = LightTextVariant.Button,
            underline = true,
            modifier = Modifier
                .padding(top = 1f.gridUnitsAsDp())
                .lightClickable(onClick = onRetry),
        )
    }
}

@Composable
private fun VideoDetailsContent(
    details: VideoDetails,
    audioPlaying: Boolean,
    audioPositionMs: Long,
    audioDurationMs: Long,
    audioError: String?,
    modifier: Modifier = Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
    ) {
        LightText(
            text = details.summary.title,
            variant = LightTextVariant.Subheading,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
        )
        LightText(
            text = details.summary.author,
            variant = LightTextVariant.Copy,
            lighten = true,
            modifier = Modifier.padding(top = 0.35f.gridUnitsAsDp()),
        )
        LightText(
            text = videoMetadataLine(details.summary),
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
        )
        LightText(
            text = streamLabel(details.selection),
            variant = LightTextVariant.Superfine,
            lighten = true,
            modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
        )
        if (audioPlaying || audioPositionMs > 0L) {
            LightText(
                text = "LISTENING  ${playbackTimeLabel(audioPositionMs, audioDurationMs)}",
                variant = LightTextVariant.Fine,
                monospace = true,
                modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
            )
        }
        audioError?.let { message ->
            LightText(
                text = "Audio failed: $message",
                variant = LightTextVariant.Detail,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
        if (details.description.isNotBlank()) {
            LightText(
                text = details.description,
                variant = LightTextVariant.Paragraph,
                maxLines = 20,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun VideoActions(
    details: VideoDetails,
    audioStarted: Boolean,
    audioPlaying: Boolean,
    onWatch: (VideoPlaybackSource) -> Unit,
    onListen: () -> Unit,
    onToggleAudio: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    if (audioStarted) {
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                    onClick = onSkipBack,
                    contentDescription = "Back 15 seconds",
                ),
                LightBarButton.LightIcon(
                    icon = if (audioPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                    onClick = onToggleAudio,
                    contentDescription = if (audioPlaying) "Pause" else "Play",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                    onClick = onSkipForward,
                    contentDescription = "Forward 15 seconds",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.MEDIA,
                    onClick = details.watchSource?.let { source -> { onWatch(source) } },
                    contentDescription = "Watch video",
                ),
            ),
        )
    } else {
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = "WATCH",
                    onClick = details.watchSource?.let { source -> { onWatch(source) } },
                ),
                LightBarButton.Text(
                    text = "LISTEN",
                    onClick = onListen.takeIf { details.audioUrl != null },
                ),
            ),
        )
    }
}

internal fun streamLabel(selection: StreamSelection): String = when {
    selection.liveHls != null -> "LIVE HLS"
    selection.progressive != null -> listOfNotNull(
        selection.progressive.qualityLabel,
        selection.progressive.container?.uppercase(),
    )
        .joinToString("  ·  ")
        .ifBlank { "PROGRESSIVE VIDEO" }
    selection.adaptiveVideo != null && selection.adaptiveAudio != null -> listOfNotNull(
        selection.adaptiveVideo.qualityLabel,
        selection.adaptiveVideo.container?.uppercase(),
        "ADAPTIVE",
    ).joinToString("  ·  ")
    else -> "NO COMPATIBLE VIDEO STREAM"
}
