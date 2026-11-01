package com.thelightphone.sdk

import android.content.Context
import com.thelightphone.sdk.apps.LightAppList
import com.thelightphone.sdk.apps.LightNotifications
import com.thelightphone.sdk.apps.requireAppAccessCapability

/**
 * The `app-access` capability's entry point for a screen: enumerate and launch
 * other apps, and read the active notification stream.
 *
 * Both handles check the capability lazily, so a tool that never touches them
 * does not pay for the metadata lookup, and one that does gets a clear error
 * naming the missing `lighttool.toml` entry.
 */
class LightAppAccess internal constructor(private val context: Context) {

    val apps: LightAppList by lazy {
        context.requireAppAccessCapability()
        LightAppList(context)
    }

    val notifications: LightNotifications by lazy {
        context.requireAppAccessCapability()
        LightNotifications(context)
    }
}
