package com.thelightphone.sdk.apps

/**
 * The facts about a posted notification that decide whether it is shown.
 *
 * Deliberately free of Android types so the filter rules — which are the part
 * of this capability with observable behaviour — can be tested off device.
 */
internal data class PostedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val isOngoing: Boolean,
    val isTransport: Boolean,
    val isGroupSummary: Boolean,
    val groupKey: String?,
    val hasContentIntent: Boolean,
)

/**
 * Decides which posted notifications a tool's list shows.
 *
 * A launcher-style notification list is only useful if it carries what the user
 * can act on, so anything they cannot dismiss, cannot open, or that exists only
 * to hold a media session is dropped.
 */
internal object NotificationFilter {

    /** Notifications the LightOS launcher posts to keep itself resident. */
    private const val LIGHT_OS_LABEL = "LightOS"
    private const val LIGHT_OS_TITLE = "LightOS"

    fun visible(posted: List<PostedNotification>, selfPackage: String): List<PostedNotification> =
        posted
            .filterNot { it.isOngoing }
            .filterNot { it.isTransport }
            .filterNot { it.isLightOsKeepAlive() }
            .filterNot { it.isRedundantGroupSummary(posted) }
            .filterNot { it.packageName == selfPackage }

    /**
     * The keep-alive post has no body and identifies itself only by label or
     * title, so a blank body is part of the match rather than incidental.
     */
    private fun PostedNotification.isLightOsKeepAlive(): Boolean {
        if (!text.isNullOrBlank()) return false
        if (title == LIGHT_OS_TITLE) return true
        if (title != null) return false
        return appLabel == LIGHT_OS_LABEL
    }

    /**
     * A group summary duplicates its own children. It is only redundant when
     * the group actually has a visible child; an empty summary is all the user
     * would see and stays.
     */
    private fun PostedNotification.isRedundantGroupSummary(
        all: List<PostedNotification>,
    ): Boolean {
        if (!isGroupSummary) return false
        return all.any { it.key != key && it.groupKey == groupKey }
    }
}
