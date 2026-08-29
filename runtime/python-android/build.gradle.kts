// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.io.File
import java.security.MessageDigest
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val officialCpythonVersion = "3.14.7"
val officialCpythonDirectory = layout.buildDirectory.dir("official/$officialCpythonVersion")
val officialCpythonSha256 = mapOf(
    "aarch64" to "6d50cc3aa66e414a439594089bcdfb5f1264358155c70c1f00471c24cfb477fb",
    "x86_64" to "2c16ce2359565cd8c24f86cfb75630768ba6607e732946b294b969797f583b60",
)
val pythonAssetSourceDirectory = layout.buildDirectory.dir("generated/pythonAssetSources")
val pythonAssetDirectory = layout.buildDirectory.dir("generated/pythonAssets")

fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun prefixFor(architecture: String): File =
    officialCpythonDirectory.get().asFile.resolve("extract-$architecture/prefix")

val verifyOfficialCpython = tasks.register("verifyOfficialCpython") {
    doLast {
        officialCpythonSha256.forEach { (architecture, expectedHash) ->
            val archive = officialCpythonDirectory.get().asFile.resolve("python-$officialCpythonVersion-$architecture-linux-android.tar.gz")
            val sigstore = File("${archive}.sigstore")
            val prefix = prefixFor(architecture)
            check(archive.isFile) { "Missing official CPython $officialCpythonVersion archive for $architecture: ${archive.absolutePath}" }
            check(archive.sha256Hex() == expectedHash) { "Official CPython archive hash mismatch for $architecture" }
            check(sigstore.isFile) { "Missing official CPython Sigstore bundle for $architecture" }
            check(prefix.resolve("include/python3.14/Python.h").isFile) { "CPython headers are not extracted for $architecture" }
            check(prefix.resolve("lib/libpython3.14.so").isFile) { "CPython libpython is not extracted for $architecture" }
            check(prefix.resolve("lib/python3.14/LICENSE.txt").isFile) { "CPython PSF license is missing for $architecture" }
        }
    }
}

val stageOfficialCpythonLibraries = tasks.register("stageOfficialCpythonLibraries") {
    dependsOn(verifyOfficialCpython)
    outputs.dir(layout.buildDirectory.dir("generated/cpython-jniLibs"))
    doLast {
        val destinationRoot = layout.buildDirectory.dir("generated/cpython-jniLibs").get().asFile
        project.delete(destinationRoot)
        officialCpythonSha256.keys.forEach { architecture ->
            val abi = if (architecture == "aarch64") "arm64-v8a" else "x86_64"
            val source = prefixFor(architecture).resolve("lib")
            val destination = destinationRoot.resolve(abi)
            destination.mkdirs()
            source.listFiles()
                ?.filter { file ->
                    file.isFile && (file.name == "libpython3.14.so" || file.name == "libpython3.so" ||
                        file.name.startsWith("lib") && file.name.endsWith("_python.so"))
                }
                ?.forEach { file -> file.copyTo(destination.resolve(file.name), overwrite = true) }
            check(destination.resolve("libpython3.14.so").isFile) { "Failed to stage CPython for $abi" }
        }
    }
}

val packagePythonStdlib = tasks.register<Zip>("packagePythonStdlib") {
    dependsOn(verifyOfficialCpython)
    archiveFileName.set("python3.14.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/pythonStdlib"))
    archiveBaseName.set("python3.14")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    entryCompression = ZipEntryCompression.STORED
    from(prefixFor("aarch64").resolve("lib/python3.14")) {
        // Keep the PSF license/notice alongside the standard library asset.
        include("**/*.py", "LICENSE.txt")
        exclude(
            "test/**", "tests/**", "idlelib/**", "tkinter/**", "turtledemo/**", "ensurepip/**", "venv/**",
            "ctypes/**", "multiprocessing/**", "subprocess.py", "socket.py", "ssl.py", "asyncio/**",
            "concurrent/futures/**", "http/**", "urllib/request.py", "urllib/parse.py",
        )
    }
    from("src/main/python")
}

val packagePythonLicense = tasks.register<Copy>("packagePythonLicense") {
    dependsOn(verifyOfficialCpython)
    from(prefixFor("aarch64").resolve("lib/python3.14/LICENSE.txt"))
    into(pythonAssetSourceDirectory.map { it.dir("licenses/cpython-$officialCpythonVersion") })
}

val packagePythonNotice = tasks.register("packagePythonNotice") {
    dependsOn(verifyOfficialCpython)
    val notice = pythonAssetSourceDirectory.map {
        it.file("licenses/cpython-$officialCpythonVersion/NOTICE.txt")
    }
    outputs.file(notice)
    doLast {
        val destination = notice.get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(
            buildString {
                appendLine("CPython $officialCpythonVersion Android embedded artifacts")
                appendLine("Source release: https://www.python.org/downloads/release/python-3147/")
                appendLine()
                appendLine("arm64-v8a (aarch64-linux-android):")
                appendLine("https://www.python.org/ftp/python/$officialCpythonVersion/python-$officialCpythonVersion-aarch64-linux-android.tar.gz")
                appendLine("SHA-256: ${officialCpythonSha256.getValue("aarch64")}")
                appendLine()
                appendLine("x86_64 (x86_64-linux-android):")
                appendLine("https://www.python.org/ftp/python/$officialCpythonVersion/python-$officialCpythonVersion-x86_64-linux-android.tar.gz")
                appendLine("SHA-256: ${officialCpythonSha256.getValue("x86_64")}")
                appendLine()
                appendLine("The adjacent LICENSE.txt is the complete upstream PSF license and notice text.")
            },
            Charsets.UTF_8,
        )
    }
}

/** Sync gives the APK a clean asset root and removes stale prior layouts. */
val packagePythonAssets = tasks.register<Sync>("packagePythonAssets") {
    dependsOn(packagePythonStdlib, packagePythonLicense, packagePythonNotice)
    from(layout.buildDirectory.file("generated/pythonStdlib/python3.14.zip")) {
        into("python")
    }
    from(pythonAssetSourceDirectory)
    into(pythonAssetDirectory)
}

android {
    namespace = "runtime.mobileagent.python"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = "27.3.13750724"
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCPYTHON_RELEASE_DIR=${officialCpythonDirectory.get().asFile.absolutePath.replace('\\', '/')}"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
sourceSets["main"].assets.srcDir(pythonAssetDirectory)
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/cpython-jniLibs"))
    androidResources {
        noCompress += "zip"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyOfficialCpython, stageOfficialCpythonLibraries, packagePythonAssets)
}
tasks.configureEach {
    if (name.contains("configureCMake") || name.contains("externalNativeBuild")) {
        dependsOn(verifyOfficialCpython, stageOfficialCpythonLibraries, packagePythonAssets)
    }
}

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:skills-api"))
    implementation(project(":platform:android:ipc"))
    implementation(libs.kotlinx.coroutines.core)

}
