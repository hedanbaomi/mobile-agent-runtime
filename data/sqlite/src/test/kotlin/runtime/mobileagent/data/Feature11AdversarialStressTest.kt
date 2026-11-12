// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.AppException
import runtime.mobileagent.domain.MessagePart
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.ProviderProfile
import runtime.mobileagent.domain.ReasoningPart
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.domain.ToolCallPart
import runtime.mobileagent.domain.ToolResultPart

class Feature11AdversarialStressTest {

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

    @Test
    fun differentialStreamingCheckpoints_reorderingShrinkageGrowthAndEmptyParts() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            val conv = repo.create(snapshotId, "Stress Conv", "conversation.stress")

            // 1. Initial Assistant message with 3 parts
            val p0 = TextPart("Initial text")
            val p1 = ReasoningPart("Initial reasoning", streaming = true)
            val p2 = ToolCallPart("call-1", "tool_search", "{\"q\":\"test\"}")
            val msg = repo.append(
                conv.id,
                MessageRole.ASSISTANT,
                text = "Initial text",
                status = "STREAMING",
                parts = listOf(p0, p1, p2),
            )

            var rows = db.query("SELECT ordinal, part_type, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(3, rows.size)
            assertEquals(0L, rows[0].long("ordinal"))
            assertEquals("text", rows[0].string("part_type"))
            assertEquals(1L, rows[1].long("ordinal"))
            assertEquals("reasoning", rows[1].string("part_type"))
            assertEquals(2L, rows[2].long("ordinal"))
            assertEquals("tool_call", rows[2].string("part_type"))

            // 2. Differential Update: Mutate only the tail part (tool_call argument)
            val p2Updated = ToolCallPart("call-1", "tool_search", "{\"q\":\"test updated\"}")
            val cp1 = repo.checkpointAssistant(
                msg.id,
                text = "Initial text",
                parts = listOf(p0, p1, p2Updated),
                status = "STREAMING",
            )
            rows = db.query("SELECT ordinal, part_type, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(3, rows.size)
            assertTrue(rows[2].string("payload_json").contains("test updated"))

            // 3. Reordering / Substitution: Swap ordinal 0 and 1: [Reasoning, Text, ToolCall]
            val cp2 = repo.checkpointAssistant(
                msg.id,
                text = "Swapped text",
                parts = listOf(p1, p0, p2Updated),
                status = "STREAMING",
            )
            rows = db.query("SELECT ordinal, part_type, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(3, rows.size)
            assertEquals("reasoning", rows[0].string("part_type"))
            assertEquals("text", rows[1].string("part_type"))
            assertEquals("tool_call", rows[2].string("part_type"))

            // 4. Growth: Expand from 3 parts to 6 parts
            val p3 = ToolResultPart("call-1", "{\"result\":\"ok\"}")
            val p4 = ReasoningPart("Second reasoning", streaming = false)
            val p5 = TextPart("Final thoughts")
            val grownParts = listOf(p1, p0, p2Updated, p3, p4, p5)
            val cp3 = repo.checkpointAssistant(
                msg.id,
                text = "Grown text",
                parts = grownParts,
                status = "STREAMING",
            )
            rows = db.query("SELECT ordinal, part_type, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(6, rows.size)
            for (i in 0 until 6) {
                assertEquals(i.toLong(), rows[i].long("ordinal"))
            }
            assertEquals("tool_result", rows[3].string("part_type"))
            assertEquals("reasoning", rows[4].string("part_type"))
            assertEquals("text", rows[5].string("part_type"))

            // 5. Shrinkage: Truncate from 6 parts down to 2 parts
            val cp4 = repo.checkpointAssistant(
                msg.id,
                text = "Shrunk text",
                parts = listOf(p1, p0),
                status = "STREAMING",
            )
            rows = db.query("SELECT ordinal, part_type, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(2, rows.size)
            assertEquals(0L, rows[0].long("ordinal"))
            assertEquals(1L, rows[1].long("ordinal"))

            // 6. Shrinkage to Empty: parts = emptyList()
            val cp5 = repo.checkpointAssistant(
                msg.id,
                text = "Empty parts",
                parts = emptyList(),
                status = "STREAMING",
            )
            rows = db.query("SELECT ordinal, part_type, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(0, rows.size)

            // Message still exists in messages table
            val msgRow = db.query("SELECT text, status, parts_json FROM messages WHERE id=?", listOf(msg.id)).single()
            assertEquals("Empty parts", msgRow.string("text"))
            assertEquals("[]", msgRow.string("parts_json"))

            // 7. Growth from Empty: parts grows back to 2 parts
            val cp6 = repo.checkpointAssistant(
                msg.id,
                text = "Re-grown",
                parts = listOf(TextPart("Reborn 1"), TextPart("Reborn 2")),
                status = "STREAMING",
            )
            rows = db.query("SELECT ordinal, part_type, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(2, rows.size)
            assertEquals("Reborn 1", cp6.parts.filterIsInstance<TextPart>()[0].value)
            assertEquals("Reborn 2", cp6.parts.filterIsInstance<TextPart>()[1].value)
        }
    }

    @Test
    fun terminalStatusRejection_completeAndErrorStatusAreImmutable() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            val conv = repo.create(snapshotId, "Terminal Conv", "conversation.terminal")

            // Test COMPLETE status
            val completeMsg = repo.append(
                conv.id,
                MessageRole.ASSISTANT,
                text = "Completed Response",
                status = "COMPLETE",
                parts = listOf(TextPart("Completed text")),
            )
            val attempt1 = repo.checkpointAssistant(
                completeMsg.id,
                text = "Attempted overwrite of COMPLETE",
                parts = listOf(TextPart("Tampered part")),
                status = "STREAMING",
            )
            assertEquals("Completed Response", attempt1.text)
            assertEquals("COMPLETE", attempt1.status)
            var rows = db.query("SELECT payload_json FROM message_parts WHERE message_id=?", listOf(completeMsg.id))
            assertEquals(1, rows.size)
            assertTrue(rows[0].string("payload_json").contains("Completed text"))

            // Test ERROR status
            val errorMsg = repo.append(
                conv.id,
                MessageRole.ASSISTANT,
                text = "Error encountered",
                status = "ERROR",
                parts = listOf(TextPart("Error text")),
            )
            val attempt2 = repo.checkpointAssistant(
                errorMsg.id,
                text = "Attempted overwrite of ERROR",
                parts = listOf(TextPart("Tampered part")),
                status = "STREAMING",
            )
            assertEquals("Error encountered", attempt2.text)
            assertEquals("ERROR", attempt2.status)
            rows = db.query("SELECT payload_json FROM message_parts WHERE message_id=?", listOf(errorMsg.id))
            assertEquals(1, rows.size)
            assertTrue(rows[0].string("payload_json").contains("Error text"))
        }
    }

    @Test
    fun userRoleCheckpointRejection() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            val conv = repo.create(snapshotId, "User Role Conv", "conversation.user")

            val userMsg = repo.append(
                conv.id,
                MessageRole.USER,
                text = "User Question",
                status = "STREAMING",
                parts = listOf(TextPart("User Question")),
            )

            val ex = assertThrows(AppException::class.java) {
                repo.checkpointAssistant(
                    userMsg.id,
                    text = "Should fail",
                    parts = listOf(TextPart("Should fail")),
                )
            }
            assertTrue(ex.error.userMessage.contains("Only assistant messages may be checkpointed"))
        }
    }
}
