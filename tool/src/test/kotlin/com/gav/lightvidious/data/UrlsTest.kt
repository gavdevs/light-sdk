package com.gav.lightvidious.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UrlsTest {
    @Test
    fun `normalizes an instance to its origin`() {
        assertEquals("https://example.com", normalizeInstanceUrl(" example.com/ "))
        assertEquals("https://example.com", normalizeInstanceUrl("HTTPS://EXAMPLE.COM:443"))
        assertEquals("https://localhost:3000", normalizeInstanceUrl("https://localhost:3000/"))
    }

    @Test
    fun `rejects unsafe or ambiguous instance URLs`() {
        listOf(
            "",
            "ftp://example.com",
            "http://localhost:3000",
            "https://user:password@example.com",
            "https://example.com/invidious",
            "https://example.com?local=true",
            "https://example.com/#settings",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) {
                normalizeInstanceUrl(value)
            }
        }
    }

    @Test
    fun `extracts IDs from common YouTube URL forms`() {
        val id = "dQw4w9WgXcQ"
        val inputs = listOf(
            id,
            "https://www.youtube.com/watch?feature=share&v=$id&t=12",
            "music.youtube.com/watch?v=$id",
            "https://youtu.be/$id?si=abc",
            "youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://youtube.com/live/$id?feature=share",
            "https://www.youtube-nocookie.com/embed/$id",
        )

        inputs.forEach { input ->
            assertEquals(id, extractYouTubeVideoId(input), input)
        }
    }

    @Test
    fun `does not extract IDs from lookalike or malformed URLs`() {
        assertNull(extractYouTubeVideoId("https://youtube.example/watch?v=dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://youtu.be/too-short"))
        assertNull(extractYouTubeVideoId("not a video"))
    }

    @Test
    fun `parses original audio metadata from encoded xtags`() {
        val metadata = parseAudioTrackUrlMetadata(
            "https://media.example/videoplayback?itag=140&" +
                "xtags=acont%3Doriginal%3Adrc%3D1%3Alang%3Den-US",
        )

        assertEquals("en-US", metadata.language)
        assertEquals(AudioContentType.ORIGINAL, metadata.content)
        assertEquals(true, metadata.dynamicRangeCompressed)
    }

    @Test
    fun `parses dubbed audio and safely ignores malformed xtags`() {
        val dubbed = parseAudioTrackUrlMetadata(
            "https://media.example/videoplayback?xtags=acont%3Ddubbed-auto%3Alang%3Dfr",
        )
        val malformed = parseAudioTrackUrlMetadata("not a URL")

        assertEquals("fr", dubbed.language)
        assertEquals(AudioContentType.DUBBED_AUTO, dubbed.content)
        assertEquals(AudioTrackUrlMetadata(), malformed)
    }
}
