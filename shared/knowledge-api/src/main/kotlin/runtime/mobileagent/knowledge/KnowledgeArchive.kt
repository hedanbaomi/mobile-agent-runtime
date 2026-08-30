// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
    private const val LOCAL = 0x04034B50
    private const val ENCRYPT_FLAG = 0x1
    private const val DATA_DESCRIPTOR_FLAG = 0x8
    private const val MAX_COMPRESSION_RATIO = 200L
    private const val UNIX_FILE_TYPE_MASK = 0xF000
    private const val UNIX_SYMLINK = 0xA000
    private const val MAX_NAME_BYTES = 4096

    private data class CentralEntry(
        val rawName: ByteArray,
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Int,
        val directory: Boolean,
    )

    fun isOfficePackage(fileName: String, bytes: ByteArray): Boolean {
        val name = fileName.lowercase()
        if (name.endsWith(".docx") || name.endsWith(".epub") || name.endsWith(".odt")) return true
        return runCatching {
            val names = centralEntries(bytes).map { it.name.lowercase() }
            names.any { it == "word/document.xml" || it.endsWith("/word/document.xml") } ||
                names.any { it == "meta-inf/container.xml" || it == "mimetype" }
        }.getOrDefault(false)
    }

    fun isOfficePackage(fileName: String, file: File): Boolean {
        val name = fileName.lowercase()
        if (name.endsWith(".docx") || name.endsWith(".epub") || name.endsWith(".odt")) return true
        return runCatching {
            val names = centralEntries(file).map { it.name.lowercase() }
            names.any { it == "word/document.xml" || it.endsWith("/word/document.xml") } ||
                names.any { it == "meta-inf/container.xml" || it == "mimetype" }
        }.getOrDefault(false)
    }

    fun inspect(bytes: ByteArray): KnowledgeArchiveSummary {
        if (bytes.size < 22 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return KnowledgeArchiveSummary(false, "Not a ZIP archive")
        }
        return runCatching { streamEntries(bytes, centralEntries(bytes)) }
            .getOrElse { KnowledgeArchiveSummary(false, it.message ?: "ZIP archive is invalid") }
    }

    /**
     * Scan and extract one entry at a time.  The callback owns the payload
     * only for the duration of the call; this avoids the old inspect-then-
     * extract path retaining every entry in a second in-memory list.
     *
     * The central directory is checked before the callback is invoked, so a
     * path traversal, duplicate, encrypted entry, or nested archive never
     * causes a partially accepted entry to be handed to the repository.
     */
    fun forEachEntry(
        bytes: ByteArray,
        onEntry: (KnowledgeArchiveEntry, ByteArray) -> Unit,
    ): KnowledgeArchiveSummary {
        if (bytes.size < 22 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return KnowledgeArchiveSummary(false, "Not a ZIP archive")
        }
        val entries = runCatching { centralEntries(bytes) }.getOrElse {
            return KnowledgeArchiveSummary(false, it.message ?: "ZIP central directory is invalid")
        }
        return streamEntries(bytes, entries, onEntry)
    }

    /**
     * File-backed archive path used by Android URI staging. The complete ZIP
     * never enters the heap: only the bounded central names and one bounded
     * uncompressed entry are resident at a time.
     */
    fun forEachEntry(
        file: File,
        onEntry: (KnowledgeArchiveEntry, ByteArray) -> Unit,
    ): KnowledgeArchiveSummary {
        if (!file.isFile || file.length() < 22L || file.length() > MAX_TOTAL_BYTES) {
            return KnowledgeArchiveSummary(false, "Not a bounded ZIP archive")
        }
        val central = runCatching { centralEntries(file) }.getOrElse {
            return KnowledgeArchiveSummary(false, it.message ?: "ZIP central directory is invalid")
        }
        val entries = ArrayList<KnowledgeArchiveEntry>()
        var total = 0L
        return runCatching {
            ZipFile(file).use { zip ->
                val locals = zip.entries()
                var index = 0
                while (locals.hasMoreElements()) {
                    if (index >= central.size) error("ZIP local entries exceed the central directory")
                    val entry = locals.nextElement()
                    val expected = central[index++]
                    val name = normalizePath(entry.name) ?: error("Archive path is not allowed: ${entry.name}")
                    if (name != normalizePath(expected.name) || entry.method != expected.method ||
                        entry.isDirectory != expected.directory || entry.crc != expected.crc ||
                        entry.size != expected.uncompressedSize || entry.compressedSize != expected.compressedSize
                    ) error("ZIP central/local entry mismatch")
                    if (entry.isDirectory) continue
                    val payload = zip.getInputStream(entry).use { input ->
                        readBounded(input, MAX_ENTRY_BYTES)
                            ?: error("Archive uncompressed size exceeds the limit")
                    }
                    total += payload.size
                    if (total > MAX_TOTAL_BYTES) error("Archive uncompressed size exceeds the limit")
                    if (payload.size >= 4 && payload[0] == 0x50.toByte() && payload[1] == 0x4B.toByte() &&
                        !isOfficePackage(name, payload)
                    ) error("Nested archives are not allowed: $name")
                    val format = MediaKind.detect(name, "", payload.copyOf(minOf(payload.size, 64)))
                    if (format == SourceFormat.UNKNOWN || format == SourceFormat.KNOWLEDGE_ARCHIVE) continue
                    val metadata = KnowledgeArchiveEntry(name, payload.size.toLong(), format)
                    entries += metadata
                    onEntry(metadata, payload)
                }
                if (index != central.size) error("ZIP central directory has missing local entries")
            }
            if (entries.isEmpty()) KnowledgeArchiveSummary(false, "Archive has no importable files")
            else KnowledgeArchiveSummary(true, "Archive inspected", entries, total)
        }.getOrElse { KnowledgeArchiveSummary(false, it.message ?: "ZIP archive is invalid") }
    }

    fun extract(bytes: ByteArray): List<Pair<KnowledgeArchiveEntry, ByteArray>> {
        val out = ArrayList<Pair<KnowledgeArchiveEntry, ByteArray>>()
        val extracted = forEachEntry(bytes) { entry, payload -> out += entry to payload.copyOf() }
        check(extracted.ok) { extracted.reason }
        return out
    }

    private fun streamEntries(
        bytes: ByteArray,
        central: List<CentralEntry>,
        onEntry: (KnowledgeArchiveEntry, ByteArray) -> Unit = { _, _ -> },
    ): KnowledgeArchiveSummary {
        if (central.size > MAX_ENTRIES) {
            return KnowledgeArchiveSummary(false, "Archive has too many entries")
        }
        val seen = HashSet<String>()
        central.forEach { metadata ->
            val normalized = normalizePath(metadata.name)
                ?: return KnowledgeArchiveSummary(false, "Archive path is not allowed: ${metadata.name}")
            val key = normalized.lowercase()
            if (!seen.add(key)) {
                return KnowledgeArchiveSummary(false, "Archive has duplicate paths")
            }
            if (looksLikeNestedArchive(normalized)) {
                return KnowledgeArchiveSummary(false, "Nested archives are not allowed: $normalized")
            }
        }
        val entries = ArrayList<KnowledgeArchiveEntry>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (count >= central.size) return KnowledgeArchiveSummary(false, "ZIP local entries exceed the central directory")
                val expected = central[count++]
                val name = normalizePath(entry.name)
                    ?: return KnowledgeArchiveSummary(false, "Archive path is not allowed: ${entry.name}")
                if (name != normalizePath(expected.name) || entry.method != expected.method || entry.isDirectory != expected.directory) {
                    return KnowledgeArchiveSummary(false, "ZIP central/local entry mismatch")
                }
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val payload = readBounded(zip, MAX_ENTRY_BYTES)
                    ?: return KnowledgeArchiveSummary(false, "Archive uncompressed size exceeds the limit")
                zip.closeEntry()
                if (entry.crc != expected.crc || entry.size != expected.uncompressedSize ||
                    entry.compressedSize != expected.compressedSize
                ) {
                    return KnowledgeArchiveSummary(false, "ZIP central/local size or CRC mismatch")
                }
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
                val metadata = KnowledgeArchiveEntry(name, payload.size.toLong(), format)
                entries += metadata
                onEntry(metadata, payload)
            }
            if (count != central.size) return KnowledgeArchiveSummary(false, "ZIP central directory has missing local entries")
        }
        if (entries.isEmpty()) return KnowledgeArchiveSummary(false, "Archive has no importable files")
        return KnowledgeArchiveSummary(true, "Archive inspected", entries, total)
    }

    private fun centralEntries(bytes: ByteArray): List<CentralEntry> {
        val eocd = findEocd(bytes) ?: error("ZIP end record is missing or malformed")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val disk = buf.getShort(eocd + 4).toInt() and 0xFFFF
        val centralDisk = buf.getShort(eocd + 6).toInt() and 0xFFFF
        val diskEntries = buf.getShort(eocd + 8).toInt() and 0xFFFF
        val entryCount = buf.getShort(eocd + 10).toInt() and 0xFFFF
        val centralSize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val centralOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        val commentLength = buf.getShort(eocd + 20).toInt() and 0xFFFF
        if (disk != 0 || centralDisk != 0) error("Multi-disk ZIP archives are not supported")
        if (diskEntries != entryCount) error("ZIP entry counts do not match")
        if (entryCount == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
            error("ZIP64 archives are not supported")
        }
        if (entryCount > MAX_ENTRIES) error("Archive has too many entries")
        if (eocd + 22L + commentLength != bytes.size.toLong()) error("ZIP end record comment is malformed")
        val centralEnd = centralOffset + centralSize
        if (centralOffset > eocd || centralEnd != eocd.toLong()) error("ZIP central directory bounds are invalid")
        val entries = ArrayList<CentralEntry>(entryCount)
        var cursor = centralOffset.toInt()
        var totalUncompressed = 0L
        repeat(entryCount) {
            if (cursor + 46 > centralEnd.toInt() || buf.getInt(cursor) != CENTRAL) {
                error("ZIP central directory record is missing or malformed")
            }
            val versionMadeBy = buf.getShort(cursor + 4).toInt() and 0xFFFF
            val flags = buf.getShort(cursor + 8).toInt() and 0xFFFF
            if (flags and ENCRYPT_FLAG != 0) error("Encrypted ZIP entries are not allowed")
            val method = buf.getShort(cursor + 10).toInt() and 0xFFFF
            if (method !in setOf(ZipEntry.STORED, ZipEntry.DEFLATED)) error("ZIP compression method is not supported")
            val crc = buf.getInt(cursor + 16).toLong() and 0xFFFFFFFFL
            val compressedSize = buf.getInt(cursor + 20).toLong() and 0xFFFFFFFFL
            val uncompressedSize = buf.getInt(cursor + 24).toLong() and 0xFFFFFFFFL
            val nameLength = buf.getShort(cursor + 28).toInt() and 0xFFFF
            val extraLength = buf.getShort(cursor + 30).toInt() and 0xFFFF
            val commentLength = buf.getShort(cursor + 32).toInt() and 0xFFFF
            val nameStart = cursor + 46
            val next = nameStart.toLong() + nameLength + extraLength + commentLength
            if (nameLength == 0 || next > centralEnd) error("ZIP central directory entry bounds are invalid")
            val rawName = bytes.copyOfRange(nameStart, nameStart + nameLength)
            val name = String(rawName, Charsets.UTF_8)
            val externalAttributes = buf.getInt(cursor + 38).toLong() and 0xFFFFFFFFL
            val madeByUnix = (versionMadeBy ushr 8) == 3
            val unixMode = ((externalAttributes ushr 16) and 0xFFFF).toInt()
            if (madeByUnix && unixMode and UNIX_FILE_TYPE_MASK == UNIX_SYMLINK) {
                error("ZIP links are not allowed: $name")
            }
            val directory = name.endsWith('/')
            if (!directory) {
                if (uncompressedSize > MAX_ENTRY_BYTES) error("Archive entry exceeds the size limit")
                if (uncompressedSize > 0L && compressedSize == 0L) error("Archive compression ratio exceeds the limit")
                if (compressedSize > 0L && uncompressedSize / compressedSize.coerceAtLeast(1L) > MAX_COMPRESSION_RATIO) {
                    error("Archive compression ratio exceeds the limit")
                }
                totalUncompressed += uncompressedSize
                if (totalUncompressed > MAX_TOTAL_BYTES) error("Archive uncompressed size exceeds the limit")
            }
            val localOffset = buf.getInt(cursor + 42).toLong() and 0xFFFFFFFFL
            if (localOffset > Int.MAX_VALUE || localOffset + 30L > centralOffset) error("ZIP local header offset is invalid")
            validateLocalHeader(bytes, buf, localOffset.toInt(), rawName, flags, method, crc, compressedSize, uncompressedSize)
            entries += CentralEntry(rawName, name, flags, method, crc, compressedSize, uncompressedSize, localOffset.toInt(), directory)
            cursor = nameStart + nameLength + extraLength + commentLength
        }
        if (cursor != centralEnd.toInt()) error("ZIP central directory size is inconsistent")
        return entries
    }

    private fun centralEntries(file: File): List<CentralEntry> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val tailSize = minOf(length, 22L + 65535L).toInt()
        val tail = ByteArray(tailSize)
        raf.seek(length - tailSize)
        raf.readFully(tail)
        val tailEocd = findEocd(tail) ?: error("ZIP end record is missing or malformed")
        val eocd = length - tailSize + tailEocd
        fun u16(offset: Long): Int {
            val bytes = ByteArray(2)
            raf.seek(offset); raf.readFully(bytes)
            return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        }
        fun u32(offset: Long): Long {
            val bytes = ByteArray(4)
            raf.seek(offset); raf.readFully(bytes)
            return leInt(bytes, 0).toLong() and 0xFFFFFFFFL
        }
        val disk = u16(eocd + 4)
        val centralDisk = u16(eocd + 6)
        val diskEntries = u16(eocd + 8)
        val entryCount = u16(eocd + 10)
        val centralSize = u32(eocd + 12)
        val centralOffset = u32(eocd + 16)
        val commentLength = u16(eocd + 20)
        if (disk != 0 || centralDisk != 0 || diskEntries != entryCount) error("Multi-disk ZIP archives are not supported")
        if (entryCount == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) error("ZIP64 archives are not supported")
        if (entryCount > MAX_ENTRIES) error("Archive has too many entries")
        if (eocd + 22L + commentLength != length || centralOffset + centralSize != eocd) error("ZIP central directory bounds are invalid")
        val entries = ArrayList<CentralEntry>(entryCount)
        var cursor = centralOffset
        var totalUncompressed = 0L
        repeat(entryCount) {
            val fixed = ByteArray(46)
            raf.seek(cursor); raf.readFully(fixed)
            if (leInt(fixed, 0) != CENTRAL) error("ZIP central directory record is missing or malformed")
            val versionMadeBy = leShort(fixed, 4)
            val flags = leShort(fixed, 8)
            if (flags and ENCRYPT_FLAG != 0) error("Encrypted ZIP entries are not allowed")
            val method = leShort(fixed, 10)
            if (method !in setOf(ZipEntry.STORED, ZipEntry.DEFLATED)) error("ZIP compression method is not supported")
            val crc = leInt(fixed, 16).toLong() and 0xFFFFFFFFL
            val compressedSize = leInt(fixed, 20).toLong() and 0xFFFFFFFFL
            val uncompressedSize = leInt(fixed, 24).toLong() and 0xFFFFFFFFL
            val nameLength = leShort(fixed, 28)
            val extraLength = leShort(fixed, 30)
            val entryCommentLength = leShort(fixed, 32)
            if (nameLength !in 1..MAX_NAME_BYTES) error("ZIP entry name is too long")
            val next = cursor + 46L + nameLength + extraLength + entryCommentLength
            if (next > eocd) error("ZIP central directory entry bounds are invalid")
            val rawName = ByteArray(nameLength)
            raf.readFully(rawName)
            val name = String(rawName, Charsets.UTF_8)
            val externalAttributes = leInt(fixed, 38).toLong() and 0xFFFFFFFFL
            val madeByUnix = (versionMadeBy ushr 8) == 3
            val unixMode = ((externalAttributes ushr 16) and 0xFFFF).toInt()
            if (madeByUnix && unixMode and UNIX_FILE_TYPE_MASK == UNIX_SYMLINK) error("ZIP links are not allowed: $name")
            val directory = name.endsWith('/')
            if (!directory) {
                if (uncompressedSize > MAX_ENTRY_BYTES) error("Archive entry exceeds the size limit")
                if (uncompressedSize > 0L && compressedSize == 0L) error("Archive compression ratio exceeds the limit")
                if (compressedSize > 0L && uncompressedSize / compressedSize.coerceAtLeast(1L) > MAX_COMPRESSION_RATIO) {
                    error("Archive compression ratio exceeds the limit")
                }
                totalUncompressed += uncompressedSize
                if (totalUncompressed > MAX_TOTAL_BYTES) error("Archive uncompressed size exceeds the limit")
            }
            val localOffset = leInt(fixed, 42).toLong() and 0xFFFFFFFFL
            if (localOffset + 30L > centralOffset) error("ZIP local header offset is invalid")
            validateLocalHeader(raf, localOffset, rawName, flags, method, crc, compressedSize, uncompressedSize)
            entries += CentralEntry(rawName, name, flags, method, crc, compressedSize, uncompressedSize, localOffset.toInt(), directory)
            cursor = next
        }
        if (cursor != eocd) error("ZIP central directory size is inconsistent")
        val seen = HashSet<String>()
        entries.forEach { metadata ->
            val normalized = normalizePath(metadata.name) ?: error("Archive path is not allowed: ${metadata.name}")
            if (!seen.add(normalized.lowercase())) error("Archive has duplicate paths")
            if (looksLikeNestedArchive(normalized)) error("Nested archives are not allowed: $normalized")
        }
        entries
    }

    private fun validateLocalHeader(
        bytes: ByteArray,
        buf: ByteBuffer,
        offset: Int,
        centralName: ByteArray,
        flags: Int,
        method: Int,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
    ) {
        if (buf.getInt(offset) != LOCAL) error("ZIP local header is missing or malformed")
        val localFlags = buf.getShort(offset + 6).toInt() and 0xFFFF
        val localMethod = buf.getShort(offset + 8).toInt() and 0xFFFF
        val localNameLength = buf.getShort(offset + 26).toInt() and 0xFFFF
        val localExtraLength = buf.getShort(offset + 28).toInt() and 0xFFFF
        val nameStart = offset + 30
        if (nameStart.toLong() + localNameLength + localExtraLength > bytes.size.toLong()) {
            error("ZIP local header bounds are invalid")
        }
        val localName = bytes.copyOfRange(nameStart, nameStart + localNameLength)
        if (!localName.contentEquals(centralName) || localFlags != flags || localMethod != method) {
            error("ZIP central/local entry mismatch")
        }
        if (flags and DATA_DESCRIPTOR_FLAG == 0) {
            val localCrc = buf.getInt(offset + 14).toLong() and 0xFFFFFFFFL
            val localCompressed = buf.getInt(offset + 18).toLong() and 0xFFFFFFFFL
            val localUncompressed = buf.getInt(offset + 22).toLong() and 0xFFFFFFFFL
            if (localCrc != crc || localCompressed != compressedSize || localUncompressed != uncompressedSize) {
                error("ZIP central/local size or CRC mismatch")
            }
        }
    }

    private fun validateLocalHeader(
        raf: RandomAccessFile,
        offset: Long,
        centralName: ByteArray,
        flags: Int,
        method: Int,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
    ) {
        val fixed = ByteArray(30)
        raf.seek(offset); raf.readFully(fixed)
        if (leInt(fixed, 0) != LOCAL) error("ZIP local header is missing or malformed")
        val localFlags = leShort(fixed, 6)
        val localMethod = leShort(fixed, 8)
        val localNameLength = leShort(fixed, 26)
        val localExtraLength = leShort(fixed, 28)
        if (localNameLength != centralName.size || offset + 30L + localNameLength + localExtraLength > raf.length()) {
            error("ZIP local header bounds are invalid")
        }
        val localName = ByteArray(localNameLength)
        raf.readFully(localName)
        if (!localName.contentEquals(centralName) || localFlags != flags || localMethod != method) error("ZIP central/local entry mismatch")
        if (flags and DATA_DESCRIPTOR_FLAG == 0) {
            val localCrc = leInt(fixed, 14).toLong() and 0xFFFFFFFFL
            val localCompressed = leInt(fixed, 18).toLong() and 0xFFFFFFFFL
            val localUncompressed = leInt(fixed, 22).toLong() and 0xFFFFFFFFL
            if (localCrc != crc || localCompressed != compressedSize || localUncompressed != uncompressedSize) {
                error("ZIP central/local size or CRC mismatch")
            }
        }
    }

    private fun findEocd(bytes: ByteArray): Int? {
        val min = (bytes.size - 22 - 65535).coerceAtLeast(0)
        var offset = bytes.size - 22
        while (offset >= min) {
            if (leInt(bytes, offset) == EOCD && offset + 22 <= bytes.size) {
                val commentLength = (bytes[offset + 20].toInt() and 0xFF) or
                    ((bytes[offset + 21].toInt() and 0xFF) shl 8)
                if (offset + 22 + commentLength == bytes.size) return offset
            }
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

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun normalizePath(name: String): String? {
        return normalizeZipEntryPath(name)
    }

    private fun looksLikeNestedArchive(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")
    }

    private fun readBounded(zip: ZipInputStream, max: Long): ByteArray? {
        return readBounded(zip as InputStream, max)
    }

    private fun readBounded(input: InputStream, max: Long): ByteArray? {
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var size = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            size += n
            if (size > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
