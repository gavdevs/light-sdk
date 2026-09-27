package com.thelightphone.sdk.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class LightPackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(
            EXTRA_LIGHT_SESSION_ID,
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
        )
        if (sessionId < 0) return

        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_LIGHT_EXPECTED_PACKAGE)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            LightPackageInstallResultStore.write(
                context,
                LightPackageInstallResult(
                    sessionId = sessionId,
                    packageName = packageName,
                    outcome = LightPackageInstallOutcome.AwaitingUserAction,
                    message = null,
                )
            )
            val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmation) }
                    .onFailure { error ->
                        LightPackageInstallResultStore.write(
                            context,
                            LightPackageInstallResult(
                                sessionId = sessionId,
                                packageName = packageName,
                                outcome = LightPackageInstallOutcome.AwaitingUserAction,
                                message = error.message
                                    ?: "Open the installer to approve this installation.",
                            )
                        )
                    }
            }
            return
        }

        val outcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> LightPackageInstallOutcome.Installed
            PackageInstaller.STATUS_FAILURE_ABORTED -> LightPackageInstallOutcome.Cancelled
            else -> LightPackageInstallOutcome.Failed
        }
        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Installed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by the system"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "The installed app has a different signing certificate"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "The app is not compatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "The Android package is invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage"
            else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?: "Installation failed"
        }
        LightPackageInstallResultStore.write(
            context,
            LightPackageInstallResult(
                sessionId = sessionId,
                packageName = packageName,
                outcome = outcome,
                message = message,
            )
        )
    }
}
