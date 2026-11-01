package com.thelightphone.sdk.apps

import android.graphics.drawable.Drawable

/**
 * A launchable activity belonging to another app on the device.
 *
 * [packageName] and [activityName] identify the component to launch. Tools
 * should treat [label] as user-facing only: launch by component, never by label.
 */
data class LightAppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val profileSerial: Long,
    val icon: Drawable? = null,
)

/**
 * A currently posted notification, as reported by the platform's active
 * notification list.
 *
 * [key] is stable for the life of the notification and is what [LightNotifications.dismiss]
 * takes. [title] falls back to the posting app's label when the notification
 * carries no title of its own.
 */
data class LightNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String?,
    val isOngoing: Boolean,
    val hasContentIntent: Boolean,
)

/**
 * A settings screen the platform exposes by intent rather than as a launchable
 * activity, so [LightAppList.installed] never reports it.
 *
 * [id] is stable across releases, which is what a tool stores when the user
 * pins or hides one of these rows; [label] is the device-local name to show.
 */
enum class LightSystemSetting(val id: String, val label: String, val intentAction: String) {
    Settings("settings", "Settings", "android.settings.SETTINGS"),
    Wifi("wifi", "Wi-Fi", "android.settings.WIFI_SETTINGS"),
    Bluetooth("bluetooth", "Bluetooth", "android.settings.BLUETOOTH_SETTINGS"),
}

/** A permission or special access the platform will not grant silently. */
class LightAppAccessException(message: String) : RuntimeException(message)
