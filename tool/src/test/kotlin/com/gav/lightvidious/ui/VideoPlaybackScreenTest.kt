package com.gav.lightvidious.ui

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoPlaybackScreenTest {
    @Test
    fun formatsShortAndLongDurations() {
        assertEquals("00:00", formatPlaybackTime(0L))
        assertEquals("01:05", formatPlaybackTime(65_999L))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000L))
    }

    @Test
    fun labelsUnknownDurationAsLive() {
        assertEquals("00:05  /  LIVE", playbackTimeLabel(5_000L, C.TIME_UNSET))
    }
}
