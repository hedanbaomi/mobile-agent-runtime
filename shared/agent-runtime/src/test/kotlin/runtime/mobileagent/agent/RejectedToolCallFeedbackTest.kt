// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

/**
 * A tool call whose arguments provably never reached dispatch is not a terminal
 * failure: the runtime feeds a bounded INVALID tool result back (paired with the
 * assistant tool_call the model actually emitted) so the next model round can
 * resend corrected arguments.  The bound keeps a model that cannot emit a valid
 * call from looping forever.
 */
class RejectedToolCallFeedbackTest {
    @Test
    fun unusableArgumentsGetInvalidFeedbackAndCorrectedResendExecutes() = runTest {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "external", "{bad"), ModelEvent.Completed),
                listOf(ModelEvent.ToolCallDelta("t2", "external", "{}"), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val invoked = mutableListOf<ToolCall>()
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                AgentRun("r-retry", "s", "c"), prompt(), "model", charArrayOf(),
                toolsEnabled = true, executor = executor(invoked),
            ),
        ).toList()

        // The malformed first call was never dispatched; only the resend ran.
        assertEquals(listOf("t2"), invoked.map { it.callId })
        val produced = events.filterIsInstance<RuntimeEvent.ToolResultProduced>()
        assertEquals(listOf("t1", "t2"), produced.map { it.callId })
        assertEquals("INVALID", produced[0].status)
        assertTrue(produced[0].resultJson.contains("Tool arguments are invalid JSON"))
        assertEquals("VALUE", produced[1].status)
        assertEquals(ModelEvent.Completed, events.filterIsInstance<RuntimeEvent.ModelEvent>().last().event)

        // The retry round's history pairs the rejected assistant tool_call with
        // its synthetic INVALID result: protocol-valid, no phantom dispatch.
        val roundTwo = adapter.requests[1]
        val assistant = roundTwo.messages.last { it.role == "assistant" }
        assertEquals(listOf("t1"), assistant.toolCalls.map { it.id })
        val feedback = roundTwo.messages.last { it.role == "tool" }
        assertEquals("t1", feedback.toolCallId)
        assertTrue(feedback.text.contains("INVALID"))
    }

    @Test
    fun feedbackCapEndsInTypedInvalidResponseAndNeverDispatches() = runTest {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "external", "{bad"), ModelEvent.Completed),
                listOf(ModelEvent.ToolCallDelta("t2", "external", "{bad"), ModelEvent.Completed),
                listOf(ModelEvent.ToolCallDelta("t3", "external", "{bad"), ModelEvent.Completed),
                listOf(ModelEvent.ToolCallDelta("t4", "external", "{bad"), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val invoked = mutableListOf<ToolCall>()
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                AgentRun("r-cap", "s", "c"), prompt(), "model", charArrayOf(),
                toolsEnabled = true, executor = executor(invoked),
            ),
        ).toList()

        // Three bounded feedbacks, then the fourth rejection is terminal.
        assertEquals(3, events.filterIsInstance<RuntimeEvent.ToolResultProduced>().count { it.status == "INVALID" })
        assertTrue(invoked.isEmpty(), "rejected calls must never reach the executor")
        val failed = events.filterIsInstance<RuntimeEvent.ModelEvent>()
            .map { it.event }
            .filterIsInstance<ModelEvent.Failed>()
            .last()
        assertTrue(
            failed.sanitizedMessage.startsWith("INVALID_RESPONSE:"),
            "cap exhaustion must stay a typed local failure, got ${failed.sanitizedMessage}",
        )
    }

    @Test
    fun unknownToolNameIsFedBackWithTheValidationError() = runTest {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "nonexistent", "{}"), ModelEvent.Completed),
                listOf(ModelEvent.ToolCallDelta("t2", "external", "{}"), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val invoked = mutableListOf<ToolCall>()
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(
                AgentRun("r-name", "s", "c"), prompt(), "model", charArrayOf(),
                toolsEnabled = true, executor = executor(invoked),
            ),
        ).toList()

        assertEquals(listOf("t2"), invoked.map { it.callId })
        val invalid = events.filterIsInstance<RuntimeEvent.ToolResultProduced>()
            .single { it.status == "INVALID" }
        assertTrue(invalid.resultJson.contains("Unknown tool nonexistent"))
        assertEquals(ModelEvent.Completed, events.filterIsInstance<RuntimeEvent.ModelEvent>().last().event)
    }

    private fun executor(invoked: MutableList<ToolCall>) = object : ToolExecutor {
        override val specs = listOf(ToolSpec("external", "external tool", "{\"type\":\"object\"}", "", false))
        override suspend fun invoke(call: ToolCall): ToolResult {
            invoked += call
            return ToolResult.Value("{\"ok\":true}")
        }
        override suspend fun approve(callId: String): ToolResult = error("unused")
    }

    private fun prompt() = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello")

    private class ScriptedAdapter(private val scripts: List<List<ModelEvent>>) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): CapabilityReport = error("not used")
        private var i = 0
        override fun stream(request: ModelRequest, secret: CharArray): kotlinx.coroutines.flow.Flow<ModelEvent> =
            kotlinx.coroutines.flow.flow {
                requests += request
                scripts.getOrNull(i++)?.forEach { emit(it) } ?: emit(ModelEvent.Completed)
            }
        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("not used")
    }
}
