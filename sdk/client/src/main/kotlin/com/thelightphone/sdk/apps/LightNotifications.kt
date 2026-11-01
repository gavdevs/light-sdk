package com.thelightphone.sdk.apps

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * Enumerates and launches the apps installed on the device.
 *
 * Only available to tools whose `lighttool.toml` declares
 * `capabilities = ["app-access"]`. Instances come from [com.thelightphone.sdk.LightAppAccess]
 * rather than from tool code directly, because constructing one requires a
 * `Context` that tools are not allowed to hold.
 */
class LightAppList internal constructor(private val context: Context) {

    /**
     * Every launchable activity across the device's user profiles, excluding
     * the calling tool.
     *
     * Uses [LauncherApps], so it matches what a launcher would show: apps
     * without a launcher activity are absent, and work-profile apps appear
     * with the serial that [launch] needs. Sorting is by [LightAppInfo.label]
     * using the device locale.
     */
    suspend fun installed(): List<LightAppInfo> = withContext(Dispatchers.Default) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        val packageManager = context.packageManager
        val self = context.packageName
        val collator = Collator.getInstance()

        val apps = mutableListOf<LightAppInfo>()
        for (profile in userManager.userProfiles) {
            val activities = try {
                launcherApps.getActivityList(null, profile)
            } catch (_: SecurityException) {
                // A profile this tool may not inspect, e.g. a locked work
                // profile. Skipping it is better than failing the whole list.
                continue
            }
            for (activity in activities) {
                val packageName = activity.applicationInfo.packageName
                if (packageName == self) continue
                apps += LightAppInfo(
                    label = activity.label?.toString() ?: packageName,
                    packageName = packageName,
                    activityName = activity.componentName.className,
                    profileSerial = userManager.getSerialNumberForUser(profile),
                    icon = try {
                        activity.getBadgedIcon(0)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    },
                )
            }
        }
        apps.sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    /**
     * Starts [app]'s main activity. Returns false when the component no longer
     * resolves — an app can be uninstalled between enumeration and the tap.
     */
    suspend fun launch(app: LightAppInfo): Boolean = withContext(Dispatchers.Default) {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val user = userFor(app.profileSerial) ?: Process.myUserHandle()
        val component = ComponentName(app.packageName, app.activityName)
        try {
            launcherApps.startMainActivity(component, user, null, null)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Opens the system's app-details screen for [packageName]. */
    suspend fun openAppInfo(packageName: String, profileSerial: Long): Boolean =
        withContext(Dispatchers.Default) {
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val user = userFor(profileSerial) ?: Process.myUserHandle()
            val activity = try {
                launcherApps.getActivityList(packageName, user).firstOrNull()
            } catch (_: SecurityException) {
                null
            } ?: return@withContext false
            try {
                launcherApps.startAppDetailsActivity(activity.componentName, user, null, null)
                true
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Opens the platform uninstall flow for [packageName]. The user still
     * confirms in the system dialog; this only starts it.
     */
    suspend fun uninstall(packageName: String): Boolean = withContext(Dispatchers.Default) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        start(intent)
    }

    /**
     * Opens a curated settings screen. These have no launcher activity, so this
     * is the only way to reach them; [installed] never reports them.
     */
    suspend fun openSystemSetting(setting: LightSystemSetting): Boolean =
        withContext(Dispatchers.Default) {
            start(Intent(setting.intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

    private fun userFor(serial: Long): UserHandle? {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        return try {
            userManager.getUserForSerialNumber(serial)
        } catch (_: Exception) {
            null
        }
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Reads and dismisses the notifications currently posted on the device.
 *
 * Requires the `app-access` capability, and additionally the user's
 * notification-access grant, which only the system settings screen can give.
 * Check [isAccessGranted] first and offer [openAccessSettings] when it fails.
 */
class LightNotifications internal constructor(private val context: Context) {

    /**
     * True when this tool is an enabled notification listener. Re-check after
     * returning from [openAccessSettings]; the grant is revoked by the user at
     * any time.
     */
    fun isAccessGranted(): Boolean =
        NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * Every notification the platform currently holds, excluding those this SDK
     * considers noise:
     *
     * - ongoing notifications, which the user cannot dismiss anyway,
     * - media-session notifications ([Notification.CATEGORY_TRANSPORT]),
     * - the LightOS keep-alive notification,
     * - group summaries that have a visible child.
     *
     * Returns an empty list if access has not been granted, so call sites read
     * [isAccessGranted] before treating that as "nothing is posted". When the
     * service has not connected yet, [isListenerConnected] is false and the list
     * is likewise empty; collect on a screen resume to re-read.
     */
    suspend fun active(): List<LightNotification> = withContext(Dispatchers.Default) {
        if (!isAccessGranted()) return@withContext emptyList()
        val packageManager = context.packageManager
        NotificationFilter
            .visible(
                posted = LightAppNotificationListener.activeNotifications().map { it.posted(packageManager) },
                selfPackage = context.packageName,
            )
            .map { it.toLightNotification() }
            .sortedWith { left, right -> left.title.compareTo(right.title, ignoreCase = true) }
    }

    /**
     * Dismisses the notification identified by [key], returning false when the
     * key is already gone.
     */
    suspend fun dismiss(key: String): Boolean = withContext(Dispatchers.Default) {
        LightAppNotificationListener.cancel(key)
    }

    /**
     * Opens whatever [notification] points at. Prefers the notification's own
     * content intent, because that usually lands on the exact conversation or
     * document; falls back to the app's launcher activity when the intent is
     * gone or throws.
     *
     * `PendingIntent`s are created by other apps and may be cancelled or point
     * at a component that was since removed, so a false return is expected
     * rather than exceptional.
     */
    suspend fun open(notification: LightNotification): Boolean = withContext(Dispatchers.Default) {
        val posted = LightAppNotificationListener
            .activeNotifications()
            .firstOrNull { it.key == notification.key }
        val contentIntent = posted?.notification?.contentIntent
        if (contentIntent != null && sendContentIntent(contentIntent)) {
            return@withContext true
        }
        launchApp(notification.packageName)
    }

    /** True once the platform has bound the tool's listener service. */
    fun isListenerConnected(): Boolean = LightAppNotificationListener.isConnected()

    /**
     * Changes whenever the platform connects the listener, posts, or removes a
     * notification. Collect it while the screen is visible to re-read [active]
     * rather than polling.
     */
    fun changes(): StateFlow<Long> = LightAppNotificationListener.version

    /** Opens the system screen where the user grants notification access. */
    fun openAccessSettings(): Boolean = start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    /**
     * Opens [packageName]'s notification settings. This is also how a user mutes
     * an app: per-channel muting only exists behind the system dialog, so there
     * is no programmatic equivalent to offer.
     */
    suspend fun openNotificationSettings(packageName: String): Boolean =
        withContext(Dispatchers.Default) {
            start(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

    /** Opens the system app-details screen for [packageName]. */
    suspend fun openAppInfo(packageName: String): Boolean = withContext(Dispatchers.Default) {
        start(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Copies [notification]'s title and body to the clipboard, for users who
     * need the text somewhere else. Returns false when both are blank.
     */
    suspend fun copyText(notification: LightNotification): Boolean =
        withContext(Dispatchers.Default) {
            val text = listOfNotNull(
                notification.title.takeIf { it.isNotBlank() },
                notification.text?.takeIf { it.isNotBlank() },
            ).joinToString("\n")
            if (text.isEmpty()) return@withContext false
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(notification.appLabel, text))
            true
        }

    private fun sendContentIntent(pendingIntent: PendingIntent): Boolean = try {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
        true
    } catch (_: PendingIntent.CanceledException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun launchApp(packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(launchIntent)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Flattens the platform object into the plain shape the filter reasons
     * about. The app label is resolved here because [NotificationFilter] also
     * needs it for the keep-alive rule.
     */
    @Suppress("DEPRECATION")
    private fun StatusBarNotification.posted(packageManager: PackageManager): PostedNotification {
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
        return PostedNotification(
            key = key,
            packageName = packageName,
            appLabel = appLabel,
            title = notification.extras.getString(Notification.EXTRA_TITLE),
            text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            isOngoing = isOngoing,
            isTransport = notification.category == Notification.CATEGORY_TRANSPORT,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            groupKey = groupKey,
            hasContentIntent = notification.contentIntent != null,
        )
    }

    private fun PostedNotification.toLightNotification(): LightNotification = LightNotification(
        key = key,
        packageName = packageName,
        appLabel = appLabel,
        title = title ?: appLabel,
        text = text,
        isOngoing = isOngoing,
        hasContentIntent = hasContentIntent,
    )
}
