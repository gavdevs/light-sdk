package com.gav.lightvidious.data

import java.net.URI
import java.net.URLDecoder

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
private val EXPLICIT_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

fun normalizeInstanceUrl(value: String): String {
    val input = value.trim()
    require(input.isNotEmpty()) { "Enter an Invidious instance URL." }

    val withScheme = if (EXPLICIT_SCHEME.containsMatchIn(input)) input else "https://$input"
    val uri = try {
        URI(withScheme)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Invidious instance URL.", error)
    }

    val scheme = uri.scheme?.lowercase()
    require(scheme == "https") {
        "Invidious instance URL must use https."
    }
    require(uri.rawUserInfo == null) { "Invidious instance URL must not include credentials." }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "Invidious instance URL must not include a query or fragment."
    }
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
        "Invidious instance URL must not include a path."
    }

    val host = uri.host?.lowercase()
    require(!host.isNullOrBlank()) { "Invidious instance URL must include a valid host." }

    val port = when {
        uri.port == -1 -> -1
        scheme == "https" && uri.port == 443 -> -1
        scheme == "http" && uri.port == 80 -> -1
        else -> uri.port
    }
    require(port in -1..65535) { "Invidious instance URL has an invalid port." }

    return URI(scheme, null, host, port, null, null, null).toASCIIString()
}

fun extractYouTubeVideoId(value: String): String? {
    val input = value.trim()
    if (VIDEO_ID.matches(input)) return input
    if (input.isEmpty()) return null

    val candidateUrl = if (EXPLICIT_SCHEME.containsMatchIn(input)) input else "https://$input"
    val uri = runCatching { URI(candidateUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null

    val candidate = when {
        host == "youtu.be" -> uri.rawPath.pathSegments().firstOrNull()
        host == "youtube.com" || host.endsWith(".youtube.com") -> {
            val segments = uri.rawPath.pathSegments()
            when (segments.firstOrNull()?.lowercase()) {
                "watch" -> uri.rawQuery.queryParameter("v")
                "shorts", "embed", "live", "v" -> segments.getOrNull(1)
                else -> null
            }
        }
        host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") -> {
            val segments = uri.rawPath.pathSegments()
            if (segments.firstOrNull()?.lowercase() == "embed") segments.getOrNull(1) else null
        }
        else -> null
    }

    return candidate?.takeIf(VIDEO_ID::matches)
}

internal fun resolveMediaUrl(value: String?, instanceUrl: String): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val base = URI("${normalizeInstanceUrl(instanceUrl)}/")
    val resolved = runCatching { base.resolve(raw) }.getOrNull() ?: return null
    return resolved.toString().takeIf(::isHttpUrl)
}

internal fun isHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}

internal data class AudioTrackUrlMetadata(
    val language: String? = null,
    val content: AudioContentType = AudioContentType.UNKNOWN,
    val dynamicRangeCompressed: Boolean = false,
)

internal fun parseAudioTrackUrlMetadata(value: String): AudioTrackUrlMetadata {
    val rawQuery = runCatching { URI(value).rawQuery }.getOrNull() ?: return AudioTrackUrlMetadata()
    val encodedTags = rawQuery
        .split('&')
        .asSequence()
        .map { part ->
            part.substringBefore('=') to part.substringAfter('=', missingDelimiterValue = "")
        }
        .firstOrNull { (key, _) -> decodeQueryComponent(key).equals("xtags", ignoreCase = true) }
        ?.second
        ?: return AudioTrackUrlMetadata()
    val tags = decodeQueryComponent(encodedTags)
        .split(':')
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
        }
        .associate { (key, entryValue) -> key.lowercase() to entryValue }

    return AudioTrackUrlMetadata(
        language = tags["lang"]?.trim()?.takeIf(String::isNotEmpty),
        content = when (tags["acont"]?.lowercase()) {
            "original" -> AudioContentType.ORIGINAL
            "dubbed" -> AudioContentType.DUBBED
            "dubbed-auto" -> AudioContentType.DUBBED_AUTO
            else -> AudioContentType.UNKNOWN
        },
        dynamicRangeCompressed = tags["drc"] == "1",
    )
}

private fun decodeQueryComponent(value: String): String =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

private fun String?.queryParameter(name: String): String? = this
    ?.split('&')
    ?.asSequence()
    ?.map { part -> part.substringBefore('=') to part.substringAfter('=', missingDelimiterValue = "") }
    ?.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
    ?.second

private fun String?.pathSegments(): List<String> = this
    ?.split('/')
    ?.filter(String::isNotEmpty)
    .orEmpty()
