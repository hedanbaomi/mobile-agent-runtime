// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.io.File
import groovy.json.JsonSlurper
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression

import runtime.mobileagent.build.PinnedArtifactDownload

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
val compatibilityDirectory = rootProject.file("vendor/cpython/3.14.7/api26-x86_64")
val compatibilitySha256 = "58325117a3352c52827be08ffe602eb7b00d17702996b7fbf031ff51e3667b0f"
val safeStdlibRegistryFile = file("safe-stdlib-modules.json")
val safeStdlibModules = listOf(
    "math", "_csv", "binascii", "_struct", "_random", "unicodedata",
    "_sha1", "_sha2", "_sha3", "_md5", "_blake2", "zlib",
)
@Suppress("UNCHECKED_CAST")
val safeStdlibRegistry = JsonSlurper().parse(safeStdlibRegistryFile) as Map<String, Map<String, Map<String, String>>>


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
    val source = URI(url)
    check(
        source.scheme == "https" &&
            source.host == "www.python.org" &&
            source.userInfo == null &&
            source.port in listOf(-1, 443),
    ) { "Official CPython source must be https://www.python.org:443" }
    PinnedArtifactDownload.download(url, destination, expectedSha256, followRedirects = false)
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

val verifyCpythonCompatibility = tasks.register("verifyCpythonCompatibility") {
    dependsOn(verifyOfficialCpython)
    doLast {
        check(compatibilityDirectory.resolve("libpython3.14.so").sha256Hex() == compatibilitySha256) {
            "API26 compatibility CPython library hash mismatch"
        }
        check(compatibilityDirectory.resolve("provenance.json").sha256Hex() ==
            "87ff06f15d0270ae6b3216d2b7d70c0a2846b39dee43ed0e30887904607a8ed7") {
            "Compatibility CPython provenance mismatch"
        }
        check(compatibilityDirectory.resolve("LICENSE.txt").sha256Hex() ==
            "b0e25a78cffb43f4d92de8b61ccfa1f1f98ecbc22330b54b5251e7b6ba010231") {
            "Compatibility CPython license mismatch"
        }
        check(safeStdlibRegistryFile.sha256Hex() ==
            "3dd29d5f21ecf5c8d08d4fbbd231e4307186c14101e465ac21ff8fdcb8fc40d6") {
            "Safe standard-library registry mismatch"
        }
        check(safeStdlibRegistry.keys == officialCpythonSha256.keys)
        safeStdlibRegistry.forEach { (architecture, modules) ->
            check(modules.keys == safeStdlibModules.toSet())
            modules.forEach { (name, record) ->
                check(record["source"] == "$name.cpython-314-$architecture-linux-android.so")
                val source = prefixFor(architecture).resolve("lib/python3.14/lib-dynload").resolve(record.getValue("source"))
                check(source.sha256Hex() == record.getValue("sha256")) {
                    "Official CPython standard-library module hash mismatch: $architecture/$name"
                }
            }
        }
    }
}

