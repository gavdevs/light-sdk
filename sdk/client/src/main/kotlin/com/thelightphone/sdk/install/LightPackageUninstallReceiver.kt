package com.thelightphone.sdk.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives PackageInstaller uninstall status. Delivery is unreliable: Android
 * guarantees the STATUS_PENDING_USER_ACTION broadcast but may never send a
 * final one, so tracking code must also reconcile against the package list.
 */
class LightPackageUninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getIntExtra(EXTRA_LIGHT_UNINSTALL_REQUEST_ID, -1)
        if (requestId < 0) return

        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_LIGHT_EXPECTED_PACKAGE)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            LightPackageUninstallResultStore.write(
                context,
                LightPackageUninstallResult(
                    requestId = requestId,
                    packageName = packageName,
                    outcome = LightPackageUninstallOutcome.AwaitingUserAction,
                    message = null,
                ),
            )
            val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmation) }
                    .onFailure { error ->
                        LightPackageUninstallResultStore.write(
                            context,
                            LightPackageUninstallResult(
                                requestId = requestId,
                                packageName = packageName,
                                outcome = LightPackageUninstallOutcome.AwaitingUserAction,
                                message = error.message
                                    ?: "Open the confirmation to approve this uninstall.",
                            ),
                        )
                    }
            }
            return
        }

        val outcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> LightPackageUninstallOutcome.Uninstalled
            PackageInstaller.STATUS_FAILURE_ABORTED -> LightPackageUninstallOutcome.Cancelled
            else -> LightPackageUninstallOutcome.Failed
        }
        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Uninstalled"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Uninstall cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Uninstall blocked by the system"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "The device policy prevents uninstalling"
            else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?: "Uninstall failed"
        }
        LightPackageUninstallResultStore.write(
            context,
            LightPackageUninstallResult(
                requestId = requestId,
                packageName = packageName,
                outcome = outcome,
                message = message,
            ),
        )
    }
}
