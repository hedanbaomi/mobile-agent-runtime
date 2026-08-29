// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.zip.ZipInputStream

/**
 * Safety gate for knowledge-library ZIP datasets. Office packages stay on
 * [ZipSafety] + [OfficeParser]; this type must not be used for DOCX/EPUB.
 */
object KnowledgeArchive {
    const val MAX_ENTRIES = 500
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val EOCD = 0x06054B50
    private const val CENTRAL = 0x02014B50
    private const val ENCRYPT_FLAG = 0x1

    fun isOfficePackage(fileName: String, bytes: ByteArray): Boolean {
        val name = fileName.lowercase()
        if (name.endsWith(".docx") || name.endsWith(".epub") || name.endsWith(".odt")) return true
        return runCatching {
            val names = centralNames(bytes).map { it.lowercase() }
            names.any { it == "word/document.xml" || it.endsWith("/word/document.xml") } ||
                names.any { it == "meta-inf/container.xml" || it == "mimetype" }
        }.getOrDefault(false)
    }

    fun inspect(bytes: ByteArray): KnowledgeArchiveSummary {
        if (bytes.size < 22 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return KnowledgeArchiveSummary(false, "Not a ZIP archive")
        }
        val names = runCatching { centralNames(bytes) }.getOrElse {
            return KnowledgeArchiveSummary(false, it.message ?: "ZIP central directory is invalid")
        }
        if (names.size > MAX_ENTRIES) {
            return KnowledgeArchiveSummary(false, "Archive has too many entries")
        }
        val seen = HashSet<String>()
        names.forEach { raw ->
            val normalized = normalizePath(raw)
            if (normalized == null) {
                return KnowledgeArchiveSummary(false, "Archive path is not allowed: $raw")
            }
            val key = normalized.lowercase()
            if (!seen.add(key)) {
                return KnowledgeArchiveSummary(false, "Archive has duplicate paths")
            }
            if (looksLikeNestedArchive(normalized)) {
                return KnowledgeArchiveSummary(false, "Nested archives are not allowed: $normalized")
            }
        }
        return streamEntries(bytes)
    }

    fun extract(bytes: ByteArray): List<Pair<KnowledgeArchiveEntry, ByteArray>> {
        val summary = inspect(bytes)
        check(summary.ok) { summary.reason }
        val out = ArrayList<Pair<KnowledgeArchiveEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = normalizePath(entry.name) ?: continue
                val payload = readBounded(zip, MAX_ENTRY_BYTES) ?: continue
                val format = MediaKind.detect(name, "", payload.copyOf(minOf(payload.size, 64)))
                if (format == SourceFormat.UNKNOWN || format == SourceFormat.KNOWLEDGE_ARCHIVE) continue
                if (format == SourceFormat.OFFICE_ARCHIVE && !isOfficePackage(name, payload)) continue
                out.add(KnowledgeArchiveEntry(name, payload.size.toLong(), format) to payload)
            }
        }
        return out
    }

    private fun streamEntries(bytes: ByteArray): KnowledgeArchiveSummary {
        val entries = ArrayList<KnowledgeArchiveEntry>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                count += 1
                if (count > MAX_ENTRIES) return KnowledgeArchiveSummary(false, "Archive has too many entries")
                val name = normalizePath(entry.name)
                    ?: return KnowledgeArchiveSummary(false, "Archive path is not allowed: ${entry.name}")
                val payload = readBounded(zip, MAX_ENTRY_BYTES)
                    ?: return KnowledgeArchiveSummary(false, "Archive uncompressed size exceeds the limit")
                total += payload.size
                if (total > MAX_TOTAL_BYTES) {
                    return KnowledgeArchiveSummary(false, "Archive uncompressed size exceeds the limit")
                }
                if (payload.size >= 4 && payload[0] == 0x50.toByte() && payload[1] == 0x4B.toByte() &&
                    !isOfficePackage(name, payload)
                ) {
                    return KnowledgeArchiveSummary(false, "Nested archives are not allowed: $name")
                }
                val format = MediaKind.detect(name, "", payload.copyOf(minOf(payload.size, 64)))
                if (format == SourceFormat.UNKNOWN || format == SourceFormat.KNOWLEDGE_ARCHIVE) {
                    continue
                }
                entries += KnowledgeArchiveEntry(name, payload.size.toLong(), format)
            }
        }
        if (entries.isEmpty()) return KnowledgeArchiveSummary(false, "Archive has no importable files")
        return KnowledgeArchiveSummary(true, "Archive inspected", entries, total)
    }

    private fun centralNames(bytes: ByteArray): List<String> {
        val eocd = findEocd(bytes) ?: error("ZIP end record is missing or malformed")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val disk = buf.getShort(eocd + 4).toInt() and 0xFFFF
        val centralDisk = buf.getShort(eocd + 6).toInt() and 0xFFFF
        val entries = buf.getShort(eocd + 10).toInt() and 0xFFFF
        val centralSize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val centralOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        if (disk != 0 || centralDisk != 0) error("Multi-disk ZIP archives are not supported")
        if (entries == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
            error("ZIP64 archives are not supported")
        }
        val centralEnd = centralOffset + centralSize
        if (centralOffset > eocd || centralEnd != eocd.toLong()) error("ZIP central directory bounds are invalid")
        val names = ArrayList<String>(entries)
        var cursor = centralOffset.toInt()
        repeat(entries) {
            if (cursor + 46 > centralEnd.toInt() || buf.getInt(cursor) != CENTRAL) {
                error("ZIP central directory record is missing or malformed")
            }
            val flags = buf.getShort(cursor + 8).toInt() and 0xFFFF
            if (flags and ENCRYPT_FLAG != 0) error("Encrypted ZIP entries are not allowed")
            val nameLength = buf.getShort(cursor + 28).toInt() and 0xFFFF
            val extraLength = buf.getShort(cursor + 30).toInt() and 0xFFFF
            val commentLength = buf.getShort(cursor + 32).toInt() and 0xFFFF
            val nameStart = cursor + 46
            val name = String(bytes, nameStart, nameLength, Charsets.UTF_8)
            names += name
            cursor = nameStart + nameLength + extraLength + commentLength
        }
        return names
    }

    private fun findEocd(bytes: ByteArray): Int? {
        val min = (bytes.size - 22 - 65535).coerceAtLeast(0)
        var offset = bytes.size - 22
        while (offset >= min) {
            if (leInt(bytes, offset) == EOCD) return offset
            offset -= 1
        }
        return null
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun normalizePath(name: String): String? {
        val replaced = name.replace('\\', '/')
        val nfc = Normalizer.normalize(replaced, Normalizer.Form.NFC)
        if (nfc.contains("..") || nfc.startsWith("/") || nfc.contains(":") || nfc.contains('\u0000')) return null
        if (nfc.startsWith("./") || nfc.contains("/./")) return null
        return nfc.trimStart('/')
    }

    private fun looksLikeNestedArchive(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")
    }

    private fun readBounded(zip: ZipInputStream, max: Long): ByteArray? {
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var size = 0L
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            size += n
            if (size > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
