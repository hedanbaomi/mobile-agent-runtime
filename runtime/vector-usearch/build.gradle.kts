// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "runtime.mobileagent.vector"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DUSEARCH_ROOT=${layout.buildDirectory.dir("source/USearch-2.25.1").get().asFile.absolutePath.replace('\\', '/')}"
            }
        }
    }
    ndkVersion = "27.3.13750724"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/vector-assets"))
}

val usearchRevision = "v2.25.1"
val usearchSourceUrl = "https://github.com/unum-cloud/USearch/archive/refs/tags/$usearchRevision.zip"
// SHA-256 of the official GitHub tag archive downloaded for this build.
val usearchSourceSha256 = "30dd99efab891a6385a89ecd3a3a8a85ed7d3f064b7657588fc3ef5ccd2d52e3"
val usearchSourceZip = layout.buildDirectory.file("source/usearch-v2.25.1.zip")
val usearchSourceDir = layout.buildDirectory.dir("source/USearch-2.25.1")
val usearchGeneratedAssets = layout.buildDirectory.dir("generated/vector-assets")
val usearchGeneratedLicense = layout.buildDirectory.file("generated/vector-assets/licenses/usearch-2.25.1/LICENSE.txt")
val usearchGeneratedNotice = layout.buildDirectory.file("generated/vector-assets/licenses/usearch-2.25.1/NOTICE.txt")

fun fileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun downloadUsearch(url: String, destination: File, expectedSha256: String) {
    destination.parentFile.mkdirs()
    if (!destination.isFile || fileSha256(destination) != expectedSha256) {
        val temporary = File(destination.parentFile, ".${destination.name}.download")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode in 200..299) { "USearch source download failed: HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        check(fileSha256(temporary) == expectedSha256) { "USearch source hash mismatch" }
        runCatching {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    check(fileSha256(destination) == expectedSha256) { "USearch source cache is corrupt" }
}

val prepareUsearchSource = tasks.register("prepareUsearchSource") {
    inputs.property("usearchRevision", usearchRevision)
    inputs.property("usearchSourceSha256", usearchSourceSha256)
    inputs.property("usearchSourceUrl", usearchSourceUrl)
    outputs.dir(usearchSourceDir)
    outputs.dir(usearchGeneratedAssets)
    outputs.file(usearchGeneratedLicense)
    outputs.file(usearchGeneratedNotice)
    doLast {
        val zip = usearchSourceZip.get().asFile
        val sourceDir = usearchSourceDir.get().asFile
        downloadUsearch(usearchSourceUrl, zip, usearchSourceSha256)
        if (!sourceDir.resolve("include/usearch/index_dense.hpp").isFile) {
            sourceDir.parentFile.mkdirs()
            copy {
                from(zipTree(zip))
                into(sourceDir.parentFile)
            }
        }
        check(sourceDir.resolve("include/usearch/index_dense.hpp").isFile) {
            "USearch source archive did not contain include/usearch/index_dense.hpp"
        }
        val licenseSource = sourceDir.resolve("LICENSE")
        check(licenseSource.isFile) {
            "USearch source archive did not contain its Apache-2.0 LICENSE"
        }
        val license = usearchGeneratedAssets.get().asFile.resolve(
            "licenses/usearch-2.25.1/LICENSE.txt",
        )
        license.parentFile.mkdirs()
        Files.copy(licenseSource.toPath(), license.toPath(), StandardCopyOption.REPLACE_EXISTING)
        license.parentFile.resolve("NOTICE.txt").writeText(
            "USearch $usearchRevision\n" +
                "Source: $usearchSourceUrl\n" +
                "Revision: $usearchRevision\n" +
                "Archive SHA-256: $usearchSourceSha256\n" +
                "License: Apache-2.0 (verbatim text in LICENSE.txt)\n",
        )
    }
}

tasks.named("preBuild").configure { dependsOn(prepareUsearchSource) }
tasks.matching { it.name.startsWith("externalNativeBuild") }.configureEach { dependsOn(prepareUsearchSource) }

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:knowledge-api"))

}
