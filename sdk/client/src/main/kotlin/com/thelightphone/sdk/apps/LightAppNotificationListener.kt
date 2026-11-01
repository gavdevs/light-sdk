package com.thelightphone.sdk.apps

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Receives notification events for tools that declare the `app-access`
 * capability. The Light Gradle plugin emits this service into the tool's
 * generated manifest, so it does not appear in the SDK library manifest and
 * tools that did not opt in never instantiate one.
 *
 * The platform owns the component's lifecycle. Everything here is read on
 * demand from [activeNotifications] rather than cached, so the tool's UI can
 * re-read after any change and stay consistent with the system.
 */
class LightAppNotificationListener : NotificationListenerService() {

    internal companion object {
        private var instance: WeakReference<LightAppNotificationListener> = WeakReference(null)

        private val _version = MutableStateFlow(0L)

        /**
         * Increments on every connect/post/remove, so a screen can re-read the
         * active list instead of polling. A tool collects this while visible.
         */
        internal val version: StateFlow<Long> = _version.asStateFlow()

        /**
         * True once the platform has connected the service. A tool reads this
         * before treating an empty notification list as "no notifications".
         */
        internal fun isConnected(): Boolean = instance.get() != null

        internal fun activeNotifications(): List<StatusBarNotification> {
            val service = instance.get() ?: return emptyList()
            return try {
                service.activeNotifications?.toList().orEmpty()
            } catch (_: SecurityException) {
                // Access was revoked between the tool's check and this read.
                emptyList()
            }
        }

        internal fun cancel(key: String): Boolean {
            val service = instance.get() ?: return false
            return try {
                service.cancelNotification(key)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    override fun onListenerConnected() {
        instance = WeakReference(this)
        _version.value++
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        _version.value++
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        _version.value++
    }

    override fun onDestroy() {
        if (instance.get() === this) instance = WeakReference(null)
        _version.value++
        super.onDestroy()
    }
}
