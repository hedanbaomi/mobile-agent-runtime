// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Paths
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

/** Real dispatch/executor/driver with deterministic in-memory FDs, not device acceptance. */
class RootUtf8WindowResumeTest {
    @Test
    fun truncatedMultibyteTailReturnsCompletePrefixAndExactResumeOffset() {
        Fixture("A中B".toByteArray()).use { f ->
            val first = f.read(0, 2)
            assertEquals(RootServiceTerminalOutcome.SUCCEEDED, first.terminalOutcome, first.fileResultJson)
            val body = Json.parseToJsonElement(first.fileResultJson!!).jsonObject
            assertEquals("A", body.getValue("text").jsonPrimitive.content)
            assertEquals(1L, body.getValue("next_offset_bytes").jsonPrimitive.long)
            assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
            assertEquals(listOf(0L to 2), f.ops.reads)

            val second = f.read(1, 4)
            assertEquals(RootServiceTerminalOutcome.SUCCEEDED, second.terminalOutcome, second.fileResultJson)
            val rest = Json.parseToJsonElement(second.fileResultJson!!).jsonObject
            assertEquals("中B", rest.getValue("text").jsonPrimitive.content)
            assertEquals(5L, rest.getValue("next_offset_bytes").jsonPrimitive.long)
            assertFalse(rest.getValue("truncated").jsonPrimitive.boolean)
            assertEquals(listOf(0L to 2, 1L to 4), f.ops.reads)
        }
    }

    @Test
    fun windowTooSmallForFirstCodePointFailsWithoutZeroProgressCursor() {
        for (text in listOf("éZ", "中Z", "😀Z")) {
            val width = text.dropLast(1).toByteArray().size
            for (budget in 1 until width) {
                Fixture(text.toByteArray()).use { f ->
                    val response = f.read(0, budget)
                    assertEquals(RootServiceTerminalOutcome.FAILED, response.terminalOutcome, "$text/$budget")
                    assertEquals("utf8_window_too_small", response.errorReason)
                    assertFalse(Json.parseToJsonElement(response.fileResultJson!!).jsonObject.containsKey("next_offset_bytes"))
                    assertEquals(listOf(0L to budget), f.ops.reads)
                }
            }
        }
    }

    @Test
    fun malformedBytesAreNotHiddenAsTruncatedTail() {
        val invalid = listOf(
            intArrayOf(0x80), intArrayOf(0xc0), intArrayOf(0xff), intArrayOf(0xe2, 0x28),
            intArrayOf(0xe0, 0x80), intArrayOf(0xed, 0xa0), intArrayOf(0xf0, 0x80),
            intArrayOf(0xf4, 0x90), intArrayOf(0xf5),
        )
        for (tail in invalid) {
            val bytes = byteArrayOf(65) + tail.map { it.toByte() }.toByteArray() + byteArrayOf(66, 67)
            Fixture(bytes).use { f ->
                val response = f.read(0, 1 + tail.size)
                assertEquals("non_utf8_text", response.errorReason, tail.contentToString())
                assertEquals(RootServiceTerminalOutcome.FAILED, response.terminalOutcome)
            }
        }
    }

    @Test
    fun continuationOffsetAndIncompleteEofRemainInvalid() {
        Fixture("A中B".toByteArray()).use { f ->
            assertEquals("non_utf8_text", f.read(2, 2).errorReason)
        }
        Fixture(byteArrayOf(65, 0xe4.toByte(), 0xb8.toByte())).use { f ->
            assertEquals("non_utf8_text", f.read(0, 3).errorReason)
        }
    }

