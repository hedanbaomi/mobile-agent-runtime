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
    // below. Keeping the quantized multilingual weights out of Git makes every clean
    // build package the same official model pack or fail closed.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/embedding-assets"))
}

val modelPackId = "distiluse-base-multilingual-cased-v2"
val modelPackRevision = "cad454171d918d9873a2701ba245054b6c1760dd"
val modelPackSource = "https://huggingface.co/Xenova/distiluse-base-multilingual-cased-v2/"
val modelPackModelSha256 = "1724c10ba3b33b58afb6ce73bbf3e0528921bd4d4e1bf5a569b6d73f234e72bf"
val modelPackTokenizerSha256 = "bf1b59b7b11c95f194f51708d918eea378e09d05f84c0e1656dc5180e8117088"
val projectionSha256 = "0a21b1ce908e772ebf09f93c20ca09524c32706e9918d9c0169a3f0663b191ed"
val projectionSource = "https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2/resolve/bfe45d0732ca50787611c0fe107ba278c7f3f889/2_Dense/model.safetensors"
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
    inputs.property("projectionSha256", projectionSha256)
    inputs.property("windowStrategy", "sentence-bounded-coverage-mean-v3")
    inputs.file(onnxRuntimeLicenseSource)
    inputs.file(rootProject.file("LICENSES/Apache-2.0.txt"))
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
            "${modelPackSource}resolve/$modelPackRevision/onnx/model_quantized.onnx?download=true",
            model,
            modelPackModelSha256,
        )
        downloadPinned(
            "${modelPackSource}resolve/$modelPackRevision/tokenizer.json?download=true",
            tokenizer,
            modelPackTokenizerSha256,
        )
        val projection = cache.resolve("dense.safetensors")
        downloadPinned(projectionSource, projection, projectionSha256)
        // This task owns modelpacks; remove retired generated packs so an incremental
        // build cannot accidentally ship the obsolete model alongside the replacement.
        generated.parentFile.deleteRecursively()
        generated.mkdirs()
        Files.copy(model.toPath(), generated.resolve("model.onnx").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(tokenizer.toPath(), generated.resolve("tokenizer.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(projection.toPath(), generated.resolve("dense.safetensors").toPath(), StandardCopyOption.REPLACE_EXISTING)
        generated.resolve("manifest.json").writeText(
            """
            {
              "id": "$modelPackId",
              "spaceId": "onnx:distiluse-base-multilingual-cased-v2@cad454171d918d9873a2701ba245054b6c1760dd:int8:d512:cosine:wp-v2:mean-dense-tanh:sentence126-bounded-mean-v3:tbf1b59b7b11c95f194f51708d918eea378e09d05f84c0e1656dc5180e8117088:p0a21b1ce908e772ebf09f93c20ca09524c32706e9918d9c0169a3f0663b191ed",
              "dimension": 512,
              "hiddenDimension": 768,
              "projectionFile": "dense.safetensors",
              "projectionSha256": "$projectionSha256",
              "windowStrategy": "sentence-bounded-coverage-mean-v3",
              "tokenizerStrategy": "bert-wordpiece-v2",
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
            "Model: sentence-transformers/distiluse-base-multilingual-cased-v2\n" +
                "Source: $modelPackSource\n" +
                "Projection source: $projectionSource\n" +
                "Projection SHA256: $projectionSha256\n" +
                "Revision: $modelPackRevision\n" +
                "Model card: https://huggingface.co/sentence-transformers/distiluse-base-multilingual-cased-v2/blob/bfe45d0732ca50787611c0fe107ba278c7f3f889/README.md\n" +
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
    testImplementation(libs.junit4)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion")
}
