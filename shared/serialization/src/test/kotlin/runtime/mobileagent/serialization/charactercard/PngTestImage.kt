// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.serialization.charactercard

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32

/**
 * 测试用 PNG 构造器：按 chunk 逐段拼装并计算真实 CRC。
 *
 * 只生成测试样本，不依赖图像库，也不解码像素（与生产解析器的安全边界一致）。
 */
internal object PngTestImage {

    class Chunk(val type: String, val data: ByteArray)

    fun signature(): ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    fun ihdr(): Chunk = Chunk("IHDR", ByteArray(13))

    fun text(keyword: String, value: String): Chunk = Chunk(
        "tEXt",
        keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1),
    )

    fun textBytes(keyword: String, value: ByteArray): Chunk = Chunk(
        "tEXt",
        keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value,
    )

    /** iTXt / zTXt：只构造关键字前缀，生产解析器不应解压它。 */
    fun compressedText(type: String, keyword: String): Chunk = Chunk(
        type,
        keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0, 0),
    )

    fun iend(): Chunk = Chunk("IEND", ByteArray(0))

    fun png(vararg chunks: Chunk): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature())
        chunks.forEach { chunk ->
            out.write(be32(chunk.data.size))
            val type = chunk.type.toByteArray(Charsets.US_ASCII)
            out.write(type)
            out.write(chunk.data)
            val crc = CRC32()
            crc.update(type)
            crc.update(chunk.data)
            out.write(be32(crc.value.toInt()))
        }
        return out.toByteArray()
    }

    fun validPng(vararg extra: Chunk): ByteArray = png(ihdr(), *extra, iend())

    fun jsonValue(json: String): String = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    fun truncated(png: ByteArray, drop: Int): ByteArray = png.copyOf(png.size - drop)

    fun flipLastByte(png: ByteArray): ByteArray = png.copyOf().also {
        it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte()
    }
}