    @Test
    fun exactBoundaryAndEmptyEofHaveBoundedNextOffset() {
        for (text in listOf("", "A", "é", "中", "😀")) {
            val bytes = text.toByteArray()
            Fixture(bytes).use { f ->
                val response = f.read(0, maxOf(1, bytes.size))
                assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome)
                val body = Json.parseToJsonElement(response.fileResultJson!!).jsonObject
                assertEquals(text, body.getValue("text").jsonPrimitive.content)
                assertEquals(bytes.size.toLong(), body.getValue("next_offset_bytes").jsonPrimitive.long)
                assertFalse(body.getValue("truncated").jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun jsonEscapingStillObeysOutputBudget() {
        Fixture(ByteArray(RootExecutionBudget.MAX_OUTPUT_BYTES) { 0 }).use { f ->
            var offset = 0L
            do {
                val response = f.read(offset, RootExecutionBudget.MAX_OUTPUT_BYTES)
                assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome)
                val encoded = RootPrivilegedServiceEngine.json.encodeToString(RootServiceExecuteResponse.serializer(), response)
                assertTrue(encoded.toByteArray().size <= RootExecutionBudget.MAX_OUTPUT_BYTES)
                val body = Json.parseToJsonElement(response.fileResultJson!!).jsonObject
                val text = body.getValue("text").jsonPrimitive.content
                assertTrue(text.all { it == '\u0000' })
                val next = body.getValue("next_offset_bytes").jsonPrimitive.long
                assertTrue(next > offset)
                assertEquals(text.toByteArray().size.toLong(), next - offset)
                offset = next
            } while (!body.getValue("eof").jsonPrimitive.boolean)
            assertEquals(RootExecutionBudget.MAX_OUTPUT_BYTES.toLong(), offset)
        }
    }

    @Test
    fun outerJsonBudgetPreservesUnicodeAndEscapedPageOffsets() {
        val text = "中😀\\\"\n".repeat(1000)
        val bytes = text.toByteArray()
        Fixture(bytes).use { f ->
            var offset = 0L
            val recovered = StringBuilder()
            while (offset < bytes.size) {
                val response = f.read(offset, RootExecutionBudget.MAX_OUTPUT_BYTES, 2048)
                assertEquals(RootServiceTerminalOutcome.SUCCEEDED, response.terminalOutcome)
                assertTrue(RootPrivilegedServiceEngine.json.encodeToString(RootServiceExecuteResponse.serializer(), response).toByteArray().size <= 2048)
                val body = Json.parseToJsonElement(response.fileResultJson!!).jsonObject
                val page = body.getValue("text").jsonPrimitive.content
                recovered.append(page)
                val next = body.getValue("next_offset_bytes").jsonPrimitive.long
                assertEquals(page.toByteArray().size.toLong(), next - offset)
                assertEquals(next == bytes.size.toLong(), body.getValue("eof").jsonPrimitive.boolean)
                assertTrue(next > offset)
                offset = next
            }
            assertEquals(text, recovered.toString())
        }
    }

    private class Fixture(bytes: ByteArray) : AutoCloseable {
        val ops = CountingOps()
        val driver = AndroidRootPosixFileDriver(Paths.get("/workspace"), ops)
        val executor: RootWorkspaceFileExecutor
        private val status = RootControlStatusSnapshot("request", "a".repeat(64), 1, RootServiceExecutionStage.ACCEPTED)

        init {
            val root = ops.openDirectoryAbsoluteNoFollow("/workspace")!!
            val rootIdentity = root.use { fd ->
                ops.createSiblingTempAt(fd, "text", 384).use { ops.writeAllAndFsync(it, bytes) }
                ops.fstatIdentity(fd)!!
            }
            val control = RootControlPlaneBoundary(
                "/data/user/0/app",
                RootFdNodeIdentity(1, 9001, 77, true, false, false, 2, 448, 10100, 10100),
                "/data/user_de/0/app",
                RootFdNodeIdentity(1, 9002, 77, true, false, false, 2, 448, 10100, 10100),
                listOf(RootFdNodeIdentity(1, 9003, 77, true, false, false, 2, 448, 10100, 10100)),
            )
            executor = RootWorkspaceFileExecutor(rootIdentity, control, driver)
        }

        fun read(offset: Long, maxBytes: Int, outputBudget: Int = RootExecutionBudget.MAX_OUTPUT_BYTES) = RootServiceFileDispatch.execute(
            executor, RootTypedArguments.FileReadText("ws", "text", offset, maxBytes), status, ByteArray(0), outputBudget,
        )

        override fun close() = driver.close()
    }

    private class CountingOps(
        private val base: InMemoryServiceFdOps = InMemoryServiceFdOps("/workspace"),
    ) : PosixFdOperations by base {
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun readFdRange(fd: PosixRetainedFd, offsetBytes: Long, maxBytes: Int): ByteArray {
            reads += offsetBytes to maxBytes
            return base.readFdRange(fd, offsetBytes, maxBytes)
        }
    }
}
