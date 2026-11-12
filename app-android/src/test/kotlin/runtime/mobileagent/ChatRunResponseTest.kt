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

    @Test fun streamingSecretSplitAcrossDeltaBoundariesIsRedactedInAnswerAndDb() = runBlocking {
        withConversation { conversations ->
            val secret = "sk-secret998877665544"
            val response = ChatRunResponse(conversations, "conversation", "run", emptyMap(),
                onFlush = { _, _, _, _ -> }, onAppend = {})
            response.beginModelResponse("", "msg-split")
            response.appendText("Here is your secret: sk-", listOf(secret))
            assertEquals("Here is your secret: ", response.answer)

            response.appendText("secret998877665544, please protect it.", listOf(secret))
            response.finish()

            assertEquals("Here is your secret: ***, please protect it.", response.answer)
            val saved = conversations.messages("conversation").single()
            assertEquals("Here is your secret: ***, please protect it.", saved.text)
            assertFalse(saved.text.contains(secret))
        }
    }

    @Test fun cancellationOrErrorDiscardsUnconfirmedPrefixInSnapshotAndDb() = runBlocking {
        withConversation { conversations ->
            val secret = "sk-private-123456789"
            val response = ChatRunResponse(conversations, "conversation", "run", emptyMap(),
                onFlush = { _, _, _, _ -> }, onAppend = {})
            response.beginModelResponse("", "msg-cancel")
            response.appendText("Public confirmed. Leaking credential sk-", listOf(secret))
            assertEquals("Public confirmed. Leaking credential ", response.answer)

            // Cancellation drops unconfirmed pending prefix
            response.checkpoint("CANCELLED")

            val saved = conversations.messages("conversation").single()
            assertEquals("CANCELLED", saved.status)
            assertEquals("Public confirmed. Leaking credential ", saved.text)
            assertFalse(saved.text.contains("sk-"))
            assertFalse(saved.text.contains(secret))
        }
    }

    @Test fun mixedInterleavedChannelsDoNotCrossPollinateOrDropCharacters() = runBlocking {
        withConversation { conversations ->
            val textSecret = "sk-text-token-111"
            val reasonSecret = "sk-reason-token-222"
            val refusalSecret = "sk-refusal-token-333"
            val secrets = listOf(textSecret, reasonSecret, refusalSecret)

            val response = ChatRunResponse(conversations, "conversation", "run", emptyMap(),
                onFlush = { _, _, _, _ -> }, onAppend = {})
            response.beginModelResponse("", "msg-interleaved")

            // Interleaved text and reasoning chunks with split secrets
            response.appendText("answer start: sk-", secrets)
            response.appendReasoning("reasoning start: sk-", secrets)

            response.appendText("text-token-111 done.", secrets)
            response.appendReasoning("reason-token-222 done.", secrets)

            // Refusal chunks with split secret joining answer stream
            response.appendRefusal(" refusal: sk-", secrets)
            response.appendRefusal("refusal-token-333 done.", secrets)

            response.finish()

            val expectedAnswer = "answer start: *** done. refusal: *** done."
            val expectedReasoning = "reasoning start: *** done."
            assertEquals(expectedAnswer, response.answer)
            assertEquals(expectedReasoning, response.reasoning)

            assertFalse(response.answer.contains("reasoning start"))
            assertFalse(response.reasoning.contains("refusal:"))
            assertFalse(response.reasoning.contains("answer start"))

            val saved = conversations.messages("conversation").single()
            assertEquals(expectedAnswer, saved.text)
            assertEquals(expectedReasoning, saved.parts.filterIsInstance<ReasoningPart>().single().text)
        }
    }

    @Test fun streamingBenchmarkStressTest500DeltasWithThrottledUiAndCheckpoint() = runBlocking {
        withConversation { conversations ->
            var clock = 0L
            var uiFlushes = 0
            val secrets = listOf("sk-perf-secret-12345", "sk-perf-secret-67890")

            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = conversationIdFixture,
                runId = "run-benchmark",
                citations = emptyMap(),
                onFlush = { _, _, _, _ -> uiFlushes++ },
                onAppend = {},
                nowMillis = { clock },
            )
            response.beginModelResponse("", "msg-benchmark")

            val deltas = 500
            val startNs = System.nanoTime()

            for (i in 0 until deltas) {
                clock += 10L // 10ms per delta, total 5000ms
                val chunk = when {
                    i == 100 -> "sk-"
                    i == 101 -> "perf-secret-12345 "
                    i == 250 -> "sk-"
                    i == 251 -> "perf-secret-67890 "
                    else -> "chunk-$i "
                }
                response.appendText(chunk, secrets)
            }

            response.finish()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            // 500 deltas of 10ms span 5000ms:
            // UI flush threshold 50ms -> around 100-110 flushes (plus initial/finish)
            assertTrue(uiFlushes in 90..120, "Expected ~100 UI flushes across 5000ms, got $uiFlushes")
            assertTrue(elapsedMs < 300, "500-delta streaming took $elapsedMs ms; expected < 300ms for incremental O(N)")

            assertTrue(response.answer.contains("***"), "Secrets must be redacted")
            assertFalse(response.answer.contains("sk-perf-secret-12345"))
            assertFalse(response.answer.contains("sk-perf-secret-67890"))
        }
    }

    @Test fun forcedFlushNeverEmitsWithheldSecretPrefixBeforeNormalFinish() = runBlocking {
        withConversation { conversations ->
            val secretRef = "synthetic-secret-ref-99887766"
            val secretHalf = "synthetic-secret-"
            val flushedAnswers = mutableListOf<String>()
            val flushedReasonings = mutableListOf<String>()
            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = conversationIdFixture,
                runId = "run-no-premature-flush",
                citations = emptyMap(),
                onFlush = { _, text, reasoningText, _ ->
                    flushedAnswers += text
                    flushedReasonings += reasoningText
                },
                onAppend = {},
            )
            response.beginModelResponse("", "msg-no-flush")
            response.appendText("Safe answer: $secretHalf", listOf(secretRef))
            response.appendReasoning("Safe reasoning: $secretHalf", listOf(secretRef))
            response.flushStreamingAnswer(response.assistantId, response.answer, force = true, reasoningText = response.reasoning)

            assertEquals("Safe answer: ", response.answer)
            assertEquals("Safe reasoning: ", response.reasoning)
            assertTrue(flushedAnswers.none { it.contains(secretHalf) })
            assertTrue(flushedReasonings.none { it.contains(secretHalf) })
        }
    }

    @Test fun abnormalTerminalStatusesDiscardWithheldSecretPrefixAcrossAllChannels() = runBlocking {
        val secretRef = "synthetic-secret-ref-terminal-4455"
        val secretHalf = "synthetic-secret-ref-"
        for (status in listOf("BUDGET_EXHAUSTED", "CANCELLED", "FAILED", "ERROR", "UNKNOWN_OUTCOME")) {
            withConversation { conversations ->
                val flushed = mutableListOf<Pair<String, String>>()
                val response = ChatRunResponse(
                    conversations = conversations,
                    conversationId = conversationIdFixture,
                    runId = "run-$status",
                    citations = emptyMap(),
                    onFlush = { _, text, reasoningText, _ -> flushed += text to reasoningText },
                    onAppend = {},
                )
                response.beginModelResponse("", "msg-$status")
                response.appendText("Confirmed text. $secretHalf", listOf(secretRef))
                response.appendRefusal("Confirmed refusal. $secretHalf", listOf(secretRef))
                response.appendReasoning("Confirmed reasoning. $secretHalf", listOf(secretRef))

                response.discard()
                response.flushStreamingAnswer(response.assistantId, response.answer, force = true, reasoningText = response.reasoning)
                response.checkpoint(status)

                val saved = conversations.messages(conversationIdFixture).single()
                assertEquals(status, saved.status)
                assertFalse(saved.text.contains(secretHalf), "Leaked secret prefix in answer for status=$status: ${saved.text}")
                val savedReasoning = saved.parts.filterIsInstance<ReasoningPart>().single().text
                assertFalse(savedReasoning.contains(secretHalf), "Leaked secret prefix in reasoning for status=$status: $savedReasoning")
                assertTrue(flushed.none { it.first.contains(secretHalf) || it.second.contains(secretHalf) })
            }
        }
    }

    @Test fun throttledDeltasDeferSnapshotUntilWindowElapsesAndKeepVisibleTextExact() = runBlocking {
        withConversation { conversations ->
            var clock = 1_000L
            val snapshots = mutableListOf<Triple<String, String, Boolean>>()
            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = conversationIdFixture,
                runId = "run-throttle-defer",
                citations = emptyMap(),
                onFlush = { _, text, reasoningText, forced -> snapshots += Triple(text, reasoningText, forced) },
                onAppend = {},
                nowMillis = { clock },
            )
            response.beginModelResponse("", "msg-throttle")
            response.appendText("A1 ")
            assertEquals(listOf(Triple("A1 ", "", false)), snapshots)

            for (step in 1..9) {
                clock += 4L // 1004..1036 (< 1050ms throttle boundary)
                response.appendText("t$step ")
                response.appendReasoning("r$step ")
                response.appendRefusal("f$step ")
            }
            assertEquals(1, snapshots.size, "No UI snapshot should be emitted inside 50ms throttle window")

            clock = 1_055L
            response.appendText("emit!")
            assertEquals(2, snapshots.size)
            assertTrue(snapshots.last().first.endsWith("emit!"))
            assertTrue(snapshots.last().second.endsWith("r9 "))
            assertFalse(snapshots.last().third)
        }
    }

    private companion object {
        const val conversationIdFixture = "conversation"
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
            if (!connection.autoCommit) return block()
            connection.autoCommit = false
            return try { block().also { connection.commit() } }
            catch (failure: Throwable) { connection.rollback(); throw failure }
            finally { connection.autoCommit = true }
        }
        override fun close() = connection.close()
    }

}
