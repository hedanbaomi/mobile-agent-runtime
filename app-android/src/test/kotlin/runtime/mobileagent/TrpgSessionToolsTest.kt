// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.skills.*

class TrpgSessionToolsTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private fun snapshot(mode: AgentMode = AgentMode.TRPG) = AgentSnapshot("s", 1, "a", "p", "m", 1, emptyList(), emptyList(), "now", mode = mode)
    private fun args(state: TrpgState, revision: Long = 0) = buildJsonObject {
        put("expectedRevision", revision); put("state", json.encodeToJsonElement(state))
    }.toString()

    @Test fun normalModeAndMismatchedSnapshotHaveNoAccess() = runBlocking<Unit> {
        for ((snapshot, run) in listOf(snapshot(AgentMode.NORMAL) to AgentRun("r", "s", "c"), snapshot() to AgentRun("r", "other", "c"))) {
            val executor = TrpgSessionToolExecutor(snapshot, run, { error("no read") }, { _, _, _ -> error("no write") }, { true })
            if (snapshot.mode == AgentMode.NORMAL) assertTrue(executor.specs.isEmpty())
            assertTrue(executor.invoke(ToolCall("read", "session_state_read", "{}")) is ToolResult.Denied)
        }
    }

    @Test fun updateIsBoundToHostSessionAndReplayCannotWriteAgainOrChangeArguments() = runBlocking<Unit> {
        val run = AgentRun("r", "s", "c")
        var current = TrpgSession("c")
        var writes = 0
        val executor = TrpgSessionToolExecutor(snapshot(), run, { current }, { _, expected, state ->
            check(expected == current.revision); writes++; current = TrpgSession("c", expected + 1, state); current
        }, { true })
        val state = TrpgState(resources = listOf(TrpgResource("HP", 7, 10)))
        val call = ToolCall("write", "session_state_update", args(state))
        val first = executor.invoke(call)
        assertTrue(first is ToolResult.Value)
        assertEquals(first, executor.invoke(call))
        assertEquals(1, writes)
        assertTrue(executor.authorizeReplay(call))
        assertTrue(executor.invoke(call.copy(argumentsJson = args(state, 1))) is ToolResult.Invalid)
        assertEquals(1, writes)
        assertTrue(executor.invoke(ToolCall("foreign", "session_state_read", "{\"conversationId\":\"other\"}")) is ToolResult.Invalid)
        assertTrue(executor.invoke(ToolCall("stale", "session_state_update", args(state))) is ToolResult.Invalid)
        assertEquals(1, writes)
        run.state = RunState.CANCELLED
        assertFalse(executor.authorizeReplay(call))
        assertTrue(executor.invoke(call) is ToolResult.Denied)
    }

    @Test fun liveRevocationBlocksReadAndCachedDisclosure() = runBlocking<Unit> {
        var live = true
        val executor = TrpgSessionToolExecutor(snapshot(), AgentRun("r", "s", "c"), { TrpgSession("c") }, { _, _, _ -> error("no write") }, { live })
        val call = ToolCall("read", "session_state_read", "{}")
        assertTrue(executor.invoke(call) is ToolResult.Value)
        live = false
        assertFalse(executor.authorizeReplay(call))
        assertTrue(executor.invoke(call) is ToolResult.Denied)
    }

    @Test fun malformedAndPartialStateNeverReachesPersistence() = runBlocking<Unit> {
        val executor = TrpgSessionToolExecutor(snapshot(), AgentRun("r", "s", "c"), { error("no read") }, { _, _, _ -> error("no write") }, { true })
        val bad = listOf("{\"expectedRevision\":0,\"state\":{}}", "{\"expectedRevision\":\"0\",\"state\":{}}",
            "{\"expectedRevision\":0,\"expectedRevision\":1,\"state\":{}}", "[".repeat(20_000))
        bad.forEachIndexed { index, value -> assertTrue(executor.invoke(ToolCall("bad$index", "session_state_update", value)) is ToolResult.Invalid) }
    }

    @Test fun guardRejectsDuplicateEscapedKeysDepthAndSizeBeforeTreeParsing() {
        for (value in listOf("{\"a\":1,\"\\u0061\":2}", "{\"state\":{\"name\":1,\"name\":2}}",
            "[".repeat(1000), " ".repeat(128 * 1024 + 1))) {
            assertThrows(IllegalArgumentException::class.java) { TrpgToolArguments.parse(value) }
        }
        assertEquals(1, TrpgToolArguments.parse("{\"a\":[{\"s\":\"commas, [brackets] and \\\"quotes\\\"\"}]}").size)
    }

    @Test fun agentEditorModePreservesSavedModeAndRejectsMutation() {
        val freshEditor = runtime.mobileagent.feature.agents.AgentEditorUi(mode = AgentMode.TRPG)
        assertEquals(AgentMode.NORMAL, resolveAgentEditorMode(null))
        assertEquals(AgentMode.TRPG, resolvePersistedAgentMode(null, freshEditor))
        val savedTrpg = runtime.mobileagent.domain.AgentProfile(
            id = "a1",
            name = "KP",
            promptRevisionId = "p1",
            chatProfileId = "m1",
            revision = 1,
            mode = AgentMode.TRPG,
        )
        assertEquals(AgentMode.TRPG, resolveAgentEditorMode(savedTrpg))
        assertEquals(AgentMode.TRPG, resolvePersistedAgentMode(savedTrpg, freshEditor.copy(id = "a1", name = "KP2")))
        assertThrows(IllegalArgumentException::class.java) {
            resolvePersistedAgentMode(savedTrpg, freshEditor.copy(id = "a1", mode = AgentMode.NORMAL))
        }
    }

    @Test fun trpgToolMessageProjectionSummarizesDiceAndCampaignState() {
        val stdRoll = DiceRoll.formatString(DiceRoll.roll("1d20+5", dc = 15))
        val stdMsg = runtime.mobileagent.domain.Message(
            id = "m1",
            conversationId = "c1",
            role = runtime.mobileagent.domain.MessageRole.TOOL,
            text = stdRoll,
            parts = listOf(runtime.mobileagent.domain.ToolResultPart("dice", stdRoll, "SUCCEEDED")),
            metadataJson = """{"toolName":"dice_roll"}""",
            status = "COMPLETE",
            createdAt = "2026-10-20T00:00:00Z",
        )
        val stdSummary = parseTrpgToolCard(stdMsg)?.let(::trpgToolEventSummary)
        assertNotNull(stdSummary)
        assertTrue(stdSummary!!.contains("1d20+5"))
        assertTrue(stdSummary.contains("DC 15"))

        val cocRoll = DiceRoll.formatString(DiceRoll.cocRoll(65, bonusDice = 1))
        val cocMsg = runtime.mobileagent.domain.Message(
            id = "m2",
            conversationId = "c1",
            role = runtime.mobileagent.domain.MessageRole.TOOL,
            text = cocRoll,
            parts = listOf(runtime.mobileagent.domain.ToolResultPart("coc", cocRoll, "SUCCEEDED")),
            metadataJson = """{"toolName":"dice_roll"}""",
            status = "COMPLETE",
            createdAt = "2026-10-20T00:00:00Z",
        )
        val cocSummary = parseTrpgToolCard(cocMsg)?.let(::trpgToolEventSummary)
        assertNotNull(cocSummary)
        assertTrue(cocSummary!!.contains("CoC7"))
        assertTrue(cocSummary.contains("/65"))

        val sessionJson = json.encodeToString(
            TrpgSession.serializer(),
            TrpgSession("c1", 3, TrpgState(TrpgRuleset.DND_5E, "Aria", "", "White Tower", "", listOf(TrpgResource("HP", 18, 20)))),
        )
        val stateMsg = runtime.mobileagent.domain.Message(
            id = "m3",
            conversationId = "c1",
            role = runtime.mobileagent.domain.MessageRole.TOOL,
            text = sessionJson,
            parts = listOf(runtime.mobileagent.domain.ToolResultPart("state", sessionJson, "SUCCEEDED")),
            metadataJson = """{"toolName":"session_state_update"}""",
            status = "COMPLETE",
            createdAt = "2026-10-20T00:00:00Z",
        )
        val stateSummary = parseTrpgToolCard(stateMsg)?.let(::trpgToolEventSummary)
        assertNotNull(stateSummary)
        assertTrue(stateSummary!!.contains("#3"))
        assertTrue(stateSummary.contains("White Tower"))
        assertTrue(stateSummary.contains("HP:18/20"))

        val starterCoc = runtime.mobileagent.ui.trpgStarterState(TrpgRuleset.COC_7, TrpgState(), true)
        assertEquals(TrpgRuleset.COC_7, starterCoc.ruleset)
        assertTrue(starterCoc.resources.any { it.name == "SAN" })
        val cocCatalog = runtime.mobileagent.ui.trpgFrameworkCatalog(TrpgRuleset.COC_7, true)
        val dndCatalog = runtime.mobileagent.ui.trpgFrameworkCatalog(TrpgRuleset.DND_5E, true)
        assertTrue(cocCatalog.size > starterCoc.resources.size)
        assertTrue(cocCatalog.any { it.name == "STR" } && cocCatalog.any { it.name == "MOV" && it.maximum == null })
        assertTrue(dndCatalog.any { it.name == "AC" && it.maximum == null } && dndCatalog.any { it.name == "强韧豁免" })
        assertEquals("line1\nline2", runtime.mobileagent.ui.appendTrpgRollToDraft("line1", "line2"))
    }
}
