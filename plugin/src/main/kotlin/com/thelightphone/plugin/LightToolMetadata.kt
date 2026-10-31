package com.thelightphone.plugin

import org.tomlj.Toml
import org.tomlj.TomlInvalidTypeException
import org.tomlj.TomlParseResult
import java.io.File

/**
 * Parsed + validated representation of `lighttool.toml`. Every field has been
 * checked against the [LightToolPolicy] allowlists; downstream code can pass
 * these values straight into AGP and the generated manifest without further
 * sanitisation.
 *
 * Validation runs at Gradle configure time, so misconfigured tools fail
 * locally with the same message a dev would get from the build server.
 */
data class LightToolMetadata(
    val toolId: String,
    val label: String,
    val versionCode: Int,
    val versionName: String,
    val permissions: List<String>,
    val capabilities: List<String> = emptyList(),
    val serverPackage: String,
    val orientation: String? = null,
    val icon: String? = null,
) {
    companion object {
        const val FILE_NAME: String = "lighttool.toml"
        const val MAX_FILE_BYTES: Long = 32L * 1024

        /** Throws [LightToolMetadataException] if [file] is missing, malformed,
         * or violates the policy. The exception message is dev-facing. */
        fun parse(file: File, allowAltServerPackage: Boolean = false): LightToolMetadata {
            if (!file.isFile) {
                throw LightToolMetadataException(
                    "missing $FILE_NAME at ${file.path} — declare your tool's metadata there"
                )
            }
            if (file.length() > MAX_FILE_BYTES) {
                throw LightToolMetadataException("$FILE_NAME exceeds $MAX_FILE_BYTES bytes")
            }

            val parsed: TomlParseResult = try {
                Toml.parse(file.toPath())
            } catch (e: Exception) {
                throw LightToolMetadataException("could not read $FILE_NAME: ${e.message}")
            }
            if (parsed.hasErrors()) {
                val msg = parsed.errors().joinToString("\n") { it.toString() }
                throw LightToolMetadataException("$FILE_NAME is not valid TOML:\n$msg")
            }

            val tool = parsed.getTable("tool")
                ?: throw LightToolMetadataException("$FILE_NAME is missing the [tool] table")

            return LightToolMetadata(
                toolId = validateToolId(tool.tomlString("id")),
                label = validateLabel(tool.tomlString("label")),
                versionCode = validateVersionCode(tool.tomlLong("versionCode")),
                versionName = validateVersionName(tool.tomlString("versionName")),
                permissions = validatePermissions(tool.tomlStringList("permissions")),
                capabilities = validateCapabilities(tool.tomlStringList("capabilities")),
                serverPackage = validateServerPackage(tool.tomlString("serverPackage"), allowAltServerPackage),
                orientation = validateOrientation(tool.tomlString("orientation")),
                icon = validateIcon(tool.tomlString("icon")),
            )
        }

        private fun validateToolId(value: String?): String {
            val v = value ?: throw LightToolMetadataException("tool.id is required")
            require(LightToolPolicy.TOOL_ID_PATTERN.matches(v)) {
                "tool.id must be a lowercase dotted Java package identifier " +
                        "(e.g. com.example.mytool); got '$v'"
            }
            return v
        }

        private fun validateLabel(value: String?): String {
            val v = value ?: throw LightToolMetadataException("tool.label is required")
            require(LightToolPolicy.TOOL_LABEL_PATTERN.matches(v)) {
                "tool.label must be 1-50 printable characters and contain no <, >, " +
                        "or control characters; got '$v'"
            }
            return v
        }

        private fun validateVersionCode(value: Long?): Int {
            val v = value ?: throw LightToolMetadataException("tool.versionCode is required")
            require(v in 1L..LightToolPolicy.MAX_VERSION_CODE.toLong()) {
                "tool.versionCode must be between 1 and ${LightToolPolicy.MAX_VERSION_CODE}; got $v"
            }
            return v.toInt()
        }

        private fun validateVersionName(value: String?): String {
            val v = value ?: throw LightToolMetadataException("tool.versionName is required")
            require(LightToolPolicy.VERSION_NAME_PATTERN.matches(v)) {
                "tool.versionName must be major.minor.patch semver; got '$v'"
            }
            return v
        }

        private fun validateServerPackage(value: String?, allowAlt: Boolean): String {
            val v = value ?: throw LightToolMetadataException("tool.serverPackage is required")
            require(LightToolPolicy.TOOL_ID_PATTERN.matches(v)) {
                "tool.serverPackage must be a lowercase dotted Java package identifier " +
                        "(e.g. com.lightos); got '$v'"
            }
            require(allowAlt || v == LightToolPolicy.LIGHTOS_SERVER_PACKAGE) {
                "tool.serverPackage is '$v', but LightOS on a Light Phone is " +
                        "${LightToolPolicy.LIGHTOS_SERVER_PACKAGE}. Light's release builds use this " +
                        "value as-is, so the tool would not connect to LightOS on a phone. " +
                        "Set serverPackage = \"${LightToolPolicy.LIGHTOS_SERVER_PACKAGE}\", or if you " +
                        "are building for the LightOS emulator, opt in with " +
                        "-DlightSdk.allowAltServerPackage=true " +
                        "(e.g. ./gradlew :tool:installDebug -DlightSdk.allowAltServerPackage=true)"
            }
            return v
        }

        private fun validateOrientation(value: String?): String? {
            if (value == null) return null
            require(value in LightToolPolicy.ALLOWED_ORIENTATIONS) {
                "tool.orientation must be one of: " +
                    LightToolPolicy.ALLOWED_ORIENTATIONS.sorted().joinToString() + "; got '$value'"
            }
            return value
        }

        private fun validateIcon(value: String?): String? {
            if (value == null) return null
            require(LightToolPolicy.ICON_PATTERN.matches(value)) {
                "tool.icon must be an Android resource reference " +
                    "(e.g. @mipmap/ic_launcher); got '$value'"
            }
            return value
        }

        private fun validatePermissions(values: List<String>?): List<String> {
            val list = values ?: emptyList()
            val seen = mutableSetOf<String>()
            for (item in list) {
                require(seen.add(item)) { "duplicate permission: $item" }
                // Capability-only permissions require the capability. Shared permissions
                // such as WAKE_LOCK also remain valid as explicit declarations.
                capabilityGenerating(item)?.takeUnless { item in LightToolPolicy.ALLOWED_PERMISSIONS }?.let { capability ->
                    throw LightToolMetadataException(
                        "permission not allowed: $item — it is generated by the " +
                                "'$capability' capability; declare " +
                                "capabilities = [\"$capability\"] instead"
                    )
                }
                require(item in LightToolPolicy.ALLOWED_PERMISSIONS) {
                    "permission not allowed: $item\nallowed: ${LightToolPolicy.ALLOWED_PERMISSIONS.sorted().joinToString()}"
                }
            }
            return list
        }

        private fun validateCapabilities(values: List<String>?): List<String> {
            val list = values ?: emptyList()
            val seen = mutableSetOf<String>()
            for (item in list) {
                require(seen.add(item)) { "duplicate capability: $item" }
                require(item in LightToolPolicy.ALLOWED_CAPABILITIES) {
                    "capability not allowed: $item\nallowed: ${LightToolPolicy.ALLOWED_CAPABILITIES.sorted().joinToString()}"
                }
            }
            return list
        }

        private fun capabilityGenerating(permission: String): String? =
            LightToolPolicy.CAPABILITY_IMPLIED_PERMISSIONS.entries
                .firstOrNull { permission in it.value }
                ?.key

        private fun require(condition: Boolean, lazyMessage: () -> String) {
            if (!condition) throw LightToolMetadataException(lazyMessage())
        }
    }
}

