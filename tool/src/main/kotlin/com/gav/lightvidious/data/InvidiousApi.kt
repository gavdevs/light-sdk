package com.gav.lightvidious.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readByte
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class InvidiousApi internal constructor(
    baseUrl: String,
    val proxyMedia: Boolean = true,
    private val transport: InvidiousHttpTransport,
    val audioLanguage: AudioLanguagePreference = AudioLanguagePreference.ORIGINAL,
) : AutoCloseable {
    val baseUrl: String = normalizeInstanceUrl(baseUrl)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    constructor(
        baseUrl: String,
        proxyMedia: Boolean = true,
        audioLanguage: AudioLanguagePreference = AudioLanguagePreference.ORIGINAL,
    ) : this(baseUrl, proxyMedia, KtorInvidiousHttpTransport(), audioLanguage)

    suspend fun popular(): Result<List<VideoSummary>> = runSuspendCatching {
        val response = transport.get("$baseUrl/api/v1/popular")
        response.requireSuccess("Popular videos")
        decodeVideoList(response.body)
    }

    suspend fun accountFeed(tokenInput: String): Result<List<VideoSummary>> = runSuspendCatching {
        val token = normalizeAuthToken(tokenInput)
        val response = transport.get(
            url = "$baseUrl/api/v1/auth/feed",
            parameters = mapOf("page" to "1", "max_results" to "40"),
            headers = authorizationHeaders(token),
        )
        response.requireSuccess("Account feed")
        val feed = try {
            json.decodeFromString<InvidiousFeedDto>(response.body)
        } catch (error: SerializationException) {
            throw InvidiousApiException("The instance returned an invalid account feed.", cause = error)
        }
        (feed.notifications.orEmpty() + feed.videos.orEmpty())
            .mapNotNull { item -> item.toVideoSummary() }
            .distinctBy(VideoSummary::videoId)
    }

    suspend fun accountHistoryIds(
        tokenInput: String,
        maxResults: Int = 1,
    ): Result<List<String>> = runSuspendCatching {
        val token = normalizeAuthToken(tokenInput)
        val response = transport.get(
            url = "$baseUrl/api/v1/auth/history",
            parameters = mapOf(
                "page" to "1",
                "max_results" to maxResults.coerceIn(1, 100).toString(),
            ),
            headers = authorizationHeaders(token),
        )
        response.requireSuccess("Account history")
        try {
            json.decodeFromString<List<String>>(response.body)
                .filter { extractYouTubeVideoId(it) == it }
                .distinct()
        } catch (error: SerializationException) {
            throw InvidiousApiException("The instance returned invalid account history.", cause = error)
        }
    }

    suspend fun markWatched(tokenInput: String, videoIdOrUrl: String): Result<Unit> =
        runSuspendCatching {
            val token = normalizeAuthToken(tokenInput)
            val videoId = extractYouTubeVideoId(videoIdOrUrl)
                ?: throw IllegalArgumentException("Enter a valid YouTube video ID or URL.")
            val response = transport.post(
                url = "$baseUrl/api/v1/auth/history/$videoId",
                headers = authorizationHeaders(token),
            )
            response.requireSuccess("Account history update")
        }

    suspend fun search(query: String): Result<List<VideoSummary>> = runSuspendCatching {
        val trimmedQuery = query.trim()
        require(trimmedQuery.isNotEmpty()) { "Enter a search query." }

        val directVideoId = extractYouTubeVideoId(trimmedQuery)
        if (directVideoId != null) {
            return@runSuspendCatching listOf(videoOrThrow(directVideoId).summary)
        }

        val response = transport.get(
            url = "$baseUrl/api/v1/search",
            parameters = mapOf(
                "q" to trimmedQuery,
                "type" to "video",
                "sort_by" to "relevance",
            ),
        )
        response.requireSuccess("Search")
        decodeVideoList(response.body)
    }

    suspend fun video(videoIdOrUrl: String): Result<VideoDetails> = runSuspendCatching {
        val videoId = extractYouTubeVideoId(videoIdOrUrl)
            ?: throw IllegalArgumentException("Enter a valid YouTube video ID or URL.")
        videoOrThrow(videoId)
    }

    suspend fun probe(): InstanceProbe {
        val searchResponse = try {
            transport.get(
                url = "$baseUrl/api/v1/search",
                parameters = mapOf(
                    "q" to PROBE_QUERY,
                    "type" to "video",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return InstanceProbe(
                instanceUrl = baseUrl,
                reachable = false,
                apiAvailable = false,
                playbackAvailable = false,
                rangeSupported = false,
                message = error.safeMessage("Could not reach the instance."),
            )
        }

        if (!searchResponse.isSuccessful) {
            return InstanceProbe(
                instanceUrl = baseUrl,
                reachable = true,
                apiAvailable = false,
                playbackAvailable = false,
                rangeSupported = false,
                apiStatusCode = searchResponse.statusCode,
                message = "The instance search API returned HTTP ${searchResponse.statusCode}.",
            )
        }

        val searchVideos = try {
            decodeVideoList(searchResponse.body)
        } catch (error: Exception) {
            return InstanceProbe(
                instanceUrl = baseUrl,
                reachable = true,
                apiAvailable = false,
                playbackAvailable = false,
                rangeSupported = false,
                apiStatusCode = searchResponse.statusCode,
                message = error.safeMessage("The instance returned an invalid API response."),
            )
        }

        if (searchVideos.isEmpty()) {
            return InstanceProbe(
                instanceUrl = baseUrl,
                reachable = true,
                apiAvailable = true,
                playbackAvailable = false,
                rangeSupported = false,
                apiStatusCode = searchResponse.statusCode,
                message = "The search API works, but it returned no video to test playback with.",
            )
        }

        var lastVideoStatus: Int? = null
        var lastPlaybackStatus: Int? = null
        var lastRangeSupported = false
        var lastMessage = "No playable stream was returned."
        for (summary in searchVideos.take(MAX_PROBE_VIDEOS)) {
            val details = try {
                videoOrThrow(summary.videoId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: InvidiousApiException) {
                lastVideoStatus = error.statusCode
                lastMessage = error.safeMessage("Video details could not be loaded.")
                continue
            } catch (error: Exception) {
                lastMessage = error.safeMessage("Video details could not be loaded.")
                continue
            }

            val mediaUrl = details.selection.watchProbeUrl
            if (mediaUrl == null) {
                lastMessage = "Video ${summary.videoId} had no usable media URL."
                continue
            }

            val playbackResponse = try {
                transport.readOneByte(mediaUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastMessage = error.safeMessage("The media URL could not be reached.")
                continue
            }

            val playbackAvailable = playbackResponse.isSuccessful && playbackResponse.receivedByte
            if (playbackAvailable) {
                return InstanceProbe(
                    instanceUrl = baseUrl,
                    reachable = true,
                    apiAvailable = true,
                    playbackAvailable = true,
                    rangeSupported = playbackResponse.rangeSupported,
                    apiStatusCode = searchResponse.statusCode,
                    playbackStatusCode = playbackResponse.statusCode,
                    videoId = summary.videoId,
                    message = if (playbackResponse.rangeSupported) {
                        "API and ranged media playback are available."
                    } else {
                        "Media playback is available, but the server ignored the byte range."
                    },
                )
            }

            lastPlaybackStatus = playbackResponse.statusCode
            lastRangeSupported = playbackResponse.rangeSupported
            lastMessage = "The media check returned HTTP ${playbackResponse.statusCode} without data."
        }

        return InstanceProbe(
            instanceUrl = baseUrl,
            reachable = true,
            apiAvailable = true,
            playbackAvailable = false,
            rangeSupported = lastRangeSupported,
            apiStatusCode = searchResponse.statusCode,
            playbackStatusCode = lastPlaybackStatus ?: lastVideoStatus,
            message = lastMessage,
        )
    }

    override fun close() {
        transport.close()
    }

    private suspend fun videoOrThrow(videoId: String): VideoDetails {
        val response = transport.get(
            url = "$baseUrl/api/v1/videos/$videoId",
            parameters = mapOf("local" to proxyMedia.toString()),
        )
        response.requireSuccess("Video details")

        val dto = try {
            json.decodeFromString<InvidiousVideoDto>(response.body)
        } catch (error: SerializationException) {
            throw InvidiousApiException("The instance returned invalid video details.", cause = error)
        }
        return dto.toVideoDetails(videoId)
    }

    private fun decodeVideoList(body: String): List<VideoSummary> {
        val items = try {
            json.decodeFromString<List<InvidiousVideoItemDto>>(body)
        } catch (error: SerializationException) {
            throw InvidiousApiException("The instance returned an invalid video list.", cause = error)
        }
        return items.mapNotNull { item -> item.toVideoSummary() }
    }

    private fun InvidiousVideoItemDto.toVideoSummary(): VideoSummary? {
        if (type != null && type.lowercase() !in VIDEO_ITEM_TYPES) return null
        val id = videoId?.takeIf { extractYouTubeVideoId(it) == it } ?: return null
        return VideoSummary(
            videoId = id,
            title = title.orEmpty().ifBlank { "Untitled video" },
            author = author.orEmpty().ifBlank { "Unknown channel" },
            lengthSeconds = lengthSeconds.asLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            viewCount = viewCount.asLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            publishedText = publishedText.orEmpty(),
            liveNow = liveNow.asBooleanOrNull() ?: false,
            thumbnailUrl = chooseThumbnail(videoThumbnails),
        )
    }

    private fun InvidiousVideoDto.toVideoDetails(fallbackVideoId: String): VideoDetails {
        val id = videoId?.takeIf { extractYouTubeVideoId(it) == it } ?: fallbackVideoId
        val summary = VideoSummary(
            videoId = id,
            title = title.orEmpty().ifBlank { "Untitled video" },
            author = author.orEmpty().ifBlank { "Unknown channel" },
            lengthSeconds = lengthSeconds.asLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            viewCount = viewCount.asLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            publishedText = publishedText.orEmpty(),
            liveNow = liveNow.asBooleanOrNull() ?: false,
            thumbnailUrl = chooseThumbnail(videoThumbnails),
        )

        val progressive = formatStreams.orEmpty().mapNotNull { format ->
            format.toMediaStream(MediaStreamKind.PROGRESSIVE)
        }
        val adaptive = adaptiveFormats.orEmpty().mapNotNull { format -> format.toAdaptiveStream() }
        val resolvedHls = resolveMediaUrl(hlsUrl, baseUrl)
        val selection = StreamSelector.select(
            formatStreams = progressive,
            adaptiveFormats = adaptive,
            hlsUrl = resolvedHls,
            liveNow = summary.liveNow,
            audioLanguage = audioLanguage,
        )

        return VideoDetails(
            summary = summary,
            description = description.orEmpty(),
            formatStreams = progressive,
            adaptiveFormats = adaptive,
            hlsUrl = resolvedHls,
            dashUrl = resolveMediaUrl(dashUrl, baseUrl),
            selection = selection,
        )
    }

    private fun InvidiousFormatDto.toAdaptiveStream(): MediaStream? {
        val mediaType = type?.substringBefore(';')?.trim()?.lowercase()
        val hasVideo = mediaType?.startsWith("video/") == true ||
            width.asIntOrNull() != null || height.asIntOrNull() != null
        val hasAudio = mediaType?.startsWith("audio/") == true ||
            audioQuality != null || audioChannels.asIntOrNull() != null
        val kind = when {
            hasAudio && !hasVideo -> MediaStreamKind.ADAPTIVE_AUDIO
            hasVideo -> MediaStreamKind.ADAPTIVE_VIDEO
            else -> MediaStreamKind.UNKNOWN
        }
        return toMediaStream(kind, hasAudio = hasAudio, hasVideo = hasVideo)
    }

    private fun InvidiousFormatDto.toMediaStream(
        kind: MediaStreamKind,
        hasAudio: Boolean = kind == MediaStreamKind.PROGRESSIVE,
        hasVideo: Boolean = kind == MediaStreamKind.PROGRESSIVE,
    ): MediaStream? {
        val resolvedUrl = resolveMediaUrl(url, baseUrl) ?: return null
        val urlAudioMetadata = parseAudioTrackUrlMetadata(resolvedUrl)
        val trackIsDefault = audioTrack?.audioIsDefault.asBooleanOrNull()
        val trackContent = when {
            trackIsDefault == true -> AudioContentType.ORIGINAL
            trackIsDefault == false -> AudioContentType.DUBBED
            audioTrack?.displayName?.contains("original", ignoreCase = true) == true ->
                AudioContentType.ORIGINAL
            else -> urlAudioMetadata.content
        }
        val rawType = type?.trim()
        val mimeType = rawType?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty)
        val codecs = rawType
            ?.let { CODECS.find(it)?.groupValues?.getOrNull(1) }
            ?: encoding
        val parsedHeight = height.asIntOrNull()
            ?: resolution?.substringAfterLast('x', missingDelimiterValue = "")?.toIntOrNull()

        return MediaStream(
            url = resolvedUrl,
            kind = kind,
            itag = itag.asIntOrNull(),
            mimeType = mimeType,
            container = container,
            codecs = codecs,
            qualityLabel = qualityLabel ?: quality,
            bitrate = bitrate.asLongOrNull()?.takeIf { it > 0L },
            width = width.asIntOrNull()?.takeIf { it > 0 },
            height = parsedHeight?.takeIf { it > 0 },
            fps = fps.asIntOrNull()?.takeIf { it > 0 },
            contentLength = contentLength.asLongOrNull()?.takeIf { it >= 0L },
            hasAudio = hasAudio,
            hasVideo = hasVideo,
            audioTrackId = audioTrack?.id,
            audioTrackName = audioTrack?.displayName,
            audioTrackIsDefault = trackIsDefault,
            audioLanguage = audioTrack?.id?.audioLanguageFromTrackId()
                ?: urlAudioMetadata.language,
            audioContent = trackContent,
            audioDynamicRangeCompressed = urlAudioMetadata.dynamicRangeCompressed,
        )
    }

    private fun chooseThumbnail(thumbnails: List<InvidiousThumbnailDto>?): String? {
        val candidates = thumbnails.orEmpty().mapNotNull { thumbnail ->
            val resolvedUrl = resolveMediaUrl(thumbnail.url, baseUrl) ?: return@mapNotNull null
            val width = thumbnail.width.asIntOrNull() ?: 0
            resolvedUrl to width
        }
        return candidates
            .filter { (_, width) -> width in MIN_THUMBNAIL_WIDTH..MAX_THUMBNAIL_WIDTH }
            .minByOrNull { (_, width) -> width }
            ?.first
            ?: candidates.minByOrNull { (_, width) -> kotlin.math.abs(width - MIN_THUMBNAIL_WIDTH) }?.first
    }

    private companion object {
        const val MAX_PROBE_VIDEOS = 3
        const val PROBE_QUERY = "Light"
        const val MIN_THUMBNAIL_WIDTH = 300
        const val MAX_THUMBNAIL_WIDTH = 720
        val VIDEO_ITEM_TYPES = setOf("video", "shortvideo")
        val CODECS = Regex("""codecs\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
    }
}

private fun String.audioLanguageFromTrackId(): String =
    replace(Regex("\\.\\d+$"), "")

private fun authorizationHeaders(token: String): Map<String, String> =
    mapOf(HttpHeaders.Authorization to "Bearer $token")

internal data class InvidiousHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299

    fun requireSuccess(operation: String) {
        if (!isSuccessful) {
            val detail = body.trim().replace(Regex("\\s+"), " ").take(300)
            val suffix = detail.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty()
            throw InvidiousApiException(
                message = "$operation returned HTTP $statusCode$suffix",
                statusCode = statusCode,
            )
        }
    }
}

internal data class OneByteResponse(
    val statusCode: Int,
    val receivedByte: Boolean,
    val rangeSupported: Boolean,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299
}

internal interface InvidiousHttpTransport {
    suspend fun get(
        url: String,
        parameters: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): InvidiousHttpResponse

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): InvidiousHttpResponse

    suspend fun readOneByte(url: String): OneByteResponse

    fun close()
}

private class KtorInvidiousHttpTransport : InvidiousHttpTransport {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
    }

    override suspend fun get(
        url: String,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): InvidiousHttpResponse {
        val response = client.get(url) {
            accept(ContentType.Application.Json)
            parameters.forEach { (name, value) -> parameter(name, value) }
            headers.forEach { (name, value) -> header(name, value) }
        }
        return InvidiousHttpResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
        )
    }

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
    ): InvidiousHttpResponse {
        val response = client.post(url) {
            accept(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
        }
        return InvidiousHttpResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
        )
    }

    override suspend fun readOneByte(url: String): OneByteResponse = client.prepareGet(url) {
        header(HttpHeaders.Range, "bytes=0-0")
        accept(ContentType.Any)
    }.execute { response ->
        val receivedByte = if (response.status.isSuccess()) {
            try {
                response.bodyAsChannel().readByte()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        OneByteResponse(
            statusCode = response.status.value,
            receivedByte = receivedByte,
            rangeSupported = response.status == HttpStatusCode.PartialContent ||
                response.headers[HttpHeaders.ContentRange] != null,
        )
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val SOCKET_TIMEOUT_MS = 20_000L
    }
}

private fun Throwable.safeMessage(fallback: String): String = message
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: fallback

private suspend fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
