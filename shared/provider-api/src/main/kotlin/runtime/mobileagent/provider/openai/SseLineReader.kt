// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

/**
 * Bounded line reader for `text/event-stream` bodies.
 *
 * Framing needs lines, and ktor's `readUTF8Line` mis-handles exactly the shape
 * CR framing always ends with: a body whose last byte is a bare CR throws
 * instead of ending the stream, so a legal CR-terminated stream reached the
 * caller as a transport failure.  Lines are split at the byte level here
 * instead.  LF, CRLF and a lone CR all terminate a line; a line is decoded only
 * once its terminator has arrived, so a UTF-8 sequence split across two reads is
 * never broken; and a trailing CR that is the last byte of the response is
 * treated as the terminator it is.
 *
 * Both transport bounds stay explicit: one line may not exceed [maxLineBytes]
 * and the whole response may not exceed [maxResponseBytes].  A violated bound
 * fails the read, and no provider text is ever copied into that failure.
 */
internal class SseLineReader(
    private val maxLineBytes: Int = MAX_LINE_BYTES,
    private val maxResponseBytes: Long = MAX_RESPONSE_BYTES,
) {
    private var pending = ByteArray(READ_WINDOW)
    private var end = 0
    private var scanFrom = 0
    private var totalBytes = 0L

    /**
     * Read [channel] to its end, handing every decoded line with the number of
     * response bytes consumed so far to [onLine].  [onLine] returns true to stop
     * reading, leaving the rest of the body unread exactly as a terminal event
     * must.  Returns the number of bytes actually read.
     */
    suspend fun read(channel: ByteReadChannel, onLine: suspend (String, Long) -> Boolean): Long {
        while (true) {
            if (end == pending.size) grow()
            val count = channel.readAvailable(pending, end, pending.size - end)
            if (count == -1) break
            totalBytes += count
            require(totalBytes <= maxResponseBytes) { RESPONSE_LIMIT_MESSAGE }
            end += count
            if (drain(onLine)) return totalBytes
            // Only the still-unterminated remainder is one line, so only it counts
            // against the per-line bound.
            val deferredCr = end > 0 && pending[end - 1].toInt() == CR
            require(end - (if (deferredCr) 1 else 0) <= maxLineBytes) { LINE_LIMIT_MESSAGE }
        }
        finish(onLine)
        return totalBytes
    }

    /** Decode every line the buffer now terminates; returns true when told to stop. */
    private suspend fun drain(onLine: suspend (String, Long) -> Boolean): Boolean {
        var index = scanFrom
        var consumed = 0
        while (index < end) {
            val byte = pending[index].toInt()
            if (byte == LF) {
                if (emitLine(consumed, index, onLine)) return true
                consumed = index + 1
                index += 1
            } else if (byte == CR) {
                // A CR as the last available byte may still be a CRLF: wait for
                // the next read instead of guessing.
                if (index + 1 == end) break
                val next = if (pending[index + 1].toInt() == LF) index + 2 else index + 1
                if (emitLine(consumed, index, onLine)) return true
                consumed = next
                index = next
            } else {
                index += 1
            }
        }
        if (consumed > 0) {
            pending.copyInto(pending, 0, consumed, end)
            end -= consumed
            index -= consumed
        }
        scanFrom = index
        return false
    }

    /**
     * The stream ended.  A trailing CR terminates the last line; any other
     * remainder is the final line the standard still processes, and the caller
     * discards whatever event it leaves pending.
     */
    private suspend fun finish(onLine: suspend (String, Long) -> Boolean) {
        if (end == 0) return
        val trailingCr = pending[end - 1].toInt() == CR
        val lineEnd = if (trailingCr) end - 1 else end
        if (lineEnd > 0) {
            emitLine(0, lineEnd, onLine)
        } else if (trailingCr) {
            // A lone CR terminated an empty line: a blank line, not payload.
            onLine("", totalBytes)
        }
    }

    private suspend fun emitLine(from: Int, to: Int, onLine: suspend (String, Long) -> Boolean): Boolean {
        require(to - from <= maxLineBytes) { LINE_LIMIT_MESSAGE }
        return onLine(String(pending, from, to - from, Charsets.UTF_8), totalBytes)
    }

    private fun grow() {
        // The per-line bound, not the buffer, is what eventually fails a stream
        // that never terminates a line: growing only has to outrun `end`.
        val grown = ByteArray(maxOf(pending.size * 2, end + 1))
        pending.copyInto(grown, 0, 0, end)
        pending = grown
    }

    private companion object {
        /**
         * The bound a single line already had, kept in bytes rather than
         * characters so it cannot depend on the payload's encoding.
         */
        const val MAX_LINE_BYTES = 1_048_576
        const val MAX_RESPONSE_BYTES = 8_388_608L
        const val READ_WINDOW = 8_192
        const val LF = 0x0A
        const val CR = 0x0D

        /** Static and provider-free: no payload text may reach a log or a user. */
        const val LINE_LIMIT_MESSAGE = "Provider response line exceeds limit"
        const val RESPONSE_LIMIT_MESSAGE = "Provider response exceeds limit"
    }
}
