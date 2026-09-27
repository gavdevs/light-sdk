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
import com.gav.lightvidious.data.AudioLanguagePreference
import com.gav.lightvidious.data.ClientSettings
import com.gav.lightvidious.data.HomePage
import com.gav.lightvidious.data.InvidiousApi
import com.gav.lightvidious.data.authTokenAllowsHistoryWrite
import com.gav.lightvidious.data.buildAuthorizationUrl
import com.gav.lightvidious.data.normalizeAuthToken
import com.gav.lightvidious.data.normalizeInstanceUrl
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: ClientSettings = ClientSettings(),
    val signedIn: Boolean = false,
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

class SettingsViewModel(
    private val services: LightvidiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = services.settings.load()
                _uiState.value = SettingsUiState(
                    settings = settings,
                    signedIn = services.accounts.load(settings.instanceUrl) != null,
                    loading = false,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not load settings."))
                }
            }
        }
    }

    fun toggleProxyMedia() = updateSetting(
        save = { services.settings.setProxyMedia(!it.proxyMedia) },
    )

    fun toggleSearchHistory() = updateSetting(
        save = { services.settings.setSearchHistoryEnabled(!it.saveSearchHistory) },
    )

    fun toggleWatchHistory() = updateSetting(
        save = { services.settings.setWatchHistoryEnabled(!it.saveWatchHistory) },
    )

    fun setAudioLanguage(preference: AudioLanguagePreference) = updateSetting(
        save = { services.settings.setAudioLanguage(preference) },
    )

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun updateSetting(save: suspend (ClientSettings) -> Unit) {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                save(_uiState.value.settings)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not save this setting.")) }
            }
        }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightvidiousServices,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {
    override val viewModelClass = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(services)

    override fun willShow() {
        viewModel.load()
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
                if (state.loading) {
                    LoadingContent("Loading…", "Settings")
                } else {
                    SettingsContent(
                        state = state,
                        onBack = { goBack() },
                        onInstance = {
                            navigateTo(
                                screenFactory = { activity ->
                                    InstanceEditorScreen(
                                        activity,
                                        services,
                                        state.settings.instanceUrl,
                                    )
                                },
                            )
                        },
                        onAccount = {
                            navigateTo(
                                screenFactory = { activity -> AccountScreen(activity, services) },
                            )
                        },
                        onPages = {
                            navigateTo(
                                screenFactory = { activity -> HomePagesScreen(activity, services) },
                            )
                        },
                        onAudioLanguage = {
                            navigateTo(
                                screenFactory = { activity ->
                                    AudioLanguageScreen(
                                        activity,
                                        state.settings.audioLanguage,
                                    )
                                },
                                resultCallback = viewModel::setAudioLanguage,
                            )
                        },
                        onProxy = viewModel::toggleProxyMedia,
                        onSearchHistory = viewModel::toggleSearchHistory,
                        onWatchHistory = viewModel::toggleWatchHistory,
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
private fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onInstance: () -> Unit,
    onAccount: () -> Unit,
    onPages: () -> Unit,
    onAudioLanguage: () -> Unit,
    onProxy: () -> Unit,
    onSearchHistory: () -> Unit,
    onWatchHistory: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Settings"),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            SettingRow("INVIDIOUS SERVER", state.settings.instanceUrl, onInstance)
            SettingRow(
                "ACCOUNT",
                if (state.signedIn) "SIGNED IN" else "NOT SIGNED IN",
                onAccount,
            )
            SettingRow(
                "BOTTOM NAVIGATION",
                state.settings.homePages.joinToString(" · ") { it.homeLabel() },
                onPages,
            )
            SettingRow(
                "AUDIO LANGUAGE",
                state.settings.audioLanguage.displayName.uppercase(),
                onAudioLanguage,
            )
            SettingRow(
                "PROXY MEDIA",
                if (state.settings.proxyMedia) "ON" else "OFF",
                onProxy,
            )
            SettingRow(
                "SAVE SEARCH HISTORY",
                if (state.settings.saveSearchHistory) "ON — local only" else "OFF — existing entries kept",
                onSearchHistory,
            )
            SettingRow(
                "SAVE WATCH HISTORY",
                if (state.settings.saveWatchHistory) "ON — local only" else "OFF — existing entries kept",
                onWatchHistory,
            )
            LightText(
                text = "Search history stays on this phone. Signed-in watch sync is controlled in Account.",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
    }
}

class AudioLanguageScreen(
    sealedActivity: SealedLightActivity,
    private val selected: AudioLanguagePreference,
) : SimpleLightScreen<AudioLanguagePreference>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
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
                    center = LightTopBarCenter.Text("Audio Language"),
                )
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    AudioLanguagePreference.entries.forEach { preference ->
                        SettingRow(
                            label = preference.displayName.uppercase(),
                            value = if (preference == selected) "SELECTED" else "",
                            onClick = { goBack(preference) },
                        )
                    }
                    LightText(
                        text = "If the chosen language is unavailable, original audio is used.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

data class HomePagesUiState(
    val settings: ClientSettings = ClientSettings(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

class HomePagesViewModel(
    private val services: LightvidiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(HomePagesUiState())
    val uiState: StateFlow<HomePagesUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = services.settings.load()
            _uiState.value = HomePagesUiState(settings = settings, loading = false)
        }
    }

    fun toggle(page: HomePage) {
        val current = _uiState.value.settings.homePages
        val next = if (page in current) {
            if (current.size == 1) {
                _uiState.update { it.copy(errorMessage = "Keep at least one page on Home.") }
                return
            }
            current - page
        } else {
            (current + page).sortedBy { HomePage.entries.indexOf(it) }
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                services.settings.setHomePages(next)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not save Home pages.")) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

class HomePagesScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightvidiousServices,
) : LightScreen<Unit, HomePagesViewModel>(sealedActivity) {
    override val viewModelClass = HomePagesViewModel::class.java

    override fun createViewModel() = HomePagesViewModel(services)

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
                if (state.loading) {
                    LoadingContent("Loading…", "Bottom Navigation")
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LightTopBar(
                            leftButton = LightBarButton.LightIcon(
                                icon = LightIcons.BACK,
                                onClick = { goBack() },
                                contentDescription = "Back",
                            ),
                            center = LightTopBarCenter.Text("Bottom Navigation"),
                        )
                        LightScrollView(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            HomePage.entries.forEach { page ->
                                SettingRow(
                                    page.homeLabel(),
                                    if (page in state.settings.homePages) "SHOWN" else "HIDDEN",
                                ) { viewModel.toggle(page) }
                            }
                            LightText(
                                text = "Popular is optional and is never loaded unless you open it.",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
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

data class AccountUiState(
    val settings: ClientSettings = ClientSettings(),
    val account: AccountSession? = null,
    val loading: Boolean = true,
    val authorizationUrl: String = "",
    val errorMessage: String? = null,
)

class AccountViewModel(
    private val services: LightvidiousServices,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    init {
        load()
    }

    fun load() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = services.settings.load()
            _uiState.value = AccountUiState(
                settings = settings,
                account = services.accounts.load(settings.instanceUrl),
                loading = false,
                authorizationUrl = buildAuthorizationUrl(
                    settings.instanceUrl,
                    includeHistorySync = settings.syncAccountHistory,
                ),
            )
        }
    }

    fun signIn(tokenInput: String) {
        val token = runCatching { normalizeAuthToken(tokenInput) }.getOrElse { error ->
            _uiState.update { it.copy(errorMessage = error.userMessage("Invalid API token.")) }
            return
        }
        requestJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val settings = _uiState.value.settings
            if (settings.syncAccountHistory && !authTokenAllowsHistoryWrite(token)) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = "This token does not allow watched-state sync. Use the authorization URL currently shown.",
                    )
                }
                return@launch
            }
            InvidiousApi(
                settings.instanceUrl,
                settings.proxyMedia,
                settings.audioLanguage,
            ).use { api ->
                val feed = api.accountFeed(token)
                val failure = feed.exceptionOrNull()
                if (failure != null) {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            errorMessage = failure.userMessage(
                                "The token was rejected or is missing account feed access.",
                            ),
                        )
                    }
                    return@launch
                }
            }
            try {
                services.accounts.save(settings.instanceUrl, token)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not save the account."))
                }
            }
        }
    }

    fun signOut() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val account = _uiState.value.account
            try {
                if (account != null) services.history.clearPendingServerWatches(account.accountKey)
                services.accounts.clear()
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not sign out.")) }
            }
        }
    }

    fun toggleSync() {
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val enabling = !_uiState.value.settings.syncAccountHistory
                val account = _uiState.value.account
                if (enabling && account != null && !authTokenAllowsHistoryWrite(account.token)) {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Sign out, turn on watch sync, then authorize again to grant access.",
                        )
                    }
                    return@launch
                }
                if (!enabling) {
                    account?.let { account ->
                        services.history.clearPendingServerWatches(account.accountKey)
                    }
                }
                services.settings.setAccountHistorySyncEnabled(enabling)
                requestJob = null
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.userMessage("Could not save sync setting.")) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

class AccountScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightvidiousServices,
) : LightScreen<Unit, AccountViewModel>(sealedActivity) {
    override val viewModelClass = AccountViewModel::class.java

    override fun createViewModel() = AccountViewModel(services)

    override fun willShow() {
        viewModel.load()
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
                if (state.loading) {
                    LoadingContent("Checking account…", "Account")
                } else {
                    AccountContent(
                        state = state,
                        onBack = { goBack() },
                        onSignIn = {
                            navigateTo(
                                screenFactory = { activity -> TokenInputScreen(activity) },
                                resultCallback = viewModel::signIn,
                            )
                        },
                        onSignOut = {
                            navigateTo(
                                screenFactory = { activity ->
                                    ConfirmScreen(
                                        activity,
                                        title = "Sign Out",
                                        message = "Remove this Invidious account token? Local histories are kept.",
                                        confirmLabel = "SIGN OUT",
                                    )
                                },
                                resultCallback = { confirmed ->
                                    if (confirmed) viewModel.signOut()
                                },
                            )
                        },
                        onToggleSync = viewModel::toggleSync,
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
private fun AccountContent(
    state: AccountUiState,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onToggleSync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Account"),
        )
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (state.account == null) {
                LightText(
                    text = "Sign in with a restricted Invidious API token. Lightious never receives your password.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = "On another device, sign in to this server and open:",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
                LightText(
                    text = state.authorizationUrl,
                    variant = LightTextVariant.Fine,
                    maxLines = 8,
                    modifier = Modifier.padding(top = 0.4f.gridUnitsAsDp()),
                )
                SettingRow(
                    "SYNC FUTURE WATCHES",
                    if (state.settings.syncAccountHistory) {
                        "ON — requests watched-state access"
                    } else {
                        "OFF — local only"
                    },
                    onToggleSync,
                )
                ActionRow("ENTER GENERATED TOKEN") { onSignIn() }
                LightText(
                    text = "The token is encrypted after saving and can be revoked from your Invidious account.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            } else {
                LightText(
                    text = "STATUS  ·  SIGNED IN",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                SettingRow(
                    "SYNC WATCHED STATE",
                    if (state.settings.syncAccountHistory) "ON — future watches only" else "OFF",
                    onToggleSync,
                )
                ActionRow("SIGN OUT") { onSignOut() }
                LightText(
                    text = "Local watch history stays on this phone. Invidious receives only watched video IDs, not playback position.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}

data class InstanceEditorUiState(
    val initialValue: String,
    val session: Int = 0,
    val loading: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

class InstanceEditorViewModel(
    private val services: LightvidiousServices,
    initialValue: String,
) : LightViewModel<Unit>() {
    private val _uiState = MutableStateFlow(InstanceEditorUiState(initialValue))
    val uiState: StateFlow<InstanceEditorUiState> = _uiState.asStateFlow()
    private var requestJob: Job? = null

    fun submit(value: CharSequence) {
        val normalized = runCatching { normalizeInstanceUrl(value.toString()) }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    initialValue = value.toString(),
                    session = it.session + 1,
                    errorMessage = error.userMessage("Invalid instance URL."),
                )
            }
            return
        }
        requestJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            val oldSettings = services.settings.load()
            val oldAccount = if (oldSettings.instanceUrl != normalized) {
                services.accounts.load(oldSettings.instanceUrl)
            } else {
                null
            }
            val probe = InvidiousApi(
                normalized,
                oldSettings.proxyMedia,
                oldSettings.audioLanguage,
            ).use { it.probe() }
            if (!probe.apiAvailable) {
                _uiState.update {
                    it.copy(
                        initialValue = normalized,
                        session = it.session + 1,
                        loading = false,
                        errorMessage = probe.message,
                    )
                }
                return@launch
            }
            try {
                services.settings.saveInstance(normalized)
                if (oldSettings.instanceUrl != normalized) {
                    withContext(NonCancellable) {
                        try {
                            oldAccount?.let { account ->
                                services.history.clearPendingServerWatches(account.accountKey)
                            }
                        } finally {
                            services.accounts.clear()
                        }
                    }
                }
                _uiState.update { it.copy(loading = false, saved = true) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.userMessage("Could not save server."))
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

class InstanceEditorScreen(
    sealedActivity: SealedLightActivity,
    private val services: LightvidiousServices,
    private val initialValue: String,
) : LightScreen<Unit, InstanceEditorViewModel>(sealedActivity) {
    override val viewModelClass = InstanceEditorViewModel::class.java

    override fun createViewModel() = InstanceEditorViewModel(services, initialValue)

    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        if (state.saved) {
            androidx.compose.runtime.LaunchedEffect(Unit) { goBack() }
        }
        LightTheme(colors = colors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                if (state.loading) {
                    LoadingContent("Checking server…", "Invidious Server")
                } else {
                    key(state.session) {
                        val text = rememberTextFieldState(state.initialValue)
                        val keyboardOptions = rememberKeyboardOptions()
                        LightTextInputEditor(
                            title = "Invidious Server",
                            state = text,
                            keyboardOptionsFlow = keyboardOptions,
                            onSubmit = viewModel::submit,
                            onBack = { goBack() },
                            submitLabel = "CHECK",
                            singleLine = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                state.errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

private class TokenInputScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        val text = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        LightTheme(colors = colors) {
            LightTextInputEditor(
                title = "API Token",
                state = text,
                keyboardOptionsFlow = keyboardOptions,
                onSubmit = { value -> goBack(value.toString()) },
                onBack = { goBack() },
                submitLabel = "SIGN IN",
                singleLine = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal class ConfirmScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text(title))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = message,
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(text = "CANCEL", onClick = { goBack(false) }),
                        LightBarButton.Text(text = confirmLabel, onClick = { goBack(true) }),
                    ),
                )
            }
        }
    }
}
