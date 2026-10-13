// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.provider.openai

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The framing layer of both protocols: [SseLineReader] turns response bytes into
 * lines, [SseEventDecoder] turns lines into the committed `data` payload of one
 * event.  Both are driven here directly and together, over a real
 * [ByteReadChannel], exactly as the adapters use them.
 */
class SseEventDecoderTest {
    @Test fun eventBudgetCountsUtf8BytesAcrossDataFields() = runTest {
        val decoder = SseEventDecoder(maxEventBytes = 8)
        decoder.feed("data: 中") { false }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { decoder.feed("data: 中文") { false } }
        }
    }

    @Test fun exactBoundLineAllowsDeferredCr() = runTest {
        for (body in listOf("data\r", "data\r\n")) {
            val lines = mutableListOf<String>()
            SseLineReader(maxLineBytes = 4).read(ByteReadChannel(body)) { line, _ -> lines += line; false }
            assertEquals(listOf("data"), lines)
        }
    }
    /** Bytes to lines to events, the way the streaming adapters do it. */
    private suspend fun payloadsOfBytes(body: String): Pair<List<String>, Boolean> {
        val decoder = SseEventDecoder()
        val payloads = mutableListOf<String>()
        SseLineReader().read(ByteReadChannel(body)) { line, _ ->
            decoder.feed(line) { payloads += it; false }
            false
        }
        return payloads to decoder.finish()
    }

    /** Lines to events.  The decoder also tolerates a CR a reader left behind. */
    private suspend fun payloadsOf(vararg lines: String): List<String> {
        val payloads = mutableListOf<String>()
        val decoder = SseEventDecoder()
        lines.forEach { line -> decoder.feed(line) { payloads += it; false } }
        decoder.finish()
        return payloads
    }

    // ---- SseLineReader: bytes to lines -------------------------------------

    /** LF, CRLF and a lone CR are all line terminators over a real channel. */
    @Test
    fun lfCrlfAndBareCrAllTerminateLines() = runTest {
        assertEquals(listOf("x"), payloadsOfBytes("data: x\n\n").first)
        assertEquals(listOf("x"), payloadsOfBytes("data: x\r\n\r\n").first)
        assertEquals(listOf("x"), payloadsOfBytes("data: x\r\r").first)
        assertEquals(listOf("a", "b"), payloadsOfBytes("data: a\n\ndata: b\n\n").first)
        assertEquals(listOf("a", "b"), payloadsOfBytes("data: a\r\rdata: b\r\r").first)
        assertEquals(listOf("a", "b"), payloadsOfBytes("data: a\r\n\r\ndata: b\r\n\r\n").first)
    }

    /** A response whose last byte is a bare CR still ends the stream cleanly. */
    @Test
    fun trailingBareCrEndsTheStreamInsteadOfFailing() = runTest {
        val (payloads, dropped) = payloadsOfBytes("data: x\r")
        // `data: x\r` terminates that line and starts no further one: the event is
        // never delimited, so it is discarded rather than dispatched.
        assertEquals(emptyList<String>(), payloads)
        assertTrue(dropped)
    }

    /** A CRLF pair split across two reads is still one terminator. */
    @Test
    fun crlfSplitAcrossReadsIsOneTerminator() = runTest {
        val channel = ByteChannel(autoFlush = true)
        channel.writeFully("data: x\r".toByteArray())
        channel.writeFully("\n\r\ndata: [DONE]\r".toByteArray())
        channel.writeFully("\n\r\n".toByteArray())
        channel.flushAndClose()
        val lines = mutableListOf<String>()
        SseLineReader().read(channel) { line, _ -> lines += line; false }
        assertEquals(listOf("data: x", "", "data: [DONE]", ""), lines)
    }

    /** A line may not exceed the single-line bound. */
    @Test
    fun lineBoundIsEnforced() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                SseLineReader(maxLineBytes = 8).read(ByteReadChannel("data: 123456789\n\n")) { _, _ -> false }
            }
        }
    }

    /** The whole response may not exceed the response bound. */
    @Test
    fun responseBoundIsEnforced() = runTest {
        val overflow = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                SseLineReader(maxResponseBytes = 4).read(ByteReadChannel("data: x\n\n")) { _, _ -> false }
            }
        }
        assertFalse(overflow.message.orEmpty().contains("data: x"), overflow.message.orEmpty())
    }

    /** A reader's line decoder stops as soon as the caller says so. */
    @Test
    fun readerStopsWhenTheCallerSaysSo() = runTest {
        val lines = mutableListOf<String>()
        SseLineReader().read(ByteReadChannel("a\nb\nc\n")) { line, _ ->
            lines += line
            lines.size == 1
        }
        assertEquals(listOf("a"), lines)
    }

    // ---- SseEventDecoder: lines to events ----------------------------------

    /** Several `data` fields of one event are joined with LF, as the standard requires. */
    @Test
    fun severalDataFieldsAreJoinedWithLfIntoOnePayload() = runTest {
        assertEquals(
            listOf("{\"a\":\n1}"),
            payloadsOf("data: {\"a\":", "data: 1}", ""),
        )
    }

    /** A blank line commits; a blank line without any data field commits nothing. */
    @Test
    fun blankLineCommitsOnlyWhenAnEventHasADataField() = runTest {
        assertEquals(emptyList<String>(), payloadsOf("event: message", "id: 7", ""))
        // `data:` with an empty value is a data field, so it does dispatch an
        // event whose payload is the empty string; so does `data` with no colon.
        assertEquals(listOf(""), payloadsOf("data:", ""))
        assertEquals(listOf(""), payloadsOf("data", ""))
    }

    /** A leading byte-order mark is stripped once, at the start of the stream. */
    @Test
    fun byteOrderMarkIsStrippedOnlyAtStreamStart() = runTest {
        assertEquals(listOf("x"), payloadsOfBytes("\uFEFFdata: x\n\n").first)
        // A later U+FEFF is part of the field name, so it is not a data field.
        assertEquals(listOf("x"), payloadsOfBytes("data: x\n\n\uFEFFdata: y\n\n").first)
    }

    /** Exactly one leading space belongs to the field syntax, not to the value. */
    @Test
    fun exactlyOneLeadingSpaceIsRemovedFromTheValue() = runTest {
        assertEquals(listOf("x"), payloadsOf("data: x", ""))
        assertEquals(listOf(" x"), payloadsOf("data:  x", ""))
        assertEquals(listOf("x"), payloadsOf("data:x", ""))
    }

    /** Comments and fields this protocol does not consume never become payload. */
    @Test
    fun commentsAndUnknownFieldsAreIgnored() = runTest {
        assertEquals(
            listOf("y"),
            payloadsOfBytes(": keep-alive\nevent: message\nid: 3\nretry: 100\ndata: y\n\n").first,
        )
    }

    /** A CR terminator that a reader left in the line never becomes payload. */
    @Test
    fun crTerminatorLeftByAReaderIsNotPayload() = runTest {
        // The CR is a terminator, so it neither joins the value nor delimits the
        // event: the event still needs its blank line.
        assertEquals(emptyList<String>(), payloadsOf("data: x\r"))
        assertEquals(listOf("x"), payloadsOf("data: x\r", ""))
        assertEquals(listOf("a", "b"), payloadsOf("data: a", "", "data: b", ""))
        assertEquals(listOf("a", "b"), payloadsOf("data: a\r\rdata: b\r\r"))
    }

    /** An event the stream never delimits is discarded, and the discard is reported. */
    @Test
    fun unterminatedEventIsDiscardedAndReportedAtFinish() = runTest {
        val (payloads, dropped) = payloadsOfBytes("data: unfinished")
        assertEquals(emptyList<String>(), payloads)
        assertTrue(dropped, "the discarded event must be observable, not silent")

        val decoder = SseEventDecoder()
        assertFalse(decoder.finish(), "a finished stream has nothing left to discard")
    }

    /** One event may not grow past the bound a single line already had. */
    @Test
    fun eventDataBudgetIsBounded() = runTest {
        val overflow = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { SseEventDecoder(maxEventBytes = 8).feed("data: 123456789") { false } }
        }
        assertFalse(overflow.message.orEmpty().contains("123456789"), overflow.message.orEmpty())
        assertEquals(listOf("1234567"), payloadsOf("data: 1234567", ""))
    }

    /** A stream may not spend its bound on committed events. */
    @Test
    fun eventCountBudgetIsBounded() = runTest {
        val decoder = SseEventDecoder(maxEvents = 2)
        val payloads = mutableListOf<String>()
        decoder.feed("data: a") { payloads += it; false }
        decoder.feed("") { payloads += it; false }
        decoder.feed("data: b") { payloads += it; false }
        decoder.feed("") { payloads += it; false }
        assertEquals(listOf("a", "b"), payloads)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                decoder.feed("data: c") { payloads += it; false }
                decoder.feed("") { payloads += it; false }
            }
        }
        assertEquals(listOf("a", "b"), payloads)
    }

    /** Stopping the consumer stops delivery for the rest of the fragment. */
    @Test
    fun consumerCanStopDeliveryWithinOneFragment() = runTest {
        val payloads = mutableListOf<String>()
        SseEventDecoder().feed("data: a\r\rdata: b\r\rdata: c\r\r") { payloads += it; payloads.size == 1 }
        assertEquals(listOf("a"), payloads)
    }

    /** The default bound keeps a full-size single-line event legal. */
    @Test
    fun defaultBoundKeepsAFullSizeSingleLineEventLegal() = runTest {
        val payloads = mutableListOf<String>()
        val decoder = SseEventDecoder()
        decoder.feed("data: " + "x".repeat(1_048_575)) { payloads += it; false }
        decoder.feed("") { payloads += it; false }
        assertEquals(1, payloads.size)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                SseEventDecoder().feed("data: " + "x".repeat(1_048_576)) { false }
            }
        }
    }
}
