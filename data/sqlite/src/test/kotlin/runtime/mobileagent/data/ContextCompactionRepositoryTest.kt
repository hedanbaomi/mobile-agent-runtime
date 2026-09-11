// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.*

class ContextCompactionRepositoryTest {
    private val summary = """{"goals":["continue"],"constraints":["preserve originals"],"decisions":[],"pending":[],"results":[]}"""
    private val at = "2026-09-11T00:00:00Z"

    @Test fun successKeepsTranscriptAndTerminalRowsImmutable() = fixture { db, repo ->
        val before = ConversationRepository(db).messages("c")
        repo.create(attempt("z"))
        repo.transition("z", ContextCompactionState.DISPATCHED)
        val result = repo.transition("z", ContextCompactionState.SUCCEEDED, summary, 500, 100, 30)
        assertEquals(before, ConversationRepository(db).messages("c"))
        assertTrue(repo.validateSources(result))
        assertEquals(result, repo.latestSucceeded("c", "s", "model-fp", "auth-fp"))
        assertEquals(100, result.inputTokens)
        assertThrows(AppException::class.java) { repo.transition("z", ContextCompactionState.FAILED) }
    }

    @Test fun failedAndUnknownAttemptsKeepObservedBillableUsage() = fixture { _, repo ->
        repo.create(attempt("failed")); repo.transition("failed", ContextCompactionState.DISPATCHED)
        val failed = repo.transition("failed", ContextCompactionState.FAILED, inputTokens = 210, outputTokens = 8)
        assertEquals(210, failed.inputTokens)
        assertNull(failed.summaryJson)
        repo.create(attempt("unknown")); repo.transition("unknown", ContextCompactionState.DISPATCHED)
        val unknown = repo.transition("unknown", ContextCompactionState.UNKNOWN_OUTCOME, inputTokens = 300)
        assertEquals(300, unknown.inputTokens)
        assertNull(repo.latestSucceeded("c", "s", "model-fp", "auth-fp"))
    }

    @Test fun coverageBeyond512MessagesAndSameTimestampChainsRemainUsable() = fixture { db, repo ->
        repeat(600) { message(db, "m${it + 1}") }
        val ids = (0..600).map { "m$it" }
        repo.create(attempt("z", ids.take(510)))
        repo.transition("z", ContextCompactionState.DISPATCHED)
        repo.transition("z", ContextCompactionState.SUCCEEDED, summary, 500)
        repo.create(attempt("a", ids).copy(parentId = "z"))
        repo.transition("a", ContextCompactionState.DISPATCHED)
        val last = repo.transition("a", ContextCompactionState.SUCCEEDED, summary, 550)
        assertEquals(601, last.sourceMessageIds.size)
        assertEquals("a", repo.latestSucceeded("c", "s", "model-fp", "auth-fp")?.id)
        assertEquals(listOf("z", "a"), repo.list("c").map { it.id })
        assertThrows(AppException::class.java) { repo.create(attempt("stale").copy(parentId = "z")) }
    }

    @Test fun sourcesMustBelongToBoundRunAndRemainCompleteInOrder() = fixture { db, repo ->
        seed(db, "other", "other-s", "other-r")
        message(db, "other-m", "other")
        assertThrows(AppException::class.java) { repo.create(attempt("cross", listOf("other-m"))) }
        assertThrows(AppException::class.java) { repo.create(attempt("cross-run").copy(runId = "other-r")) }
        assertThrows(AppException::class.java) { repo.create(attempt("duplicate", listOf("m0", "m0"))) }
        message(db, "later")
        assertThrows(AppException::class.java) { repo.create(attempt("order", listOf("later", "m0"))) }
        db.execute("UPDATE messages SET status='STREAMING' WHERE id='later'")
        assertThrows(AppException::class.java) { repo.create(attempt("stream", listOf("later"))) }
        repo.create(attempt("changed")); repo.transition("changed", ContextCompactionState.DISPATCHED)
        db.execute("UPDATE messages SET text='modified' WHERE id='m0'")
        assertThrows(AppException::class.java) { repo.transition("changed", ContextCompactionState.SUCCEEDED, summary) }
        assertFalse(repo.validateSources(repo.get("changed")!!))
    }

