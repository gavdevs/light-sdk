package com.gav.lightvidious.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AccountStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher = AndroidTokenCipher(),
) {
    suspend fun load(instanceUrl: String): AccountSession? {
        val normalizedInstance = normalizeInstanceUrl(instanceUrl)
        val preferences = dataStore.data.first()
        if (preferences[ACCOUNT_INSTANCE] != normalizedInstance) return null
        val encoded = preferences[ENCRYPTED_TOKEN] ?: return null
        val token = runCatching {
            val encrypted = Base64.getDecoder().decode(encoded)
            normalizeAuthToken(cipher.decrypt(encrypted))
        }.getOrNull() ?: return null
        return accountSession(normalizedInstance, token, preferences[ACCOUNT_USERNAME])
    }

    suspend fun save(instanceUrl: String, tokenInput: String, username: String? = null): AccountSession {
        val normalizedInstance = normalizeInstanceUrl(instanceUrl)
        val token = normalizeAuthToken(tokenInput)
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(token))
        val cleanUsername = username?.trim()?.takeIf(String::isNotEmpty)
        dataStore.edit { preferences ->
            preferences[ACCOUNT_INSTANCE] = normalizedInstance
            preferences[ENCRYPTED_TOKEN] = encrypted
            if (cleanUsername == null) {
                preferences.remove(ACCOUNT_USERNAME)
            } else {
                preferences[ACCOUNT_USERNAME] = cleanUsername
            }
        }
        return accountSession(normalizedInstance, token, cleanUsername)
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(ACCOUNT_INSTANCE)
            preferences.remove(ENCRYPTED_TOKEN)
            preferences.remove(ACCOUNT_USERNAME)
        }
    }

    private companion object {
        val ACCOUNT_INSTANCE = stringPreferencesKey("invidious_account_instance")
        val ENCRYPTED_TOKEN = stringPreferencesKey("invidious_account_token_encrypted")
        val ACCOUNT_USERNAME = stringPreferencesKey("invidious_account_username")
    }
}

internal interface TokenCipher {
    fun encrypt(plaintext: String): ByteArray
    fun decrypt(blob: ByteArray): String
}

private class AndroidTokenCipher(
    private val keyStore: LightvidiousTokenKeyStore = LightvidiousTokenKeyStore(),
) : TokenCipher {
    init {
        keyStore.ensureKey()
    }

    override fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyStore.getSecretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return ByteBuffer.allocate(cipher.iv.size + ciphertext.size)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    }

    override fun decrypt(blob: ByteArray): String {
        require(blob.size > GCM_IV_LENGTH) { "Stored account token is invalid." }
        val buffer = ByteBuffer.wrap(blob)
        val iv = ByteArray(GCM_IV_LENGTH)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyStore.getSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

private class LightvidiousTokenKeyStore(
    private val keyAlias: String = KEY_ALIAS,
) {
    fun ensureKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: error("Lightious account key is unavailable.")
    }

    private companion object {
        const val KEY_ALIAS = "lightvidious_invidious_account_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

private val tokenJson = Json { ignoreUnknownKeys = true }

fun normalizeAuthToken(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "Enter an Invidious API token." }
    val queryToken = extractQueryValue(trimmed, "token")
        ?: extractQueryValue(trimmed, "access_token")
    var candidate = queryToken ?: trimmed
    repeat(2) {
        if (candidate.startsWith("%7B", ignoreCase = true)) {
            candidate = URLDecoder.decode(candidate, StandardCharsets.UTF_8)
        }
    }
    val token = runCatching { tokenJson.parseToJsonElement(candidate).jsonObject }
        .getOrElse { throw IllegalArgumentException("This is not a valid Invidious API token.") }
    val session = token["session"]?.jsonPrimitive?.content.orEmpty()
    val signature = token["signature"]?.jsonPrimitive?.content.orEmpty()
    val scopes = token["scopes"] as? JsonArray
    require(session.startsWith("v1:") && signature.isNotBlank() && !scopes.isNullOrEmpty()) {
        "This token is missing its session, scopes, or signature."
    }
    return token.toString()
}

fun authTokenAllowsHistoryWrite(tokenInput: String): Boolean {
    val token = tokenJson.parseToJsonElement(normalizeAuthToken(tokenInput)).jsonObject
    val scopes = token["scopes"] as? JsonArray ?: return false
    return scopes.any { element ->
        val scope = element.jsonPrimitive.content
        val separator = scope.indexOf(':')
        if (separator < 0) return@any false
        val methodPart = scope.substring(0, separator)
        val methods = methodPart.split(';')
        val endpoint = scope.substring(separator + 1)
        (methodPart.isEmpty() || "POST" in methods) &&
            (endpoint == "history/*" || endpoint == "*")
    }
}

fun buildAuthorizationUrl(
    instanceUrl: String,
    includeHistorySync: Boolean = false,
): String {
    val base = normalizeInstanceUrl(instanceUrl)
    val scopes = buildList {
        add("GET:feed")
        if (includeHistorySync) add("POST:history/*")
    }.joinToString(",")
    return "$base/authorize_token?scopes=${encodeQuery(scopes)}"
}

internal fun accountSession(
    instanceUrl: String,
    token: String,
    username: String? = null,
): AccountSession {
    val normalized = normalizeInstanceUrl(instanceUrl)
    val parsed = tokenJson.parseToJsonElement(token).jsonObject
    val session = parsed.getValue("session").jsonPrimitive.content
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$normalized\n$session".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return AccountSession(normalized, token, digest, username)
}

private fun extractQueryValue(value: String, name: String): String? {
    val rawQuery = runCatching { URI(value).rawQuery }.getOrNull() ?: return null
    return rawQuery.split('&').firstNotNullOfOrNull { pair ->
        val separator = pair.indexOf('=')
        if (separator < 0) return@firstNotNullOfOrNull null
        val key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8)
        if (key != name) return@firstNotNullOfOrNull null
        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8)
    }
}

private fun encodeQuery(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
