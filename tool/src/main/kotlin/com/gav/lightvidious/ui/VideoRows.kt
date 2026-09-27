package com.gav.lightvidious.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.gav.lightvidious.data.VideoSummary
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun VideoRow(
    video: VideoSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.6f.gridUnitsAsDp()),
    ) {
        LightText(
            text = video.title,
            variant = LightTextVariant.Copy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = video.author,
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.1f.gridUnitsAsDp()),
        )
        LightText(
            text = videoMetadataLine(video),
            variant = LightTextVariant.Fine,
            lighten = true,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 0.1f.gridUnitsAsDp()),
        )
    }
}

internal fun videoMetadataLine(video: VideoSummary): String = buildList {
    if (video.liveNow) {
        add("LIVE")
    } else if (video.lengthSeconds > 0L) {
        add(formatSeconds(video.lengthSeconds))
    }
    if (video.viewCount > 0L) add("${formatCompactCount(video.viewCount)} views")
    video.publishedText.takeIf(String::isNotBlank)?.let(::add)
}.joinToString("  ·  ")

internal fun formatSeconds(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = (safe % 3_600L) / 60L
    val seconds = safe % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

internal fun formatCompactCount(value: Long): String = when {
    value >= 1_000_000_000L -> formatCompactUnit(value, 1_000_000_000L, "B")
    value >= 1_000_000L -> formatCompactUnit(value, 1_000_000L, "M")
    value >= 1_000L -> formatCompactUnit(value, 1_000L, "K")
    else -> NumberFormat.getIntegerInstance(Locale.US).format(value)
}

private fun formatCompactUnit(value: Long, divisor: Long, suffix: String): String {
    val whole = value / divisor
    val tenths = (value % divisor) / (divisor / 10L)
    return if (whole >= 100L || tenths == 0L) {
        "$whole$suffix"
    } else {
        "$whole.$tenths$suffix"
    }
}
