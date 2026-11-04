// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

sealed interface RootStreamingPatchResult {
    data class Succeeded(val totalWrittenBytes: Long, val sha256Hex: String) : RootStreamingPatchResult
    data class Rejected(val reason: String) : RootStreamingPatchResult
}

private const val MAX_STREAM_LINE_BYTES = 1024 * 1024

/**
 * Bounded-memory streaming patch applier for `unified` and `search_replace` formats (`ROOT09`, `ROOT14`, `S30`).
 *
 * Reads source content in 64 KiB windows and emits patched chunks through a 64 KiB sink buffer
 * without holding the full file in heap memory. The unified-diff state machine is the same
 * [UnifiedPatchApplier] the in-memory engines use, so context matching, empty-file anchors,
 * CRLF/LF preservation and `\ No newline at end of file` stay identical across engines.
 */
object RootStreamingPatch {
    const val CHUNK_BYTES = 64 * 1024

    fun applyStreamed(
        totalSizeBytes: Long,
        readRange: (offsetBytes: Long, maxBytes: Int) -> ByteArray?,
        format: String,
        patchBytes: ByteArray,
        emitChunk: (ByteArray, Int, Int) -> Unit,
    ): RootStreamingPatchResult {
        if (totalSizeBytes !in 0L..RootContentVersionPolicy.MAX_CONTENT_BYTES) {
            return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET)
        }
        return when (format) {
            "unified" -> applyUnified(totalSizeBytes, readRange, patchBytes, emitChunk)
            "search_replace" -> applySearchReplace(totalSizeBytes, readRange, patchBytes, emitChunk)
            else -> RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
        }
    }

    private fun applyUnified(
        totalSizeBytes: Long,
        readRange: (Long, Int) -> ByteArray?,
        patchBytes: ByteArray,
        emitChunk: (ByteArray, Int, Int) -> Unit,
    ): RootStreamingPatchResult {
        val hunks = UnifiedTextPatch.parsePatchHunks(patchBytes)
            ?: return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
        val sink = StreamedSink(emitChunk)
        val applier = UnifiedPatchApplier(
            hunks,
            UnifiedPatchLineSink { line, terminator -> sink.write(line, terminator) },
        )
        val reader = StreamedLineReader(totalSizeBytes, readRange)
        val walked = reader.forEachLine { line, terminator -> applier.onSourceLine(line, terminator) }
        if (walked != null) return RootStreamingPatchResult.Rejected(walked)
        val finished = applier.finish()
        if (finished != null) return RootStreamingPatchResult.Rejected(finished)
        return sink.finish()
    }

    private fun applySearchReplace(
        totalSizeBytes: Long,
        readRange: (Long, Int) -> ByteArray?,
        patchBytes: ByteArray,
        emitChunk: (ByteArray, Int, Int) -> Unit,
    ): RootStreamingPatchResult {
        val doc = RootSearchReplacePatch.parseDocument(patchBytes)
            ?: return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
        val search = doc.search.toByteArray(Charsets.UTF_8)
        val replace = doc.replace.toByteArray(Charsets.UTF_8)
        val sink = StreamedSink(emitChunk)
        val utf8Validator = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val charScratch = CharBuffer.allocate(CHUNK_BYTES)
        var utf8Carry = ByteArray(0)
        var carry = ByteArray(0)
        var offset = 0L
        var matches = 0
        var totalReplacementBytes = 0L
        while (offset < totalSizeBytes) {
            if (Thread.currentThread().isInterrupted) {
                return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
            }
            val ask = minOf(CHUNK_BYTES.toLong(), totalSizeBytes - offset).toInt()
            val chunk = readRange(offset, ask)
                ?: return RootStreamingPatchResult.Rejected(RootFileOperationReason.READ_FAILED)
            if (chunk.size != ask) return RootStreamingPatchResult.Rejected(RootFileOperationReason.READ_FAILED)
            if (chunk.any { it == 0.toByte() }) {
                return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
            }
            offset += chunk.size
            // CharsetDecoder leaves an incomplete UTF-8 suffix in the input on UNDERFLOW;
            // it does not retain those bytes internally between separate ByteBuffers.
            utf8Carry = validateUtf8Chunk(utf8Validator, charScratch, utf8Carry + chunk, offset == totalSizeBytes)
                ?: return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
            val combined = ByteArray(carry.size + chunk.size)
            System.arraycopy(carry, 0, combined, 0, carry.size)
            System.arraycopy(chunk, 0, combined, carry.size, chunk.size)
            var cursor = 0
            while (cursor <= combined.size - search.size) {
                val at = indexOfBytes(combined, search, cursor)
                if (at < 0) break
                matches += 1
                if (matches > 1 && !doc.replaceAll) {
                    return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_AMBIGUOUS)
                }
                totalReplacementBytes += replace.size
                if (totalReplacementBytes > RootExecutionBudget.MAX_OUTPUT_BYTES ||
                    !sink.writeRaw(combined, cursor, at - cursor) ||
                    !sink.writeRaw(replace, 0, replace.size)
                ) {
                    return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET)
                }
                cursor = at + search.size
            }
            val keepTail = minOf(search.size - 1, combined.size - cursor)
            val flushLen = (combined.size - cursor) - keepTail
            if (flushLen > 0 && !sink.writeRaw(combined, cursor, flushLen)) {
                return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET)
            }
            carry = combined.copyOfRange(combined.size - keepTail, combined.size)
        }
        if (totalSizeBytes == 0L || matches == 0) {
            return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_CONTEXT_MISMATCH)
        }
        if (carry.isNotEmpty() && !sink.writeRaw(carry, 0, carry.size)) {
            return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET)
        }
        return sink.finish()
    }

    private fun validateUtf8Chunk(
        decoder: java.nio.charset.CharsetDecoder,
        scratch: CharBuffer,
        chunk: ByteArray,
        endOfInput: Boolean,
    ): ByteArray? {
        val input = ByteBuffer.wrap(chunk)
        while (true) {
            scratch.clear()
            val res = decoder.decode(input, scratch, endOfInput)
            if (res.isError) return null
            if (res.isUnderflow) break
        }
        if (endOfInput) {
            if (input.hasRemaining()) return null
            scratch.clear()
            if (decoder.flush(scratch).isError) return null
        }
        return ByteArray(input.remaining()).also { input.get(it) }
    }

    internal fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        for (start in from..(haystack.size - needle.size)) {
            var k = 0
            while (k < needle.size && haystack[start + k] == needle[k]) k += 1
            if (k == needle.size) return start
        }
        return -1
    }
}

