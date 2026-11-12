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
import runtime.mobileagent.domain.TextPart

class ConversationMessagePartsRepositoryTest {
    @Test
    fun anotherRepositoryArchivingDuringPreflightRejectsUserAppendAndRunAdmission() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val sender = ConversationRepository(db)
            val archiver = ConversationRepository(db)
            sender.create(snapshotId, "Race", "conversation.race")
            val previous = sender.append("conversation.race", MessageRole.USER, "Previously admitted")
            // The sender's initial UI check has already passed; another VM archives before
            // asynchronous user-message persistence and RunCoordinator preparation.
            assertEquals(false, sender.get("conversation.race")?.archived)
            assertEquals(true, archiver.setArchived("conversation.race", true))
            assertThrows(AppException::class.java) { sender.append("conversation.race", MessageRole.USER, "New draft") }
            assertThrows(AppException::class.java) {
                RunRepository(db).create(runtime.mobileagent.domain.RunRecord(
                    "race.run", snapshotId, "conversation.race", createdAt = "now"))
            }
            assertEquals(listOf(previous), sender.messages("conversation.race"))
            assertEquals(emptyList<runtime.mobileagent.domain.RunRecord>(), RunRepository(db).list("conversation.race"))
        }
    }

    @Test
    fun archiveAfterUserAppendStillRejectsRunWithoutDeletingAdmittedMessage() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            repo.create(snapshotId, "Race", "conversation.admission")
            val message = repo.append("conversation.admission", MessageRole.USER, "Already persisted")
            assertEquals(true, repo.setArchived("conversation.admission", true))
            assertThrows(AppException::class.java) {
                RunRepository(db).create(runtime.mobileagent.domain.RunRecord(
                    "admission.run", snapshotId, "conversation.admission", createdAt = "now"))
            }
            assertEquals(listOf(message), repo.messages("conversation.admission"))
            assertEquals(emptyList<runtime.mobileagent.domain.RunRecord>(), RunRepository(db).list("conversation.admission"))
        }
    }

    @Test
    fun admittedRunBlocksOtherRepositoryArchivalUntilTerminal() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val sender = ConversationRepository(db)
            val archiver = ConversationRepository(db)
            sender.create(snapshotId, "Admission", "conversation.active")
            val run = RunRepository(db).create(runtime.mobileagent.domain.RunRecord(
                "active.run", snapshotId, "conversation.active", createdAt = "now"))
            assertEquals(false, archiver.setArchived("conversation.active", true))
            assertEquals(false, sender.get("conversation.active")?.archived)
            RunRepository(db).save(run.copy(state = runtime.mobileagent.domain.RunStatus.COMPLETED))
            assertEquals(true, archiver.setArchived("conversation.active", true))
        }
    }

    @Test
    fun archivePersistsAcrossDatabaseCloseAndReopen() {
        val file = java.nio.file.Files.createTempFile("conversation-archive-", ".sqlite")
        try {
            JdbcSqlConnection("jdbc:sqlite:$file").use { db ->
                Migrations.apply(db)
                val snapshotId = createSnapshot(db)
                val repo = ConversationRepository(db)
                repo.create(snapshotId, "Durable archive", "conversation.durable")
                repo.append("conversation.durable", MessageRole.USER, "Survives restart")
                assertEquals(true, repo.setArchived("conversation.durable", true))
            }
            JdbcSqlConnection("jdbc:sqlite:$file").use { db ->
                Migrations.apply(db)
                val repo = ConversationRepository(db)
                assertEquals(true, repo.get("conversation.durable")?.archived)
                assertEquals(emptyList<runtime.mobileagent.domain.Conversation>(), repo.listActive())
                assertEquals("Survives restart", repo.messages("conversation.durable").single().text)
                assertEquals("snapshot.parts", repo.listArchived().single().snapshotId)
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }

    @Test
    fun archivePreservesHistoryAndSnapshotAndSurvivesRepositoryRestart() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val snapshot = AgentRepository(db).getSnapshot(snapshotId)
            val repo = ConversationRepository(db)
            repo.create(snapshotId, "Archive", "conversation.archive")
            repo.append("conversation.archive", MessageRole.USER, "Preserved transcript")
            val before = checkNotNull(repo.get("conversation.archive"))
            val messages = repo.messages(before.id)
            assertEquals(true, repo.setArchived(before.id, true))
            val reopened = ConversationRepository(db)
            assertEquals(emptyList<runtime.mobileagent.domain.Conversation>(), reopened.listActive())
            assertEquals(listOf(before.copy(archived = true)), reopened.listArchived())
            assertEquals(messages, reopened.messages(before.id))
            assertEquals(snapshot, AgentRepository(db).getSnapshot(snapshotId))
            assertEquals(1, reopened.list().size)
            assertEquals(true, reopened.setArchived(before.id, false))
            assertEquals(listOf(before), reopened.listActive())
        }
    }

    @Test
    fun everyNonterminalRunBlocksArchiveAndTerminalRunsAllowIt() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            repo.create(snapshotId, "Running", "conversation.running")
            val terminal = setOf("COMPLETED", "CANCELLED", "FAILED", "BUDGET_EXHAUSTED", "UNKNOWN_OUTCOME")
            runtime.mobileagent.domain.RunStatus.entries.forEach { state ->
                db.execute("DELETE FROM runs")
                db.execute("INSERT INTO runs(run_id,snapshot_id,conversation_id,state,budget_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    listOf("archive.run", snapshotId, "conversation.running", state.name, "{}", "now", "now"))
                assertEquals(state.name in terminal, repo.setArchived("conversation.running", true), state.name)
                if (state.name in terminal) repo.setArchived("conversation.running", false)
                assertEquals(false, repo.get("conversation.running")?.archived)
            }
        }
    }

    @Test
    fun v29ArchiveUpgradeIsIdempotentPreservesDataAndRollsBackDdlFailure() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            repo.create(snapshotId, "Upgrade", "conversation.upgrade")
            repo.append("conversation.upgrade", MessageRole.USER, "Legacy transcript")
            val before = repo.get("conversation.upgrade")
            val messages = repo.messages("conversation.upgrade")
            val snapshot = AgentRepository(db).getSnapshot(snapshotId)
            db.execute("ALTER TABLE conversations DROP COLUMN archived")
            db.execute("UPDATE schema_version SET version=29")
            val failing = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql == "DELETE FROM schema_version") error("Injected failure after ALTER")
                    db.execute(sql, args)
                }
            }
            assertThrows(IllegalStateException::class.java) { Migrations.apply(failing) }
            assertEquals(29L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(false, db.query("PRAGMA table_info(conversations)").any { it.string("name") == "archived" })
            Migrations.apply(db)
            Migrations.apply(db)
            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(before, repo.get("conversation.upgrade"))
            assertEquals(messages, repo.messages("conversation.upgrade"))
            assertEquals(snapshot, AgentRepository(db).getSnapshot(snapshotId))
        }
    }

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
            val whitespaceMessage = conversations.appendMessage(Message(
                id = "message.whitespace", conversationId = conversation.id, role = MessageRole.ASSISTANT,
                status = "STREAMING", createdAt = "2026-09-02T00:00:01Z",
            ))
            val whitespace = conversations.checkpointAssistant(whitespaceMessage.id, "", listOf(ReasoningPart(" \n")), status = "COMPLETE")
            assertEquals(" \n", conversations.message(whitespaceMessage.id)!!.parts.filterIsInstance<ReasoningPart>().single().text)
            assertEquals(whitespace, conversations.message(whitespaceMessage.id))
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

    @Test
    fun checkpointDifferentialUpdateGrowsTailInPlaceAndAppendsParts() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            val conv = repo.create(snapshotId, "Diff", "conversation.diff")

            // 1. Initial streaming assistant message with 1 reasoning part
            val initialPart = ReasoningPart("Think", streaming = true)
            val msg = repo.append(
                conv.id,
                MessageRole.ASSISTANT,
                text = "Think",
                status = "STREAMING",
                parts = listOf(initialPart),
            )
            var partsCount = db.query("SELECT COUNT(*) AS c FROM message_parts WHERE message_id=?", listOf(msg.id)).single().long("c")
            assertEquals(1L, partsCount)

            // 2. Checkpoint 1: update reasoning part text in-place (ordinal 0 unchanged, payload updated)
            val updatedPart1 = ReasoningPart("Thinking deeper...", streaming = true)
            repo.checkpointAssistant(msg.id, "Thinking deeper...", listOf(updatedPart1), status = "STREAMING")
            partsCount = db.query("SELECT COUNT(*) AS c FROM message_parts WHERE message_id=?", listOf(msg.id)).single().long("c")
            assertEquals(1L, partsCount)
            val row0 = db.query("SELECT ordinal, payload_json FROM message_parts WHERE message_id=?", listOf(msg.id)).single()
            assertEquals(0L, row0.long("ordinal"))
            assertEquals(true, row0.string("payload_json").contains("Thinking deeper..."))

            // 3. Checkpoint 2: append second part (ReasoningPart done + TextPart streaming)
            val finalizedReasoning = ReasoningPart("Done", streaming = false)
            val streamingText = TextPart("Answer")
            repo.checkpointAssistant(msg.id, "Answer", listOf(finalizedReasoning, streamingText), status = "STREAMING")
            partsCount = db.query("SELECT COUNT(*) AS c FROM message_parts WHERE message_id=?", listOf(msg.id)).single().long("c")
            assertEquals(2L, partsCount)
            val rows = db.query("SELECT ordinal, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(0L, rows[0].long("ordinal"))
            assertEquals(true, rows[0].string("payload_json").contains("Done"))
            assertEquals(1L, rows[1].long("ordinal"))
            assertEquals(true, rows[1].string("payload_json").contains("Answer"))

            // 4. Checkpoint 3: grow second part text in-place (first part unchanged)
            val grownText = TextPart("Answer continued...")
            repo.checkpointAssistant(msg.id, "Answer continued...", listOf(finalizedReasoning, grownText), status = "STREAMING")
            partsCount = db.query("SELECT COUNT(*) AS c FROM message_parts WHERE message_id=?", listOf(msg.id)).single().long("c")
            assertEquals(2L, partsCount)
            val rowsAfterGrowth = db.query("SELECT ordinal, payload_json FROM message_parts WHERE message_id=? ORDER BY ordinal ASC", listOf(msg.id))
            assertEquals(true, rowsAfterGrowth[1].string("payload_json").contains("Answer continued..."))

            // 5. Checkpoint 4 (prune): list shrunk to 1 part -> ordinal >= 1 must be deleted
            val singleFinalPart = TextPart("Final Answer")
            repo.checkpointAssistant(msg.id, "Final Answer", listOf(singleFinalPart), status = "STREAMING")
            partsCount = db.query("SELECT COUNT(*) AS c FROM message_parts WHERE message_id=?", listOf(msg.id)).single().long("c")
            assertEquals(1L, partsCount)
            val finalRow = db.query("SELECT ordinal, payload_json FROM message_parts WHERE message_id=?", listOf(msg.id)).single()
            assertEquals(0L, finalRow.long("ordinal"))
            assertEquals(true, finalRow.string("payload_json").contains("Final Answer"))
        }
    }

    @Test
    fun checkpointTerminalStatusRejectsSubsequentCheckpoints() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            val conv = repo.create(snapshotId, "Terminal", "conversation.terminal")

            val initial = repo.append(
                conv.id,
                MessageRole.ASSISTANT,
                text = "Done",
                status = "COMPLETE",
                parts = listOf(TextPart("Done")),
            )

            // Calling checkpoint on COMPLETE message returns stored row unaltered
            val afterAttempt = repo.checkpointAssistant(
                initial.id,
                text = "Should not apply",
                parts = listOf(TextPart("Should not apply")),
                status = "STREAMING",
            )
            assertEquals("Done", afterAttempt.text)
            assertEquals("COMPLETE", afterAttempt.status)

            val partRow = db.query("SELECT payload_json FROM message_parts WHERE message_id=?", listOf(initial.id)).single()
            assertEquals(true, partRow.string("payload_json").contains("Done"))
        }
    }

    @Test
    fun checkpointNonAssistantAndNonexistentThrowsInvalidConfig() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val snapshotId = createSnapshot(db)
            val repo = ConversationRepository(db)
            val conv = repo.create(snapshotId, "Validation", "conversation.val")

            val userMsg = repo.append(conv.id, MessageRole.USER, text = "User message")
            val err1 = assertThrows(AppException::class.java) {
                repo.checkpointAssistant(userMsg.id, "Attempt", listOf(TextPart("Attempt")))
            }
            assertEquals(runtime.mobileagent.domain.ErrorCode.INVALID_CONFIG, err1.error.code)

            val err2 = assertThrows(AppException::class.java) {
                repo.checkpointAssistant("nonexistent-msg", "Attempt", listOf(TextPart("Attempt")))
            }
            assertEquals(runtime.mobileagent.domain.ErrorCode.INVALID_CONFIG, err2.error.code)
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
