package com.thelightphone.sdk.audio

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Service-owned streaming queues for unrestricted tool integrations.
 * Register the resolver during application bootstrap, before opening a session.
 * Resolver work belongs to the service, never to the screen or calling coroutine.
 */
object LightAudioQueueSessions {
    private val mutableState = MutableStateFlow(LightAudioQueueSnapshot())
    val state: StateFlow<LightAudioQueueSnapshot> = mutableState.asStateFlow()
    private val connectionLock = Mutex()
    @Volatile
    internal var resolver: LightAudioQueueResolver? = null
        private set
    private var session: ResolvingSessionPlayer? = null

    @Synchronized
    fun configure(resolver: LightAudioQueueResolver) {
        check(this.resolver == null || this.resolver === resolver) {
            "A streaming queue resolver is already registered"
        }
        this.resolver = resolver
    }

    suspend fun start(
        context: Context,
        items: List<LightAudioQueueItem>,
        startIndex: Int = 0,
        sourceName: String? = null,
        continuousPlayback: Boolean = true,
    ): LightAudioQueueSnapshot = withSession(context) {
        it.queue.start(items, startIndex, sourceName, continuousPlayback)
        it.queue.snapshot
    }

    suspend fun control(
        context: Context,
        command: LightAudioQueueCommand,
        positionMs: Long? = null,
    ): LightAudioQueueSnapshot = withSession(context) {
        it.queue.control(command, positionMs)
        it.queue.snapshot
    }

    /** Stops the existing queue without starting a service or reopening a controller. */
    suspend fun stop() = connectionLock.withLock {
        withContext(Dispatchers.Main.immediate) {
            session?.clearQueue()
        }
    }

    private suspend fun <T> withSession(
        context: Context,
        block: (ResolvingSessionPlayer) -> T,
    ): T = connectionLock.withLock {
        withContext(Dispatchers.Main.immediate) {
            check(resolver != null) { "Register a streaming queue resolver before playback" }
            val handle = newDetachedAudioPlayer(
                context.applicationContext,
                LightPlayerSpec(usage = LightAudioUsage.Music, playback = LightAudioPlayback.Detached),
            )
            try {
                check(withTimeout(10_000L) { handle.awaitReady() }) {
                    "The detached audio service could not connect"
                }
                val connected = checkNotNull(session) { "The detached audio service has no streaming queue" }
                val result = block(connected)
                if (connected.queue.snapshot.playWhenReady) {
                    // A controller only binds the service. Mark it started before releasing
                    // this temporary handle, including while the first source is resolving.
                    try {
                        context.startForegroundService(Intent(context, LightAudioService::class.java))
                    } catch (error: RuntimeException) {
                        connected.clearQueue()
                        throw error
                    }
                }
                result
            } finally {
                handle.release()
            }
        }
    }

    internal fun attach(player: ResolvingSessionPlayer) {
        check(session == null) { "A streaming queue session is already active" }
        session = player
    }

    internal fun publish(snapshot: LightAudioQueueSnapshot) {
        mutableState.value = snapshot
    }

    internal fun detach(player: ResolvingSessionPlayer) {
        if (session !== player) return
        session = null
        mutableState.value = LightAudioQueueSnapshot()
    }
}