/**
 * Buffers one emitted line with its exact terminator, so CRLF/LF choices made by the shared
 * applier reach the file byte for byte. `indexOfBytes`-style raw writes remain available for the
 * byte-oriented `search_replace` format.
 */
private class StreamedSink(private val emitChunk: (ByteArray, Int, Int) -> Unit) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val buffer = ByteArray(RootStreamingPatch.CHUNK_BYTES)
    private var buffered = 0
    private var totalWritten = 0L

    fun write(line: String, terminator: String): Boolean {
        val text = line.toByteArray(Charsets.UTF_8)
        if (!writeRaw(text, 0, text.size)) return false
        if (terminator.isEmpty()) return true
        val eol = terminator.toByteArray(Charsets.UTF_8)
        return writeRaw(eol, 0, eol.size)
    }

    fun writeRaw(src: ByteArray, offset: Int, length: Int): Boolean {
        if (totalWritten + length > RootContentVersionPolicy.MAX_CONTENT_BYTES) return false
        var pos = offset
        var rem = length
        while (rem > 0) {
            val copy = minOf(rem, buffer.size - buffered)
            System.arraycopy(src, pos, buffer, buffered, copy)
            buffered += copy
            pos += copy
            rem -= copy
            if (buffered == buffer.size) flushBuffer()
        }
        totalWritten += length
        return true
    }

    fun finish(): RootStreamingPatchResult {
        if (buffered > 0) flushBuffer()
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return RootStreamingPatchResult.Succeeded(totalWritten, hex)
    }

    private fun flushBuffer() {
        digest.update(buffer, 0, buffered)
        emitChunk(buffer, 0, buffered)
        buffered = 0
    }
}

/**
 * Splits a byte source into lines without materialising the file: each visit carries the line text
 * (decoded UTF-8, CR stripped only when it belongs to a CRLF terminator) and the exact terminator
 * (`"\n"`, `"\r\n"` or `""` for a final unterminated line).
 */
private class StreamedLineReader(
    private val totalSizeBytes: Long,
    private val readRange: (Long, Int) -> ByteArray?,
) {
    fun forEachLine(visitor: (String, String) -> String?): String? {
        if (totalSizeBytes == 0L) return null
        val lineBuf = ByteArrayOutputStream(256)
        var offset = 0L
        while (offset < totalSizeBytes) {
            if (Thread.currentThread().isInterrupted) return RootFileOperationReason.PATCH_INVALID
            val ask = minOf(RootStreamingPatch.CHUNK_BYTES.toLong(), totalSizeBytes - offset).toInt()
            val chunk = readRange(offset, ask) ?: return RootFileOperationReason.READ_FAILED
            if (chunk.size != ask) return RootFileOperationReason.READ_FAILED
            for (i in chunk.indices) {
                val b = chunk[i]
                if (b == '\n'.code.toByte()) {
                    val raw = lineBuf.toByteArray()
                    lineBuf.reset()
                    val crlf = raw.isNotEmpty() && raw[raw.size - 1] == '\r'.code.toByte()
                    val textBytes = if (crlf) raw.copyOf(raw.size - 1) else raw
                    val text = runCatching {
                        textBytes.decodeToString(throwOnInvalidSequence = true)
                    }.getOrNull() ?: return RootFileOperationReason.PATCH_INVALID
                    visitor(text, if (crlf) "\r\n" else "\n")?.let { return it }
                } else {
                    if (lineBuf.size() >= MAX_STREAM_LINE_BYTES) return RootFileOperationReason.PATCH_INVALID
                    lineBuf.write(b.toInt())
                }
            }
            offset += chunk.size
        }
        if (lineBuf.size() > 0) {
            val text = runCatching {
                lineBuf.toByteArray().decodeToString(throwOnInvalidSequence = true)
            }.getOrNull() ?: return RootFileOperationReason.PATCH_INVALID
            visitor(text, "")?.let { return it }
        }
        return null
    }
}