val stageOfficialCpythonLibraries = tasks.register("stageOfficialCpythonLibraries") {
    dependsOn(verifyCpythonCompatibility)
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
            if (architecture == "x86_64") {
                compatibilityDirectory.resolve("libpython3.14.so")
                    .copyTo(destination.resolve("libpython3.14.so"), overwrite = true)
            }
            safeStdlibRegistry.getValue(architecture).forEach { (name, record) ->
                prefixFor(architecture).resolve("lib/python3.14/lib-dynload").resolve(record.getValue("source"))
                    .copyTo(destination.resolve("libcpython_$name.so"), overwrite = true)
            }
            check(destination.resolve("libpython3.14.so").isFile) { "Failed to stage CPython for $abi" }
        }
        destinationRoot.resolve("safe_stdlib_modules.h").writeText(buildString {
            appendLine("// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors")
            appendLine("// SPDX-" + "License-Identifier: AGPL-3.0-only")
            safeStdlibModules.forEach { name -> appendLine("extern PyObject *PyInit_$name(void);") }
            appendLine("static struct _inittab safe_stdlib_modules[] = {")
            safeStdlibModules.forEach { name -> appendLine("    {\"$name\", &PyInit_$name},") }
            appendLine("    {NULL, NULL}")
            appendLine("};")
        })
        destinationRoot.resolve("safe_stdlib_modules.cmake").writeText(buildString {
            appendLine("# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors")
            appendLine("# SPDX-" + "License-Identifier: AGPL-3.0-only")
            safeStdlibModules.forEach { name ->
                appendLine("add_library(cpython_stdlib_$name SHARED IMPORTED GLOBAL)")
                appendLine("set_target_properties(cpython_stdlib_$name PROPERTIES")
                appendLine("    IMPORTED_LOCATION \"\${CPYTHON_STDLIB_DIR}/\${CMAKE_ANDROID_ARCH_ABI}/libcpython_$name.so\"")
                appendLine("    IMPORTED_NO_SONAME TRUE)")
            }
            appendLine("set(CPYTHON_STDLIB_TARGETS " + safeStdlibModules.joinToString(" ") { "cpython_stdlib_$it" } + ")")
        })
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
    // ZIP imports do not need executable bits. The upstream archive has two
    // executable .py files; Windows extraction loses those bits, while Linux
    // preserves them. Pin both modes so the packaged inventory is portable.
    filePermissions { unix("0644") }
    dirPermissions { unix("0755") }
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
    dependsOn(verifyCpythonCompatibility)
    val notice = pythonAssetSourceDirectory.map {
        it.file("licenses/cpython-$officialCpythonVersion/NOTICE.txt")
    }
    outputs.file(notice)
    doLast {
        val destination = notice.get().asFile
        destination.parentFile.mkdirs()
        compatibilityDirectory.resolve("provenance.json").copyTo(destination.parentFile.resolve("compatibility-provenance.json"), overwrite = true)
        compatibilityDirectory.resolve("HACL-LICENSE.txt").copyTo(destination.parentFile.resolve("HACL-LICENSE.txt"), overwrite = true)
        compatibilityDirectory.resolve("BLAKE2-NOTICE.txt").copyTo(destination.parentFile.resolve("BLAKE2-NOTICE.txt"), overwrite = true)
        compatibilityDirectory.resolve("CC0-1.0.txt").copyTo(destination.parentFile.resolve("CC0-1.0.txt"), overwrite = true)
        safeStdlibRegistryFile.copyTo(destination.parentFile.resolve("safe-stdlib-modules.json"), overwrite = true)
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
                appendLine("x86_64 libpython3.14.so is a project-built derivative of the unmodified source release.")
                appendLine("Source archive: https://www.python.org/ftp/python/3.14.7/Python-3.14.7.tar.xz")
                appendLine("Source SHA-256: 3b48dac8fb59f62eaa67ac83c1eb12bda1b7a08406dd286e252c11a66be27f81")
                appendLine("Change: --without-mimalloc removes its constructor's legacy SYS_open on isolated API26.")
                appendLine("Verified build-input SHA-256: $compatibilitySha256")
                appendLine("Android packaging may strip debug/symbol tables; packaged hashes are recorded in the APK native SBOM.")
                appendLine("Recipe: tools/rebuild_cpython_api26.py; provenance: compatibility-provenance.json")
                appendLine("Other architectures retain the pinned official libpython.")
                appendLine("Fixed official data-only extension modules are linked and registered as builtins.")
                appendLine("The adjacent LICENSE.txt is the complete upstream PSF license and notice text.")
                appendLine("HACL-LICENSE.txt and BLAKE2-NOTICE.txt retain those upstream module notices.")
                appendLine("CC0-1.0.txt contains the complete dedication terms referenced by the BLAKE2 notice.")
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
                arguments += "-DCPYTHON_COMPAT_LIBRARY=${compatibilityDirectory.resolve("libpython3.14.so").absolutePath.replace('\\', '/')}"
                arguments += "-DCPYTHON_STDLIB_DIR=${layout.buildDirectory.dir("generated/cpython-jniLibs").get().asFile.absolutePath.replace('\\', '/')}"
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
