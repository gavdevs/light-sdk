package com.gav.lightvidious.data

object StreamSelector {
    const val MAX_PROGRESSIVE_HEIGHT: Int = 720

    fun select(
        formatStreams: List<MediaStream>,
        adaptiveFormats: List<MediaStream>,
        hlsUrl: String?,
        liveNow: Boolean,
        audioLanguage: AudioLanguagePreference = AudioLanguagePreference.ORIGINAL,
    ): StreamSelection {
        val liveHls = hlsUrl
            ?.takeIf { liveNow && isHttpUrl(it) }
            ?.let {
                MediaStream(
                    url = it,
                    kind = MediaStreamKind.LIVE_HLS,
                    mimeType = "application/x-mpegURL",
                    hasAudio = true,
                    hasVideo = true,
                )
            }

        val adaptiveAudio = selectAdaptiveAudio(adaptiveFormats, audioLanguage)
        return StreamSelection(
            liveHls = liveHls,
            progressive = selectProgressive(formatStreams),
            adaptiveAudio = adaptiveAudio,
            adaptiveVideo = selectAdaptiveVideo(adaptiveFormats),
            preferAdaptivePair = audioLanguage != AudioLanguagePreference.ORIGINAL &&
                adaptiveAudio.matches(audioLanguage),
        )
    }

    fun selectProgressive(streams: List<MediaStream>): MediaStream? = streams
        .asSequence()
        .filter {
            it.kind == MediaStreamKind.PROGRESSIVE &&
                it.hasAudio &&
                it.hasVideo &&
                isHttpUrl(it.url)
        }
        .mapNotNull { stream ->
            val height = stream.inferredHeight() ?: return@mapNotNull null
            stream.takeIf { height in 1..MAX_PROGRESSIVE_HEIGHT }?.let { it to height }
        }
        .maxWithOrNull(
            compareBy<Pair<MediaStream, Int>> { it.second }
                .thenBy { (stream, _) -> stream.videoCompatibilityRank() }
                .thenBy { (stream, _) -> stream.bitrate ?: 0L },
        )
        ?.first

    fun selectAdaptiveAudio(
        streams: List<MediaStream>,
        audioLanguage: AudioLanguagePreference = AudioLanguagePreference.ORIGINAL,
    ): MediaStream? {
        val candidates = streams
        .asSequence()
        .filter {
            it.kind == MediaStreamKind.ADAPTIVE_AUDIO &&
                it.hasAudio &&
                !it.hasVideo &&
                isHttpUrl(it.url)
        }
        .toList()
        if (candidates.isEmpty()) return null

        val bestTrackRank = candidates.maxOf { it.audioTrackPreferenceRank(audioLanguage) }
        val preferredTrack = candidates.filter {
            it.audioTrackPreferenceRank(audioLanguage) == bestTrackRank
        }

        // Current stock Invidious does not expose audioTrack. In that case it sorts
        // original/default audio first, so preserving response order is safer than
        // jumping to a higher-bitrate dub whose language cannot be identified.
        if (preferredTrack.none(MediaStream::hasAudioTrackMetadata)) {
            return preferredTrack.first()
        }

        return preferredTrack.maxWithOrNull(
            compareBy<MediaStream> { it.audioContentPreferenceRank() }
                .thenBy { if (it.audioDynamicRangeCompressed) 0 else 1 }
                .thenBy { it.audioCompatibilityRank() }
                .thenBy { it.bitrate ?: 0L },
        )
    }

    fun selectAdaptiveVideo(streams: List<MediaStream>): MediaStream? = streams
        .asSequence()
        .filter {
            it.kind == MediaStreamKind.ADAPTIVE_VIDEO &&
                it.hasVideo &&
                isHttpUrl(it.url)
        }
        .mapNotNull { stream ->
            val height = stream.inferredHeight() ?: return@mapNotNull null
            stream.takeIf { height in 1..MAX_PROGRESSIVE_HEIGHT }?.let { it to height }
        }
        .maxWithOrNull(
            compareBy<Pair<MediaStream, Int>> { it.second }
                .thenBy { (stream, _) -> stream.videoCompatibilityRank() }
                .thenBy { (stream, _) -> stream.bitrate ?: 0L },
        )
        ?.first
}

internal fun MediaStream.inferredHeight(): Int? {
    height?.takeIf { it > 0 }?.let { return it }

    val labelHeight = qualityLabel
        ?.let { QUALITY_HEIGHT.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    if (labelHeight != null) return labelHeight

    return when (qualityLabel?.lowercase()) {
        "tiny" -> 144
        "small" -> 240
        "medium" -> 360
        "large" -> 480
        "hd720" -> 720
        "hd1080" -> 1080
        "highres" -> 2160
        else -> null
    }
}

private fun MediaStream.videoCompatibilityRank(): Int = when {
    codecs?.contains("avc1", ignoreCase = true) == true ||
        codecs?.contains("avc3", ignoreCase = true) == true ||
        codecs?.contains("h264", ignoreCase = true) == true -> 5
    mimeType.equals("video/mp4", ignoreCase = true) -> 4
    container.equals("mp4", ignoreCase = true) -> 4
    codecs?.contains("vp9", ignoreCase = true) == true ||
        codecs?.contains("vp09", ignoreCase = true) == true -> 3
    mimeType.equals("video/webm", ignoreCase = true) -> 2
    else -> 1
}

private fun MediaStream.audioCompatibilityRank(): Int = when {
    mimeType.equals("audio/mp4", ignoreCase = true) -> 3
    container.equals("m4a", ignoreCase = true) || container.equals("mp4", ignoreCase = true) -> 3
    mimeType.equals("audio/webm", ignoreCase = true) -> 2
    else -> 1
}

private fun MediaStream.audioTrackPreferenceRank(
    preference: AudioLanguagePreference,
): Int = when {
    preference != AudioLanguagePreference.ORIGINAL && matches(preference) -> 5
    audioTrackIsDefault == true || audioContent == AudioContentType.ORIGINAL -> 4
    audioContent == AudioContentType.UNKNOWN -> 3
    preference == AudioLanguagePreference.ORIGINAL &&
        (audioLanguage.matchesLanguageCode("en") || audioTrackName.isEnglishTrackName()) -> 2
    else -> 1
}

private fun MediaStream.audioContentPreferenceRank(): Int = when (audioContent) {
    AudioContentType.ORIGINAL -> 4
    AudioContentType.DUBBED -> 3
    AudioContentType.UNKNOWN -> 2
    AudioContentType.DUBBED_AUTO -> 1
}

private fun MediaStream.hasAudioTrackMetadata(): Boolean =
    audioTrackId != null ||
        audioTrackName != null ||
        audioTrackIsDefault != null ||
        audioLanguage != null ||
        audioContent != AudioContentType.UNKNOWN

private fun MediaStream?.matches(preference: AudioLanguagePreference): Boolean {
    val languageCode = preference.languageCode ?: return preference == AudioLanguagePreference.ORIGINAL
    return this?.audioLanguage.matchesLanguageCode(languageCode) ||
        (languageCode == "en" && this?.audioTrackName.isEnglishTrackName())
}

private fun String?.matchesLanguageCode(languageCode: String): Boolean {
    val tag = this?.substringBeforeLast('.', missingDelimiterValue = this)?.lowercase() ?: return false
    val expected = languageCode.lowercase()
    return tag == expected || tag.startsWith("$expected-")
}

private fun String?.isEnglishTrackName(): Boolean =
    this?.trim()?.startsWith("English", ignoreCase = true) == true

private val QUALITY_HEIGHT = Regex("(\\d{2,4})p", RegexOption.IGNORE_CASE)