class LightToolMetadataException(message: String) : RuntimeException(message)

/** Policy values lifted into one object so tests and the validator share them. */
object LightToolPolicy {
    val TOOL_ID_PATTERN: Regex = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    val ICON_PATTERN: Regex = Regex("^@[a-z]+/[a-z0-9_]+$")
    val VERSION_NAME_PATTERN: Regex =
        Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$""")
    val TOOL_LABEL_PATTERN: Regex = Regex("^[^\\x00-\\x1f<>]{1,50}$")
    const val MAX_VERSION_CODE: Int = 2_100_000_000
    const val LIGHTOS_SERVER_PACKAGE: String = "com.lightos"
    val ALLOWED_ORIENTATIONS: Set<String> = setOf("portrait")

    val ALLOWED_PERMISSIONS: Set<String> = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.WAKE_LOCK",
        "android.permission.VIBRATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.NFC",
    )

    const val DETACHED_AUDIO: String = "detached-audio"
    const val AUDIO_DASH: String = "audio-dash"
    const val TOOL_MANAGER_PROVIDER: String = "tool-manager-provider"
    /**
     * Allows a tool to access resources over plain HTTP, which Android blocks by
     * default. Useful for tools that need to reach self-hosted servers on the
     * user's own network.
     */
    const val CLEARTEXT_HTTP: String = "cleartext-http"

    /**
     * Lets a tool enumerate and launch other apps, open app details, and read
     * the active notification stream. The permissions this needs are
     * restricted/special-access ones, so the capability owns them instead of
     * widening [ALLOWED_PERMISSIONS] for every tool.
     */
    const val APP_ACCESS: String = "app-access"

    /**
     * Lets a tool install and uninstall packages with the user's confirmation.
     * The receiver here receives the platform's install session result; the
     * screens themselves live in `com.thelightphone.sdk.install`.
     */
    const val PACKAGE_INSTALL_REQUEST: String = "package-install-request"

    val ALLOWED_CAPABILITIES: Set<String> = setOf(
        DETACHED_AUDIO,
        AUDIO_DASH,
        TOOL_MANAGER_PROVIDER,
        CLEARTEXT_HTTP,
        APP_ACCESS,
        PACKAGE_INSTALL_REQUEST,
    )

    /** media3 version the SDK is built against, generated from its catalog. */
    val MEDIA3_VERSION: String = LightSdkPlugin.readResourceLines("media3-version.txt").single()

    /** Dependencies a capability puts on the tool's classpath. */
    val CAPABILITY_IMPLIED_DEPENDENCIES: Map<String, List<String>> = mapOf(
        AUDIO_DASH to listOf("androidx.media3:media3-exoplayer-dash:$MEDIA3_VERSION"),
    )

    /**
     * Permissions contributed by a capability. Foreground-service permissions
     * remain capability-only; shared permissions can also appear in the allowlist.
     */
    val CAPABILITY_IMPLIED_PERMISSIONS: Map<String, List<String>> = mapOf(
        DETACHED_AUDIO to listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.WAKE_LOCK",
        ),
        APP_ACCESS to listOf(
            "android.permission.REQUEST_DELETE_PACKAGES",
        ),
        // Installing is a different grant from deleting, so it is a separate
        // capability: a tool that only needs uninstall should not be able to
        // install. QUERY_ALL_PACKAGES comes with install because the installer
        // must resolve the package it is about to replace.
        PACKAGE_INSTALL_REQUEST to listOf(
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
            // The install flow also offers uninstall, so the capability carries
            // the matching delete permission rather than forcing the tool to
            // declare app-access as well.
            "android.permission.REQUEST_DELETE_PACKAGES",
        ),
    )

    /**
     * Notifications access needs no requested permission: the platform grants it
     * from system settings to an enabled listener, and
     * `BIND_NOTIFICATION_LISTENER_SERVICE` is only a signature-level guard on
     * the service element, never something a non-system app can be granted.
     *
     * Package enumeration likewise requests nothing: the capability emits a
     * `<queries>` block for launcher activities, which is the scoped mechanism
     * for seeing exactly the apps a list can launch. Asking for
     * `QUERY_ALL_PACKAGES` on top of that would be broader than the need and is
     * a Play policy concern.
     */
    const val APP_ACCESS_BIND_PERMISSION: String =
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"

    /**
     * Application `<meta-data>` key marking [capability] as declared. Under our
     * own namespace, so only our generator can produce it — the property a
     * platform permission cannot have.
     */
    fun capabilityMarker(capability: String): String =
        "com.thelightphone.sdk.CAPABILITY_" + capability.uppercase().replace('-', '_')

    // <provider> <meta-data> key a LightFileProvider-compatible provider must
    // declare (value "true") to be discoverable by the tool manager.
    const val META_DATA_TOOL_MANAGER_PROVIDER: String = "com.thelightphone.toolmanager.TOOL_MANAGER_PROVIDER"

    /**
     * Permissions that Play Store / lint infer as also requiring a hardware
     * feature. Lacking a matching `<uses-feature>` element triggers
     * PermissionImpliesUnsupportedChromeOsHardware (and similar) lint
     * failures. We auto-emit each implied feature with `required="false"`
     * because every Light Phone has the hardware, and we don't want Play
     * Store filters excluding devices we don't actually need to exclude.
     */
    val PERMISSION_IMPLIED_FEATURES: Map<String, List<String>> = mapOf(
        "android.permission.CAMERA" to listOf("android.hardware.camera"),
        "android.permission.RECORD_AUDIO" to listOf("android.hardware.microphone"),
        "android.permission.ACCESS_FINE_LOCATION" to listOf("android.hardware.location.gps"),
        "android.permission.ACCESS_COARSE_LOCATION" to listOf("android.hardware.location.network"),
        "android.permission.NFC" to listOf("android.hardware.nfc"),
    )

    /**
     * Permissions that pull in another permission's `<uses-permission>` element.
     * Android/Play Store lint (`CoarseFineLocation`) flags requesting FINE
     * without also requesting COARSE, so we emit both any time a tool declares
     * just FINE rather than making every tool remember to list both.
     */
    val PERMISSION_IMPLIED_PERMISSIONS: Map<String, List<String>> = mapOf(
        "android.permission.ACCESS_FINE_LOCATION" to listOf("android.permission.ACCESS_COARSE_LOCATION"),
    )
}

// --- TomlTable extension helpers --------------------------------------------
// tomlj's TomlTable accessors can throw on type mismatches; wrap them so the
// caller gets a clean LightToolMetadataException instead of a TomlInvalidType.

private fun org.tomlj.TomlTable.tomlString(key: String): String? = try {
    if (contains(key)) getString(key) else null
} catch (e: TomlInvalidTypeException) {
    throw LightToolMetadataException("tool.$key must be a string")
}

private fun org.tomlj.TomlTable.tomlLong(key: String): Long? = try {
    if (contains(key)) getLong(key) else null
} catch (e: TomlInvalidTypeException) {
    throw LightToolMetadataException("tool.$key must be an integer")
}

private fun org.tomlj.TomlTable.tomlStringList(key: String): List<String>? {
    if (!contains(key)) return null
    val arr = try {
        getArray(key) ?: return null
    } catch (e: TomlInvalidTypeException) {
        throw LightToolMetadataException("tool.$key must be an array of strings")
    }
    val out = ArrayList<String>(arr.size())
    for (i in 0 until arr.size()) {
        val item = arr.get(i)
        if (item !is String) {
            throw LightToolMetadataException("tool.$key entries must be strings")
        }
        out.add(item)
    }
    return out
}
