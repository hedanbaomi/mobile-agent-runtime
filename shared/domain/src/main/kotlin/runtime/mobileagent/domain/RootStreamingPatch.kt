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

/**
 * Bounded-memory streaming patch applier for `unified` and `search_replace` formats (`ROOT09`, `ROOT14`, `S30`).
 *
 * Reads source content in 64 KiB windows and emits patched chunks through a 64 KiB sink buffer
 * without holding the full file in heap memory.
 */
object RootStreamingPatch {
    const val CHUNK_BYTES = 64 * 1024
    private const val MAX_LINE_BYTES = 1024 * 1024

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
        val hunks = RootUnifiedPatch.parsePatchHunks(patchBytes)
            ?: return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
        val sink = StreamedSink(emitChunk)
        val state = UnifiedStreamState(hunks, sink)
        val lineReader = StreamedLineReader(totalSizeBytes, readRange)
        val walked = lineReader.forEachLine { line -> state.onSourceLine(line) }
        if (walked != null) return RootStreamingPatchResult.Rejected(walked)
        val finished = state.finish(lineReader.endedWithNewline)
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
            offset += chunk.size
            if (!validateUtf8Chunk(utf8Validator, charScratch, chunk, offset == totalSizeBytes)) {
                return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_INVALID)
            }
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
                    !sink.write(combined, cursor, at - cursor) ||
                    !sink.write(replace, 0, replace.size)
                ) {
                    return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET)
                }
                cursor = at + search.size
            }
            val keepTail = minOf(search.size - 1, combined.size - cursor)
            val flushLen = (combined.size - cursor) - keepTail
            if (flushLen > 0 && !sink.write(combined, cursor, flushLen)) {
                return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET)
            }
            carry = combined.copyOfRange(combined.size - keepTail, combined.size)
        }
        if (totalSizeBytes == 0L || matches == 0) {
            return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_CONTEXT_MISMATCH)
        }
        if (carry.isNotEmpty() && !sink.write(carry, 0, carry.size)) {
            return RootStreamingPatchResult.Rejected(RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET)
        }
        return sink.finish()
    }

    private fun validateUtf8Chunk(
        decoder: java.nio.charset.CharsetDecoder,
        scratch: CharBuffer,
        chunk: ByteArray,
        endOfInput: Boolean,
    ): Boolean {
        val input = ByteBuffer.wrap(chunk)
        while (true) {
            scratch.clear()
            val res = decoder.decode(input, scratch, endOfInput)
            if (res.isError) return false
            if (res.isUnderflow) break
        }
        if (endOfInput) {
            scratch.clear()
            if (decoder.flush(scratch).isError) return false
        }
        return true
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

private class StreamedSink(private val emitChunk: (ByteArray, Int, Int) -> Unit) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val buffer = ByteArray(RootStreamingPatch.CHUNK_BYTES)
    private var buffered = 0
    private var totalWritten = 0L
    private var wroteAnyLine = false

    fun writeLine(line: String): Boolean {
        val newline = byteArrayOf('\n'.code.toByte())
        if (wroteAnyLine && !write(newline, 0, 1)) return false
        wroteAnyLine = true
        val bytes = line.toByteArray(Charsets.UTF_8)
        return write(bytes, 0, bytes.size)
    }

    fun writeTrailingNewlineIfAny(trailingNewline: Boolean): Boolean {
        if (!trailingNewline || !wroteAnyLine) return true
        return write(byteArrayOf('\n'.code.toByte()), 0, 1)
    }

    fun write(src: ByteArray, offset: Int, length: Int): Boolean {
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

private class StreamedLineReader(
    private val totalSizeBytes: Long,
    private val readRange: (Long, Int) -> ByteArray?,
) {
    var endedWithNewline: Boolean = false
        private set

    fun forEachLine(visitor: (String) -> String?): String? {
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
                val atFinalByte = (offset + i + 1L) == totalSizeBytes
                if (b == '\n'.code.toByte()) {
                    val line = decodeLine(lineBuf) ?: return RootFileOperationReason.PATCH_INVALID
                    lineBuf.reset()
                    visitor(line)?.let { return it }
                    if (atFinalByte) endedWithNewline = true
                } else {
                    if (lineBuf.size() >= 1024 * 1024) return RootFileOperationReason.PATCH_INVALID
                    lineBuf.write(b.toInt())
                }
            }
            offset += chunk.size
        }
        if (!endedWithNewline) {
            val line = decodeLine(lineBuf) ?: return RootFileOperationReason.PATCH_INVALID
            visitor(line)?.let { return it }
        }
        return null
    }

    private fun decodeLine(buf: ByteArrayOutputStream): String? =
        runCatching { buf.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

private class UnifiedStreamState(
    private val hunks: List<RootUnifiedPatch.Hunk>,
    private val sink: StreamedSink,
) {
    private var sourceCursor = 0
    private var hunkIndex = 0
    private var hunkLineIndex = 0
    private val initError: String? = advancePureInsertionHunks()

    fun onSourceLine(line: String): String? {
        initError?.let { return it }
        while (hunkIndex < hunks.size) {
            val hunk = hunks[hunkIndex]
            val base = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
            if (base < sourceCursor && hunkLineIndex == 0) {
                return RootFileOperationReason.PATCH_CONTEXT_MISMATCH
            }
            if (sourceCursor < base) break
            emitPlusLinesAtCurrentHunk()?.let { return it }
            if (hunkLineIndex >= hunk.lines.size) {
                hunkIndex += 1
                hunkLineIndex = 0
                advancePureInsertionHunks()?.let { return it }
                continue
            }
            val expected = hunk.lines[hunkLineIndex]
            if (expected.text != line) return RootFileOperationReason.PATCH_CONTEXT_MISMATCH
            if (expected.kind == ' ' && !sink.writeLine(expected.text)) {
                return RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET
            }
            hunkLineIndex += 1
            sourceCursor += 1
            emitPlusLinesAtCurrentHunk()?.let { return it }
            if (hunkLineIndex >= hunk.lines.size) {
                hunkIndex += 1
                hunkLineIndex = 0
                advancePureInsertionHunks()?.let { return it }
            }
            return null
        }
        sourceCursor += 1
        return if (sink.writeLine(line)) null else RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET
    }

    fun finish(trailingNewline: Boolean): String? {
        (initError ?: advancePureInsertionHunks())?.let { return it }
        if (hunkIndex < hunks.size) return RootFileOperationReason.PATCH_CONTEXT_MISMATCH
        return if (sink.writeTrailingNewlineIfAny(trailingNewline)) null else RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET
    }

    private fun advancePureInsertionHunks(): String? {
        while (hunkIndex < hunks.size) {
            val hunk = hunks[hunkIndex]
            if (hunk.oldCount != 0 || hunk.oldStart != sourceCursor) break
            emitPlusLinesAtCurrentHunk()?.let { return it }
            hunkIndex += 1
            hunkLineIndex = 0
        }
        return null
    }

    private fun emitPlusLinesAtCurrentHunk(): String? {
        val hunk = hunks[hunkIndex]
        while (hunkLineIndex < hunk.lines.size && hunk.lines[hunkLineIndex].kind == '+') {
            if (!sink.writeLine(hunk.lines[hunkLineIndex].text)) {
                return RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET
            }
            hunkLineIndex += 1
        }
        return null
    }
}
