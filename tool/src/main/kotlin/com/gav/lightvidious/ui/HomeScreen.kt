package com.gav.lightvidious.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.gav.lightvidious.LightvidiousServices
import com.gav.lightvidious.data.AccountSession
import com.gav.lightvidious.data.ClientSettings
import com.gav.lightvidious.data.HomePage
import com.gav.lightvidious.data.InvidiousApi
import com.gav.lightvidious.data.normalizeInstanceUrl
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HomeMode {
    data class Loading(val message: String) : HomeMode
    data object Ready : HomeMode
    data class InstanceEditor(
        val initialValue: String,
        val session: Int,
    ) : HomeMode
}

data class HomeUiState(
    val settings: ClientSettings = ClientSettings(),
    val account: AccountSession? = null,
    val mode: HomeMode = HomeMode.Loading("Loading…"),
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val services: LightvidiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var requestJob: Job? = null
    private var editorSession = 0

    init {
        reload()
    }

    fun reload() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = try {
                services.settings.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mode = HomeMode.Loading("Could not load settings."),
                        errorMessage = error.userMessage("Could not read saved settings."),
                    )
                }
                return@launch
            }
            if (settings.instanceUrl.isBlank()) {
                showInstanceEditor("")
                return@launch
            }
            val account = try {
                services.accounts.load(settings.instanceUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            _uiState.value = HomeUiState(
                settings = settings,
                account = account,
                mode = HomeMode.Ready,
            )
        }
    }

    fun submitInstance(value: CharSequence) {
        val normalized = runCatching { normalizeInstanceUrl(value.toString()) }
            .getOrElse { error ->
                showInstanceEditor(
                    value.toString(),
                    error.userMessage("Invalid instance URL."),
                )
                return
            }
        requestJob?.cancel()
        _uiState.update {
            it.copy(mode = HomeMode.Loading("Checking server…"), errorMessage = null)
        }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val probe = InvidiousApi(
                normalized,
                _uiState.value.settings.proxyMedia,
                _uiState.value.settings.audioLanguage,
            ).use { api ->
                api.probe()
            }
            if (!probe.apiAvailable) {
                showInstanceEditor(normalized, probe.message)
                return@launch
            }
            try {
                services.settings.saveInstance(normalized)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showInstanceEditor(normalized, error.userMessage("Could not save the server."))
                return@launch
            }
            reload()
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun showInstanceEditor(initialValue: String, error: String? = null) {
        editorSession += 1
        _uiState.update {
            it.copy(
                mode = HomeMode.InstanceEditor(initialValue, editorSession),
                errorMessage = error,
            )
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {
    private val services by lazy { LightvidiousServices.from(lightContext) }

    override val viewModelClass = HomeViewModel::class.java

    override fun createViewModel() = HomeViewModel(services)

    override fun willShow() {
        viewModel.reload()
    }

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()

        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val mode = state.mode) {
                    is HomeMode.Loading -> LoadingContent(mode.message)
                    HomeMode.Ready -> HomeMenuContent(
                        settings = state.settings,
                        signedIn = state.account != null,
                        onPage = ::openPage,
                        onSettings = {
                            navigateTo(
                                screenFactory = { activity -> SettingsScreen(activity, services) },
                            )
                        },
                    )
                    is HomeMode.InstanceEditor -> InitialInstanceEditor(mode, viewModel)
                }

                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }

    private fun openPage(page: HomePage) {
        when (page) {
            HomePage.SEARCH -> navigateTo(
                screenFactory = { activity -> SearchScreen(activity, services) },
            )
            HomePage.ACCOUNT_FEED,
            HomePage.POPULAR,
            -> navigateTo(
                screenFactory = { activity -> NetworkPageScreen(activity, services, page) },
            )
            HomePage.WATCH_HISTORY ->
                navigateTo(
                    screenFactory = { activity -> WatchHistoryScreen(activity, services) },
                )
            HomePage.SEARCH_HISTORY ->
                navigateTo(
                    screenFactory = { activity -> SearchHistoryScreen(activity, services) },
                )
        }
    }
}

@Composable
private fun HomeMenuContent(
    settings: ClientSettings,
    signedIn: Boolean,
    onPage: (HomePage) -> Unit,
    onSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text("Lightious"),
            rightButton = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = onSettings,
                contentDescription = "Settings",
            ),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (settings.homePages.isEmpty()) {
                LightText(
                    text = "Choose at least one bottom navigation page in Settings.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            } else {
                LightText(
                    text = "Choose a page from the bottom bar.",
                    variant = LightTextVariant.Subheading,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = settings.homePages.joinToString("  ·  ") { it.homeLabel() },
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                if (HomePage.ACCOUNT_FEED in settings.homePages && !signedIn) {
                    LightText(
                        text = "Sign in from Settings to use subscriptions.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
        if (settings.homePages.isNotEmpty()) {
            LightBottomBar(
                items = settings.homePages.distinct().map { page ->
                    LightBarButton.LightIcon(
                        icon = page.homeIcon(),
                        onClick = { onPage(page) },
                        contentDescription = page.homeLabel(),
                    )
                },
            )
        }
    }
}

@Composable
private fun InitialInstanceEditor(mode: HomeMode.InstanceEditor, viewModel: HomeViewModel) {
    key(mode.session) {
        val text = rememberTextFieldState(mode.initialValue)
        val keyboardOptions = rememberKeyboardOptions()
        LightTextInputEditor(
            title = "Invidious Server",
            state = text,
            keyboardOptionsFlow = keyboardOptions,
            onSubmit = viewModel::submitInstance,
            onBack = {},
            submitLabel = "CHECK",
            showBackButton = false,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun LoadingContent(message: String, title: String = "Lightious") {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(center = LightTopBarCenter.Text(title))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
    }
}

@Composable
internal fun ActionRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Subheading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.8f.gridUnitsAsDp()),
    )
}

@Composable
internal fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Superfine, lighten = true)
        LightText(
            text = value,
            variant = LightTextVariant.Copy,
            modifier = Modifier.padding(top = 0.15f.gridUnitsAsDp()),
        )
    }
}

internal fun HomePage.homeLabel(): String = when (this) {
    HomePage.SEARCH -> "SEARCH"
    HomePage.ACCOUNT_FEED -> "ACCOUNT FEED"
    HomePage.WATCH_HISTORY -> "WATCH HISTORY"
    HomePage.SEARCH_HISTORY -> "SEARCH HISTORY"
    HomePage.POPULAR -> "POPULAR"
}

private fun HomePage.homeIcon() = when (this) {
    HomePage.SEARCH -> LightIcons.SEARCH
    HomePage.ACCOUNT_FEED -> LightIcons.CONTACTS
    HomePage.WATCH_HISTORY -> LightIcons.PLAY
    HomePage.SEARCH_HISTORY -> LightIcons.LIST
    HomePage.POPULAR -> LightIcons.STAR
}

internal fun Throwable.userMessage(fallback: String): String =
    message?.trim()?.takeIf(String::isNotEmpty) ?: fallback
