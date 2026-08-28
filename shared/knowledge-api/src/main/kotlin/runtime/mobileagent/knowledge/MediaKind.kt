// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

enum class SourceFormat {
    TEXT,
    MARKDOWN,
    IMAGE,
    PDF,
    OFFICE_ARCHIVE,
    UNKNOWN,
}

object MediaKind {
    const val MAX_IMPORT_BYTES: Long = 32L * 1024 * 1024

    fun detect(fileName: String, mediaType: String, header: ByteArray): SourceFormat {
        val name = fileName.lowercase()
        val mime = mediaType.lowercase()
        if (header.startsWith("%PDF".toByteArray(Charsets.US_ASCII))) return SourceFormat.PDF
        if (header.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) ||
            header.startsWith(byteArrayOf(0x50, 0x4B, 0x05, 0x06))
        ) {
            return SourceFormat.OFFICE_ARCHIVE
        }
        if (isImageHeader(header) || mime.startsWith("image/") ||
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".gif") || name.endsWith(".webp")
        ) {
            return SourceFormat.IMAGE
        }
        if (mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md") ||
            name.endsWith(".markdown")
        ) {
            return if (name.endsWith(".md") || name.endsWith(".markdown")) SourceFormat.MARKDOWN else SourceFormat.TEXT
        }
        if (looksLikeUtf8Text(header)) {
            return if (name.endsWith(".md") || name.endsWith(".markdown")) SourceFormat.MARKDOWN else SourceFormat.TEXT
        }
        return SourceFormat.UNKNOWN
    }

    fun isImage(format: SourceFormat): Boolean = format == SourceFormat.IMAGE

    private fun isImageHeader(header: ByteArray): Boolean {
        if (header.size < 12) return false
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return true
        if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte()) return true
        if (header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte()) return true
        val riff = header.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        val webp = header.copyOfRange(8, 12).toString(Charsets.US_ASCII)
        return riff == "RIFF" && webp == "WEBP"
    }

    private fun looksLikeUtf8Text(header: ByteArray): Boolean {
        if (header.isEmpty()) return false
        val sample = header.copyOf(minOf(header.size, 512))
        if (sample.any { it == 0.toByte() }) return false
        return runCatching { String(sample, Charsets.UTF_8) }.isSuccess
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
