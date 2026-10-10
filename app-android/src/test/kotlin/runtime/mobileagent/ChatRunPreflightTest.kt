// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*

class ChatRunPreflightTest {
    @Test fun unacknowledgedUnknownStopsBeforeNewUserMessage() = runBlocking {
        withConversation { db, conversations, runs ->
            runs.create(RunRecord("run", "snapshot", "conversation",
                state = RunStatus.UNKNOWN_OUTCOME, createdAt = AT))
            var projected = false
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null)
                .prepare("conversation", "New request") { projected = true }
            assertEquals(ChatPreflightResult.Unknown("run"), result)
            assertFalse(projected)
            assertTrue(conversations.messages("conversation").isEmpty())
        }
    }

    @Test fun bindingFailureKeepsUserMessageAndItsImmediateProjection() = runBlocking {
        withConversation { db, conversations, runs ->
            val projected = mutableListOf<Message>()
            // Deliberately lacks a resolvable model/prompt binding. User persistence must
            // precede that configuration failure and must not be retried by the preparer.
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null)
                .prepare("conversation", "Retain this first turn") { projected += it }
            assertTrue(result is ChatPreflightResult.Failed)
            assertTrue((result as ChatPreflightResult.Failed).userMessagePersisted)
            assertEquals(1, projected.size)
            assertEquals(projected, conversations.messages("conversation"))
            assertEquals("Retain this first turn", projected.single().text)
        }
    }

    @Test fun missingConversationDoesNotAppendOrProject() = runBlocking {
        withConversation { db, conversations, runs ->
            val result = ChatRunPreflight(runs, conversations, TransferRepository(db), null, null)
                .prepare("missing", "Request") { fail<Unit>("Missing conversation must not project") }
            assertEquals(ChatPreflightResult.MissingConversation, result)
            assertTrue(conversations.messages("conversation").isEmpty())
        }
    }

    private suspend fun withConversation(block: suspend (SqlConnection, ConversationRepository, RunRepository) -> Unit) {
        JdbcConnection().use { db ->
            Migrations.apply(db)
            db.execute("INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("snapshot", 1, "agent", "prompt", "model", 1, "[]", "[]", AT))
            val conversations = ConversationRepository(db) { AT }
            conversations.create("snapshot", "Preflight fixture", "conversation")
            block(db, conversations, RunRepository(db) { AT })
        }
    }

    private class JdbcConnection : SqlConnection, AutoCloseable {
        private val connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        }
        override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.execute()
            }
        }
        override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(SqlRow((1..result.metaData.columnCount).associate {
                    result.metaData.getColumnLabel(it) to result.getObject(it)
                }))
            } }
        }
        override fun <T> transaction(block: () -> T): T {
            connection.autoCommit = false
            return try { block().also { connection.commit() } }
            catch (failure: Throwable) { connection.rollback(); throw failure }
            finally { connection.autoCommit = true }
        }
        override fun close() = connection.close()
    }


    companion object { private const val AT = "2026-10-10T00:00:00Z" }
}
