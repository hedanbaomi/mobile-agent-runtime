// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

import java.net.HttpURLConnection
import java.net.URI
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "runtime.mobileagent.embedding"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The generated directory is populated by a pinned, hash-checked task
    // below.  Keeping the 90 MiB weights out of Git still makes every clean
    // build package the same official model pack or fail closed.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/embedding-assets"))
}

val modelPackId = "all-MiniLM-L6-v2"
val modelPackRevision = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
val modelPackSource = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/"
val modelPackModelSha256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"
val modelPackTokenizerSha256 = "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"
val onnxRuntimeVersion = "1.29.0"
val onnxRuntimeLicenseUrl = "https://github.com/microsoft/onnxruntime/blob/v$onnxRuntimeVersion/LICENSE"
val onnxRuntimeLicenseSource = rootProject.file("runtime/embedding-onnx/third-party/onnxruntime-$onnxRuntimeVersion/LICENSE.txt")
val modelPackCache = layout.buildDirectory.dir("modelpack/$modelPackId")
val modelPackGenerated = layout.buildDirectory.dir("generated/embedding-assets/modelpacks/$modelPackId")
val embeddingAssetsGenerated = layout.buildDirectory.dir("generated/embedding-assets")
val onnxRuntimeLicenseGenerated = layout.buildDirectory.file(
    "generated/embedding-assets/licenses/onnxruntime-$onnxRuntimeVersion/LICENSE.txt",
)
val onnxRuntimeNoticeGenerated = layout.buildDirectory.file(
    "generated/embedding-assets/licenses/onnxruntime-$onnxRuntimeVersion/NOTICE.txt",
)

fun sha256(file: java.io.File): String {
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

fun downloadPinned(url: String, destination: File, expectedSha256: String) {
    destination.parentFile.mkdirs()
    if (!destination.isFile || sha256(destination) != expectedSha256) {
        val temporary = File(destination.parentFile, ".${destination.name}.download")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        try {
            check(connection.responseCode in 200..299) { "Model pack download failed: HTTP ${connection.responseCode}" }
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
        check(sha256(temporary) == expectedSha256) { "Model pack hash mismatch for ${destination.name}" }
        runCatching {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    check(sha256(destination) == expectedSha256) { "Model pack cache is corrupt: ${destination.name}" }
}

val prepareModelPack = tasks.register("prepareModelPack") {
    inputs.property("modelPackRevision", modelPackRevision)
    inputs.property("modelPackModelSha256", modelPackModelSha256)
    inputs.property("modelPackTokenizerSha256", modelPackTokenizerSha256)
    inputs.property("onnxRuntimeVersion", onnxRuntimeVersion)
    inputs.file(onnxRuntimeLicenseSource)
    outputs.dir(modelPackGenerated)
    outputs.dir(embeddingAssetsGenerated)
    outputs.file(onnxRuntimeLicenseGenerated)
    outputs.file(onnxRuntimeNoticeGenerated)
    doLast {
        val cache = modelPackCache.get().asFile
        val generated = modelPackGenerated.get().asFile
        val model = cache.resolve("model.onnx")
        val tokenizer = cache.resolve("tokenizer.json")
        downloadPinned(
            "${modelPackSource}resolve/$modelPackRevision/onnx/model.onnx?download=true",
            model,
            modelPackModelSha256,
        )
        downloadPinned(
            "${modelPackSource}resolve/$modelPackRevision/tokenizer.json?download=true",
            tokenizer,
            modelPackTokenizerSha256,
        )
        generated.deleteRecursively()
        generated.mkdirs()
        Files.copy(model.toPath(), generated.resolve("model.onnx").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(tokenizer.toPath(), generated.resolve("tokenizer.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
        generated.resolve("manifest.json").writeText(
            """
            {
              "id": "$modelPackId",
              "spaceId": "onnx:$modelPackId@$modelPackRevision:d384:cosine",
              "dimension": 384,
              "sha256": "$modelPackModelSha256",
              "tokenizerSha256": "$modelPackTokenizerSha256",
              "license": "Apache-2.0",
              "source": "$modelPackSource",
              "revision": "$modelPackRevision",
              "modelFile": "model.onnx",
              "tokenizerFile": "tokenizer.json",
              "maxSequenceLength": 128,
              "pooling": "mean",
              "normalize": true,
              "distance": "cosine",
              "tokenizerType": "bert-wordpiece",
              "outputName": "last_hidden_state"
            }
            """.trimIndent(),
        )
        generated.resolve("LICENSE-NOTICE.txt").writeText(
            "Model: sentence-transformers/all-MiniLM-L6-v2\n" +
                "Source: $modelPackSource\n" +
                "Revision: $modelPackRevision\n" +
                "Model card: ${modelPackSource}blob/$modelPackRevision/README.md\n" +
                "License: Apache-2.0 (verified from the official model card metadata)\n" +
                "Runtime: com.microsoft.onnxruntime:onnxruntime-android:1.29.0\n" +
                "Runtime license: MIT (verified from the Maven POM)\n",
        )
        val license = generated.resolve("LICENSES/Apache-2.0.txt")
        license.parentFile.mkdirs()
        Files.copy(
            rootProject.file("LICENSES/Apache-2.0.txt").toPath(),
            license.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )

        check(onnxRuntimeLicenseSource.isFile) {
            "ONNX Runtime $onnxRuntimeVersion license source is missing"
        }
        val runtimeLicense = embeddingAssetsGenerated.get().asFile.resolve(
            "licenses/onnxruntime-$onnxRuntimeVersion/LICENSE.txt",
        )
        runtimeLicense.parentFile.mkdirs()
        Files.copy(
            onnxRuntimeLicenseSource.toPath(),
            runtimeLicense.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        runtimeLicense.parentFile.resolve("NOTICE.txt").writeText(
            "ONNX Runtime Android $onnxRuntimeVersion\n" +
                "Source: $onnxRuntimeLicenseUrl\n" +
                "License: MIT (verbatim text in LICENSE.txt)\n",
        )
    }
}

tasks.named("preBuild").configure { dependsOn(prepareModelPack) }

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:knowledge-api"))
    implementation(project(":shared:provider-api"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion")
}
