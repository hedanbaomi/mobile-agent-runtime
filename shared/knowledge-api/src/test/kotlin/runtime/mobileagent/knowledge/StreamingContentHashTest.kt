// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StreamingContentHashTest {
    @Test fun largeGeneratedStreamUsesBoundedReadsAndKeepsTheSameHash() {
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        val copies = 512
        val expected = MessageDigest.getInstance("SHA-256")
        repeat(copies) { expected.update(block) }
        var offset = 0
        var reads = 0
        var largestRead = 0
        val input = object : InputStream() {
            override fun read(): Int = if (offset == block.size * copies) -1 else block[offset++ % block.size].toInt() and 255
            override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                assertTrue(length <= block.size, "Full-blob verification must remain bounded")
                if (offset == block.size * copies) return -1
                val n = minOf(length, block.size - offset % block.size)
                block.copyInto(bytes, start, offset % block.size, offset % block.size + n)
                offset += n; reads++
                return n
            }
        }
        val hex = expected.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(hex, sha256Hex(input))
        assertEquals(copies, reads)
        assertEquals(64 * 1024, largestRead)
    }

    @Test fun shortAndZeroReadsKeepEveryByteAndCallerOwnsTheStream() {
        val bytes = ByteArray(257) { it.toByte() }
        var offset = 0
        var empty = true
        var closed = false
        val input = object : InputStream() {
            override fun read() = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 255
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (empty) { empty = false; return 0 }
                if (offset == bytes.size) return -1
                val n = minOf(7, bytes.size - offset)
                bytes.copyInto(buffer, start, offset, offset + n); offset += n
                return n
            }
            override fun close() { closed = true }
        }
        assertEquals(sha256Hex(bytes), sha256Hex(input))
        assertFalse(closed)
        assertEquals(sha256Hex(byteArrayOf()), sha256Hex(byteArrayOf().inputStream()))
    }

    @Test fun existingSameLengthCorruptionIsVerifiedAndRepairedDuringPut() {
        val root = Files.createTempDirectory("streaming-cas-hash").toFile()
        try {
            val sink = FileBlobSink(root)
            val original = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            val stored = sink.put(original, "application/octet-stream")
            assertEquals(stored, sink.put(original, "application/octet-stream"))
            val file = root.resolve(stored.sha256.take(2)).resolve(stored.sha256)
            file.writeBytes(ByteArray(original.size))
            assertNull(sink.get(stored.sha256))
            assertEquals(stored, sink.put(original, "application/octet-stream"))
            assertArrayEquals(original, sink.get(stored.sha256))
        } finally { root.deleteRecursively() }
    }
}
