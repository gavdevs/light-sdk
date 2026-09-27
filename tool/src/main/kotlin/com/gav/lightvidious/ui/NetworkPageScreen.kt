package com.gav.lightvidious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.gav.lightvidious.LightvidiousServices
import com.gav.lightvidious.data.ClientSettings
import com.gav.lightvidious.data.HomePage
import com.gav.lightvidious.data.InvidiousApi
import com.gav.lightvidious.data.VideoSummary
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NetworkPageMode {
    data object Loading : NetworkPageMode
    data object SignInRequired : NetworkPageMode
    data class Loaded(val videos: List<VideoSummary>) : NetworkPageMode
}

data class NetworkPageUiState(
    val settings: ClientSettings = ClientSettings(),
    val mode: NetworkPageMode = NetworkPageMode.Loading,
    val errorMessage: String? = null,
)

class NetworkPageViewModel(
    private val services: LightvidiousServices,
    private val page: HomePage,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(NetworkPageUiState())
    val uiState: StateFlow<NetworkPageUiState> = _uiState.asStateFlow()
    private val _navigationTarget = MutableStateFlow<VideoSummary?>(null)
    val navigationTarget: StateFlow<VideoSummary?> = _navigationTarget.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        _uiState.update { it.copy(mode = NetworkPageMode.Loading, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = services.settings.load()
            _uiState.update { it.copy(settings = settings) }
            val account = if (page == HomePage.ACCOUNT_FEED) {
                services.accounts.load(settings.instanceUrl)
            } else {
                null
            }
            if (page == HomePage.ACCOUNT_FEED && account == null) {
                _uiState.update { it.copy(mode = NetworkPageMode.SignInRequired) }
                return@launch
            }
            InvidiousApi(
                settings.instanceUrl,
                settings.proxyMedia,
                settings.audioLanguage,
            ).use { api ->
                val result = when (page) {
                    HomePage.ACCOUNT_FEED -> api.accountFeed(checkNotNull(account).token)
                    HomePage.POPULAR -> api.popular()
                    else -> Result.failure(IllegalArgumentException("This is not a network page."))
                }
                result.fold(
                    onSuccess = { videos ->
                        _uiState.update {
                            it.copy(mode = NetworkPageMode.Loaded(videos), errorMessage = null)
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                mode = NetworkPageMode.Loaded(emptyList()),
                                errorMessage = error.userMessage("Could not load this page."),
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
}

class NetworkPageScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightvidiousServices,
    private val page: HomePage,
) : LightScreen<Unit, NetworkPageViewModel>(sealedActivity) {
    init {
        require(page == HomePage.ACCOUNT_FEED || page == HomePage.POPULAR)
    }

    override val viewModelClass = NetworkPageViewModel::class.java

    override fun createViewModel() = NetworkPageViewModel(services, page)

    override fun willShow() {
        viewModel.load()
    }

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
                    NetworkPageMode.Loading -> LoadingContent("Loading…", page.homeLabel())
                    NetworkPageMode.SignInRequired -> SignInRequiredContent(
                        title = page.homeLabel(),
                        onBack = { goBack() },
                        onSignIn = {
                            navigateTo(
                                screenFactory = { activity -> AccountScreen(activity, services) },
                            )
                        },
                    )
                    is NetworkPageMode.Loaded -> VideoListContent(
                        title = page.homeLabel(),
                        videos = mode.videos,
                        emptyMessage = when (page) {
                            HomePage.ACCOUNT_FEED -> "Your account feed is empty."
                            else -> "This instance returned no popular videos."
                        },
                        onBack = { goBack() },
                        onRefresh = viewModel::load,
                        onVideo = viewModel::selectVideo,
                    )
                }
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun VideoListContent(
    title: String,
    videos: List<VideoSummary>,
    emptyMessage: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onVideo: (VideoSummary) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(title),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (videos.isEmpty()) {
                LightText(
                    text = emptyMessage,
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                videos.forEach { video -> VideoRow(video) { onVideo(video) } }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.REFRESH,
                    onClick = onRefresh,
                    contentDescription = "Refresh",
                ),
            ),
        )
    }
}

@Composable
private fun SignInRequiredContent(
    title: String,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(title),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "Sign in to your Invidious instance to load your subscription feed.",
                variant = LightTextVariant.Copy,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
            ActionRow("SIGN IN") { onSignIn() }
        }
    }
}
