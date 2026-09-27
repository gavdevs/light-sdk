package com.gav.lightvidious.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsStoreTest {
    @Test
    fun `defaults to unconfigured with media proxying enabled`() = runTest {
        val store = createStore(File.createTempFile("lightvidious-settings", ".preferences_pb"))

        assertEquals(ClientSettings(instanceUrl = "", proxyMedia = true), store.load())
    }

    @Test
    fun `default home pages are focused and exclude popular`() = runTest {
        val store = createStore(File.createTempFile("lightvidious-settings", ".preferences_pb"))

        assertEquals(
            listOf(
                HomePage.SEARCH,
                HomePage.ACCOUNT_FEED,
                HomePage.WATCH_HISTORY,
                HomePage.SEARCH_HISTORY,
            ),
            store.load().homePages,
        )
        assertEquals(false, HomePage.POPULAR in store.load().homePages)
        assertEquals(false, store.load().syncAccountHistory)
    }

    @Test
    fun `normalizes and persists both settings`() = runTest {
        val store = createStore(File.createTempFile("lightvidious-settings", ".preferences_pb"))

        store.saveInstance("Example.COM:443/")
        store.setProxyMedia(false)

        assertEquals(
            ClientSettings(instanceUrl = "https://example.com", proxyMedia = false),
            store.load(),
        )
    }

    @Test
    fun `home pages preserve order while removing duplicates`() = runTest {
        val store = createStore(File.createTempFile("lightvidious-settings", ".preferences_pb"))

        store.setHomePages(
            listOf(
                HomePage.WATCH_HISTORY,
                HomePage.SEARCH,
                HomePage.WATCH_HISTORY,
                HomePage.ACCOUNT_FEED,
                HomePage.SEARCH,
            ),
        )

        assertEquals(
            listOf(
                HomePage.WATCH_HISTORY,
                HomePage.SEARCH,
                HomePage.ACCOUNT_FEED,
            ),
            store.load().homePages,
        )
    }

    @Test
    fun `persists independent history toggles`() = runTest {
        val store = createStore(File.createTempFile("lightvidious-settings", ".preferences_pb"))

        store.setSearchHistoryEnabled(false)
        store.setWatchHistoryEnabled(false)
        store.setAccountHistorySyncEnabled(true)

        val settings = store.load()
        assertEquals(false, settings.saveSearchHistory)
        assertEquals(false, settings.saveWatchHistory)
        assertEquals(true, settings.syncAccountHistory)
    }

    @Test
    fun `persists one app wide audio language preference`() = runTest {
        val store = createStore(File.createTempFile("lightvidious-settings", ".preferences_pb"))

        store.setAudioLanguage(AudioLanguagePreference.ENGLISH)

        assertEquals(AudioLanguagePreference.ENGLISH, store.load().audioLanguage)
    }

    @Test
    fun `does not persist an invalid instance`() = runTest {
        val store = createStore(File.createTempFile("lightvidious-settings", ".preferences_pb"))

        assertFailsWith<IllegalArgumentException> {
            store.saveInstance("https://example.com/not-an-origin")
        }
        assertEquals("", store.load().instanceUrl)
    }

    private fun kotlinx.coroutines.test.TestScope.createStore(file: File): SettingsStore {
        file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        return SettingsStore(dataStore)
    }
}
