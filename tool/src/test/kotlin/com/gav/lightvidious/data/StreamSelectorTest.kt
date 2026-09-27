package com.gav.lightvidious.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class StreamSelectorTest {
    @Test
    fun `selects the best muxed progressive stream no higher than 720p`() {
        val streams = listOf(
            stream("https://media.example/1080", MediaStreamKind.PROGRESSIVE, 1080, "video/mp4", 5_000_000),
            stream("https://media.example/720-webm", MediaStreamKind.PROGRESSIVE, 720, "video/webm", 4_000_000),
            stream("https://media.example/720-mp4", MediaStreamKind.PROGRESSIVE, 720, "video/mp4", 2_000_000),
            stream("https://media.example/360", MediaStreamKind.PROGRESSIVE, 360, "video/mp4", 1_000_000),
        )

        assertEquals("https://media.example/720-mp4", StreamSelector.selectProgressive(streams)?.url)
    }

    @Test
    fun `requires a muxed and valid progressive stream`() {
        val streams = listOf(
            stream(
                url = "https://media.example/video-only",
                kind = MediaStreamKind.PROGRESSIVE,
                height = 720,
                mimeType = "video/mp4",
                bitrate = 2_000_000,
                hasAudio = false,
            ),
            stream("file:///tmp/video", MediaStreamKind.PROGRESSIVE, 360, "video/mp4", 1_000_000),
        )

        assertNull(StreamSelector.selectProgressive(streams))
    }

    @Test
    fun `original audio wins over a higher bitrate dub`() {
        val audio = listOf(
            stream(
                "https://media.example/english-original",
                MediaStreamKind.ADAPTIVE_AUDIO,
                null,
                "audio/mp4",
                128_000,
                hasVideo = false,
                audioLanguage = "en-US",
                audioContent = AudioContentType.ORIGINAL,
            ),
            stream(
                "https://media.example/french-dub",
                MediaStreamKind.ADAPTIVE_AUDIO,
                null,
                "audio/webm",
                192_000,
                hasVideo = false,
                audioLanguage = "fr",
                audioContent = AudioContentType.DUBBED,
            ),
        )

        assertEquals(
            "https://media.example/english-original",
            StreamSelector.selectAdaptiveAudio(audio)?.url,
        )
    }

    @Test
    fun `preserves first server stream when audio track metadata is unavailable`() {
        val audio = listOf(
            stream(
                "https://media.example/first-aac",
                MediaStreamKind.ADAPTIVE_AUDIO,
                null,
                "audio/mp4",
                128_000,
                hasVideo = false,
            ),
            stream(
                "https://media.example/later-opus",
                MediaStreamKind.ADAPTIVE_AUDIO,
                null,
                "audio/webm",
                192_000,
                hasVideo = false,
            ),
        )

        assertEquals("https://media.example/first-aac", StreamSelector.selectAdaptiveAudio(audio)?.url)
    }

    @Test
    fun `app wide language preference chooses that language and falls back to original`() {
        val originalSpanish = stream(
            "https://media.example/spanish-original",
            MediaStreamKind.ADAPTIVE_AUDIO,
            null,
            "audio/mp4",
            128_000,
            hasVideo = false,
            audioLanguage = "es",
            audioContent = AudioContentType.ORIGINAL,
        )
        val englishDub = stream(
            "https://media.example/english-dub",
            MediaStreamKind.ADAPTIVE_AUDIO,
            null,
            "audio/mp4",
            96_000,
            hasVideo = false,
            audioLanguage = "en-US",
            audioContent = AudioContentType.DUBBED_AUTO,
        )

        assertEquals(
            englishDub.url,
            StreamSelector.selectAdaptiveAudio(
                listOf(originalSpanish, englishDub),
                AudioLanguagePreference.ENGLISH,
            )?.url,
        )
        assertEquals(
            originalSpanish.url,
            StreamSelector.selectAdaptiveAudio(
                listOf(originalSpanish, englishDub),
                AudioLanguagePreference.FRENCH,
            )?.url,
        )
    }

    @Test
    fun `language preference uses adaptive pair instead of muxed original audio`() {
        val progressive = stream(
            "https://media.example/progressive-original",
            MediaStreamKind.PROGRESSIVE,
            360,
            "video/mp4",
            1_000_000,
        )
        val video = stream(
            "https://media.example/adaptive-video",
            MediaStreamKind.ADAPTIVE_VIDEO,
            720,
            "video/mp4",
            2_000_000,
            hasAudio = false,
        )
        val english = stream(
            "https://media.example/english",
            MediaStreamKind.ADAPTIVE_AUDIO,
            null,
            "audio/mp4",
            128_000,
            hasVideo = false,
            audioLanguage = "en",
            audioContent = AudioContentType.DUBBED,
        )

        val selection = StreamSelector.select(
            formatStreams = listOf(progressive),
            adaptiveFormats = listOf(video, english),
            hlsUrl = null,
            liveNow = false,
            audioLanguage = AudioLanguagePreference.ENGLISH,
        )

        assertEquals(true, selection.preferAdaptivePair)
        assertIs<VideoPlaybackSource.Separate>(selection.watchSource)
    }

    @Test
    fun `adaptive video and audio form a watchable merged source`() {
        val audio = stream(
            "https://media.example/audio",
            MediaStreamKind.ADAPTIVE_AUDIO,
            null,
            "audio/mp4",
            128_000,
            hasVideo = false,
        )
        val video = stream(
            "https://media.example/video",
            MediaStreamKind.ADAPTIVE_VIDEO,
            720,
            "video/mp4",
            2_000_000,
            hasAudio = false,
        )

        val selection = StreamSelector.select(
            formatStreams = emptyList(),
            adaptiveFormats = listOf(audio, video),
            hlsUrl = null,
            liveNow = false,
        )

        val source = assertIs<VideoPlaybackSource.Separate>(selection.watchSource)
        assertEquals(video.url, source.video.url)
        assertEquals(audio.url, source.audio.url)
    }

    @Test
    fun `live HLS takes precedence only for live videos`() {
        val progressive = stream(
            "https://media.example/720",
            MediaStreamKind.PROGRESSIVE,
            720,
            "video/mp4",
            2_000_000,
        )
        val live = StreamSelector.select(
            formatStreams = listOf(progressive),
            adaptiveFormats = emptyList(),
            hlsUrl = "https://media.example/live.m3u8",
            liveNow = true,
        )
        val recorded = StreamSelector.select(
            formatStreams = listOf(progressive),
            adaptiveFormats = emptyList(),
            hlsUrl = "https://media.example/stale.m3u8",
            liveNow = false,
        )

        assertEquals(
            "https://media.example/live.m3u8",
            assertIs<VideoPlaybackSource.Single>(live.watchSource).stream.url,
        )
        assertEquals("https://media.example/live.m3u8", live.audioUrl)
        assertEquals(
            "https://media.example/720",
            assertIs<VideoPlaybackSource.Single>(recorded.watchSource).stream.url,
        )
        assertNull(recorded.liveHls)
    }

    private fun stream(
        url: String,
        kind: MediaStreamKind,
        height: Int?,
        mimeType: String,
        bitrate: Long,
        hasAudio: Boolean = true,
        hasVideo: Boolean = true,
        audioLanguage: String? = null,
        audioContent: AudioContentType = AudioContentType.UNKNOWN,
    ) = MediaStream(
        url = url,
        kind = kind,
        mimeType = mimeType,
        height = height,
        bitrate = bitrate,
        hasAudio = hasAudio,
        hasVideo = hasVideo,
        audioLanguage = audioLanguage,
        audioContent = audioContent,
    )
}
