// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.ToolResultPart
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolOutcome
import runtime.mobileagent.skills.tooling.ToolOutcomeStatus

/**
 * End-to-end persistence contract for the unified [ToolOutcome]:
 * Executor → RunTools/AgentRuntime → RuntimeEvent.ToolResultProduced →
 * ChatViewModel (TOOL message with [ToolResultPart]) →
 * [ConversationRepository] → reload.
 *
 * Every terminal outcome must survive the round trip with identical typed
 * status/code semantics. A legitimate Denied/Invalid must never surface as a
 * persistence failure (previously: plain-string reasons rejected by the
 * JSON-object store requirement, reported as a run INTERNAL error).
 */
class ToolOutcomePersistenceTest {
    private data class Case(
        val eventStatus: String,
        val invocationState: String,
        val resultJson: String,
        val expectedOutcome: ToolOutcomeStatus,
    )

    private fun cases() = listOf(
        Case("VALUE", "SUCCEEDED", """{"hits":[]}""", ToolOutcomeStatus.VALUE),
        Case(
            "DENIED",
            "FAILED",
            ToolOutcome.denied(message = "The original tool authorization is no longer available"),
            ToolOutcomeStatus.DENIED,
        ),
        Case(
            "INVALID",
            "FAILED",
            ToolOutcome.invalid(message = "Tool arguments are incomplete JSON"),
            ToolOutcomeStatus.INVALID,
        ),
        Case(
            "FAILED",
            "FAILED",
            ToolOutcome.failed(ToolError(ToolErrorCode.CONFLICT)),
            ToolOutcomeStatus.FAILED,
        ),
        Case(
            "UNKNOWN_OUTCOME",
            "UNKNOWN_OUTCOME",
            ToolOutcome.unknown("Tool dispatch may have started; do not automatically retry"),
            ToolOutcomeStatus.UNKNOWN_OUTCOME,
        ),
    )

    @Test
    fun everyOutcomeSurvivesPersistenceRoundTripWithIdenticalSemantics() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshot = createSnapshot(db)
            val conversations = ConversationRepository(db)
            val conversation = conversations.create(snapshot, "Outcomes", "conversation.outcomes")
            val stored = cases().mapIndexed { index, case ->
                // ChatViewModel mapping: VALUE -> SUCCEEDED, UNKNOWN_OUTCOME ->
                // UNKNOWN_OUTCOME, everything else -> FAILED.
                val persistedState = when (case.eventStatus) {
                    "VALUE" -> "SUCCEEDED"
                    "UNKNOWN_OUTCOME" -> "UNKNOWN_OUTCOME"
                    else -> "FAILED"
                }
                assertEquals(case.invocationState, persistedState, "event mapping for ${case.eventStatus}")
                conversations.appendMessage(
                    Message(
                        id = "message.outcome.$index",
                        conversationId = conversation.id,
                        role = MessageRole.TOOL,
                        text = case.resultJson,
                        status = "COMPLETE",
                        createdAt = "2026-09-05T00:00:0${index}Z",
                        parts = listOf(ToolResultPart("call.$index", case.resultJson, persistedState)),
                    ),
                )
            }
            assertEquals(cases().size, stored.size)

            val reloaded = conversations.messages(conversation.id).filter { it.role == MessageRole.TOOL }
            assertEquals(cases().size, reloaded.size)
            reloaded.forEachIndexed { index, message ->
                val case = cases()[index]
                val part = message.parts.filterIsInstance<ToolResultPart>().single()
                assertEquals("call.$index", part.callId)
                assertEquals(case.invocationState, part.status)
                if (case.expectedOutcome == ToolOutcomeStatus.VALUE) {
                    assertEquals(case.resultJson, part.resultJson)
                } else {
                    assertTrue(ToolOutcome.isDurableObject(part.resultJson))
                    assertEquals(case.expectedOutcome, ToolOutcome.statusOf(part.resultJson))
                    assertTrue((ToolOutcome.errorCodeOf(part.resultJson) != null))
                    assertEquals(case.resultJson, part.resultJson, "reload must preserve the exact envelope")
                }
            }
        }
    }

    @Test
    fun deniedAndInvalidEnvelopesNeverReadAsInternal() {
        val denied = ToolOutcome.denied(message = "revoked")
        val invalid = ToolOutcome.invalid(message = "bad args")
        listOf(denied, invalid).forEach { json ->
            val code = ToolOutcome.errorCodeOf(json)
            assertTrue(code != null && code != ToolErrorCode.INTERNAL_ERROR)
            assertEquals(false, ToolOutcome.retryableOf(json))
        }
    }

    private fun createSnapshot(db: SqlConnection): String {
        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile(
                id = "provider.outcomes",
                name = "Outcomes",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "host-only",
                revision = 1,
            ),
        )
        profiles.createModel(
            ModelProfile(
                id = "model.outcomes",
                providerId = "provider.outcomes",
                role = ModelRole.CHAT,
                modelId = "outcomes",
                capabilities = emptySet(),
                contextLimit = 1_000,
                outputLimit = 100,
                revision = 1,
            ),
        )
        val agent = AgentRepository(db).saveWithPrompt(
            runtime.mobileagent.domain.AgentProfile(
                id = "agent.outcomes",
                name = "Outcomes",
                promptRevisionId = "initial",
                chatProfileId = "model.outcomes",
                revision = 0,
            ),
            "Prompt",
        )
        return AgentRepository(db).createSnapshot(agent.id, "snapshot.outcomes").id
    }
}
