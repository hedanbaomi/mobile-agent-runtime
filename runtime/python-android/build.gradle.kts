// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
val officialCpythonSigstoreSha256 = mapOf(
    "aarch64" to "e65340a247a68e2248556c1ac16a5eea0689c2b3d6ec31be6f72b0dda5cd1c65",
    "x86_64" to "840007443d6ac16262d33753875ed183bb55cc350ad809b090b4cf011055e099",
)
val officialCpythonSource = "https://www.python.org/ftp/python/$officialCpythonVersion"
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

fun downloadPinned(url: String, destination: File, expectedSha256: String) {
    destination.parentFile.mkdirs()
    if (!destination.isFile || destination.sha256Hex() != expectedSha256) {
        val temporary = destination.resolveSibling(".${destination.name}.download")
        temporary.delete()
        val source = URI(url)
        check(
            source.scheme == "https" &&
                source.host == "www.python.org" &&
                source.userInfo == null &&
                source.port in listOf(-1, 443),
        ) { "Official CPython source must be https://www.python.org:443" }
        val connection = source.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        try {
            check(connection.responseCode in 200..299) {
                "Official CPython download failed for ${destination.name}: HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        check(temporary.sha256Hex() == expectedSha256) {
            "Official CPython download hash mismatch for ${destination.name}"
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    check(destination.sha256Hex() == expectedSha256) {
        "Official CPython cache is corrupt: ${destination.name}"
    }
}

val prepareOfficialCpython = tasks.register("prepareOfficialCpython") {
    // Deliberately keep this task untracked: every build re-hashes ignored
    // external inputs, while valid downloads and matching extractions are reused.
    inputs.property("officialCpythonVersion", officialCpythonVersion)
    inputs.property("officialCpythonSha256", officialCpythonSha256)
    inputs.property("officialCpythonSigstoreSha256", officialCpythonSigstoreSha256)
    doLast {
        officialCpythonSha256.forEach { (architecture, archiveSha256) ->
            val root = officialCpythonDirectory.get().asFile
            val archive = root.resolve("python-$officialCpythonVersion-$architecture-linux-android.tar.gz")
            val sigstore = File("${archive}.sigstore")
            downloadPinned("$officialCpythonSource/${archive.name}", archive, archiveSha256)
            downloadPinned(
                "$officialCpythonSource/${sigstore.name}",
                sigstore,
                officialCpythonSigstoreSha256.getValue(architecture),
            )

            val prefix = prefixFor(architecture)
            val extracted = root.resolve("extract-$architecture")
            val sourceMarker = extracted.resolve(".archive-sha256")
            val extractionComplete =
                sourceMarker.isFile &&
                    sourceMarker.readText(Charsets.UTF_8).trim() == archiveSha256 &&
                    prefix.resolve("include/python3.14/Python.h").isFile &&
                    prefix.resolve("lib/libpython3.14.so").isFile &&
                    prefix.resolve("lib/python3.14/LICENSE.txt").isFile
            if (!extractionComplete) {
                val temporary = root.resolve(".extract-$architecture.tmp")
                temporary.deleteRecursively()
                project.copy {
                    from(tarTree(resources.gzip(archive)))
                    into(temporary)
                }
                val temporaryPrefix = temporary.resolve("prefix")
                check(temporaryPrefix.resolve("include/python3.14/Python.h").isFile) {
                    "Official CPython headers were not extracted for $architecture"
                }
                check(temporaryPrefix.resolve("lib/libpython3.14.so").isFile) {
                    "Official CPython library was not extracted for $architecture"
                }
                check(temporaryPrefix.resolve("lib/python3.14/LICENSE.txt").isFile) {
                    "Official CPython PSF license was not extracted for $architecture"
                }
                temporary.resolve(".archive-sha256").writeText("$archiveSha256\n", Charsets.UTF_8)
                extracted.deleteRecursively()
                project.copy {
                    from(temporary)
                    into(extracted)
                }
                temporary.deleteRecursively()
            }
        }
    }
}

val verifyOfficialCpython = tasks.register("verifyOfficialCpython") {
    dependsOn(prepareOfficialCpython)
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
