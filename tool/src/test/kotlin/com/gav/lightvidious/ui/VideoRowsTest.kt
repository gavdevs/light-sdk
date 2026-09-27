package com.gav.lightvidious.ui

import com.gav.lightvidious.data.VideoSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoRowsTest {
    @Test
    fun formatsDurationAndCountsForSmallScreen() {
        assertEquals("4:05", formatSeconds(245L))
        assertEquals("1:01:01", formatSeconds(3_661L))
        assertEquals("999", formatCompactCount(999L))
        assertEquals("1.2K", formatCompactCount(1_250L))
        assertEquals("2M", formatCompactCount(2_000_000L))
    }

    @Test
    fun buildsLiveMetadataWithoutDuration() {
        val video = VideoSummary(
            videoId = "abcdefghijk",
            title = "Live",
            author = "Creator",
            lengthSeconds = 0L,
            viewCount = 1_200L,
            publishedText = "now",
            liveNow = true,
        )

        assertEquals("LIVE  ·  1.2K views  ·  now", videoMetadataLine(video))
    }
}
