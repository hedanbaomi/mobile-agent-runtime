// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

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
                val name = entry.name.replace('\\', '/')
                if (name.contains("..") || name.startsWith("/") || name.contains(":")) {
                    return ZipInspection(false, "Archive path is not allowed: $name")
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
