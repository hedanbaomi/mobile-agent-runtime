// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.sql.DriverManager
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.knowledge.Citation

class ChatRunResponseTest {
    @Test fun cancellationCheckpointPreservesPartialReasoningToolsAndOnlyOwnRunCitations() = runBlocking {
        withConversation { conversations ->
            val own = Citation("own", "run", "kb", "doc", "chunk")
            val foreign = own.copy(citationId = "foreign", runId = "other-run")
            val response = ChatRunResponse(conversations, "conversation", "run",
                mapOf("own" to (own to "own evidence"), "foreign" to (foreign to "foreign evidence")),
                onFlush = { _, _, _, _ -> }, onAppend = {})
            response.assistantId = conversations.append("conversation", MessageRole.ASSISTANT, "", status = "STREAMING").id
            response.answer = "Partial answer"
            response.reasoning = "Declared reasoning"
            response.metadata = """{"visualBatchAnalysis":true}"""
            response.observed["call"] = ToolCallPart("call", "knowledge.search", "{}")
            response.terminalError = ErrorPart(MessageErrorCode.UNKNOWN_OUTCOME, "Result unknown")
            val ready = CompletableDeferred<Unit>()
            val job = launch {
                try { ready.complete(Unit); awaitCancellation() }
                finally { response.checkpoint("UNKNOWN_OUTCOME") }
            }
            ready.await()
            job.cancelAndJoin()
            val saved = conversations.messages("conversation").single()
            assertEquals("UNKNOWN_OUTCOME", saved.status)
            assertEquals("Partial answer", saved.text)
            assertEquals(response.metadata, saved.metadataJson)
            assertEquals(listOf(ReasoningPart("Declared reasoning", streaming = false)), saved.parts.filterIsInstance<ReasoningPart>())
            assertEquals(listOf("call"), saved.parts.filterIsInstance<ToolCallPart>().map { it.callId })
            assertEquals(listOf("own"), saved.parts.filterIsInstance<CitationPart>().map { it.citationId })
            assertEquals(MessageErrorCode.UNKNOWN_OUTCOME, saved.parts.filterIsInstance<ErrorPart>().single().code)
        }
    }

    @Test fun terminalErrorCreatesOneAssistantRowAndKeepsTerminalCheckpointImmutable() = runBlocking {
        withConversation { conversations ->
            val appended = mutableListOf<Message>()
            val response = ChatRunResponse(conversations, "conversation", "run", emptyMap(),
                onFlush = { _, _, _, _ -> }, onAppend = { appended += it })
            response.persistTerminalError(ErrorPart(MessageErrorCode.INTERNAL, "First failure"))
            response.answer = "Retained partial"
            response.persistTerminalError(ErrorPart(MessageErrorCode.UNKNOWN_OUTCOME, "Unknown failure"))
            assertEquals(1, appended.size)
            val saved = conversations.messages("conversation").single()
            assertEquals(appended.single().id, saved.id)
            assertEquals("ERROR", saved.status)
            assertEquals("First failure", saved.text)
            assertEquals(MessageErrorCode.INTERNAL, saved.parts.filterIsInstance<ErrorPart>().single().code)
            assertEquals("First failure", saved.parts.filterIsInstance<ErrorPart>().single().message)
        }
    }

    @Test fun errorCheckpointPreservesAlreadyStreamingPartialAndRejectsLaterReplacement() = runBlocking {
        withConversation { conversations ->
            val appended = mutableListOf<Message>()
            val response = ChatRunResponse(conversations, "conversation", "run", emptyMap(),
                onFlush = { _, _, _, _ -> }, onAppend = { appended += it })
            response.beginModelResponse("", "assistant")
            response.answer = "Retained partial"
            response.persistTerminalError(ErrorPart(MessageErrorCode.UNKNOWN_OUTCOME, "Unknown failure"))
            // Once ERROR is durable, neither a later collector checkpoint nor a later failure
            // may replace its partial response or downgrade the recorded unknown outcome.
            response.answer = "Late replacement"
            response.persistTerminalError(ErrorPart(MessageErrorCode.INTERNAL, "Late failure"))
            val saved = conversations.messages("conversation").single()
            assertEquals("assistant", saved.id)
            assertEquals("ERROR", saved.status)
            assertEquals("Retained partial", saved.text)
            assertEquals(listOf(TextPart("Retained partial")), saved.parts.filterIsInstance<TextPart>())
            assertEquals(MessageErrorCode.UNKNOWN_OUTCOME, saved.parts.filterIsInstance<ErrorPart>().single().code)
            assertEquals("Unknown failure", saved.parts.filterIsInstance<ErrorPart>().single().message)
            assertTrue(appended.isEmpty())
        }
    }

    @Test fun nextModelRoundCompletesPreviousResponseAndClearsTransientParts() = runBlocking {
        withConversation { conversations ->
            val response = ChatRunResponse(conversations, "conversation", "run", emptyMap(),
                onFlush = { _, _, _, _ -> }, onAppend = {})
            response.beginModelResponse("Warning\n", "first")
            response.appendText("answer synthetic-secret", listOf("synthetic-secret"))
            response.appendReasoning("reason synthetic-secret", listOf("synthetic-secret"))
            response.observed["call"] = ToolCallPart("call", "knowledge.search", "{}")
            response.terminalError = ErrorPart(MessageErrorCode.TOOL_FAILED, "Tool failed")
            response.beginModelResponse("", "second")
            val first = conversations.messages("conversation").first()
            assertEquals("COMPLETE", first.status)
            assertTrue(first.text.startsWith("Warning\nanswer"))
            assertFalse(first.text.contains("synthetic-secret"))
            assertFalse(first.parts.filterIsInstance<ReasoningPart>().single().text.contains("synthetic-secret"))
            assertFalse(first.parts.filterIsInstance<ReasoningPart>().single().streaming)
            assertEquals("second", response.assistantId)
            assertEquals("", response.answer)
            assertEquals("", response.reasoning)
            assertTrue(response.observed.isEmpty())
            assertNull(response.terminalError)
        }
    }

    @Test fun forcedFlushPublishesEvenInsideThrottleWindow() = runBlocking {
        withConversation { conversations ->
            var now = 100L
            val published = mutableListOf<String>()
            val response = ChatRunResponse(conversations, "conversation", "run", emptyMap(),
                onFlush = { _, text, _, _ -> published += text }, onAppend = {}, nowMillis = { now })
            response.flushStreamingAnswer(null, "first", false)
            now = 110
            response.flushStreamingAnswer(null, "throttled", false)
            response.flushStreamingAnswer(null, "terminal", true)
            assertEquals(listOf("first", "terminal"), published)
        }
    }

    private suspend fun withConversation(block: suspend (ConversationRepository) -> Unit) {
        JdbcConnection().use { db ->
            Migrations.apply(db)
            val at = "2026-10-10T00:00:00Z"
            db.execute("INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("snapshot", 1, "agent", "prompt", "model", 1, "[]", "[]", at))
            val conversations = ConversationRepository(db) { at }
            conversations.create("snapshot", "Response fixture", "conversation")
            block(conversations)
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

}
