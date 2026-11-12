// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.runtime.mutableStateOf
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.agent.RunState
import runtime.mobileagent.agent.RuntimeEvent
import runtime.mobileagent.data.ConversationRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.RunRepository
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.ErrorPart
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.ReasoningPart
import runtime.mobileagent.domain.RunRecord
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.feature.chat.ChatCitationUi
import runtime.mobileagent.feature.chat.ChatCompactionUi
import runtime.mobileagent.feature.chat.ChatMessageUi
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.provider.ModelEvent

class ChatRuntimeEventsTest {
    @Test
    fun modelEventFailedDiscardsUnconfirmedSecretRefPrefixFromUiAndSqlite() = runBlocking {
        val secretRef = "synthetic-secret-ref-failed-9001"
        val secretHalf = "synthetic-secret-ref-"
        withHarness(runId = "run-failed", secret = secretRef) { harness ->
            harness.events.accept(RuntimeEvent.RunStarted("run-failed", "snapshot", "conversation"))
            harness.events.accept(requestPrepared("msg-failed"))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.TextDelta("Confirmed answer. $secretHalf")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.ReasoningDelta("Confirmed reasoning. $secretHalf")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.Failed("INTERNAL")))
            harness.events.accept(RuntimeEvent.RunFinished("run-failed", RunState.FAILED, "error", 1, 0))

            assertTrue(harness.flushes.none { it.first.contains(secretHalf) || it.second.contains(secretHalf) })
            assertFalse(harness.ui.state.value.status.contains(secretHalf))

