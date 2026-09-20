// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Copies the packaged model pack into an app-private no-backup directory and
 * verifies it before returning a usable ONNX handle.  The hard-coded pins are
 * part of the application build and protect against a changed generated
 * manifest being treated as a new embedding space accidentally.
 */
class AndroidModelPackLoader(
    private val context: Context,
    private val assetRoot: String = DEFAULT_ASSET_ROOT,
) {
    fun load(packId: String = DEFAULT_MODEL_ID): OnnxModelPack {
        require(packId == DEFAULT_MODEL_ID) { "Unknown bundled model pack: $packId" }
        val manifest = context.assets.open("$assetRoot/manifest.json").use { input ->
            Json.decodeFromString<ModelPackManifest>(input.reader(Charsets.UTF_8).readText())
        }
        verifyManifest(manifest)

        val destination = File(context.noBackupFilesDir, "mobileagent/modelpacks/${manifest.id}/${manifest.sha256}")
        if (!destination.exists()) destination.mkdirs()
        val model = copyAndVerify(
            assetPath = "$assetRoot/${safeAssetName(manifest.modelFile)}",
            destination = File(destination, safeAssetName(manifest.modelFile)),
            expectedSha256 = manifest.sha256,
        )
        val tokenizer = copyAndVerify(
            assetPath = "$assetRoot/${safeAssetName(manifest.tokenizerFile)}",
            destination = File(destination, safeAssetName(manifest.tokenizerFile)),
            expectedSha256 = manifest.tokenizerSha256,
        )
        val projection = copyAndVerify("$assetRoot/dense.safetensors", File(destination, "dense.safetensors"), DEFAULT_PROJECTION_SHA256)
        return OnnxModelPack(manifest, model, tokenizer, projection)
    }

    private fun copyAndVerify(assetPath: String, destination: File, expectedSha256: String): File {
        require(expectedSha256.matches(SHA256_PATTERN)) { "Invalid model pack hash" }
        val existingHash = destination.takeIf { it.isFile }?.let(::sha256)
        if (existingHash != expectedSha256.lowercase()) {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, ".${destination.name}.tmp")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(sha256(temporary) == expectedSha256.lowercase()) {
                temporary.delete()
                "Bundled model pack hash mismatch for ${destination.name}"
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
        return destination
    }

    private fun verifyManifest(manifest: ModelPackManifest) {
        require(manifest.id == DEFAULT_MODEL_ID) { "Unexpected bundled model id" }
        require(manifest.dimension == DEFAULT_DIMENSION) { "Unexpected model dimension" }
        require(manifest.spaceId == DEFAULT_SPACE_ID) { "Unexpected embedding space id" }
        require(manifest.sha256.equals(DEFAULT_MODEL_SHA256, ignoreCase = true)) { "Unexpected model hash" }
        require(manifest.tokenizerSha256.equals(DEFAULT_TOKENIZER_SHA256, ignoreCase = true)) {
            "Unexpected tokenizer hash"
        }
        require(manifest.revision == DEFAULT_REVISION) { "Unexpected model revision" }
        require(manifest.license.equals("Apache-2.0", ignoreCase = true)) { "Model license is not Apache-2.0" }
        require(manifest.maxSequenceLength == 128) { "Unsupported model sequence length" }
        require(manifest.pooling.equals("mean", ignoreCase = true)) { "Unsupported model pooling" }
        require(manifest.hiddenDimension == 768 && manifest.projectionFile == "dense.safetensors")
        require(manifest.projectionSha256 == DEFAULT_PROJECTION_SHA256)
        require(manifest.windowStrategy == "sentence-bounded-coverage-mean-v3")
        require(manifest.outputName == "last_hidden_state")
        require(manifest.normalize) { "The bundled model must use normalized vectors" }
        require(manifest.distance.equals("cosine", ignoreCase = true)) { "Unsupported model distance" }
        require(manifest.tokenizerType == "bert-wordpiece") { "Unsupported model tokenizer" }
        require(manifest.tokenizerStrategy == "bert-wordpiece-v2") { "Unsupported tokenizer strategy" }
        require(manifest.spaceId.endsWith(":t${manifest.tokenizerSha256}:p${manifest.projectionSha256}"))
        require(manifest.source.startsWith(DEFAULT_SOURCE_PREFIX)) { "Untrusted model source" }
    }

    private fun safeAssetName(name: String): String {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != "..") {
            "Model pack assets must be plain filenames"
        }
        return name
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val LEGACY_LOCAL_SPACE_IDS = setOf("onnx:all-MiniLM-L6-v2@1110a243fdf4706b3f48f1d95db1a4f5529b4d41:d384:cosine")
        const val DEFAULT_PROJECTION_SHA256 = "0a21b1ce908e772ebf09f93c20ca09524c32706e9918d9c0169a3f0663b191ed"
        const val DEFAULT_MODEL_ID = "distiluse-base-multilingual-cased-v2"
        const val DEFAULT_ASSET_ROOT = "modelpacks/distiluse-base-multilingual-cased-v2"
        const val DEFAULT_DIMENSION = 512
        const val DEFAULT_REVISION = "cad454171d918d9873a2701ba245054b6c1760dd"
        const val DEFAULT_SPACE_ID = "onnx:distiluse-base-multilingual-cased-v2@cad454171d918d9873a2701ba245054b6c1760dd:int8:d512:cosine:wp-v2:mean-dense-tanh:sentence126-bounded-mean-v3:tbf1b59b7b11c95f194f51708d918eea378e09d05f84c0e1656dc5180e8117088:p0a21b1ce908e772ebf09f93c20ca09524c32706e9918d9c0169a3f0663b191ed"
        const val DEFAULT_MODEL_SHA256 = "1724c10ba3b33b58afb6ce73bbf3e0528921bd4d4e1bf5a569b6d73f234e72bf"
        const val DEFAULT_TOKENIZER_SHA256 = "bf1b59b7b11c95f194f51708d918eea378e09d05f84c0e1656dc5180e8117088"
        const val DEFAULT_SOURCE_PREFIX = "https://huggingface.co/Xenova/distiluse-base-multilingual-cased-v2/"
        const val COPY_BUFFER_SIZE = 64 * 1024
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}
