// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.AgentContextPolicy
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.ContextCompactionState
import runtime.mobileagent.provider.AssistantToolCall
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.ChatMessage
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.InlineImage
import runtime.mobileagent.provider.InputBudgetEstimate
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.ProviderContinuationItem
import runtime.mobileagent.provider.RequestInputBudget
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

class ContextCompactionRuntimeTest {
    @Test
    fun oversizedHistoricalToolArgumentsFailBeforeProviderDispatchWhenAutoCompactionIsDisabled() = runTest {
        val arguments = "{\"payload\":\"${"x".repeat(65_000)}\"}"
        val history = listOf(
            ChatMessage("user", "Original constraint: keep the result local."),
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(AssistantToolCall("call-large", "external", arguments)),
            ),
            ChatMessage("tool", "{\"ok\":true}", toolCallId = "call-large"),
        )
        val adapter = RecordingAdapter { _, _ -> emit(ModelEvent.Completed) }
        val run = AgentRun("overflow-history", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history),
                context = context(history, AgentContextPolicy(autoCompact = false)),
                maxInputBudgetUnits = 64_000,
            ),
        ).toList()

        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals("context-or-image-budget", run.stopReason)
        assertTrue(adapter.requests.isEmpty(), "oversized history must not reach the provider")
        assertTrue(events.hasFailureContaining("CONTEXT_OVERFLOW"))
    }

    @Test
    fun oversizedToolCallBlocksFollowingRequestWithoutReplayingFirstCall() = runTest {
        val arguments = "{\"payload\":\"${"x".repeat(40_000)}\"}"
        val adapter = RecordingAdapter { _, index ->
            if (index == 0) {
                emit(ModelEvent.ToolCallDelta("call-large", "external", arguments))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.TextDelta("unexpected replay"))
                emit(ModelEvent.Completed)
            }
        }
        val executor = ValueExecutor()
        val run = AgentRun("overflow-after-tool", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(currentUser = "execute once"),
                context = context(emptyList(), AgentContextPolicy(autoCompact = false)),
                maxInputBudgetUnits = 20_000,
                toolsEnabled = true,
                executor = executor,
            ),
        ).toList()

        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals("context-or-image-budget", run.stopReason)
        assertEquals(1, executor.invocations)
        assertEquals(1, adapter.requests.size)
        assertTrue(events.hasFailureContaining("CONTEXT_OVERFLOW"))
    }

    @Test
    fun historyOverTwentyMessagesCompactsAndKeepsInitialConstraintAndRecentCompleteRounds() = runTest {
        val (history, turnIds) = twelveRoundHistory()
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.TextDelta("done"))
                emit(ModelEvent.Completed)
            }
        }
        val run = AgentRun("history-compaction", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history, currentUser = "current question"),
                context = context(
                    history = history,
                    policy = AgentContextPolicy(maxHistoryMessages = 20, keepRecentTurns = 2),
                    turnIds = turnIds,
                ),
                maxInputBudgetUnits = 50_000,
            ),
        ).toList()

        assertEquals(RunState.COMPLETED, run.state)
        assertEquals(1, run.compactionRequests)
        assertEquals(2, run.modelRounds, "one summary request plus one continuation request")
        assertEquals(2, adapter.requests.size)
        val summaryRequest = adapter.requests.first()
        val continuationRequest = adapter.requests.last()
        assertTrue(summaryRequest.isCompaction())
        assertTrue(continuationRequest.messages.any { it.text.contains("[Conversation summary:") })
        assertTrue(continuationRequest.messages.any { it.text == "Original constraint: preserve this instruction." })
        (10..11).forEach { turn ->
            assertTrue(continuationRequest.messages.any { it.text == "history turn $turn user" })
            assertTrue(continuationRequest.messages.any { it.text == "history turn $turn assistant" })
        }
        assertFalse(continuationRequest.messages.any { it.text == "history turn 2 user" })
        assertTrue(events.any { it is RuntimeEvent.ContextCompactionChanged })
    }

    @Test
    fun summaryUsesSameModelWithoutToolsAndPersistsSourcesHashAndUsage() = runTest {
        val history = listOf(
            ChatMessage("user", "Original constraint: preserve this instruction."),
            ChatMessage("assistant", "old answer 0"),
            ChatMessage("user", "old question 1"),
            ChatMessage("assistant", "old answer 1"),
            ChatMessage("user", "recent question"),
            ChatMessage("assistant", "recent answer"),
        )
        val turnIds = listOf("turn-0", "turn-0", "turn-1", "turn-1", "turn-2", "turn-2")
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.Usage(inputTokens = 321, outputTokens = 42))
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.TextDelta("continued"))
                emit(ModelEvent.Completed)
            }
        }
        val persisted = mutableListOf<ContextCompactionRecord>()
        val run = AgentRun("summary-metadata", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history),
                context = context(
                    history = history,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    turnIds = turnIds,
                    persist = { record ->
                        persisted += record
                        record
                    },
                ),
                maxInputBudgetUnits = 50_000,
                modelId = "same-model",
            ),
        ).toList()

        val summaryRequest = adapter.requests.first { it.isCompaction() }
        val finalRecord = events
            .filterIsInstance<RuntimeEvent.ContextCompactionChanged>()
            .map { it.record }
            .last()
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals("same-model", summaryRequest.modelId)
        assertTrue(summaryRequest.tools.isEmpty())
        assertTrue(summaryRequest.messages.last().text.contains("h-"))
        assertTrue(finalRecord.sourceMessageIds.isNotEmpty())
        assertTrue(finalRecord.inputHash.isNotBlank())
        assertEquals(ContextWindow.digest(summaryRequest.messages), finalRecord.inputHash)
        assertEquals(321, finalRecord.inputTokens)
        assertEquals(42, finalRecord.outputTokens)
        assertEquals(ContextCompactionState.SUCCEEDED, finalRecord.state)
        assertEquals(finalRecord, persisted.last())
    }

    @Test
    fun segmentCompactionResetsOnlySegmentRoundsWhileTotalModelAndToolBudgetsRemainCumulative() = runTest {
        var normalCalls = 0
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
                emit(ModelEvent.Completed)
            } else {
                val callId = "call-${normalCalls++}"
                emit(ModelEvent.ToolCallDelta(callId, "external", "{}"))
                emit(ModelEvent.Completed)
            }
        }
        val executor = ValueExecutor()
        val run = AgentRun(
            "segment-budget",
            "snapshot",
            "conversation",
            budget = RunBudget(maxModelRounds = 5, maxToolCalls = 100, maxRuntimeMs = 60_000),
        )
        AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(currentUser = "keep running"),
                context = context(
                    history = emptyList(),
                    policy = AgentContextPolicy(
                        maxHistoryMessages = 100,
                        maxHistoryTurns = 100,
                        maxModelRoundsPerSegment = 2,
                    ),
                ),
                maxInputBudgetUnits = 100_000,
                toolsEnabled = true,
                executor = executor,
            ),
        ).toList()

        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals("context-compaction-request-budget", run.stopReason)
        assertEquals(1, run.compactionRequests)
        assertEquals(5, run.modelRounds, "the segment counter may reset, but total rounds do not")
        assertEquals(4, run.toolCalls, "tool budget accounting survives compaction")
        assertEquals(4, executor.invocations)
        assertEquals(5, adapter.requests.size)
        assertEquals(1, adapter.requests.count { it.isCompaction() })
    }

    @Test
    fun invalidSummaryFailsWithoutReplacingOriginalHistoryOrRetrying() = runTest {
        val history = compactableHistory()
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.TextDelta("{}"))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.TextDelta("must not run"))
                emit(ModelEvent.Completed)
            }
        }
        val persisted = mutableListOf<ContextCompactionRecord>()
        val run = AgentRun("invalid-summary", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history),
                context = context(
                    history = history,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    turnIds = compactableTurnIds(),
                    persist = { record ->
                        persisted += record
                        record
                    },
                ),
                maxInputBudgetUnits = 50_000,
            ),
        ).toList()

        assertEquals(RunState.FAILED, run.state)
        assertTrue(run.stopReason.orEmpty().contains("invalid summary"))
        assertEquals(1, adapter.requests.size)
        assertEquals(
            listOf(ContextCompactionState.PREPARED, ContextCompactionState.DISPATCHED, ContextCompactionState.FAILED),
            persisted.map { it.state },
        )
        assertEquals(ContextCompactionState.FAILED, events.lastCompaction().state)
        assertTrue(events.hasFailureContaining("invalid summary"))
    }

    @Test
    fun nonReducingInputBudgetSummaryFailsWithoutAutomaticRetry() = runTest {
        val history = compactableHistory()
        val adapter = RecordingAdapter(
            script = { request, _ ->
                if (request.isCompaction()) {
                    emit(ModelEvent.TextDelta(VALID_SUMMARY))
                    emit(ModelEvent.Completed)
                } else {
                    emit(ModelEvent.Completed)
                }
            },
            estimator = { request ->
                if (request.messages.any { it.text.contains("[Conversation summary:") }) {
                    InputBudgetEstimate(10_000, 0)
                } else {
                    InputBudgetEstimate(1_000, 0)
                }
            },
        )
        val run = AgentRun("non-reducing", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history),
                context = context(
                    history = history,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    turnIds = compactableTurnIds(),
                ),
                maxInputBudgetUnits = 1_000,
            ),
        ).toList()

        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertTrue(run.stopReason.orEmpty().contains("summary did not reduce"))
        assertEquals(1, adapter.requests.size)
        assertEquals(ContextCompactionState.FAILED, events.lastCompaction().state)
        assertTrue(events.hasFailureContaining("summary did not reduce"))
    }

    @Test
    fun summaryWithoutCompletedIsUnknownAndIsNeverRetriedOrReplaced() = runTest {
        val history = compactableHistory()
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
            } else {
                emit(ModelEvent.TextDelta("must not run"))
                emit(ModelEvent.Completed)
            }
        }
        val run = AgentRun("summary-no-completed", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history),
                context = context(
                    history = history,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    turnIds = compactableTurnIds(),
                ),
                maxInputBudgetUnits = 50_000,
            ),
        ).toList()

        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, adapter.requests.size)
        assertEquals(ContextCompactionState.UNKNOWN_OUTCOME, events.lastCompaction().state)
        assertTrue(events.hasFailureContaining("UNKNOWN_OUTCOME"))
    }

    @Test
    fun summaryTimeoutBecomesUnknownWithoutReplacement() = runTest {
        val history = compactableHistory()
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
                delay(5_000)
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.Completed)
            }
        }
        val run = AgentRun(
            "summary-timeout",
            "snapshot",
            "conversation",
            budget = RunBudget(maxRuntimeMs = 1_000),
        )
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history),
                context = context(
                    history = history,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    turnIds = compactableTurnIds(),
                ),
                maxInputBudgetUnits = 50_000,
            ),
        ).toList()

        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, adapter.requests.size)
        assertEquals(ContextCompactionState.UNKNOWN_OUTCOME, events.lastCompaction().state)
        assertTrue(events.hasFailureContaining("UNKNOWN_OUTCOME"))
    }

    @Test
    fun cancellingSummaryMarksRunCancelledAndPersistsUnknownCompactionWithoutRetry() = runTest {
        val history = compactableHistory()
        val summaryStarted = CompletableDeferred<Unit>()
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                summaryStarted.complete(Unit)
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
                awaitCancellation()
            } else {
                emit(ModelEvent.Completed)
            }
        }
        val persisted = mutableListOf<ContextCompactionRecord>()
        val run = AgentRun("summary-cancel", "snapshot", "conversation")
        val job = launch {
            try {
                AgentRuntime(adapter).run(
                    request(
                        run = run,
                        prompt = prompt(history = history),
                        context = context(
                            history = history,
                            policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                            turnIds = compactableTurnIds(),
                            persist = { record ->
                                persisted += record
                                record
                            },
                        ),
                        maxInputBudgetUnits = 50_000,
                    ),
                ).toList()
            } catch (_: CancellationException) {
                // The runtime intentionally propagates caller cancellation.
            }
        }
        summaryStarted.await()
        job.cancelAndJoin()

        assertEquals(RunState.CANCELLED, run.state)
        assertTrue(run.stopReason.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, adapter.requests.size)
        assertEquals(ContextCompactionState.UNKNOWN_OUTCOME, persisted.last().state)
    }

    @Test
    fun protectedImagePrivateContinuationAndToolResultRemainAtomic() {
        val continuation = ProviderContinuationItem(itemId = "reasoning-1", encryptedContent = "opaque-encrypted")
        val history = listOf(
            ChatMessage("user", "Original constraint"),
            ChatMessage(
                role = "assistant",
                toolCalls = listOf(AssistantToolCall("call-1", "external", "{\"q\":\"x\"}")),
                providerContinuationItems = listOf(continuation),
            ),
            ChatMessage("tool", "{\"evidence\":true}", toolCallId = "call-1"),
            ChatMessage(
                role = "user",
                text = "Visual evidence",
                images = listOf(InlineImage("image/png", "opaque-image", "asset-image")),
            ),
            ChatMessage("user", "discardable older question"),
            ChatMessage("assistant", "discardable older answer"),
            ChatMessage("user", "recent question"),
            ChatMessage("assistant", "recent answer"),
        )
        val turnIds = listOf("turn-0", "turn-1", "turn-1", "turn-1", "turn-2", "turn-2", "turn-3", "turn-3")
        val context = context(
            history = history,
            policy = AgentContextPolicy(keepRecentTurns = 1),
            turnIds = turnIds,
        )
        val request = ModelRequest("model", prompt(history = history).asMessages())
        val minimum = ContextPreflight.minimumRequest(prompt(history = history), context, request)

        assertTrue(minimum.messages.any { it.toolCalls.any { call -> call.id == "call-1" } })
        assertTrue(minimum.messages.any { it.role == "tool" && it.toolCallId == "call-1" })
        assertTrue(minimum.messages.any { it.images.any { image -> image.assetId == "asset-image" } })
        val retainedAssistant = minimum.messages.first { it.toolCalls.any { call -> call.id == "call-1" } }
        assertEquals(listOf(continuation), retainedAssistant.providerContinuationItems)
        assertTrue(minimum.messages.any { it.text == "Original constraint" })
        assertTrue(minimum.messages.any { it.text == "recent question" })
        assertTrue(minimum.messages.any { it.text == "recent answer" })
        assertFalse(minimum.messages.any { it.text == "discardable older question" })
        assertFalse(minimum.messages.any { it.text == "discardable older answer" })
    }

    @Test
    fun successfulSummaryCanBeRestoredAndNextSummaryUsesUnionOfSourceIds() = runTest {
        val firstHistory = compactableHistory()
        val firstAdapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.Completed)
            }
        }
        val firstRun = AgentRun("summary-first", "snapshot", "conversation")
        val firstEvents = AgentRuntime(firstAdapter).run(
            request(
                run = firstRun,
                prompt = prompt(history = firstHistory),
                context = context(
                    history = firstHistory,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    turnIds = compactableTurnIds(),
                ),
                maxInputBudgetUnits = 50_000,
            ),
        ).toList()
        val firstRecord = firstEvents.lastCompaction()
        assertEquals(ContextCompactionState.SUCCEEDED, firstRecord.state)

        val secondHistory = firstHistory + listOf(
            ChatMessage("user", "new historical question"),
            ChatMessage("assistant", "new historical answer"),
        )
        val secondTurnIds = compactableTurnIds() + listOf("turn-3", "turn-3")
        val secondAdapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                emit(ModelEvent.TextDelta(VALID_SUMMARY))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.Completed)
            }
        }
        val secondRun = AgentRun("summary-second", "snapshot", "conversation")
        val secondEvents = AgentRuntime(secondAdapter).run(
            request(
                run = secondRun,
                prompt = prompt(history = secondHistory, currentUser = "new current question"),
                context = context(
                    history = secondHistory,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 1),
                    turnIds = secondTurnIds,
                    initialSummary = firstRecord,
                ),
                maxInputBudgetUnits = 50_000,
            ),
        ).toList()
        val secondRecord = secondEvents.lastCompaction()

        assertEquals(RunState.COMPLETED, secondRun.state)
        assertEquals(ContextCompactionState.SUCCEEDED, secondRecord.state)
        assertTrue(secondRecord.sourceMessageIds.containsAll(firstRecord.sourceMessageIds))
        assertTrue(secondRecord.sourceMessageIds.contains("h-4"))
        assertEquals(firstRecord.id, secondRecord.parentId)
        assertTrue(secondAdapter.requests.first { it.isCompaction() }
            .messages.last().text.contains("previous_summary"))
    }

    /**
     * Regression for the ACK-only history failure: the first user message is pinned as the
     * original goal and the most recent turns are retained, so a bare atomic-unit selection left
     * only an orphan assistant reply. Summarizing it produced a structurally valid but
     * content-less summary, which was recorded as FAILED and aborted the run. Complete-turn
     * selection must skip that candidate instead of paying for it.
     */
    @Test
    fun uninformativeOnlyHistoryNeverSummarizesAnOrphanAssistantReply() = runTest {
        val history = listOf(
            ChatMessage("user", "Remember token ALPHA."),
            ChatMessage("assistant", "ACK one"),
            ChatMessage("user", "Remember place BETA."),
            ChatMessage("assistant", "ACK two"),
            ChatMessage("user", "Remember provider GAMMA."),
            ChatMessage("assistant", "ACK three"),
        )
        val turnIds = listOf("turn-1", "turn-1", "turn-2", "turn-2", "turn-3", "turn-3")
        val summaryTranscripts = mutableListOf<String>()
        val adapter = RecordingAdapter { request, _ ->
            if (request.isCompaction()) {
                val transcript = request.messages.last().text
                summaryTranscripts += transcript
                // A transcript with no user request can only yield a content-less summary.
                emit(ModelEvent.TextDelta(if ("\"role\":\"user\"" in transcript) VALID_SUMMARY else EMPTY_SUMMARY))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.TextDelta("done"))
                emit(ModelEvent.Completed)
            }
        }
        val run = AgentRun("ack-only-history", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(
            request(
                run = run,
                prompt = prompt(history = history, currentUser = "carry on"),
                context = context(
                    history = history,
                    policy = AgentContextPolicy(maxHistoryMessages = 4, keepRecentTurns = 2),
                    turnIds = turnIds,
                ),
                maxInputBudgetUnits = 200_000,
            ),
        ).toList()

        assertEquals(RunState.COMPLETED, run.state, run.stopReason)
        assertFalse(events.hasFailureContaining("CONTEXT_COMPACTION_FAILED"))
        assertTrue(
            events.filterIsInstance<RuntimeEvent.ContextCompactionChanged>()
                .none { it.record.state == ContextCompactionState.FAILED },
        )
        summaryTranscripts.forEach { transcript ->
            assertTrue(
                "\"role\":\"user\"" in transcript,
                "a compaction candidate must be a complete turn: $transcript",
            )
        }
    }
    private fun request(
        run: AgentRun,
        prompt: EffectivePrompt,
        context: RuntimeContext?,
        maxInputBudgetUnits: Long?,
        modelId: String = "model",
        toolsEnabled: Boolean = false,
        executor: ToolExecutor? = null,
    ): AgentRuntimeRequest = AgentRuntimeRequest(
        run = run,
        prompt = prompt,
        modelId = modelId,
        secret = "synthetic-context-fixture-secret".toCharArray(),
        toolsEnabled = toolsEnabled,
        executor = executor,
        maxInputBudgetUnits = maxInputBudgetUnits,
        context = context,
    )

    private fun prompt(
        history: List<ChatMessage> = emptyList(),
        currentUser: String = "hello",
        currentImages: List<InlineImage> = emptyList(),
    ): EffectivePrompt = EffectivePrompt(
        runtimeContract = "runtime contract",
        userSystemPrompt = "user system prompt",
        skillInstructions = emptyList(),
        retrieved = emptyList(),
        history = history.map { it.role to it.text },
        currentUser = currentUser,
        currentImages = currentImages,
        typedHistory = history,
    )

    private fun context(
        history: List<ChatMessage>,
        policy: AgentContextPolicy,
        turnIds: List<String> = history.indices.map { "turn-$it" },
        initialSummary: ContextCompactionRecord? = null,
        persist: suspend (ContextCompactionRecord) -> ContextCompactionRecord = { it },
    ): RuntimeContext {
        require(turnIds.size == history.size)
        return RuntimeContext(
            policy = policy,
            historySources = history.indices.map { index -> ContextSource("h-$index", turnIds[index]) },
            currentUserMessageId = "current-user",
            modelFingerprint = "model-fingerprint",
            authorizationFingerprint = "authorization-fingerprint",
            initialSummary = initialSummary,
            persist = persist,
        )
    }

    private fun compactableHistory(): List<ChatMessage> = listOf(
        ChatMessage("user", "Original constraint: preserve this instruction."),
        ChatMessage("assistant", "old answer 0"),
        ChatMessage("user", "old question 1"),
        ChatMessage("assistant", "old answer 1"),
        ChatMessage("user", "recent question"),
        ChatMessage("assistant", "recent answer"),
    )

    private fun compactableTurnIds(): List<String> =
        listOf("turn-0", "turn-0", "turn-1", "turn-1", "turn-2", "turn-2")

    private fun twelveRoundHistory(): Pair<List<ChatMessage>, List<String>> {
        val messages = mutableListOf<ChatMessage>()
        val turnIds = mutableListOf<String>()
        repeat(12) { turn ->
            messages += ChatMessage(
                "user",
                if (turn == 0) "Original constraint: preserve this instruction." else "history turn $turn user",
            )
            messages += ChatMessage("assistant", "history turn $turn assistant")
            turnIds += "turn-$turn"
            turnIds += "turn-$turn"
        }
        return messages to turnIds
    }

    private fun List<RuntimeEvent>.hasFailureContaining(value: String): Boolean = any {
        it is RuntimeEvent.ModelEvent && it.event is ModelEvent.Failed &&
            it.event.sanitizedMessage.contains(value)
    }

    private fun List<RuntimeEvent>.lastCompaction(): ContextCompactionRecord =
        filterIsInstance<RuntimeEvent.ContextCompactionChanged>().last().record

    private class ValueExecutor : ToolExecutor {
        override val specs = listOf(
            ToolSpec("external", "external test tool", "{\"type\":\"object\"}", "external", false),
        )
        var invocations = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            invocations++
            return ToolResult.Value("{\"ok\":true}")
        }

        override suspend fun approve(callId: String): ToolResult = error("unused")
    }

    private fun ModelRequest.isCompaction(): Boolean =
        messages.firstOrNull()?.text?.startsWith("Summarize the supplied conversation data") == true

    private class RecordingAdapter(
        private val estimator: (ModelRequest) -> InputBudgetEstimate = { RequestInputBudget.estimate(it) },
        private val script: suspend FlowCollector<ModelEvent>.(ModelRequest, Int) -> Unit,
    ) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        private var streamIndex = 0

        override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): CapabilityReport =
            error("unused")

        override fun estimateInput(request: ModelRequest): InputBudgetEstimate = estimator(request)

        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            val index = streamIndex++
            requests += request
            script.invoke(this, request, index)
        }

        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch =
            error("unused")
    }

    private companion object {
        const val VALID_SUMMARY =
            "{\"goals\":[\"goal\"],\"constraints\":[\"constraint\"],\"decisions\":[\"decision\"],\"pending\":[\"pending\"],\"results\":[\"result\"]}"
        const val EMPTY_SUMMARY =
            "{\"goals\":[],\"constraints\":[],\"decisions\":[],\"pending\":[],\"results\":[]}"
    }
}