            val saved = harness.conversations.messages("conversation").single()
            assertEquals("ERROR", saved.status)
            assertTrue(saved.text.startsWith("Confirmed answer.\n"))
            assertFalse(saved.text.contains(secretHalf))
            val reasoning = saved.parts.filterIsInstance<ReasoningPart>().single().text
            assertEquals("Confirmed reasoning. ", reasoning)
            assertFalse(reasoning.contains(secretHalf))
            assertTrue(saved.parts.filterIsInstance<ErrorPart>().isNotEmpty())
            assertEquals(RunStatus.FAILED, harness.runs.get("run-failed")!!.state)
        }
    }

    @Test
    fun cancelledAndBudgetExhaustedRunsDiscardUnconfirmedSecretRefPrefixFromUiAndSqlite() = runBlocking {
        val secretRef = "synthetic-secret-ref-abort-9002"
        val secretHalf = "synthetic-secret-ref-"
        val cases = listOf(
            RunState.CANCELLED to "CANCELLED",
            RunState.BUDGET_EXHAUSTED to "BUDGET_EXHAUSTED",
        )
        for ((runState, terminalStatus) in cases) {
            val runId = "run-${runState.name.lowercase()}"
            withHarness(runId = runId, secret = secretRef) { harness ->
                harness.events.accept(RuntimeEvent.RunStarted(runId, "snapshot", "conversation"))
                harness.events.accept(requestPrepared("msg-$terminalStatus"))
                harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.TextDelta("Safe prefix: $secretHalf")))
                harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.RefusalDelta(secretHalf)))
                harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.ReasoningDelta("Safe thought: $secretHalf")))
                harness.events.accept(RuntimeEvent.RunFinished(runId, runState, terminalStatus.lowercase(), 1, 0))
                harness.response.checkpoint(terminalStatus)

                assertTrue(
                    harness.flushes.none { it.first.contains(secretHalf) || it.second.contains(secretHalf) },
                    "UI flush leaked half-secret for $runState: ${harness.flushes}",
                )
                val saved = harness.conversations.messages("conversation").single()
                assertEquals(terminalStatus, saved.status)
                assertEquals("Safe prefix: ", saved.text)
                assertFalse(saved.text.contains(secretHalf))
                val savedReasoning = saved.parts.filterIsInstance<ReasoningPart>().single().text
                assertEquals("Safe thought: ", savedReasoning)
                assertFalse(savedReasoning.contains(secretHalf))
                assertEquals(RunStatus.valueOf(runState.name), harness.runs.get(runId)!!.state)
            }
        }
    }

    @Test
    fun completedRunWiresBeginModelResponseRefusalReasoningAndSettlesSafeTailsAcrossRounds() = runBlocking {
        val secretRef = "not-a-real-secret-ref-9003"
        withHarness(runId = "run-complete", secret = secretRef, warning = "Notice") { harness ->
            harness.events.accept(RuntimeEvent.RunStarted("run-complete", "snapshot", "conversation"))
            harness.events.accept(requestPrepared("round-1"))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.ReasoningDelta("Plan sk")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.ReasoningDelta("-synthetic1234567890 ok")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.RefusalDelta("Refusing Cook")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.RefusalDelta("ie: sid=synthetic_refusal\n")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.RefusalDelta("and not-a-real-")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.RefusalDelta("secret-ref-9003. Do not")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.Completed))

            harness.events.accept(requestPrepared("round-2"))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.TextDelta("Second round clean Cook")))
            harness.events.accept(RuntimeEvent.ModelEvent(ModelEvent.Completed))
            harness.events.accept(RuntimeEvent.RunFinished("run-complete", RunState.COMPLETED, "stop", 2, 0))
            harness.response.checkpoint("COMPLETE")

            val messages = harness.conversations.messages("conversation")
            assertEquals(2, messages.size)

            val first = messages[0]
            assertEquals("COMPLETE", first.status)
            assertEquals("Notice\n\nRefusing Cookie: ***\nand ***. Do not", first.text)
            assertFalse(first.text.contains(secretRef))
            assertFalse(first.text.contains("synthetic_refusal"))
            assertEquals("Plan *** ok", first.parts.filterIsInstance<ReasoningPart>().single().text)

            val second = messages[1]
            assertEquals("COMPLETE", second.status)
            assertEquals("Second round clean Cook", second.text)
            assertEquals(RunStatus.COMPLETED, harness.runs.get("run-complete")!!.state)
        }
    }

    private fun requestPrepared(messageId: String) = RuntimeEvent.RequestPrepared(
        operationId = "op-$messageId",
        modelId = "model",
        messages = emptyList(),
        toolNames = emptyList(),
        parameterKeys = emptyList(),
        headerNames = emptyList(),
        assistantMessageId = messageId,
    )

    private class Harness(
        val conversations: ConversationRepository,
        val runs: RunRepository,
        val ui: RecordingUi,
        val response: ChatRunResponse,
        val events: ChatRuntimeEvents,
        val flushes: List<Pair<String, String>>,
    )

    private suspend fun withHarness(
        runId: String,
        secret: String,
        warning: String? = null,
        block: suspend (Harness) -> Unit,
    ) {
        JdbcConnection().use { db ->
            Migrations.apply(db)
            val at = "2026-10-10T00:00:00Z"
            db.execute(
                "INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("snapshot", 1, "agent", "prompt", "model", 1, "[]", "[]", at),
            )
            val conversations = ConversationRepository(db) { at }
            val runs = RunRepository(db) { at }
            conversations.create("snapshot", "RuntimeEvents fixture", "conversation")
            val initialRecord = runs.create(
                RunRecord(
                    runId = runId,
                    snapshotId = "snapshot",
                    conversationId = "conversation",
                    createdAt = at,
                ),
            )
            val flushes = mutableListOf<Pair<String, String>>()
            var clock = 1_000L
            val response = ChatRunResponse(
                conversations = conversations,
                conversationId = "conversation",
                runId = runId,
                citations = emptyMap(),
                onFlush = { _, text, reasoningText, _ ->
                    clock += 100L
                    flushes += text to reasoningText
                },
                onAppend = {},
                nowMillis = { clock },
            )
            val ui = RecordingUi()
            val owner = ChatRunOwner(runId, "conversation", 1L)
            val state = object : ChatRunEventState {
                override var record: RunRecord = initialRecord
                override var round: Int = 0
                override var modelInFlight: Boolean = false
                override var approvedToolInFlight: Boolean = false
                override var approvedToolCallId: String? = null
                override var toolCallInFlight: Boolean = false
                override var toolWaitingApproval: Boolean = false
                override var preparationStage: String? = null
            }
            val events = ChatRuntimeEvents(
                conversations = conversations,
                runs = runs,
                ui = ui,
                owner = owner,
                runId = runId,
                modelRounds = { state.round },
                runCitations = linkedMapOf(),
                invocations = linkedMapOf(),
                secret = secret.toCharArray(),
                warning = warning,
                progress = state,
                response = response,
                lastProgressAt = AtomicLong(0L),
                invocationId = { _, _ -> null },
                captureRequestPreview = { _, _ -> },
                recordInvocation = { _, _, _, _, _ -> },
                headerNames = emptySet(),
                elapsedRealtime = { clock },
            )
            block(Harness(conversations, runs, ui, response, events, flushes))
        }
    }

    private class RecordingUi : ChatExecutionUi {
        override val state = mutableStateOf(ChatUiState())
        override val unknownRetry = mutableStateOf<String?>(null)
        override val inputRevision: Long = 0L
        override val citations: Map<String, Pair<Citation, String>> = emptyMap()
        override val imageDraft: ChatImageDraft get() = error("unused")
        override val requestPreviews: ChatRequestPreviews get() = error("unused")
        override fun canProjectRun(owner: ChatRunOwner): Boolean = true
        override fun publishRunState(owner: ChatRunOwner, next: ChatUiState) {
            state.value = next
        }
        override fun saveRunDraft(owner: ChatRunOwner) = Unit
        override fun failRun(owner: ChatRunOwner, failure: Exception) = throw failure
        override fun messageUi(message: Message): ChatMessageUi = error("unused")
        override fun citationUis(source: Map<String, Pair<Citation, String>>): List<ChatCitationUi> = emptyList()
        override fun publishRunCitations(owner: ChatRunOwner, source: Map<String, Pair<Citation, String>>) = Unit
        override suspend fun refreshMessages(owner: ChatRunOwner) = Unit
        override fun compactionUi(record: ContextCompactionRecord): ChatCompactionUi = error("unused")
        override fun reloadChat(preserveTerminalStatus: Boolean) = Unit
    }

    private class JdbcConnection : SqlConnection, AutoCloseable {
        private val connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        }
        override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.execute()
            }
        }
        override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            SqlRow(
                                (1..result.metaData.columnCount).associate {
                                    result.metaData.getColumnLabel(it) to result.getObject(it)
                                },
                            ),
                        )
                    }
                }
            }
        }
        override fun <T> transaction(block: () -> T): T {
            if (!connection.autoCommit) return block()
            connection.autoCommit = false
            return try {
                block().also { connection.commit() }
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }
        override fun close() = connection.close()
    }
}
