package com.gav.lightvidious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    defaultInstanceUrl: String = DEFAULT_INVIDIOUS_INSTANCE_URL,
) {
    private val normalizedDefaultInstanceUrl = defaultInstanceUrl
        .takeIf(String::isNotBlank)
        ?.let(::normalizeInstanceUrl)
        .orEmpty()

    val settings: Flow<ClientSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map(::settingsFromPreferences)

    suspend fun load(): ClientSettings = settings.first()

    suspend fun saveInstance(instanceUrl: String) {
        val normalized = normalizeInstanceUrl(instanceUrl)
        dataStore.edit { preferences ->
            preferences[INSTANCE_URL] = normalized
        }
    }

    suspend fun setProxyMedia(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PROXY_MEDIA] = enabled
        }
    }

    suspend fun setHomePages(pages: List<HomePage>) {
        val normalized = normalizeHomePages(pages)
        require(normalized.isNotEmpty()) { "Keep at least one page on Home." }
        dataStore.edit { preferences ->
            preferences[HOME_PAGES] = normalized.joinToString(",", transform = HomePage::name)
        }
    }

    suspend fun setSearchHistoryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SAVE_SEARCH_HISTORY] = enabled
        }
    }

    suspend fun setWatchHistoryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SAVE_WATCH_HISTORY] = enabled
        }
    }

    suspend fun setAccountHistorySyncEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SYNC_ACCOUNT_HISTORY] = enabled
        }
    }

    suspend fun setAudioLanguage(preference: AudioLanguagePreference) {
        dataStore.edit { preferences ->
            preferences[AUDIO_LANGUAGE] = preference.name
        }
    }

    private fun settingsFromPreferences(preferences: Preferences): ClientSettings {
        val storedInstance = preferences[INSTANCE_URL]
        val instanceUrl = storedInstance
            ?.let { runCatching { normalizeInstanceUrl(it) }.getOrNull() }
            ?: normalizedDefaultInstanceUrl

        return ClientSettings(
            instanceUrl = instanceUrl,
            proxyMedia = preferences[PROXY_MEDIA] ?: true,
            homePages = parseHomePages(preferences[HOME_PAGES]),
            saveSearchHistory = preferences[SAVE_SEARCH_HISTORY] ?: true,
            saveWatchHistory = preferences[SAVE_WATCH_HISTORY] ?: true,
            syncAccountHistory = preferences[SYNC_ACCOUNT_HISTORY] ?: false,
            audioLanguage = preferences[AUDIO_LANGUAGE]
                ?.let { stored ->
                    AudioLanguagePreference.entries.firstOrNull { it.name == stored }
                }
                ?: AudioLanguagePreference.ORIGINAL,
        )
    }

    private fun parseHomePages(value: String?): List<HomePage> {
        if (value == null) return DEFAULT_HOME_PAGES
        val parsed = value
            .split(',')
            .mapNotNull { name -> HomePage.entries.firstOrNull { it.name == name.trim() } }
        return normalizeHomePages(parsed).ifEmpty { listOf(HomePage.SEARCH) }
    }

    private fun normalizeHomePages(pages: List<HomePage>): List<HomePage> =
        pages.distinct()

    private companion object {
        val INSTANCE_URL = stringPreferencesKey("invidious_instance_url")
        val PROXY_MEDIA = booleanPreferencesKey("invidious_proxy_media")
        val HOME_PAGES = stringPreferencesKey("home_pages")
        val SAVE_SEARCH_HISTORY = booleanPreferencesKey("save_search_history")
        val SAVE_WATCH_HISTORY = booleanPreferencesKey("save_watch_history")
        val SYNC_ACCOUNT_HISTORY = booleanPreferencesKey("sync_account_history")
        val AUDIO_LANGUAGE = stringPreferencesKey("audio_language")
    }
}
