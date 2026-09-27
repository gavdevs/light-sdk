package com.gav.lightvidious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.gav.lightvidious.LightvidiousServices
import com.gav.lightvidious.data.ClientSettings
import com.gav.lightvidious.data.InvidiousApi
import com.gav.lightvidious.data.VideoSummary
import com.gav.lightvidious.data.extractYouTubeVideoId
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SearchMode {
    data class Loading(val message: String) : SearchMode
    data class Editor(val initialValue: String, val session: Int) : SearchMode
    data class Results(val query: String, val videos: List<VideoSummary>) : SearchMode
}

data class SearchUiState(
    val settings: ClientSettings = ClientSettings(),
    val mode: SearchMode = SearchMode.Loading("Loading…"),
    val errorMessage: String? = null,
)

class SearchViewModel(
    private val services: LightvidiousServices,
    private val initialQuery: String,
    private val autoSubmit: Boolean,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private val _navigationTarget = MutableStateFlow<VideoSummary?>(null)
    val navigationTarget: StateFlow<VideoSummary?> = _navigationTarget.asStateFlow()

    private var requestJob: Job? = null
    private var editorSession = 0

    init {
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = services.settings.load()
            _uiState.update { it.copy(settings = settings) }
            requestJob = null
            if (autoSubmit && initialQuery.isNotBlank()) {
                submitSearch(initialQuery)
            } else {
                showEditor(initialQuery)
            }
        }
    }

    fun showEditor(value: String = (_uiState.value.mode as? SearchMode.Results)?.query.orEmpty()) {
        requestJob?.cancel()
        editorSession += 1
        _uiState.update {
            it.copy(mode = SearchMode.Editor(value, editorSession), errorMessage = null)
        }
    }

    fun submitSearch(value: CharSequence) {
        val query = value.toString().trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a search or YouTube link.") }
            return
        }
        extractYouTubeVideoId(query)?.let { videoId ->
            _navigationTarget.value = placeholderVideo(videoId)
            return
        }

        requestJob?.cancel()
        _uiState.update {
            it.copy(mode = SearchMode.Loading("Searching…"), errorMessage = null)
        }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = _uiState.value.settings
            InvidiousApi(
                settings.instanceUrl,
                settings.proxyMedia,
                settings.audioLanguage,
            ).use { api ->
                api.search(query).fold(
                    onSuccess = { videos ->
                        if (settings.saveSearchHistory) {
                            try {
                                services.history.recordSearch(query)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Search remains usable if local history storage fails.
                            }
                        }
                        _uiState.update {
                            it.copy(
                                mode = SearchMode.Results(query, videos),
                                errorMessage = null,
                            )
                        }
                    },
                    onFailure = { error ->
                        editorSession += 1
                        _uiState.update {
                            it.copy(
                                mode = SearchMode.Editor(query, editorSession),
                                errorMessage = error.userMessage("Search failed."),
                            )
                        }
                    },
                )
            }
        }
    }

    fun selectVideo(video: VideoSummary) {
        _navigationTarget.value = video
    }

    fun consumeNavigationTarget() {
        _navigationTarget.value = null
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun placeholderVideo(videoId: String) = VideoSummary(
        videoId = videoId,
        title = "YouTube video",
        author = "",
        lengthSeconds = 0L,
        viewCount = 0L,
        publishedText = "",
        liveNow = false,
    )
}

class SearchScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightvidiousServices,
    private val initialQuery: String = "",
    private val autoSubmit: Boolean = false,
) : LightScreen<Unit, SearchViewModel>(sealedActivity) {
    override val viewModelClass = SearchViewModel::class.java

    override fun createViewModel() = SearchViewModel(services, initialQuery, autoSubmit)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val navigationTarget by viewModel.navigationTarget.collectAsState()

        LaunchedEffect(navigationTarget?.videoId) {
            navigationTarget?.let { video ->
                navigateTo(
                    screenFactory = { activity ->
                        VideoScreen(activity, state.settings, video, services)
                    },
                )
                viewModel.consumeNavigationTarget()
            }
        }

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val mode = state.mode) {
                    is SearchMode.Loading -> LoadingContent(mode.message, "Search")
                    is SearchMode.Editor -> SearchEditor(mode, viewModel, onBack = { goBack() })
                    is SearchMode.Results -> SearchResults(mode, viewModel, onBack = { goBack() })
                }
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun SearchEditor(
    mode: SearchMode.Editor,
    viewModel: SearchViewModel,
    onBack: () -> Unit,
) {
    key(mode.session) {
        val text = rememberTextFieldState(mode.initialValue)
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Search",
            state = text,
            keyboardOptionsFlow = keyboardOptions,
            onSubmit = viewModel::submitSearch,
            onBack = onBack,
            submitIcon = LightIcons.SEARCH,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SearchResults(
    mode: SearchMode.Results,
    viewModel: SearchViewModel,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Results"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.SEARCH,
                onClick = { viewModel.showEditor(mode.query) },
                contentDescription = "New search",
            ),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (mode.videos.isEmpty()) {
                LightText(
                    text = "No videos found for “${mode.query}”.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                mode.videos.forEach { video ->
                    VideoRow(video) { viewModel.selectVideo(video) }
                }
            }
        }
    }
}
