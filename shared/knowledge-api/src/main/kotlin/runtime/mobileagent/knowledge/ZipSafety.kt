// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayInputStream
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Returns a safe, NFC-normalized logical archive path, or null when the name
 * could address a path outside the import root.  Dot checks are segment based
 * so ordinary filenames such as `Hes.+theog..pdf` remain valid.
 */
internal fun normalizeZipEntryPath(name: String): String? {
    val normalized = Normalizer.normalize(name.replace('\\', '/'), Normalizer.Form.NFC)
    if (normalized.isBlank() || normalized.any { Character.isISOControl(it) }) return null
    if (normalized.startsWith('/')) return null
    val segments = normalized.split('/')
    if (segments.any { it == "." || it == ".." }) return null
    // Keep rejecting colons anywhere in an archive name: this covers drive
    // prefixes and Windows alternate data streams on the extraction side.
    if (normalized.contains(':')) return null
    return normalized
}

object ZipSafety {
    const val MAX_ENTRIES = 256
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 48L * 1024 * 1024

    fun inspect(bytes: ByteArray): ZipInspection {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return ZipInspection(ok = false, reason = "Not a ZIP archive")
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                count += 1
                if (count > MAX_ENTRIES) return ZipInspection(false, "Archive has too many entries")
                val rawName = entry.name
                val name = normalizeZipEntryPath(rawName)
                if (name == null) {
                    return ZipInspection(false, "Archive path is not allowed: ${rawName.replace('\\', '/')}")
                }
                val buf = ByteArray(8192)
                var size = 0L
                while (true) {
                    val n = zip.read(buf)
                    if (n <= 0) break
                    size += n
                    total += n
                    if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        return ZipInspection(false, "Archive uncompressed size exceeds the limit")
                    }
                }
            }
        }
        return ZipInspection(ok = true, reason = "Archive inspected in memory; it was not extracted")
    }
}

data class ZipInspection(val ok: Boolean, val reason: String)
