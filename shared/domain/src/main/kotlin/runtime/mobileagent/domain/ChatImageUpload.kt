// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.io.InputStream
import java.io.ByteArrayOutputStream

/** App-owned bytes from a foreground picker. No URI, path or remote URL crosses this boundary. */
class ChatImageUpload(bytes: ByteArray, val mediaType: String) {
    private val content: ByteArray
    val byteLength: Int get() = content.size
    fun bytes(): ByteArray = content.copyOf()

    init {
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "INPUT_OVERFLOW: chat image must be at most 2 MiB" }
        require(detectedType(bytes) == mediaType) { "CONFIG_INVALID: unsupported or mismatched chat image format" }
        content = bytes.copyOf()
    }

    override fun toString(): String = "ChatImageUpload($mediaType, $byteLength bytes)"

    companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_IMAGES_PER_MESSAGE = 8
        const val ASSET_PREFIX = "chat-upload:"

        /** Bounded intake; the picker retains stream ownership. Never reads the whole file first. */
        fun read(input: InputStream, mediaType: String): ChatImageUpload {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_IMAGE_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_IMAGE_BYTES + 1 - output.size()))
                if (count < 0) break
                if (count == 0) {
                    val next = input.read()
                    if (next < 0) break
                    output.write(next)
                } else output.write(buffer, 0, count)
            }
            return ChatImageUpload(output.toByteArray(), mediaType)
        }

        private fun detectedType(bytes: ByteArray): String? {
            fun prefix(vararg values: Int) = bytes.size >= values.size &&
                values.indices.all { bytes[it].toInt() and 255 == values[it] }
            return when {
                prefix(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
                prefix(255, 216, 255) -> "image/jpeg"
                bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
                bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> null
            }
        }
    }
}
