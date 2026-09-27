package com.thelightphone.sdk.install

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LightPackageInstallerTest {
    @Test
    fun `request keeps base before splits`() {
        val request = LightPackageInstallRequest(
            packageName = "com.example.bank",
            baseApk = LightPackageArtifact("base.apk", "downloads/base.apk"),
            splitApks = listOf(
                LightPackageArtifact("split_config.en.apk", "downloads/en.apk"),
                LightPackageArtifact("split_config.arm64_v8a.apk", "downloads/arm64.apk"),
            ),
        )

        assertEquals(
            listOf("base.apk", "split_config.en.apk", "split_config.arm64_v8a.apk"),
            validateInstallRequest(request).map { it.name },
        )
    }

    @Test
    fun `duplicate install names are rejected`() {
        val request = LightPackageInstallRequest(
            packageName = "com.example.bank",
            baseApk = LightPackageArtifact("base.apk", "downloads/base.apk"),
            splitApks = listOf(LightPackageArtifact("base.apk", "downloads/split.apk")),
        )

        assertFailsWith<LightPackageInstallException> { validateInstallRequest(request) }
    }

    @Test
    fun `unsafe install names are rejected`() {
        val request = LightPackageInstallRequest(
            packageName = "com.example.bank",
            baseApk = LightPackageArtifact("../base.apk", "downloads/base.apk"),
        )

        assertFailsWith<LightPackageInstallException> { validateInstallRequest(request) }
    }

    @Test
    fun `private artifact resolves beneath files directory`() {
        val root = Files.createTempDirectory("light-package-installer")
        try {
            val apk = root.resolve("downloads/base.apk")
            Files.createDirectories(apk.parent)
            Files.write(apk, byteArrayOf(1, 2, 3))

            assertEquals(apk.toFile().canonicalFile, resolvePrivateArtifact(root.toFile(), "downloads/base.apk"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parent traversal is rejected`() {
        val parent = Files.createTempDirectory("light-package-installer")
        try {
            val root = parent.resolve("files")
            Files.createDirectories(root)
            val outside = parent.resolve("outside.apk")
            Files.write(outside, byteArrayOf(1))

            assertFailsWith<LightPackageInstallException> {
                resolvePrivateArtifact(root.toFile(), "../outside.apk")
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `symbolic links are rejected`() {
        val root = Files.createTempDirectory("light-package-installer")
        try {
            val target = root.resolve("target.apk")
            Files.write(target, byteArrayOf(1))
            val link = root.resolve("link.apk")
            Files.createSymbolicLink(link, target)

            assertFailsWith<LightPackageInstallException> {
                resolvePrivateArtifact(root.toFile(), "link.apk")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
