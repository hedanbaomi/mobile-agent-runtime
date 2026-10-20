// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.worldbook

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** One central directory record. [path] is null when the name is undecodable or unsafe. */
internal class WorldBookZipEntry(
    val rawName: ByteArray,
    val path: String?,
    val flags: Int,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localOffset: Long,
    val link: Boolean,
) {
    val directory: Boolean get() = path?.endsWith('/') == true
    val encrypted: Boolean get() = flags and 0x1 != 0
}

internal class WorldBookZipDirectory(val entries: List<WorldBookZipEntry>, val centralOffset: Long)

/** Structural ZIP parsing for [WorldBookArchive]: central directory, local headers and DEFLATE. */
internal object WorldBookZipStructure {
    const val METHOD_STORED = 0
    const val METHOD_DEFLATED = 8
    private const val MAX_CENTRAL_BYTES = 8 * 1024 * 1024
    private const val EOCD = 0x06054B50
    private const val CENTRAL = 0x02014B50
    private const val LOCAL = 0x04034B50
    private const val FLAG_UTF8 = 0x800
    private const val UNICODE_PATH_EXTRA = 0x7075
    private const val CHUNK = 64 * 1024
    private val GB18030: Charset = Charset.forName("GB18030")
    private val DRIVE = Regex("^[A-Za-z]:")

    fun directory(raf: RandomAccessFile, maxEntries: Int): WorldBookZipDirectory {
        val length = raf.length()
        val tail = ByteArray(minOf(length, 22L + 65_535L).toInt())
        raf.seek(length - tail.size)
        raf.readFully(tail)
        val tailOffset = findEocd(tail) ?: throw WorldBookArchiveException("ZIP end record is missing or malformed")
        val eocd = ByteBuffer.wrap(tail, tailOffset, 22).slice().order(ByteOrder.LITTLE_ENDIAN)
        val entryCount = u16(eocd, 10)
        val centralSize = u32(eocd, 12)
        val centralOffset = u32(eocd, 16)
        if (u16(eocd, 4) != 0 || u16(eocd, 6) != 0 || u16(eocd, 8) != entryCount) {
            throw WorldBookArchiveException("Multi-volume ZIP archives are not supported")
        }
        if (entryCount == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) {
            throw WorldBookArchiveException("ZIP64 archives are not supported")
        }
        if (entryCount > maxEntries) throw WorldBookArchiveException("ZIP archive has more than $maxEntries entries")
        if (centralOffset + centralSize != length - tail.size + tailOffset || centralSize > MAX_CENTRAL_BYTES) {
            throw WorldBookArchiveException("ZIP central directory bounds are invalid")
        }
        val bytes = ByteArray(centralSize.toInt())
        raf.seek(centralOffset)
        raf.readFully(bytes)
        val entries = parseCentral(bytes, entryCount)
        if (entries.any { it.localOffset + 30L > centralOffset }) throw WorldBookArchiveException("ZIP local header offset is invalid")
        if (entries.map { it.localOffset }.toSet().size != entries.size) throw WorldBookArchiveException("ZIP entries overlap")
        return WorldBookZipDirectory(entries, centralOffset)
    }

    private fun parseCentral(directory: ByteArray, entryCount: Int): List<WorldBookZipEntry> {
        val buffer = ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ArrayList<WorldBookZipEntry>(entryCount)
        var cursor = 0
        repeat(entryCount) {
            if (cursor + 46 > directory.size || buffer.getInt(cursor) != CENTRAL) {
                throw WorldBookArchiveException("ZIP central directory record is missing or malformed")
            }
            val nameLength = u16(buffer, cursor + 28)
            val extraLength = u16(buffer, cursor + 30)
            val nameStart = cursor + 46
            val next = nameStart + nameLength + extraLength + u16(buffer, cursor + 32)
            if (nameLength == 0 || next > directory.size) throw WorldBookArchiveException("ZIP central directory entry bounds are invalid")
            val sizes = longArrayOf(u32(buffer, cursor + 20), u32(buffer, cursor + 24), u32(buffer, cursor + 42))
            if (sizes.any { it == 0xFFFFFFFFL }) throw WorldBookArchiveException("ZIP64 archives are not supported")
            val rawName = directory.copyOfRange(nameStart, nameStart + nameLength)
            val extra = directory.copyOfRange(nameStart + nameLength, nameStart + nameLength + extraLength)
            val flags = u16(buffer, cursor + 8)
            val unixMode = ((u32(buffer, cursor + 38) ushr 16) and 0xFFFF).toInt()
            val link = (u16(buffer, cursor + 4) ushr 8) == 3 && unixMode and 0xF000 == 0xA000
            entries += WorldBookZipEntry(rawName, normalizePath(decodeName(rawName, extra, flags)), flags,
                u16(buffer, cursor + 10), u32(buffer, cursor + 16), sizes[0], sizes[1], sizes[2], link)
            cursor = next
        }
        if (cursor != directory.size) throw WorldBookArchiveException("ZIP central directory size is inconsistent")
        return entries
    }

