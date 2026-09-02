// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.DiffPart
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageErrorCode
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.ReasoningPart

class ConversationMessagePartsRepositoryTest {
    @Test
    fun appendAndCheckpointPersistNewParts() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshot = createSnapshot(db)
            val conversations = ConversationRepository(db)
            val conversation = conversations.create(snapshot, "Parts", "conversation.parts")
            val message = conversations.appendMessage(
                Message(
                    id = "message.parts",
                    conversationId = conversation.id,
                    role = MessageRole.ASSISTANT,
                    status = "STREAMING",
                    createdAt = "2026-09-02T00:00:00Z",
                    parts = listOf(ReasoningPart("provider reasoning", streaming = true)),
                ),
            )

            val checkpointed = conversations.checkpointAssistant(
                message.id,
                text = "done",
                parts = listOf(
                    ReasoningPart("provider reasoning"),
                    DiffPart("Updated one file", "diff --git a/src/Main.kt b/src/Main.kt"),
                    ErrorPart(MessageErrorCode.TIMEOUT, "The request timed out", retryable = true),
                ),
                status = "COMPLETE",
            )

            assertEquals(3, checkpointed.parts.size)
            assertEquals(checkpointed, conversations.message(message.id))
            assertEquals("diff", db.query("SELECT part_type FROM message_parts WHERE message_id=? AND ordinal=1", listOf(message.id)).single().string("part_type"))
            assertEquals("error", db.query("SELECT part_type FROM message_parts WHERE message_id=? AND ordinal=2", listOf(message.id)).single().string("part_type"))
        }
    }

    @Test
    fun invalidPersistedToolResultIsRejectedClosed() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshot = createSnapshot(db)
            val conversations = ConversationRepository(db)
            val conversation = conversations.create(snapshot, "Parts", "conversation.invalid")
            val error = assertThrows(AppException::class.java) {
                conversations.appendMessage(
                    Message(
                        id = "message.invalid",
                        conversationId = conversation.id,
                        role = MessageRole.TOOL,
                        status = "COMPLETE",
                        createdAt = "2026-09-02T00:00:00Z",
                        parts = listOf(
                            runtime.mobileagent.domain.ToolResultPart(
                                callId = "call.one",
                                resultJson = "not-json",
                            ),
                        ),
                    ),
                )
            }
            assertEquals(runtime.mobileagent.domain.ErrorCode.INVALID_CONFIG, error.error.code)

            // A legacy/imported row must be checked when it is read too, not just on new writes.
            db.execute(
                "INSERT INTO messages(id,conversation_id,parent_message_id,role,text,status,created_at,parts_json,metadata_json) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf(
                    "message.persisted-invalid",
                    conversation.id,
                    null,
                    MessageRole.TOOL.name,
                    "",
                    "COMPLETE",
                    "2026-09-02T00:00:01Z",
                    "[{\"type\":\"tool_result\",\"callId\":\"call.one\",\"resultJson\":\"not-json\",\"status\":\"SUCCEEDED\"}]",
                    "{}",
                ),
            )
            assertThrows(AppException::class.java) { conversations.message("message.persisted-invalid") }
        }
    }

    private fun createSnapshot(db: SqlConnection): String {
        val profiles = ProfileRepository(db)
        profiles.createProvider(
            ProviderProfile(
                id = "provider.parts",
                name = "Parts",
                apiFormat = ApiFormat.OPENAI_COMPATIBLE,
                baseUrl = "https://example.invalid/v1",
                secretRef = "host-only",
                revision = 1,
            ),
        )
        profiles.createModel(
            ModelProfile(
                id = "model.parts",
                providerId = "provider.parts",
                role = ModelRole.CHAT,
                modelId = "parts",
                capabilities = emptySet(),
                contextLimit = 1_000,
                outputLimit = 100,
                revision = 1,
            ),
        )
        val agent = AgentRepository(db).saveWithPrompt(
            runtime.mobileagent.domain.AgentProfile(
                id = "agent.parts",
                name = "Parts",
                promptRevisionId = "initial",
                chatProfileId = "model.parts",
                revision = 0,
            ),
            "Prompt",
        )
        return AgentRepository(db).createSnapshot(agent.id, "snapshot.parts").id
    }
}
