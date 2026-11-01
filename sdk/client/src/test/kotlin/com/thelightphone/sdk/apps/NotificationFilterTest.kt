package com.thelightphone.sdk.apps

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The filter rules are the observable behaviour of the notification capability,
 * and every one of them was specified, so each gets a case here.
 */
class NotificationFilterTest {

    private fun posted(
        key: String,
        title: String? = "Title",
        text: String? = "Body",
        appLabel: String = "App",
        packageName: String = "com.example.app",
        isOngoing: Boolean = false,
        isTransport: Boolean = false,
        isGroupSummary: Boolean = false,
        groupKey: String? = null,
    ) = PostedNotification(
        key = key,
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        isOngoing = isOngoing,
        isTransport = isTransport,
        isGroupSummary = isGroupSummary,
        groupKey = groupKey,
        hasContentIntent = true,
    )

    private fun keysOf(posted: List<PostedNotification>) =
        NotificationFilter.visible(posted, SELF).map { it.key }

    @Test
    fun `ordinary notifications pass through`() {
        assertEquals(listOf("a", "b"), keysOf(listOf(posted("a"), posted("b"))))
    }

    @Test
    fun `ongoing notifications are dropped`() {
        assertEquals(listOf("b"), keysOf(listOf(posted("a", isOngoing = true), posted("b"))))
    }

    @Test
    fun `media session notifications are dropped`() {
        assertEquals(listOf("b"), keysOf(listOf(posted("a", isTransport = true), posted("b"))))
    }

    @Test
    fun `the LightOS keep-alive post is dropped when it has no body`() {
        val keepAlive = posted("keep", title = "LightOS", text = null, appLabel = "LightOS")

        assertEquals(emptyList(), keysOf(listOf(keepAlive)))
    }

    @Test
    fun `the keep-alive rule falls back to the app label when the title is absent`() {
        val keepAlive = posted("keep", title = null, text = "", appLabel = "LightOS")

        assertEquals(emptyList(), keysOf(listOf(keepAlive)))
    }

    @Test
    fun `a LightOS notification with a real body is kept`() {
        val real = posted("real", title = "LightOS", text = "Backup complete", appLabel = "LightOS")

        assertEquals(listOf("real"), keysOf(listOf(real)))
    }

    @Test
    fun `a notification titled LightOS from another app with a body is kept`() {
        val app = posted("x", title = "LightOS", text = "hi", appLabel = "Notes")

        assertEquals(listOf("x"), keysOf(listOf(app)))
    }

    @Test
    fun `a group summary with a visible child is dropped`() {
        val summary = posted("summary", isGroupSummary = true, groupKey = "g")
        val child = posted("child", groupKey = "g")

        assertEquals(listOf("child"), keysOf(listOf(summary, child)))
    }

    @Test
    fun `a group summary is kept when its group has no other member`() {
        val summary = posted("summary", isGroupSummary = true, groupKey = "g")

        assertEquals(listOf("summary"), keysOf(listOf(summary)))
    }

    @Test
    fun `a group summary is dropped when its sibling exists even if that sibling is hidden`() {
        val summary = posted("summary", isGroupSummary = true, groupKey = "g")
        val ongoingChild = posted("child", groupKey = "g", isOngoing = true)

        // Redundancy is judged against everything posted, so a summary whose
        // only child is itself filtered out still collapses away. This mirrors
        // the platform: the summary exists to represent that child.
        assertEquals(emptyList(), keysOf(listOf(summary, ongoingChild)))
    }

    @Test
    fun `this app's own notifications are dropped`() {
        val own = posted("own", packageName = SELF)

        assertEquals(emptyList(), keysOf(listOf(own)))
    }

    @Test
    fun `filtering keeps the original order`() {
        val rows = listOf(posted("c"), posted("a"), posted("b"))

        assertEquals(listOf("c", "a", "b"), keysOf(rows))
    }

    private companion object {
        const val SELF = "com.example.notifications"
    }
}
