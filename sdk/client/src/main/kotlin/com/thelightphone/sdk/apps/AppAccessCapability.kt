package com.thelightphone.sdk.apps

import android.content.Context
import android.content.pm.PackageManager

/**
 * Written by the Light Gradle plugin for tools that declare the capability in
 * `lighttool.toml`. Nothing else can contribute it, so a tool that did not opt
 * in cannot reach another app's notification stream just by asking.
 */
private const val APP_ACCESS_MARKER = "com.thelightphone.sdk.CAPABILITY_APP_ACCESS"

internal fun Context.requireAppAccessCapability() {
    @Suppress("DEPRECATION")
    val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    if (appInfo.metaData?.getBoolean(APP_ACCESS_MARKER) != true) {
        throw LightAppAccessException(
            """
            Accessing apps and notifications requires this lighttool.toml capability:
            capabilities = ["app-access"]
            """.trimIndent()
        )
    }
}
