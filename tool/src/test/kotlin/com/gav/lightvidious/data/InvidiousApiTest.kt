package com.gav.lightvidious.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InvidiousApiTest {
    @Test
    fun `search ignores non-video results and accepts flexible numbers`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ ->
                check(url.endsWith("/api/v1/search"))
                InvidiousHttpResponse(
                    200,
                    """
                    [
                      {"type":"channel","author":"Channel"},
                      {
                        "type":"video",
                        "videoId":"dQw4w9WgXcQ",
                        "title":"A video",
                        "author":"An author",
                        "lengthSeconds":"213",
                        "viewCount":1234,
                        "publishedText":"2 days ago",
                        "liveNow":"false"
                      }
                    ]
                    """.trimIndent(),
                )
            },
        )
        val api = InvidiousApi("invidious.example", proxyMedia = true, transport = transport)

        val videos = api.search("test").getOrThrow()

        assertEquals(1, videos.size)
        assertEquals(213, videos.single().lengthSeconds)
        assertEquals(1234, videos.single().viewCount)
        assertFalse(videos.single().liveNow)
        assertEquals("video", transport.requests.single().parameters["type"])
        assertTrue(transport.requests.single().headers.isEmpty())
        api.close()
    }

    @Test
    fun `account feed sends bearer token and combines unique feed videos`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, parameters ->
                check(url.endsWith("/api/v1/auth/feed"))
                assertEquals("1", parameters["page"])
                assertEquals("40", parameters["max_results"])
                InvidiousHttpResponse(
                    200,
                    """
                    {
                      "notifications":[
                        {"type":"video","videoId":"dQw4w9WgXcQ","title":"New upload"}
                      ],
                      "videos":[
                        {"type":"video","videoId":"dQw4w9WgXcQ","title":"Duplicate"},
                        {"type":"video","videoId":"aqz-KE-bpKQ","title":"Older upload"}
                      ]
                    }
                    """.trimIndent(),
                )
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val videos = api.accountFeed(AUTH_TOKEN).getOrThrow()

        assertEquals(listOf("dQw4w9WgXcQ", "aqz-KE-bpKQ"), videos.map(VideoSummary::videoId))
        assertEquals("Bearer $AUTH_TOKEN", transport.requests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `account history sends bearer token clamps limit and filters invalid ids`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, parameters ->
                check(url.endsWith("/api/v1/auth/history"))
                assertEquals("1", parameters["page"])
                assertEquals("100", parameters["max_results"])
                InvidiousHttpResponse(
                    200,
                    """["dQw4w9WgXcQ","invalid","dQw4w9WgXcQ","aqz-KE-bpKQ"]""",
                )
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val ids = api.accountHistoryIds(AUTH_TOKEN, maxResults = 1_000).getOrThrow()

        assertEquals(listOf("dQw4w9WgXcQ", "aqz-KE-bpKQ"), ids)
        assertEquals("Bearer $AUTH_TOKEN", transport.requests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `mark watched extracts video id and posts with bearer token`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ -> error("Unexpected GET: $url") },
            postResponse = { url, _ ->
                check(url.endsWith("/api/v1/auth/history/dQw4w9WgXcQ"))
                InvidiousHttpResponse(204, "")
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        api.markWatched(AUTH_TOKEN, "https://www.youtube.com/watch?v=dQw4w9WgXcQ").getOrThrow()

        assertEquals(1, transport.postRequests.size)
        assertEquals("Bearer $AUTH_TOKEN", transport.postRequests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `public calls never add an authorization header`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ ->
                when {
                    url.endsWith("/api/v1/popular") -> InvidiousHttpResponse(200, "[]")
                    url.endsWith("/api/v1/search") -> InvidiousHttpResponse(200, "[]")
                    url.endsWith("/api/v1/videos/dQw4w9WgXcQ") ->
                        InvidiousHttpResponse(200, VIDEO_DETAILS_JSON)
                    else -> error("Unexpected URL: $url")
                }
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        api.popular().getOrThrow()
        api.search("kittens").getOrThrow()
        api.video("dQw4w9WgXcQ").getOrThrow()

        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests.all { request -> "Authorization" !in request.headers })
        api.close()
    }

    @Test
    fun `video maps relative media and selects 720p plus adaptive audio`() = runTest {
        val transport = FakeTransport(getResponse = { url, parameters ->
            check(url.endsWith("/api/v1/videos/dQw4w9WgXcQ"))
            assertEquals("true", parameters["local"])
            InvidiousHttpResponse(200, VIDEO_DETAILS_JSON)
        })
        val api = InvidiousApi("https://invidious.example/", proxyMedia = true, transport = transport)

        val details = api.video("https://youtu.be/dQw4w9WgXcQ").getOrThrow()

        assertEquals(
            "https://invidious.example/videoplayback?itag=22",
            assertIs<VideoPlaybackSource.Single>(details.watchSource).stream.url,
        )
        assertEquals("https://invidious.example/videoplayback?itag=140", details.audioUrl)
        assertEquals(720, details.selection.progressive?.height)
        assertEquals(128_000, details.selection.adaptiveAudio?.bitrate)
        api.close()
    }

    @Test
    fun `video maps adaptive pair and selects nested original audio metadata`() = runTest {
        val transport = FakeTransport(getResponse = { _, _ ->
            InvidiousHttpResponse(200, MULTILINGUAL_ADAPTIVE_VIDEO_JSON)
        })
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val details = api.video("m8KnrXli-bA").getOrThrow()

        val source = assertIs<VideoPlaybackSource.Separate>(details.watchSource)
        assertEquals("https://invidious.example/videoplayback?itag=136", source.video.url)
        assertEquals("en-US.4", source.audio.audioTrackId)
        assertEquals("en-US", source.audio.audioLanguage)
        assertEquals(AudioContentType.ORIGINAL, source.audio.audioContent)
        assertEquals(128_000, source.audio.bitrate)
        api.close()
    }

    @Test
    fun `probe checks one byte of a selected playback URL`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ ->
                when {
                    url.endsWith("/api/v1/search") -> InvidiousHttpResponse(
                        200,
                        """[{"type":"video","videoId":"dQw4w9WgXcQ","title":"Probe"}]""",
                    )
                    url.endsWith("/api/v1/videos/dQw4w9WgXcQ") ->
                        InvidiousHttpResponse(200, VIDEO_DETAILS_JSON)
                    else -> error("Unexpected URL: $url")
                }
            },
            byteResponse = OneByteResponse(
                statusCode = 206,
                receivedByte = true,
                rangeSupported = true,
            ),
        )
        val api = InvidiousApi("https://invidious.example", proxyMedia = true, transport = transport)

        val probe = api.probe()

        assertTrue(probe.successful)
        assertTrue(probe.rangeSupported)
        assertEquals(206, probe.playbackStatusCode)
        assertEquals("Light", transport.requests.first().parameters["q"])
        assertEquals("video", transport.requests.first().parameters["type"])
        assertEquals("https://invidious.example/videoplayback?itag=22", transport.byteRequests.single())
        api.close()
    }

    @Test
    fun `probe distinguishes an HTTP API failure from network failure`() = runTest {
        val httpApi = InvidiousApi(
            "https://invidious.example",
            true,
            FakeTransport(getResponse = { _, _ -> InvidiousHttpResponse(503, "unavailable") }),
        )
        val networkApi = InvidiousApi(
            "https://offline.example",
            true,
            FakeTransport(getResponse = { _, _ -> throw IllegalStateException("offline") }),
        )

        val httpProbe = httpApi.probe()
        val networkProbe = networkApi.probe()

        assertTrue(httpProbe.reachable)
        assertFalse(httpProbe.apiAvailable)
        assertEquals(503, httpProbe.apiStatusCode)
        assertFalse(networkProbe.reachable)
        assertFalse(networkProbe.apiAvailable)
        httpApi.close()
        networkApi.close()
    }

    @Test
    fun `search and probe propagate coroutine cancellation`() = runTest {
        val searchApi = InvidiousApi(
            "https://invidious.example",
            true,
            FakeTransport(getResponse = { _, _ -> throw CancellationException("leave search") }),
        )
        val probeApi = InvidiousApi(
            "https://invidious.example",
            true,
            FakeTransport(getResponse = { _, _ -> throw CancellationException("leave setup") }),
        )

        assertFailsWith<CancellationException> { searchApi.search("test") }
        assertFailsWith<CancellationException> { probeApi.probe() }
        searchApi.close()
        probeApi.close()
    }

    private data class Request(
        val url: String,
        val parameters: Map<String, String>,
        val headers: Map<String, String>,
    )

    private data class PostRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    private class FakeTransport(
        private val getResponse: suspend (String, Map<String, String>) -> InvidiousHttpResponse,
        private val postResponse: suspend (String, Map<String, String>) -> InvidiousHttpResponse =
            { url, _ -> error("Unexpected POST: $url") },
        private val byteResponse: OneByteResponse = OneByteResponse(206, true, true),
    ) : InvidiousHttpTransport {
        val requests = mutableListOf<Request>()
        val postRequests = mutableListOf<PostRequest>()
        val byteRequests = mutableListOf<String>()

        override suspend fun get(
            url: String,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): InvidiousHttpResponse {
            requests += Request(url, parameters, headers)
            return getResponse(url, parameters)
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
        ): InvidiousHttpResponse {
            postRequests += PostRequest(url, headers)
            return postResponse(url, headers)
        }

        override suspend fun readOneByte(url: String): OneByteResponse {
            byteRequests += url
            return byteResponse
        }

        override fun close() = Unit
    }

    private companion object {
        const val AUTH_TOKEN =
            "{\"session\":\"v1:test-session\",\"scopes\":[\"GET:feed\",\"GET:history\",\"POST:history/*\"],\"signature\":\"test-signature\"}"

        val VIDEO_DETAILS_JSON = """
            {
              "videoId":"dQw4w9WgXcQ",
              "title":"A video",
              "author":"An author",
              "lengthSeconds":213,
              "viewCount":"1234",
              "publishedText":"2 days ago",
              "liveNow":false,
              "description":"Description",
              "formatStreams":[
                {
                  "url":"/videoplayback?itag=37",
                  "itag":"37",
                  "type":"video/mp4; codecs=\"avc1.640028, mp4a.40.2\"",
                  "qualityLabel":"1080p",
                  "height":1080,
                  "bitrate":5000000
                },
                {
                  "url":"/videoplayback?itag=22",
                  "itag":22,
                  "type":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"",
                  "qualityLabel":"720p",
                  "height":"720",
                  "bitrate":2000000
                }
              ],
              "adaptiveFormats":[
                {
                  "url":"/videoplayback?itag=140",
                  "itag":"140",
                  "type":"audio/mp4; codecs=\"mp4a.40.2\"",
                  "audioQuality":"AUDIO_QUALITY_MEDIUM",
                  "bitrate":128000
                },
                {
                  "url":"/videoplayback?itag=251",
                  "itag":"251",
                  "type":"audio/webm; codecs=\"opus\"",
                  "audioQuality":"AUDIO_QUALITY_MEDIUM",
                  "bitrate":"160000"
                }
              ]
            }
        """.trimIndent()

        val MULTILINGUAL_ADAPTIVE_VIDEO_JSON = """
            {
              "videoId":"m8KnrXli-bA",
              "title":"The 9 Bar Fallacy",
              "author":"Lance Hedrick",
              "adaptiveFormats":[
                {
                  "url":"/videoplayback?itag=136",
                  "itag":136,
                  "type":"video/mp4; codecs=\"avc1.4d401f\"",
                  "qualityLabel":"720p",
                  "height":720,
                  "bitrate":2000000
                },
                {
                  "url":"/videoplayback?itag=140&xtags=acont%3Ddubbed%3Alang%3Dfr",
                  "itag":140,
                  "type":"audio/mp4; codecs=\"mp4a.40.2\"",
                  "bitrate":192000,
                  "audioTrack":{
                    "id":"fr.3",
                    "displayName":"French",
                    "audioIsDefault":false
                  }
                },
                {
                  "url":"/videoplayback?itag=140&xtags=acont%3Ddubbed%3Alang%3Dfr",
                  "itag":140,
                  "type":"audio/mp4; codecs=\"mp4a.40.2\"",
                  "bitrate":128000,
                  "audioTrack":{
                    "id":"en-US.4",
                    "displayName":"English (US) original",
                    "audioIsDefault":true
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
