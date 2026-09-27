package com.thelightphone.sdk.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class LightPackageArtifact(
    val name: String,
    val relativePath: String,
)

data class LightPackageInstallRequest(
    val packageName: String,
    val baseApk: LightPackageArtifact,
    val splitApks: List<LightPackageArtifact> = emptyList(),
    val preferUnattendedUpdate: Boolean = false,
)

data class LightPackageIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signerSha256: List<String>,
)

data class LightPackageInstallSession(val id: Int)

enum class LightPackageInstallOutcome {
    AwaitingUserAction,
    Installed,
    Cancelled,
    Failed,
}

data class LightPackageInstallResult(
    val sessionId: Int,
    val packageName: String?,
    val outcome: LightPackageInstallOutcome,
    val message: String?,
)

class LightPackageInstallException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class LightPackageInstaller internal constructor(private val androidContext: Context) {

    val canRequestPackageInstalls: Boolean
        get() {
            androidContext.requirePackageInstallCapability()
            return androidContext.packageManager.canRequestPackageInstalls()
        }

    fun openInstallAccessSettings(): Boolean {
        androidContext.requirePackageInstallCapability()
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${androidContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            androidContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun installedPackage(packageName: String): LightPackageIdentity? {
        androidContext.requirePackageInstallCapability()
        return try {
            androidContext.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                ),
            ).toIdentity()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun inspectBaseApk(relativePath: String): LightPackageIdentity? {
        androidContext.requirePackageInstallCapability()
        val apk = resolvePrivateArtifact(androidContext.filesDir, relativePath)
        return androidContext.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            ),
        )?.toIdentity()
    }

    suspend fun install(request: LightPackageInstallRequest): LightPackageInstallSession =
        withContext(Dispatchers.IO) {
            androidContext.requirePackageInstallCapability()
            val artifacts = validateInstallRequest(request).map { artifact ->
                artifact to resolvePrivateArtifact(androidContext.filesDir, artifact.relativePath)
            }

            val baseIdentity = androidContext.packageManager.getPackageArchiveInfo(
                artifacts.first().second.absolutePath,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                ),
            )?.toIdentity() ?: throw LightPackageInstallException(
                "The base artifact is not a readable Android package."
            )
            if (baseIdentity.packageName != request.packageName) {
                throw LightPackageInstallException(
                    "The base artifact is ${baseIdentity.packageName}, not ${request.packageName}."
                )
            }

            val packageInstaller = androidContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(request.packageName)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                val canUpdateWithoutPrompt = request.preferUnattendedUpdate &&
                    androidContext.isInstallerOfRecord(request.packageName)
                setRequireUserAction(
                    if (canUpdateWithoutPrompt) {
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    } else {
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                    }
                )
            }

            val sessionId = try {
                packageInstaller.createSession(params)
            } catch (error: Exception) {
                throw LightPackageInstallException("Could not create an install session.", error)
            }

            try {
                packageInstaller.openSession(sessionId).use { session ->
                    for ((artifact, file) in artifacts) {
                        session.openWrite(artifact.name, 0, file.length()).use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                            session.fsync(output)
                        }
                    }

                    LightPackageInstallResultStore.clear(androidContext, sessionId)
                    val callback = Intent(
                        androidContext,
                        LightPackageInstallReceiver::class.java,
                    ).apply {
                        putExtra(EXTRA_LIGHT_SESSION_ID, sessionId)
                        putExtra(EXTRA_LIGHT_EXPECTED_PACKAGE, request.packageName)
                    }
                    val pending = PendingIntent.getBroadcast(
                        androidContext,
                        sessionId,
                        callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pending.intentSender)
                }
            } catch (error: Exception) {
                runCatching { packageInstaller.abandonSession(sessionId) }
                throw LightPackageInstallException("Could not submit the install session.", error)
            }

            LightPackageInstallSession(sessionId)
        }

    fun result(sessionId: Int): LightPackageInstallResult? {
        androidContext.requirePackageInstallCapability()
        return LightPackageInstallResultStore.read(androidContext, sessionId)
    }

    fun clearResult(sessionId: Int) {
        androidContext.requirePackageInstallCapability()
        LightPackageInstallResultStore.clear(androidContext, sessionId)
    }
}

