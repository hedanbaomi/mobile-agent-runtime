// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.skills.ToolBroker
import runtime.mobileagent.skills.ToolContext

class AgentRuntimeTest {
    @Test
    fun toolLoopExecutesCalculatorThenCompletes() {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "calculator", """{"expression":"2+2"}"""), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("4"), ModelEvent.Completed),
            ),
        )
        val broker = ToolBroker(
            effectiveCapabilities = emptySet(),
            context = ToolContext(search = { _, _, _ -> "{}" }, readDocument = { _, _ -> "{}" }),
        )
        val runtime = AgentRuntime(adapter, tools = broker)
        val run = AgentRun("r", "s", "c")
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = true).toList()
        }
        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "4" })
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals(1, run.toolCalls)
        assertEquals(2, adapter.requests.size)
        val second = adapter.requests.last().messages
        assertTrue(second.any { it.role == "assistant" && it.toolCalls.any { call -> call.id == "t1" } })
        assertTrue(second.any { it.role == "tool" && it.toolCallId == "t1" })
    }

    @Test
    fun toolsDisabledWhenModelHasNoTools() {
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("hi"), ModelEvent.Completed)))
        val runtime = AgentRuntime(adapter, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c")
        runBlocking { runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = false).toList() }
        assertTrue(adapter.requests.single().tools.isEmpty())
        assertEquals(RunState.COMPLETED, run.state)
    }

    @Test
    fun budgetStopsToolLoop() {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "calculator", """{"expression":"1"}"""), ModelEvent.Completed),
            ),
        )
        val runtime = AgentRuntime(adapter, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c", budget = RunBudget(maxModelRounds = 8, maxToolCalls = 0))
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = true).toList()
        }
        assertTrue(events.any { it is ModelEvent.Failed && it.sanitizedMessage.contains("Tool call budget") })
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
    }

    @Test
    fun secretsInToolOutputAreRedacted() {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "knowledge_search", """{"query":"q"}"""), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val broker = ToolBroker(
            setOf("knowledge.search"),
            ToolContext(
                search = { _, _, _ -> """{"secret":"sk-test-token"}""" },
                readDocument = { _, _ -> "{}" },
            ),
        )
        val runtime = AgentRuntime(adapter, tools = broker, secretsForRedaction = { listOf("sk-test-token") })
        runBlocking {
            runtime.run(AgentRun("r", "s", "c"), prompt(), "m", charArrayOf('x'), toolsEnabled = true).toList()
        }
        val toolMsg = adapter.requests.last().messages.first { it.role == "tool" }
        assertFalseSecret(toolMsg.text)
    }

    @Test
    fun toolsDisabledDoesNotExecuteBroker() {
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.ToolCallDelta("t1", "calculator", """{"expression":"1"}"""), ModelEvent.Completed)),
        )
        val runtime = AgentRuntime(adapter, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c")
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = false).toList()
        }
        assertTrue(events.any { it is ModelEvent.Failed })
        assertEquals(0, run.toolCalls)
        assertTrue(adapter.requests.single().tools.isEmpty())
    }

    @Test
    fun budgetCheckedAfterStreamEnds() {
        var now = 0L
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.TextDelta("late"), ModelEvent.Completed)),
            onStream = { now = 2000 },
        )
        val runtime = AgentRuntime(adapter, clock = { now }, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c", budget = RunBudget(maxRuntimeMs = 1000))
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = true).toList()
        }
        assertTrue(events.any { it is ModelEvent.Failed && it.sanitizedMessage.contains("budget") })
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertTrue(events.none { it is ModelEvent.Completed })
    }

    @Test
    fun currentSecretIsRedactedFromInvalidToolResult() {
        val secret = "live-provider-secret-token"
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "knowledge_search", """{"query":"q"}"""), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val runtime = AgentRuntime(
            adapter,
            tools = ToolBroker(
                setOf("knowledge.search"),
                ToolContext(
                    search = { _, _, _ -> error("provider said $secret") },
                    readDocument = { _, _ -> "{}" },
                    grantedKnowledgeBaseIds = setOf("kb-a"),
                ),
            ),
        )
        runBlocking {
            runtime.run(AgentRun("r", "s", "c"), prompt(), "m", secret.toCharArray(), toolsEnabled = true).toList()
        }
        val toolMsg = adapter.requests.last().messages.first { it.role == "tool" }
        assertTrue(secret !in toolMsg.text)
    }

    private fun assertFalseSecret(text: String) {
        assertTrue("sk-test-token" !in text)
    }

    private fun prompt() = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello")

    private class ScriptedAdapter(
        private val scripts: List<List<ModelEvent>>,
        private val onStream: () -> Unit = {},
    ) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        private var i = 0
        override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): CapabilityReport {
            error("not used")
        }

        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            requests += request
            onStream()
            val events = scripts.getOrElse(i++) { listOf(ModelEvent.Completed) }
            events.forEach { emit(it) }
        }

        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch {
            error("not used")
        }
    }
}
