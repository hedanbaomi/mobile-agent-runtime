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
        return OnnxModelPack(manifest, model, tokenizer)
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
        require(manifest.maxSequenceLength in 3..512) { "Unsupported model sequence length" }
        require(manifest.pooling.equals("mean", ignoreCase = true)) { "Unsupported model pooling" }
        require(manifest.normalize) { "The bundled model must use normalized vectors" }
        require(manifest.distance.equals("cosine", ignoreCase = true)) { "Unsupported model distance" }
        require(manifest.tokenizerType == "bert-wordpiece") { "Unsupported model tokenizer" }
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
        const val DEFAULT_MODEL_ID = "all-MiniLM-L6-v2"
        const val DEFAULT_ASSET_ROOT = "modelpacks/all-MiniLM-L6-v2"
        const val DEFAULT_DIMENSION = 384
        const val DEFAULT_REVISION = "1110a243fdf4706b3f48f1d95db1a4f5529b4d41"
        const val DEFAULT_SPACE_ID = "onnx:all-MiniLM-L6-v2@1110a243fdf4706b3f48f1d95db1a4f5529b4d41:d384:cosine"
        const val DEFAULT_MODEL_SHA256 = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"
        const val DEFAULT_TOKENIZER_SHA256 = "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"
        const val DEFAULT_SOURCE_PREFIX = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/"
        const val COPY_BUFFER_SIZE = 64 * 1024
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}
