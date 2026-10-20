// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import java.util.zip.CRC32

/**
 * 有界 PNG chunk 扫描器：只做结构校验，**从不解码像素**（IDAT 内容既不读也不 inflate）。
 *
 * 校验项：8 字节签名、chunk 长度边界（含截断）、chunk 数量上限、类型为 4 个 ASCII 字母、
 * 每个 chunk 的 CRC32、首 chunk 必须是 13 字节 `IHDR`、必须出现 0 长度 `IEND`。
 * 校验通过后按顺序回调非 `IEND` chunk 的 `[dataStart, dataEnd)` 视图，调用方按需读取。
 */
internal object PngChunkScanner {

    const val TYPE_IHDR = "IHDR"
    const val TYPE_IEND = "IEND"

    private const val CRC_BYTES = 4
    private const val CHUNK_OVERHEAD = 12
    private const val SIGNATURE_BYTES = 8
    private const val IHDR_LENGTH = 13

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun hasPngSignature(bytes: ByteArray): Boolean {
        if (bytes.size < SIGNATURE_BYTES) return false
        for (index in SIGNATURE.indices) {
            if (bytes[index] != SIGNATURE[index]) return false
        }
        return true
    }

    /**
     * @param onChunk 回调参数为 (chunk 类型, data 起点, data 终点)，区间不含长度/类型/CRC。
     * @throws CharacterCardImportException 结构错误（长度、CRC、截断、IHDR/IEND、chunk 数）。
     */
    fun scan(bytes: ByteArray, limits: CharacterCardLimits, onChunk: (String, Int, Int) -> Unit) {
        var offset = SIGNATURE_BYTES
        var index = 0
        while (true) {
            if (offset == bytes.size) throw missingIend()
            if (offset + CHUNK_OVERHEAD > bytes.size) throw truncated()
            val length = readInt(bytes, offset)
            if (length < 0) throw chunkLength()
            val typeStart = offset + 4
            val dataStart = offset + 8
            if (length > bytes.size - dataStart - CRC_BYTES) throw truncated()
            val dataEnd = dataStart + length
            val type = readType(bytes, typeStart)
            if (readInt(bytes, dataEnd) != crc32(bytes, typeStart, dataEnd)) throw crcMismatch()
            if (index == 0 && (type != TYPE_IHDR || length != IHDR_LENGTH)) throw ihdrInvalid()
            index++
            if (index > limits.maxPngChunks) throw chunkCount(limits.maxPngChunks)
            if (type == TYPE_IEND) {
                if (length != 0) throw iendInvalid()
                return
            }
            onChunk(type, dataStart, dataEnd)
            offset = dataEnd + CRC_BYTES
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readType(bytes: ByteArray, start: Int): String {
        for (index in 0 until 4) {
            val value = bytes[start + index]
            if (value < 'A'.code.toByte() || value > 'z'.code.toByte()) throw chunkType()
            if (value > 'Z'.code.toByte() && value < 'a'.code.toByte()) throw chunkType()
        }
        return String(bytes, start, 4, Charsets.US_ASCII)
    }

    private fun crc32(bytes: ByteArray, start: Int, end: Int): Int {
        val crc = CRC32()
        crc.update(bytes, start, end - start)
        return crc.value.toInt()
    }

    private fun truncated(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_CHUNK_TRUNCATED,
        "PNG ends inside a chunk; the file is truncated.",
    )

    private fun missingIend(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_MISSING_IEND,
        "PNG has no IEND chunk.",
    )

    private fun chunkLength(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_CHUNK_LENGTH,
        "PNG chunk length is invalid.",
    )

    private fun chunkType(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_CHUNK_TYPE,
        "PNG chunk type is not four ASCII letters.",
    )

    private fun chunkCount(limit: Int): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_CHUNK_COUNT,
        "PNG has more than $limit chunks.",
    )

    private fun crcMismatch(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_CRC,
        "PNG chunk CRC does not match its contents.",
    )

    private fun ihdrInvalid(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_IHDR,
        "PNG must start with a 13-byte IHDR chunk.",
    )

    private fun iendInvalid(): CharacterCardImportException = CharacterCardImportException(
        CharacterCardErrorCodes.PNG_MISSING_IEND,
        "PNG IEND chunk must be empty.",
    )
}