    @Test fun oneActiveAttemptPerConversationAndRevokedFingerprintCannotReviveOlderSummary() = fixture { _, repo ->
        repo.create(attempt("first"))
        assertThrows(AppException::class.java) { repo.create(attempt("concurrent").copy(modelFingerprint = "other")) }
        repo.transition("first", ContextCompactionState.DISPATCHED)
        repo.transition("first", ContextCompactionState.SUCCEEDED, summary)
        assertNull(repo.latestSucceeded("c", "s", "model-fp", "revoked"))
        repo.create(attempt("second").copy(parentId = "first", authorizationFingerprint = "new-auth"))
        repo.transition("second", ContextCompactionState.DISPATCHED)
        repo.transition("second", ContextCompactionState.SUCCEEDED, summary)
        assertNull(repo.latestSucceeded("c", "s", "model-fp", "auth-fp"))
        assertEquals("second", repo.latestSucceeded("c", "s", "model-fp", "new-auth")?.id)
    }

    @Test fun restartSettlesAttemptsWithoutReplayOrTranscriptRewrite() = fixture { db, repo ->
        seed(db, "other", "other-s", "other-r"); message(db, "other-m", "other")
        repo.create(attempt("prepared"))
        repo.create(attempt("dispatched", listOf("other-m")).copy(conversationId = "other", snapshotId = "other-s", runId = "other-r"))
        repo.transition("dispatched", ContextCompactionState.DISPATCHED)
        val before = ConversationRepository(db).messages("c")
        RunRepository(db).markInFlightUnknown(at)
        assertEquals(ContextCompactionState.CANCELLED, repo.get("prepared")?.state)
        assertEquals(ContextCompactionState.UNKNOWN_OUTCOME, repo.get("dispatched")?.state)
        assertEquals(before, ConversationRepository(db).messages("c"))
        assertThrows(AppException::class.java) { repo.transition("dispatched", ContextCompactionState.SUCCEEDED, summary) }
        assertThrows(AppException::class.java) { repo.create(attempt("replay")) }
        assertEquals(emptyList<String>(), repo.markInFlightUnknown(at))
    }

    @Test fun v17UpgradeAndRepeatedOpenPreserveMessages() = fixture { db, repo ->
        val before = ConversationRepository(db).messages("c")
        db.execute("DROP TABLE context_compactions")
        db.execute("UPDATE schema_version SET version=17")
        Migrations.apply(db)
        assertEquals(18L, db.query("SELECT version FROM schema_version").single().long("version"))
        assertEquals(before, ConversationRepository(db).messages("c"))
        repo.create(attempt("saved")); repo.transition("saved", ContextCompactionState.DISPATCHED)
        repo.transition("saved", ContextCompactionState.SUCCEEDED, summary)
        Migrations.apply(db)
        assertEquals("saved", ContextCompactionRepository(db).latestSucceeded("c", "s", "model-fp", "auth-fp")?.id)
    }

    @Test fun strictSummaryRejectsExecutableOrEmptyPayload() = fixture { db, repo ->
        repo.create(attempt("invalid")); repo.transition("invalid", ContextCompactionState.DISPATCHED)
        assertThrows(IllegalArgumentException::class.java) {
            repo.transition("invalid", ContextCompactionState.SUCCEEDED, """{"tool_calls":[]}""")
        }
        assertEquals(ContextCompactionState.DISPATCHED, repo.get("invalid")?.state)
        assertEquals(1, ConversationRepository(db).messages("c").size)
    }

    @Test fun terminalRunBetweenPrepareAndDispatchCannotSendSummary() = fixture { db, repo ->
        repo.create(attempt("prepared"))
        db.execute("UPDATE runs SET state='CANCELLED' WHERE run_id='r'")
        assertThrows(AppException::class.java) { repo.transition("prepared", ContextCompactionState.DISPATCHED) }
        assertEquals(ContextCompactionState.PREPARED, repo.get("prepared")?.state)
    }

    private fun fixture(block: (JdbcSqlConnection, ContextCompactionRepository) -> Unit) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db); seed(db); message(db, "m0")
            block(db, ContextCompactionRepository(db) { at })
        }
    }
    private fun attempt(id: String, ids: List<String> = listOf("m0")) = ContextCompactionRecord(
        id, "c", "s", "r", ids, "input-hash", "model", "model-fp", "auth-fp", "history-messages",
        beforeUnits = 10_000, createdAt = at,
    )
    private fun message(db: SqlConnection, id: String, conversation: String = "c") {
        ConversationRepository(db) { at }.append(conversation, MessageRole.USER, "Original $id", messageId = id)
    }
    private fun seed(db: SqlConnection, conversation: String = "c", snapshot: String = "s", run: String = "r") {
        db.execute("INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf(snapshot, 1, "agent", "prompt", "model", 1, "[]", "[]", at))
        ConversationRepository(db) { at }.create(snapshot, "Fixture", conversation)
        RunRepository(db) { at }.create(RunRecord(run, snapshot, conversation, state = RunStatus.ASSEMBLING, createdAt = at))
    }
}