    /** Reads one member bounded by [limit]; null means corrupt (only this member is skipped). */
    fun readMember(raf: RandomAccessFile, entry: WorldBookZipEntry, limit: Long, checkpoint: () -> Unit): ByteArray? {
        val header = ByteArray(30)
        raf.seek(entry.localOffset)
        raf.readFully(header)
        val local = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (local.getInt(0) != LOCAL || u16(local, 6) != entry.flags || u16(local, 8) != entry.method) return null
        val nameLength = u16(local, 26)
        if (nameLength != entry.rawName.size) return null
        val localName = ByteArray(nameLength)
        raf.readFully(localName)
        if (!localName.contentEquals(entry.rawName)) return null
        val dataStart = entry.localOffset + 30L + nameLength + u16(local, 28)
        if (dataStart + entry.compressedSize > limit) return null
        raf.seek(dataStart)
        val size = entry.uncompressedSize.toInt()
        val output = if (entry.method == METHOD_STORED) {
            if (entry.compressedSize != entry.uncompressedSize) return null
            ByteArray(size).also { raf.readFully(it) }
        } else {
            inflate(raf, entry.compressedSize, size, checkpoint) ?: return null
        }
        return output.takeIf { CRC32().apply { update(output) }.value == entry.crc }
    }

    /** Raw DEFLATE; the output must be exactly the declared size, otherwise the member is corrupt. */
    private fun inflate(raf: RandomAccessFile, compressedSize: Long, size: Int, checkpoint: () -> Unit): ByteArray? {
        val output = ByteArray(size)
        val input = ByteArray(CHUNK)
        val probe = ByteArray(1)
        val inflater = Inflater(true)
        try {
            var remaining = compressedSize
            var written = 0
            var padded = false
            // Every pass either consumes input or produces output; the guard only makes that explicit.
            val maxPasses = size.toLong() + compressedSize / CHUNK + 16
            var passes = 0L
            while (!inflater.finished()) {
                checkpoint()
                if (++passes > maxPasses || inflater.needsDictionary()) return null
                if (inflater.needsInput()) {
                    when {
                        remaining > 0L -> {
                            val count = minOf(remaining, CHUNK.toLong()).toInt()
                            raf.readFully(input, 0, count)
                            remaining -= count
                            inflater.setInput(input, 0, count)
                        }
                        // Raw DEFLATE may ask for one padding byte after the final block.
                        !padded -> { inflater.setInput(probe, 0, 1); padded = true }
                        else -> return null
                    }
                }
                if (written < size) written += inflater.inflate(output, written, size - written)
                // Output beyond the declared size means the headers lied.
                else if (inflater.inflate(probe, 0, 1) > 0) return null
            }
            return output.takeIf { written == size }
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    private fun decodeName(raw: ByteArray, extra: ByteArray, flags: Int): String? {
        if (flags and FLAG_UTF8 != 0) return strict(raw, Charsets.UTF_8)
        return unicodePath(raw, extra) ?: strict(raw, Charsets.UTF_8) ?: strict(raw, GB18030)
    }

    /** Info-ZIP Unicode Path extra field; only trusted when its CRC matches the header name. */
    private fun unicodePath(raw: ByteArray, extra: ByteArray): String? {
        val buffer = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 0
        while (cursor + 4 <= extra.size) {
            val id = u16(buffer, cursor)
            val length = u16(buffer, cursor + 2)
            val start = cursor + 4
            if (start + length > extra.size) return null
            if (id == UNICODE_PATH_EXTRA && length >= 5 && extra[start].toInt() == 1 &&
                CRC32().apply { update(raw) }.value == u32(buffer, start + 1)
            ) {
                return strict(extra.copyOfRange(start + 5, start + length), Charsets.UTF_8)
            }
            cursor = start + length
        }
        return null
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Paths are only displayed and used for book names, never written; traversal is still refused. */
    private fun normalizePath(name: String?): String? {
        val normalized = name?.let { Normalizer.normalize(it.replace('\\', '/'), Normalizer.Form.NFC) } ?: return null
        val unsafe = normalized.isBlank() || normalized.any { Character.isISOControl(it) } ||
            normalized.startsWith('/') || DRIVE.containsMatchIn(normalized) ||
            normalized.split('/').any { it == "." || it == ".." }
        return normalized.takeUnless { unsafe }
    }

    private fun findEocd(bytes: ByteArray): Int? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = bytes.size - 22
        while (offset >= 0) {
            if (buffer.getInt(offset) == EOCD && offset + 22 + u16(buffer, offset + 20) == bytes.size) return offset
            offset -= 1
        }
        return null
    }

    private fun u16(buffer: ByteBuffer, offset: Int): Int = buffer.getShort(offset).toInt() and 0xFFFF
    private fun u32(buffer: ByteBuffer, offset: Int): Long = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
}
