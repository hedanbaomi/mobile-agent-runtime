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
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolOutcome
import runtime.mobileagent.skills.tooling.ToolOutcomeStatus

/**
 * Every terminal [ToolResult] must project to a durable JSON-object
 * [ToolOutcome] envelope: Denied/Invalid previously crossed the runtime
 * boundary as plain strings, which the conversation store rejects.
 */
class ToolOutcomeRuntimeTest {
    @Test
    fun deniedProjectsToDurableDeniedEnvelope() = runTest {
        val produced = runSingleTool(ToolResult.Denied("The original tool authorization is no longer available"))
        assertEquals("DENIED", produced.status)
        assertTrue(ToolOutcome.isDurableObject(produced.resultJson), "DENIED must persist as a JSON object")
        assertEquals(ToolOutcomeStatus.DENIED, ToolOutcome.statusOf(produced.resultJson))
        assertEquals(ToolErrorCode.PERMISSION_DENIED, ToolOutcome.errorCodeOf(produced.resultJson))
    }

    @Test
    fun invalidProjectsToDurableInvalidEnvelope() = runTest {
        val produced = runSingleTool(ToolResult.Invalid("Tool arguments are incomplete JSON"))
        assertEquals("INVALID", produced.status)
        assertTrue(ToolOutcome.isDurableObject(produced.resultJson), "INVALID must persist as a JSON object")
        assertEquals(ToolOutcomeStatus.INVALID, ToolOutcome.statusOf(produced.resultJson))
        assertEquals(ToolErrorCode.INVALID_REQUEST, ToolOutcome.errorCodeOf(produced.resultJson))
    }

    @Test
    fun failureProjectsToDurableFailedEnvelope() = runTest {
        val produced = runSingleTool(ToolResult.Failure(ToolError(ToolErrorCode.CONFLICT)))
        assertEquals("FAILED", produced.status)
        assertTrue(ToolOutcome.isDurableObject(produced.resultJson))
        assertEquals(ToolOutcomeStatus.FAILED, ToolOutcome.statusOf(produced.resultJson))
        assertEquals(ToolErrorCode.CONFLICT, ToolOutcome.errorCodeOf(produced.resultJson))
    }

    @Test
    fun valueKeepsRawJson() = runTest {
        val produced = runSingleTool(ToolResult.Value("""{"hits":[]}"""))
        assertEquals("VALUE", produced.status)
        assertEquals("""{"hits":[]}""", produced.resultJson)
    }

    @Test
    fun unknownOutcomeStaysUnknownAndNotReplayable() = runTest {
        val produced = runSingleTool(ToolResult.UnknownOutcome("Tool dispatch may have started"))
        assertEquals("UNKNOWN_OUTCOME", produced.status)
        assertTrue(ToolOutcome.isDurableObject(produced.resultJson))
        assertEquals(ToolOutcomeStatus.UNKNOWN_OUTCOME, ToolOutcome.statusOf(produced.resultJson))
        assertTrue(produced.resultJson.contains("\"automaticReplayAllowed\":false"))
    }

    private suspend fun runSingleTool(result: ToolResult): RuntimeEvent.ToolResultProduced {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "external", "{}"), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val executor = object : ToolExecutor {
            override val specs = listOf(ToolSpec("external", "external tool", "{\"type\":\"object\"}", "", false))
            override suspend fun invoke(call: ToolCall): ToolResult = result
            override suspend fun approve(callId: String): ToolResult = error("unused")
        }
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(AgentRun("r-outcome", "s", "c"), prompt(), "model", charArrayOf(), toolsEnabled = true, executor = executor),
        ).toList()
        return events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
    }

    private fun prompt() = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello")

    private class ScriptedAdapter(private val scripts: List<List<ModelEvent>>) : ModelAdapter {
        override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): CapabilityReport = error("not used")
        private var i = 0
        override fun stream(request: ModelRequest, secret: CharArray): kotlinx.coroutines.flow.Flow<ModelEvent> =
            kotlinx.coroutines.flow.flow {
                scripts.getOrNull(i++)?.forEach { emit(it) } ?: emit(ModelEvent.Completed)
            }
        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("not used")
    }
}