internal const val EXTRA_LIGHT_SESSION_ID =
    "com.thelightphone.sdk.install.extra.SESSION_ID"
internal const val EXTRA_LIGHT_EXPECTED_PACKAGE =
    "com.thelightphone.sdk.install.extra.EXPECTED_PACKAGE"

private val INSTALL_NAME_PATTERN = Regex("^[A-Za-z0-9._-]+\\.apk$")

internal fun validateInstallRequest(
    request: LightPackageInstallRequest,
): List<LightPackageArtifact> {
    if (request.packageName.isBlank() || request.packageName.any(Char::isWhitespace)) {
        throw LightPackageInstallException("A package name is required.")
    }
    val artifacts = listOf(request.baseApk) + request.splitApks
    val names = mutableSetOf<String>()
    artifacts.forEach { artifact ->
        if (!INSTALL_NAME_PATTERN.matches(artifact.name)) {
            throw LightPackageInstallException(
                "Artifact names must be safe APK names; got '${artifact.name}'."
            )
        }
        if (!names.add(artifact.name)) {
            throw LightPackageInstallException("Duplicate artifact name: ${artifact.name}")
        }
        if (artifact.relativePath.isBlank()) {
            throw LightPackageInstallException("Artifact paths cannot be blank.")
        }
    }
    return artifacts
}

internal fun resolvePrivateArtifact(root: File, relativePath: String): File {
    val relative = File(relativePath)
    if (relative.isAbsolute) {
        throw LightPackageInstallException("Artifact paths must be relative to the app files directory.")
    }
    val rootCanonical = root.canonicalFile
    val candidate = File(rootCanonical, relativePath)
    var current: File? = candidate
    while (current != null && current != rootCanonical) {
        if (Files.isSymbolicLink(current.toPath())) {
            throw LightPackageInstallException("Symbolic links are not allowed in artifact paths.")
        }
        current = current.parentFile
    }
    val canonical = candidate.canonicalFile
    if (canonical == rootCanonical || !canonical.toPath().startsWith(rootCanonical.toPath())) {
        throw LightPackageInstallException("Artifact path escapes the app files directory.")
    }
    if (!canonical.isFile || canonical.length() <= 0L) {
        throw LightPackageInstallException("Artifact does not exist or is empty: $relativePath")
    }
    return canonical
}

private fun Context.isInstallerOfRecord(packageName: String): Boolean = try {
    packageManager.getInstallSourceInfo(packageName).installingPackageName == this.packageName
} catch (_: PackageManager.NameNotFoundException) {
    false
}

private fun PackageInfo.toIdentity(): LightPackageIdentity {
    val signatures = signingInfo?.let { info ->
        if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
    }.orEmpty()
    return LightPackageIdentity(
        packageName = packageName,
        versionCode = longVersionCode,
        versionName = versionName,
        signerSha256 = signatures
            .map { signature -> sha256(signature.toByteArray()) }
            .distinct()
            .sorted(),
    )
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

internal object LightPackageInstallResultStore {
    private const val PREFERENCES = "LIGHT_PACKAGE_INSTALL_RESULTS"

    fun write(context: Context, result: LightPackageInstallResult) {
        val prefix = prefix(result.sessionId)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(prefix + "outcome", result.outcome.name)
            .apply {
                if (result.packageName == null) remove(prefix + "package")
                else putString(prefix + "package", result.packageName)
                if (result.message == null) remove(prefix + "message")
                else putString(prefix + "message", result.message)
            }
            .apply()
    }

    fun read(context: Context, sessionId: Int): LightPackageInstallResult? {
        val prefix = prefix(sessionId)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val outcome = preferences.getString(prefix + "outcome", null)
            ?.let { stored ->
                LightPackageInstallOutcome.entries.firstOrNull { it.name == stored }
            }
            ?: return null
        return LightPackageInstallResult(
            sessionId = sessionId,
            packageName = preferences.getString(prefix + "package", null),
            outcome = outcome,
            message = preferences.getString(prefix + "message", null),
        )
    }

    fun clear(context: Context, sessionId: Int) {
        val prefix = prefix(sessionId)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .remove(prefix + "outcome")
            .remove(prefix + "package")
            .remove(prefix + "message")
            .apply()
    }

    private fun prefix(sessionId: Int): String = "$sessionId:"
}
