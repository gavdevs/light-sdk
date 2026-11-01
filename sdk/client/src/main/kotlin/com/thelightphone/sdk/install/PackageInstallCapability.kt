package com.thelightphone.sdk.install

import android.content.Context
import android.content.pm.PackageManager

private const val PACKAGE_INSTALL_MARKER =
    "com.thelightphone.sdk.CAPABILITY_PACKAGE_INSTALL_REQUEST"

internal fun Context.requirePackageInstallCapability() {
    val appInfo = packageManager.getApplicationInfo(
        packageName,
        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
    )
    if (appInfo.metaData?.getBoolean(PACKAGE_INSTALL_MARKER) != true) {
        throw LightPackageInstallException(
            """
            Package installation requires this lighttool.toml capability:
            capabilities = ["package-install-request"]
            """.trimIndent()
        )
    }
}
