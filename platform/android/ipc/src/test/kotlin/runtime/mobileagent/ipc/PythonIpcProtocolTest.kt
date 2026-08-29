// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ipc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json

class PythonIpcProtocolTest {
    private val ticket = InvocationTicket(
        invocationId = "invocation-1",
        runId = "run-1",
        packageHash = "a".repeat(64),
        grantRevision = 3,
        oneTimeToken = "token_abcdefghijklmnopqrstuvwxyz012345",
    )

    @Test
    fun `broker request round trips ticket and raw json`() {
        val request = PythonIpcProtocol.BrokerRequest(ticket, "r1", "knowledge.search", "{\"query\":\"nav\"}")
        val decoded = PythonIpcProtocol.decodeBrokerRequest(PythonIpcProtocol.encodeBrokerRequest(request))
        assertEquals(request, decoded)
    }

    @Test
    fun `malformed broker json and oversized frame are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PythonIpcProtocol.decodeBrokerRequest("{}".toByteArray())
        }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            PythonIpcProtocol.Frames.write(output, ByteArray(PythonIpcProtocol.MAX_CONTROL_FRAME_BYTES + 1))
        }
    }

    @Test
    fun `frames use bounded length prefix`() {
        val encoded = ByteArrayOutputStream()
        PythonIpcProtocol.Frames.write(encoded, "ok".toByteArray())
        assertEquals("ok", PythonIpcProtocol.Frames.read(ByteArrayInputStream(encoded.toByteArray()))!!.decodeToString())
        assertEquals(null, PythonIpcProtocol.Frames.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `broker response chunks remain bounded after JSON escaping`() {
        // Each raw JSON escape is two bytes, but becomes larger again when the
        // value is encoded as a JSON string inside the response envelope.
        val escapedQuotes = "\\\"".repeat(40_000)
        val valueJson = "[\"$escapedQuotes\"]"
        val response = PythonIpcProtocol.BrokerResponse("r1", "OK", valueJson)
        val frames = PythonIpcProtocol.encodeBrokerResponseChunks(response)

        assertTrue(frames.size > 1)
        assertTrue(frames.all { it.size <= PythonIpcProtocol.MAX_CONTROL_FRAME_BYTES })
        val chunks = frames.map { PythonIpcProtocol.decodeBrokerResponseChunk(it) }
        assertTrue(chunks.all { it.chunkCount == frames.size })
        assertEquals((0 until frames.size).toList(), chunks.map { it.chunkIndex })
        val reassembled = chunks.joinToString(separator = "") { it.response.valueJson }
        assertEquals(valueJson, reassembled)
        Json.parseToJsonElement(reassembled)
    }

    @Test
    fun `entrypoint and limits are fail closed`() {
        assertTrue(PythonIpcProtocol.validateEntrypoint("scripts.main:run"))
        assertFalse(PythonIpcProtocol.validateEntrypoint("../escape:run"))
        assertFalse(PythonIpcProtocol.validateEntrypoint("scripts.main:run.bad"))
        assertTrue(PythonIpcProtocol.validateLimits(PythonIpcProtocol.PythonLimits()))
        assertFalse(PythonIpcProtocol.validateLimits(PythonIpcProtocol.PythonLimits(timeoutMs = 30_001)))
    }
}
