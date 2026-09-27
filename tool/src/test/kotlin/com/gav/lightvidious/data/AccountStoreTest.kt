package com.gav.lightvidious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AccountStoreTest {
    @Test
    fun `raw and URL encoded callback tokens normalize to the same JSON`() {
        val encodedToken = URLEncoder.encode(AUTH_TOKEN, StandardCharsets.UTF_8)
        val callbackToken = URLEncoder.encode(encodedToken, StandardCharsets.UTF_8)
        val callbackUrl =
            "https://client.example/callback?state=nonce&token=$callbackToken&username=gav"

        assertEquals(AUTH_TOKEN, normalizeAuthToken("  $AUTH_TOKEN  "))
        assertEquals(AUTH_TOKEN, normalizeAuthToken(encodedToken))
        assertEquals(AUTH_TOKEN, normalizeAuthToken(callbackUrl))
        assertEquals(true, authTokenAllowsHistoryWrite(AUTH_TOKEN))
        assertEquals(
            false,
            authTokenAllowsHistoryWrite(
                "{\"session\":\"v1:feed-only\",\"scopes\":[\"GET:feed\"],\"signature\":\"sig\"}",
            ),
        )
    }

    @Test
    fun `authorization URL requests feed scope and adds history sync only when enabled`() {
        val authorizationUrl = buildAuthorizationUrl("Example.COM:443/")
        val uri = URI(authorizationUrl)
        val encodedScopes = uri.rawQuery.substringAfter("scopes=")

        assertEquals("https://example.com/authorize_token", "${uri.scheme}://${uri.authority}${uri.path}")
        assertEquals(
            "GET:feed",
            URLDecoder.decode(encodedScopes, StandardCharsets.UTF_8),
        )
        assertFalse(uri.rawQuery.contains('&'))

        val syncScopes = URI(buildAuthorizationUrl("https://example.com", includeHistorySync = true))
            .rawQuery
            .substringAfter("scopes=")
        assertEquals(
            "GET:feed,POST:history/*",
            URLDecoder.decode(syncScopes, StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `encrypted account store round trips through injected cipher`() = runTest {
        val dataStore = createDataStore(File.createTempFile("lightvidious-account", ".preferences_pb"))
        val cipher = FakeTokenCipher()
        val store = AccountStore(dataStore, cipher)

        val saved = store.save(
            instanceUrl = "Example.COM:443/",
            tokenInput = AUTH_TOKEN,
            username = "  gav  ",
        )

        assertEquals("https://example.com", saved.instanceUrl)
        assertEquals(AUTH_TOKEN, saved.token)
        assertEquals("gav", saved.username)
        assertEquals(listOf(AUTH_TOKEN), cipher.encryptedPlaintexts)
        assertFalse(
            dataStore.data.first().asMap().values
                .map(Any::toString)
                .any { persistedValue -> persistedValue.contains("v1:test-session") },
        )

        val loaded = AccountStore(dataStore, cipher).load("https://EXAMPLE.com/")

        assertEquals(saved, loaded)
        assertEquals(1, cipher.decryptCalls)
    }

    private fun TestScope.createDataStore(file: File): DataStore<Preferences> {
        file.delete()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
    }

    private class FakeTokenCipher : TokenCipher {
        val encryptedPlaintexts = mutableListOf<String>()
        var decryptCalls: Int = 0
            private set

        override fun encrypt(plaintext: String): ByteArray {
            encryptedPlaintexts += plaintext
            return "$CIPHERTEXT_PREFIX${plaintext.reversed()}".toByteArray(StandardCharsets.UTF_8)
        }

        override fun decrypt(blob: ByteArray): String {
            decryptCalls += 1
            val ciphertext = String(blob, StandardCharsets.UTF_8)
            require(ciphertext.startsWith(CIPHERTEXT_PREFIX))
            return ciphertext.removePrefix(CIPHERTEXT_PREFIX).reversed()
        }

        private companion object {
            const val CIPHERTEXT_PREFIX = "fake-encrypted:"
        }
    }

    private companion object {
        const val AUTH_TOKEN =
            "{\"session\":\"v1:test-session\",\"scopes\":[\"GET:feed\",\"GET:history\",\"POST:history/*\"],\"signature\":\"test-signature\"}"
    }
}
