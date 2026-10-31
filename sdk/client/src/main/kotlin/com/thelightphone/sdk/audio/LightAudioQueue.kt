package com.thelightphone.sdk.audio

/** A stable catalog identity whose playable source is resolved just before playback. */
data class LightAudioQueueItem(val id: String, val metadata: LightMediaMetadata)

/** A playable location and optional content type, including dynamically generated manifests. */
data class LightAudioResolvedSource(val source: LightAudioSource, val mimeType: String? = null)

/**
 * Application-owned catalog access used by the service-owned queue.
 * Implementations must move blocking work off the main thread and support cancellation.
 * Resolved locations are never included in queue snapshots.
 */
interface LightAudioQueueResolver {
    suspend fun resolve(item: LightAudioQueueItem): LightAudioResolvedSource
    suspend fun continuation(seed: LightAudioQueueItem): List<LightAudioQueueItem> = emptyList()
}

enum class LightAudioRepeatMode { Off, All, One }

enum class LightAudioQueueCommand {
    Play, Pause, TogglePlayPause, Previous, Next, ToggleShuffle, CycleRepeat, Seek,
}

data class LightAudioQueueSnapshot(
    val items: List<LightAudioQueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val sourceName: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isLoading: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: LightAudioRepeatMode = LightAudioRepeatMode.Off,
    val error: LightAudioError? = null,
)
