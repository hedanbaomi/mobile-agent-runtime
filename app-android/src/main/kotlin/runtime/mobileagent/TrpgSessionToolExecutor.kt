// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import runtime.mobileagent.agent.AgentRun
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.domain.AgentMode
import runtime.mobileagent.domain.AgentSnapshot
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgState
import runtime.mobileagent.domain.trpg.TrpgStateValidation
import runtime.mobileagent.domain.trpg.TrpgCommand
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec
import runtime.mobileagent.skills.TrpgToolArguments

/** Host-bound campaign tools: model arguments can never select a conversation, snapshot or Run. */
internal class TrpgSessionToolExecutor(
    private val snapshot: AgentSnapshot,
    private val run: AgentRun,
    private val read: () -> TrpgSession,
    private val update: (ToolCall, Long, TrpgState) -> TrpgSession,
    private val liveBinding: () -> Boolean,
    private val command: ((ToolCall, Long, TrpgCommand) -> TrpgSession)? = null,
) : ToolExecutor {
    private val mutex = Mutex()
    private val calls = linkedMapOf<String, Pair<ToolCall, ToolResult>>()
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = false }
    override val specs: List<ToolSpec> = if (snapshot.mode == AgentMode.TRPG) listOfNotNull(READ, UPDATE, COMMAND.takeIf { command != null }) else emptyList()

    override suspend fun invoke(call: ToolCall): ToolResult = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            if (!available()) return@runInterruptible ToolResult.Denied("TRPG session is no longer active")
            if (call.callId.isBlank() || call.callId.length > 256) return@runInterruptible ToolResult.Invalid("Invalid call id")
            calls[call.callId]?.let { (previous, result) ->
                return@runInterruptible if (previous == call) result else ToolResult.Invalid("Call id was reused with different arguments")
            }
            val result = try {
                val args = TrpgToolArguments.parse(call.argumentsJson)
                val session = when (call.name) {
                    READ.name -> { require(args.isEmpty()); read() }
                    UPDATE.name -> {
                        require(args.keys == setOf("expectedRevision", "state"))
                        val revision = args["expectedRevision"] as? JsonPrimitive
                        require(revision != null && !revision.isString && revision.longOrNull != null)
                        val state = args.getValue("state") as? kotlinx.serialization.json.JsonObject ?: error("Invalid state")
                        require(state.keys == STATE_FIELDS || state.keys == STATE_FIELDS + "campaign") { "Provide the complete legacy state" }
                        val decoded = json.decodeFromJsonElement<TrpgState>(state)
                        TrpgStateValidation.validate(decoded)
                        require(available())
                        update(call, checkNotNull(revision.longOrNull), decoded)
                    }
                    COMMAND.name -> {
                        require(args.keys == setOf("expectedRevision", "command"))
                        val revision = args["expectedRevision"] as? JsonPrimitive
                        require(revision != null && !revision.isString && revision.longOrNull != null)
                        val decoded = json.decodeFromJsonElement<TrpgCommand>(args.getValue("command"))
                        decoded.validate()
                        require(available())
                        checkNotNull(command)(call, checkNotNull(revision.longOrNull), decoded)
                    }
                    else -> return@runInterruptible ToolResult.Invalid("Unknown TRPG tool")
                }
                require(session.conversationId == run.conversationId)
                TrpgStateValidation.validate(session)
                ToolResult.Value(json.encodeToString(session))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                ToolResult.Invalid("TRPG state request rejected; check the current revision, complete state, bounds and active session")
            }
            calls[call.callId] = call to result
            result
        }
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean = mutex.withLock {
        runInterruptible(Dispatchers.IO) { available() && calls[call.callId]?.first == call }
    }

    override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("No pending TRPG approval")

    private fun available(): Boolean = snapshot.mode == AgentMode.TRPG && run.snapshotId == snapshot.id &&
        run.state !in TERMINAL && liveBinding()

    companion object {
        private val TERMINAL = setOf(RunState.COMPLETED, RunState.FAILED, RunState.CANCELLED, RunState.BUDGET_EXHAUSTED, RunState.UNKNOWN_OUTCOME)
        private val STATE_FIELDS = setOf("ruleset", "playerName", "characterSheet", "scene", "notes", "resources")
        private val READ = ToolSpec("session_state_read", "Read this roleplaying conversation's authoritative state and revision. No other conversation can be selected.",
            """{"type":"object","properties":{},"additionalProperties":false}""", "", false)
        private val COMMAND = ToolSpec("campaign_action", "Apply one campaign operation using the revision from session_state_read. REQUEST_CHECK creates a real dice request; the user's dice preference chooses player roll or an atomic automatic roll. RESOLVE_CHECK requires an actual stored roll and a resolution note. Never fabricate roll evidence. Facts can reference an existing message in this conversation. PARTY applies one typed actor/inventory/condition/encounter/rest operation. Specify only fields belonging to its operation. REST follows configured recovery, not assumed rules. ARCHIVE_CHECK removes a finished check from the active list. User preferences cannot be changed here.",
            TrpgCommandSchema.JSON, "", true)
        private val UPDATE = ToolSpec("session_state_update", "Replace this conversation's complete campaign state using expectedRevision from session_state_read. Resources support universal CoC/D&D framework entries as well as custom lorebook numeric columns (set maximum for capped pools or omit maximum for standalone attributes/modifiers; not every preset field is required). Only record established events and report resource changes. A conflict requires reading and reconciling again.",
            """{"type":"object","properties":{"expectedRevision":{"type":"integer","minimum":0},"state":{"type":"object","properties":{"ruleset":{"type":"string","enum":["CUSTOM","DND_3R","DND_5E","COC_7"]},"playerName":{"type":"string","maxLength":128},"characterSheet":{"type":"string","maxLength":8192},"scene":{"type":"string","maxLength":4096},"notes":{"type":"string","maxLength":8192},"resources":{"type":"array","maxItems":32,"items":{"type":"object","properties":{"name":{"type":"string","maxLength":64},"current":{"type":"integer","minimum":-1000000,"maximum":1000000},"maximum":{"type":"integer","minimum":0,"maximum":1000000}},"required":["name","current"],"additionalProperties":false}}},"required":["ruleset","playerName","characterSheet","scene","notes","resources"],"additionalProperties":false}},"required":["expectedRevision","state"],"additionalProperties":false}""", "", true)
    }
}
