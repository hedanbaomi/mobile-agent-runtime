// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.sql.DriverManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.agent.*
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.provider.*

/** Actual Runtime + SQLite + the Chat collector's accounting, including a cancelled collector. */
class CompactionUsageSettlementTest {
    @Test fun successAccountsLatestSnapshotAndNormalReplyExactlyOnce() = scenario(Outcome.SUCCESS)
    @Test fun explicitFailureRetainsObservedUsage() = scenario(Outcome.FAILURE)
    @Test fun callerCancellationRetainsUsageWithoutAClosingCollectorEvent() = scenario(Outcome.CANCEL)
    @Test fun directStreamExceptionRetainsObservedUsage() = scenario(Outcome.THROW)
    @Test fun internalTimeoutRetainsObservedUsage() = scenario(Outcome.TIMEOUT)
    @Test fun missingUsageRemainsUnknownRatherThanEstimated() = scenario(Outcome.NO_USAGE)
    @Test fun cancellationAfterDurableSuccessBeforeDeliveryStillAccountsUsage() = scenario(Outcome.CANCEL_ON_COMMIT)

    private enum class Outcome { SUCCESS, FAILURE, CANCEL, THROW, TIMEOUT, NO_USAGE, CANCEL_ON_COMMIT }

    private fun scenario(outcome: Outcome) = runBlocking {
        JdbcConnection().use { db ->
            Migrations.apply(db)
            val at = "2026-09-11T00:00:00Z"
            db.execute("INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("snapshot", 1, "agent", "prompt", "model", 1, "[]", "[]", at))
            val conversations = ConversationRepository(db) { at }
            conversations.create("snapshot", "Usage fixture", "conversation")
            repeat(4) { index ->
                conversations.append("conversation", MessageRole.USER, "Question $index", messageId = "u$index")
                conversations.append("conversation", MessageRole.ASSISTANT, "Completed answer $index", messageId = "a$index")
            }
            val originals = conversations.messages("conversation")
            val runs = RunRepository(db) { at }
            var record = RunRecord("run", "snapshot", "conversation", state = RunStatus.ASSEMBLING, createdAt = at)
            runs.create(record)
            val attempts = ContextCompactionRepository(db) { at }
            val readyToCancel = CompletableDeferred<Unit>()
            val delivered = mutableListOf<RuntimeEvent>()
            val accounting = RunCompactionUsage()
            var requests = 0
            val adapter = object : ModelAdapter {
                override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
                override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
                override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
                    requests++
                    if (!request.messages.first().text.startsWith("Summarize the supplied conversation data")) {
                        emit(ModelEvent.TextDelta("Done")); emit(ModelEvent.Usage(7, 3)); emit(ModelEvent.Completed)
                        return@flow
                    }
                    if (outcome != Outcome.NO_USAGE) {
                        emit(ModelEvent.Usage(12, 2))
                        emit(ModelEvent.Usage(321, 42))
                        emit(ModelEvent.Usage(321, 42)) // Duplicate cumulative snapshot, never an increment.
                    }
                    when (outcome) {
                        Outcome.CANCEL -> { readyToCancel.complete(Unit); awaitCancellation() }
                        Outcome.TIMEOUT -> awaitCancellation()
                        Outcome.THROW, Outcome.NO_USAGE -> error("synthetic transport failure")
                        Outcome.FAILURE -> emit(ModelEvent.Failed("synthetic explicit failure"))
                        else -> { emit(ModelEvent.TextDelta(SUMMARY)); emit(ModelEvent.Completed) }
                    }
                }
            }
            val run = AgentRun("run", "snapshot", "conversation", budget = RunBudget(maxRuntimeMs = 250))
            val request = AgentRuntimeRequest(run = run,
                prompt = EffectivePrompt("runtime", "system", emptyList(), emptyList(), emptyList(), "Continue",
                    typedHistory = originals.map { ChatMessage(it.role.name.lowercase(), it.text) }),
                modelId = "model", secret = "synthetic-usage-fixture-secret".toCharArray(), toolsEnabled = false,
                maxInputBudgetUnits = 50_000,
                context = RuntimeContext(policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    historySources = originals.mapIndexed { index, message -> ContextSource(message.id, "turn-${index / 2}") },
                    currentUserMessageId = "current", modelFingerprint = "model-fp", authorizationFingerprint = "auth-fp",
                    persist = { attempt ->
                        val saved = if (attempt.state == ContextCompactionState.PREPARED) attempts.create(attempt)
                        else attempts.transition(attempt.id, attempt.state, attempt.summaryJson, attempt.afterUnits,
                            attempt.inputTokens, attempt.outputTokens)
                        if (outcome == Outcome.CANCEL_ON_COMMIT && saved.state == ContextCompactionState.SUCCEEDED) {
                            readyToCancel.complete(Unit)
                            awaitCancellation()
                        }
                        saved
                    }))
            val job = launch {
                try {
                    AgentRuntime(adapter, clock = { 0L }).run(request).collect { event ->
                        delivered += event
                        when (event) {
                            is RuntimeEvent.ContextCompactionChanged -> record = accounting.reconcile(record, listOf(event.record))
                            is RuntimeEvent.ModelEvent -> if (event.event is ModelEvent.Usage) {
                                val usage = event.event as ModelEvent.Usage
                                record = record.copy(inputTokens = record.inputTokens + usage.inputTokens,
                                    outputTokens = record.outputTokens + usage.outputTokens)
                            }
                            else -> Unit
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        record = accounting.reconcile(record, attempts.list("conversation"))
                        runs.save(record.copy(state = RunStatus.valueOf(run.state.name)))
                    }
                }
            }
            if (outcome == Outcome.CANCEL || outcome == Outcome.CANCEL_ON_COMMIT) {
                withTimeout(5_000) { readyToCancel.await() }; job.cancelAndJoin()
            } else job.join()
            val saved = attempts.list("conversation").single()
            val expected = when (outcome) {
                Outcome.SUCCESS, Outcome.CANCEL_ON_COMMIT -> ContextCompactionState.SUCCEEDED
                Outcome.FAILURE -> ContextCompactionState.FAILED
                else -> ContextCompactionState.UNKNOWN_OUTCOME
            }
            assertEquals(expected, saved.state)
            assertEquals(if (outcome == Outcome.NO_USAGE) 0 else 321, saved.inputTokens)
            assertEquals(if (outcome == Outcome.NO_USAGE) 0 else 42, saved.outputTokens)
            val persistedRun = runs.get("run")!!
            assertEquals(if (outcome == Outcome.NO_USAGE) 0 else if (outcome == Outcome.SUCCESS) 328 else 321, persistedRun.inputTokens)
            assertEquals(if (outcome == Outcome.NO_USAGE) 0 else if (outcome == Outcome.SUCCESS) 45 else 42, persistedRun.outputTokens)
            assertEquals(if (outcome == Outcome.SUCCESS) 2 else 1, requests)
            assertEquals(originals, conversations.messages("conversation"))
            assertEquals(persistedRun, accounting.reconcile(persistedRun, listOf(saved, saved.copy(id = "foreign", runId = "other"))))
            if (outcome == Outcome.CANCEL || outcome == Outcome.CANCEL_ON_COMMIT) {
                assertFalse(delivered.filterIsInstance<RuntimeEvent.ContextCompactionChanged>().any { it.record.state == expected })
            }
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

    companion object {
        private const val SUMMARY = "{\"goals\":[\"continue\"],\"constraints\":[\"retain originals\"],\"decisions\":[],\"pending\":[],\"results\":[]}"
    }
}
