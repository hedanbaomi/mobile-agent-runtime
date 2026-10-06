// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.provider.CapabilityReport
import runtime.mobileagent.provider.EmbeddingBatch
import runtime.mobileagent.provider.EmbeddingRequest
import runtime.mobileagent.provider.ModelAdapter
import runtime.mobileagent.provider.ModelEvent
import runtime.mobileagent.provider.ModelRequest
import runtime.mobileagent.provider.reportDiagnostic
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolBroker
import runtime.mobileagent.skills.ToolContext
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec

class AgentRuntimeTest {
    @Test
    fun deadlineDuringRequestPreparationStartsNoModelRequest() = runTest {
        var now = 0L
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed)))
        val run = AgentRun("preparation-deadline", "s", "c", budget = RunBudget(maxRuntimeMs = 1_000))
        AgentRuntime(adapter, clock = { now }).run(
            AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = false),
        ).collect { event ->
            // The durable request preview can suspend after the earlier admission check.
            if (event is RuntimeEvent.RequestPrepared) now = 2_000
        }
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals("time", run.stopReason)
        assertEquals(0, adapter.requests.size)
    }

    @Test fun completedWithheldSearchProjectsKnownExecutionFactsToEventAndModel() = runTest {
        val adapter = ScriptedAdapter(listOf(
            listOf(ModelEvent.ToolCallDelta("s1", "web_search", """{"query":"public"}"""), ModelEvent.Completed),
            listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
        ))
        var calls = 0
        val executor = runtime.mobileagent.skills.WebSearchToolExecutor(true, { calls == 0 }, false) { _, _, dispatch ->
            calls++; dispatch(); """{"results":[{"title":"PRIVATE_PROVIDER_BODY"}]}"""
        }
        val run = AgentRun("withheld", "snapshot", "conversation")
        val events = AgentRuntime(adapter).run(AgentRuntimeRequest(
            run = run, prompt = prompt(), modelId = "model", secret = "synthetic-test-secret".toCharArray(),
            toolsEnabled = true, executor = executor,
        )).toList()
        val result = events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
        assertEquals("COMPLETED_WITHHELD", result.status)
        assertTrue(result.resultJson.contains("\"dispatched\":true"))
        assertTrue(result.resultJson.contains("\"completed\":true"))
        assertTrue(result.resultJson.contains("\"automaticReplayAllowed\":false"))
        assertTrue(!result.resultJson.contains("PRIVATE_PROVIDER_BODY"))
        val feedback = adapter.requests.last().messages.single { it.role == "tool" }
        assertTrue(feedback.text.contains("COMPLETED_WITHHELD"))
        assertTrue(!feedback.text.contains("PRIVATE_PROVIDER_BODY"))
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals(1, calls)
    }

    @Test
    fun toolLoopExecutesCalculatorThenCompletes() {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "calculator", """{"expression":"2+2"}"""), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("4"), ModelEvent.Completed),
            ),
        )
        val broker = ToolBroker(
            effectiveCapabilities = emptySet(),
            context = ToolContext(search = { _, _, _ -> "{}" }, readDocument = { _, _ -> "{}" }),
        )
        val runtime = AgentRuntime(adapter, tools = broker)
        val run = AgentRun("r", "s", "c")
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = true).toList()
        }
        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "4" })
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals(1, run.toolCalls)
        assertEquals(2, adapter.requests.size)
        val second = adapter.requests.last().messages
        assertTrue(second.any { it.role == "assistant" && it.toolCalls.any { call -> call.id == "t1" } })
        assertTrue(second.any { it.role == "tool" && it.toolCallId == "t1" })
    }

    @Test
    fun toolsDisabledWhenModelHasNoTools() {
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("hi"), ModelEvent.Completed)))
        val runtime = AgentRuntime(adapter, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c")
        runBlocking { runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = false).toList() }
        assertTrue(adapter.requests.single().tools.isEmpty())
        assertEquals(RunState.COMPLETED, run.state)
    }

    @Test
    fun budgetStopsToolLoop() {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "calculator", """{"expression":"1"}"""), ModelEvent.Completed),
            ),
        )
        val runtime = AgentRuntime(adapter, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c", budget = RunBudget(maxModelRounds = 8, maxToolCalls = 0))
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = true).toList()
        }
        assertTrue(events.any { it is ModelEvent.Failed && it.sanitizedMessage.contains("Tool call budget") })
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
    }

    @Test
    fun secretsInToolOutputAreRedacted() {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "knowledge_search", """{"query":"q"}"""), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val broker = ToolBroker(
            setOf("knowledge.search"),
            ToolContext(
                search = { _, _, _ -> """{"secret":"sk-test-token"}""" },
                readDocument = { _, _ -> "{}" },
            ),
        )
        val runtime = AgentRuntime(adapter, tools = broker, secretsForRedaction = { listOf("sk-test-token") })
        runBlocking {
            runtime.run(AgentRun("r", "s", "c"), prompt(), "m", charArrayOf('x'), toolsEnabled = true).toList()
        }
        val toolMsg = adapter.requests.last().messages.first { it.role == "tool" }
        assertFalseSecret(toolMsg.text)
    }

    @Test
    fun toolsDisabledDoesNotExecuteBroker() {
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.ToolCallDelta("t1", "calculator", """{"expression":"1"}"""), ModelEvent.Completed)),
        )
        val runtime = AgentRuntime(adapter, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c")
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = false).toList()
        }
        assertTrue(events.any { it is ModelEvent.Failed })
        assertEquals(0, run.toolCalls)
        assertTrue(adapter.requests.single().tools.isEmpty())
    }

    @Test
    fun budgetExpiryDuringDispatchedReplyStillDeliversTheCompleteReply() {
        var now = 0L
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.TextDelta("late"), ModelEvent.TextDelta(" but complete"), ModelEvent.Completed)),
            onStream = { now = 2000 },
        )
        val runtime = AgentRuntime(adapter, clock = { now }, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c", budget = RunBudget(maxRuntimeMs = 1000))
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = true).toList()
        }
        assertEquals(RunState.COMPLETED, run.state)
        assertEquals("late but complete", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.any { it is ModelEvent.Completed })
        assertTrue(events.none { it is ModelEvent.Failed })
        assertEquals(1, adapter.requests.size)
    }

    @Test
    fun deadlineAfterCompleteReplyStartsNoToolOrFollowUpRequest() = runTest {
        var now = 0L
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.TextDelta("checking"), ModelEvent.ToolCallDelta("t1", "external", "{}"), ModelEvent.Completed)),
            onStream = { now = 2000 },
        )
        val executor = CountingExecutor()
        val run = AgentRun("deadline-tools", "s", "c", budget = RunBudget(maxRuntimeMs = 1000))
        val events = AgentRuntime(adapter, clock = { now }).run(
            AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = true, executor = executor),
        ).toList()

        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals(0, executor.invocations)
        assertEquals(1, adapter.requests.size)
        assertTrue(events.filterIsInstance<RuntimeEvent.ModelEvent>().any { (it.event as? ModelEvent.TextDelta)?.text == "checking" })
        assertTrue(events.none { it is RuntimeEvent.ToolResultProduced })
    }
    @Test
    fun beforeModelRequestTimeoutExhaustsBudgetWithoutStartingModel() = runTest {
        var callbackStarted = false
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("never"), ModelEvent.Completed)))
        val runtime = AgentRuntime(
            adapter,
            clock = { 0L },
            tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })),
        )
        val run = AgentRun("r", "s", "c", budget = RunBudget(maxRuntimeMs = 20))

        val events = runtime.run(
            AgentRuntimeRequest(
                run = run,
                prompt = prompt(),
                modelId = "model",
                secret = charArrayOf('s'),
                toolsEnabled = false,
                beforeModelRequest = {
                    callbackStarted = true
                    delay(21)
                },
            ),
        ).toList()

        assertTrue(callbackStarted)
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertTrue(events.any { it is RuntimeEvent.ModelEvent && it.event is ModelEvent.Failed })
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun nullableStringTypeSchemaReachesRequestPreparedAndValidatesCwd() = runTest {
        suspend fun runShellLike(
            argumentsJson: String,
            schema: String = nullableCwdSchema(),
        ): ShellLikeRun {
            val adapter = ShellLikeAdapter(argumentsJson)
            val executor = ShellLikeExecutor(schema)
            val run = AgentRun("shell-schema", "s", "c")
            val events = AgentRuntime(adapter).run(
                AgentRuntimeRequest(
                    run = run,
                    prompt = prompt(),
                    modelId = "model",
                    secret = charArrayOf('s'),
                    toolsEnabled = true,
                    executor = executor,
                    emitRequestPreview = true,
                ),
            ).toList()
            return ShellLikeRun(run, events, adapter, executor)
        }

        val nullCwd = runShellLike("""{"command":"pwd","cwd":null}""")
        assertEquals(RunState.COMPLETED, nullCwd.run.state)
        assertTrue(nullCwd.events.any { it is RuntimeEvent.RequestPrepared })
        assertEquals(2, nullCwd.adapter.previewCalls)
        assertEquals(2, nullCwd.adapter.streamCalls)
        assertEquals(1, nullCwd.executor.invocations)

        val stringCwd = runShellLike("""{"command":"pwd","cwd":"/tmp"}""")
        assertEquals(RunState.COMPLETED, stringCwd.run.state)
        assertTrue(stringCwd.events.any { it is RuntimeEvent.RequestPrepared })
        assertEquals(1, stringCwd.executor.invocations)

        val reverseUnion = runShellLike(
            """{"command":"pwd","cwd":"/tmp"}""",
            nullableCwdSchema("[\"null\",\"string\"]"),
        )
        assertEquals(RunState.COMPLETED, reverseUnion.run.state)
        assertEquals(1, reverseUnion.executor.invocations)

        // Schema rejections before dispatch are fed back as INVALID results for
        // a corrected resend; the second scripted round completes the run.
        val numericCwd = runShellLike("""{"command":"pwd","cwd":1}""")
        assertEquals(RunState.COMPLETED, numericCwd.run.state)
        val numericCwdFeedback = numericCwd.events
            .filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
        assertEquals("INVALID", numericCwdFeedback.status)
        assertTrue(numericCwdFeedback.resultJson.contains("cwd"))
        assertTrue(numericCwd.events.any { it is RuntimeEvent.RequestPrepared })
        assertEquals(2, numericCwd.adapter.previewCalls)
        assertEquals(2, numericCwd.adapter.streamCalls)
        assertEquals(0, numericCwd.executor.invocations)

        val stringBoolean = runShellLike(
            """{"flag":"true"}""",
            nullableBooleanSchema(),
        )
        assertEquals(RunState.COMPLETED, stringBoolean.run.state)
        val stringBooleanFeedback = stringBoolean.events
            .filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
        assertEquals("INVALID", stringBooleanFeedback.status)
        assertTrue(stringBooleanFeedback.resultJson.contains("flag"))
        assertEquals(0, stringBoolean.executor.invocations)

        val enumNull = runShellLike(
            """{"command":"pwd","cwd":null}""",
            nullableCwdEnumSchema(),
        )
        assertEquals(RunState.COMPLETED, enumNull.run.state)
        val enumNullFeedback = enumNull.events
            .filterIsInstance<RuntimeEvent.ToolResultProduced>().single()
        assertEquals("INVALID", enumNullFeedback.status)
        assertTrue(enumNullFeedback.resultJson.contains("allowed value"))
        assertEquals(0, enumNull.executor.invocations)
    }

    @Test
    fun schemaTypeArraysRejectMalformedAndNonNullableUnionsBeforeDispatch() = runTest {
        val cases = listOf(
            "[]" to "type array cannot be empty",
            "[1,\"null\"]" to "type array must contain only strings",
            "[\"string\",\"string\"]" to "type array contains duplicate types",
            "[\"string\",\"integer\"]" to "type array must contain exactly one value type and null",
            "[\"string\"]" to "type array must contain exactly one value type and null",
            "[\"string\",\"null\",\"integer\"]" to "type array must contain exactly one value type and null",
            "[\"string\",\"unknown\"]" to "type array contains unsupported type",
        )
        cases.forEachIndexed { index, (typeJson, message) ->
            val adapter = ShellLikeAdapter("""{"command":"pwd"}""")
            val executor = ShellLikeExecutor(nullableCwdSchema(typeJson))
            val run = AgentRun("invalid-shell-schema-$index", "s", "c")
            val events = AgentRuntime(adapter).run(
                AgentRuntimeRequest(
                    run = run,
                    prompt = prompt(),
                    modelId = "model",
                    secret = charArrayOf('s'),
                    toolsEnabled = true,
                    executor = executor,
                ),
            ).toList()

            assertEquals(RunState.FAILED, run.state, "case $index")
            assertTrue(
                events.any {
                    it is RuntimeEvent.ModelEvent &&
                        it.event is ModelEvent.Failed &&
                        it.event.sanitizedMessage.contains(message)
                },
                "case $index",
            )
            assertEquals(0, adapter.previewCalls, "case $index")
            assertEquals(0, adapter.streamCalls, "case $index")
            assertEquals(0, executor.invocations, "case $index")
        }
    }

    @Test
    fun slowButActiveStreamIsReceivedInFullPastTheDeadline() {
        val adapter = object : ModelAdapter {
            var emitted = 0
            override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile) = error("not used")
            override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
                repeat(3) {
                    kotlinx.coroutines.delay(80)
                    emitted += 1
                    emit(ModelEvent.TextDelta("x"))
                }
                emit(ModelEvent.Completed)
            }
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray) = error("not used")
        }
        val runtime = AgentRuntime(adapter, tools = ToolBroker(emptySet(), ToolContext({ _, _, _ -> "{}" }, { _, _ -> "{}" })))
        val run = AgentRun("r", "s", "c", budget = RunBudget(maxRuntimeMs = 20))
        val events = runBlocking {
            runtime.run(run, prompt(), "model", charArrayOf('s'), toolsEnabled = false).toList()
        }
        assertEquals(3, adapter.emitted)
        assertEquals("xxx", events.filterIsInstance<ModelEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.any { it is ModelEvent.Completed })
        assertEquals(RunState.COMPLETED, run.state)
    }
    @Test
    fun stalledModelStreamIsUnknownAndIsNotReplayed() = runTest {
        val adapter = object : ModelAdapter {
            var requests = 0
            override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile) = error("not used")
            override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
                requests += 1
                emit(ModelEvent.TextDelta("partial"))
                delay(100)
                emit(ModelEvent.Completed)
            }
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray) = error("not used")
        }
        val run = AgentRun("model-stall", "s", "c", budget = RunBudget(stallTimeoutMs = 20))
        val events = AgentRuntime(adapter).run(
            run,
            prompt(),
            "model",
            charArrayOf(),
            toolsEnabled = false,
        ).toList()

        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, adapter.requests)
        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "partial" })
        assertTrue(events.any { it is ModelEvent.Failed && it.sanitizedMessage.contains("UNKNOWN_OUTCOME") })
    }
    @Test
    fun transportChunksWithoutModelEventsAreProgressNotAStall() = runTest {
        // A long tool-argument or hidden-reasoning phase sends SSE chunks but no ModelEvent yet.
        val adapter = object : ModelAdapter {
            override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile) = error("not used")
            override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
                repeat(10) {
                    delay(10)
                    request.reportDiagnostic(runtime.mobileagent.provider.ModelDiagnosticEvent(
                        runtime.mobileagent.provider.ModelDiagnosticStage.STREAM_EVENT,
                        runtime.mobileagent.provider.ModelDispatchStatus.RESPONSE_RECEIVED, 0,
                    ))
                }
                emit(ModelEvent.TextDelta("after a long silent phase"))
                emit(ModelEvent.Completed)
            }
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray) = error("not used")
        }
        val run = AgentRun("chunks", "s", "c", budget = RunBudget(stallTimeoutMs = 30))
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = false,
                diagnostics = runtime.mobileagent.provider.ModelDiagnosticSink { }),
        ).toList()

        assertEquals(RunState.COMPLETED, run.state)
        assertTrue(events.filterIsInstance<RuntimeEvent.ModelEvent>().any {
            (it.event as? ModelEvent.TextDelta)?.text == "after a long silent phase"
        })
    }

    @Test
    fun slowConsumerIsNeverMistakenForAStalledStream() = runTest {
        val adapter = ScriptedAdapter(listOf(listOf(
            ModelEvent.TextDelta("a"), ModelEvent.TextDelta("b"), ModelEvent.TextDelta("c"), ModelEvent.Completed,
        )))
        val run = AgentRun("slow-consumer", "s", "c", budget = RunBudget(stallTimeoutMs = 20))
        val received = StringBuilder()
        AgentRuntime(adapter).run(
            AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = false),
        ).collect { event ->
            ((event as? RuntimeEvent.ModelEvent)?.event as? ModelEvent.TextDelta)?.let {
                received.append(it.text)
                delay(100) // e.g. a durable checkpoint write in the chat collector
            }
        }

        assertEquals("abc", received.toString())
        assertEquals(RunState.COMPLETED, run.state)
    }

    @Test
    fun midStreamTransportFailureIsUnknownAndIsNotReplayed() = runTest {
        val adapter = object : ModelAdapter {
            var requests = 0
            override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile) = error("not used")
            override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
                requests += 1
                emit(ModelEvent.TextDelta("partial"))
                throw java.io.IOException("connection reset")
            }
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray) = error("not used")
        }
        val run = AgentRun("reset", "s", "c")
        val events = AgentRuntime(adapter).run(run, prompt(), "model", charArrayOf(), toolsEnabled = false).toList()

        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, adapter.requests)
        assertTrue(events.any { it is ModelEvent.TextDelta && it.text == "partial" })
        assertTrue(events.any { it is ModelEvent.Failed && it.sanitizedMessage.contains("UNKNOWN_OUTCOME") })
    }

    @Test
    fun stalledToolIsUnknownAndIsNotReplayed() = runTest {
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.ToolCallDelta("slow", "external", "{}"), ModelEvent.Completed)),
        )
        val executor = CountingExecutor(delayMs = 100)
        val run = AgentRun("tool-stall", "s", "c", budget = RunBudget(stallTimeoutMs = 20))
        val events = AgentRuntime(adapter).run(
            AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = true, executor = executor),
        ).toList()

        assertEquals(RunState.UNKNOWN_OUTCOME, run.state)
        assertEquals(1, executor.invocations)
        assertEquals(1, adapter.requests.size)
        assertEquals("UNKNOWN_OUTCOME", events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single().status)
    }

    @Test
    fun toolRunningPastTheDeadlineKeepsItsResultAndStopsBeforeTheNextRequest() = runTest {
        var now = 0L
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.ToolCallDelta("long", "external", "{}"), ModelEvent.Completed)),
        )
        val executor = CountingExecutor(onInvoke = { now = 2000 })
        val run = AgentRun("tool-deadline", "s", "c", budget = RunBudget(maxRuntimeMs = 1000))
        val events = AgentRuntime(adapter, clock = { now }).run(
            AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = true, executor = executor),
        ).toList()

        assertEquals(1, executor.invocations)
        assertEquals("VALUE", events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single().status)
        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals(1, adapter.requests.size)
    }

    @Test
    fun approvalWaitIsStillBoundedByTheDeadlineAndNeverDispatches() = runTest {
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.ToolCallDelta("ask", "external", "{}"), ModelEvent.Completed)),
        )
        val executor = CountingExecutor(needsApproval = true)
        val run = AgentRun("approval-deadline", "s", "c", budget = RunBudget(maxRuntimeMs = 20))
        AgentRuntime(adapter, onApprove = { delay(1_000); true }).run(
            AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = true, executor = executor),
        ).toList()

        assertEquals(RunState.BUDGET_EXHAUSTED, run.state)
        assertEquals(0, executor.approvals)
        assertEquals(1, adapter.requests.size)
    }
    @Test
    fun callerCancellationAfterModelDispatchPropagatesAndMarksUnknown() = runTest {
        val started = CompletableDeferred<Unit>()
        val adapter = object : ModelAdapter {
            var requests = 0
            override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile) = error("not used")
            override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
                requests += 1
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray) = error("not used")
        }
        val run = AgentRun("model-cancel", "s", "c")
        val job = launch {
            AgentRuntime(adapter).run(run, prompt(), "model", charArrayOf(), toolsEnabled = false).toList()
        }
        started.await()
        job.cancelAndJoin()

        assertEquals(RunState.CANCELLED, run.state)
        assertTrue(run.stopReason.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, adapter.requests)
    }

    @Test
    fun callerCancellationAfterToolDispatchPropagatesAndMarksUnknown() = runTest {
        val started = CompletableDeferred<Unit>()
        val adapter = ScriptedAdapter(
            listOf(listOf(ModelEvent.ToolCallDelta("cancel", "external", "{}"), ModelEvent.Completed)),
        )
        val executor = object : runtime.mobileagent.skills.ToolExecutor {
            override val specs = listOf(
                runtime.mobileagent.skills.ToolSpec("external", "external tool", "{\"type\":\"object\"}", "external", false),
            )
            var invocations = 0
            override suspend fun invoke(call: runtime.mobileagent.skills.ToolCall): runtime.mobileagent.skills.ToolResult {
                invocations += 1
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun approve(callId: String): runtime.mobileagent.skills.ToolResult = error("unused")
        }
        val run = AgentRun("tool-cancel", "s", "c")
        val job = launch {
            AgentRuntime(adapter).run(
                AgentRuntimeRequest(run, prompt(), "model", charArrayOf(), toolsEnabled = true, executor = executor),
            ).toList()
        }
        started.await()
        job.cancelAndJoin()

        assertEquals(RunState.CANCELLED, run.state)
        assertTrue(run.stopReason.orEmpty().contains("UNKNOWN_OUTCOME"))
        assertEquals(1, executor.invocations)
        assertEquals(1, adapter.requests.size)
    }

    @Test
    fun currentSecretIsRedactedFromInvalidToolResult() {
        val secret = "live-provider-secret-token"
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.ToolCallDelta("t1", "knowledge_search", """{"query":"q"}"""), ModelEvent.Completed),
                listOf(ModelEvent.TextDelta("done"), ModelEvent.Completed),
            ),
        )
        val runtime = AgentRuntime(
            adapter,
            tools = ToolBroker(
                setOf("knowledge.search"),
                ToolContext(
                    search = { _, _, _ -> error("provider said $secret") },
                    readDocument = { _, _ -> "{}" },
                    grantedKnowledgeBaseIds = setOf("kb-a"),
                ),
            ),
        )
        runBlocking {
            runtime.run(AgentRun("r", "s", "c"), prompt(), "m", secret.toCharArray(), toolsEnabled = true).toList()
        }
        val toolMsg = adapter.requests.last().messages.first { it.role == "tool" }
        assertTrue(secret !in toolMsg.text)
    }

    private fun assertFalseSecret(text: String) {
        assertTrue("sk-test-token" !in text)
    }

    private fun prompt() = EffectivePrompt("contract", "", emptyList(), emptyList(), emptyList(), "hello")

    private fun nullableCwdSchema(typeJson: String = "[\"string\",\"null\"]"): String =
        """{"type":"object","additionalProperties":false,"required":["command"],"properties":{"command":{"type":"string","minLength":1},"cwd":{"type":$typeJson,"maxLength":4096}}}"""

    private fun nullableCwdEnumSchema(): String =
        """{"type":"object","additionalProperties":false,"required":["command"],"properties":{"command":{"type":"string","minLength":1},"cwd":{"type":["string","null"],"enum":["/tmp"]}}}"""

    private fun nullableBooleanSchema(): String =
        """{"type":"object","additionalProperties":false,"required":["flag"],"properties":{"flag":{"type":["boolean","null"]}}}"""

    private data class ShellLikeRun(
        val run: AgentRun,
        val events: List<RuntimeEvent>,
        val adapter: ShellLikeAdapter,
        val executor: ShellLikeExecutor,
    )

    private class ShellLikeExecutor(schema: String) : ToolExecutor {
        override val specs = listOf(ToolSpec("shell_like", "shell-like test tool", schema, "shell.execute", sideEffect = false))
        var invocations = 0

        override suspend fun invoke(call: ToolCall): ToolResult {
            invocations += 1
            return ToolResult.Value("""{"ok":true}""")
        }

        override suspend fun approve(callId: String): ToolResult = error("approval is not used")
    }

    private class ShellLikeAdapter(private val argumentsJson: String) : ModelAdapter {
        var previewCalls = 0
        var streamCalls = 0

        override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): CapabilityReport = error("not used")

        override fun previewRequest(request: ModelRequest): String {
            previewCalls += 1
            return "preview"
        }

        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            streamCalls += 1
            if (streamCalls == 1) {
                emit(ModelEvent.ToolCallDelta("shell-call", "shell_like", argumentsJson))
                emit(ModelEvent.Completed)
            } else {
                emit(ModelEvent.TextDelta("done"))
                emit(ModelEvent.Completed)
            }
        }

        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("not used")
    }

    private class CountingExecutor(
        private val delayMs: Long = 0,
        private val needsApproval: Boolean = false,
        private val onInvoke: () -> Unit = {},
    ) : ToolExecutor {
        override val specs = listOf(ToolSpec("external", "external tool", "{\"type\":\"object\"}", "external", false))
        var invocations = 0
        var approvals = 0
        override suspend fun invoke(call: ToolCall): ToolResult {
            invocations += 1
            onInvoke()
            if (delayMs > 0) delay(delayMs)
            return if (needsApproval) ToolResult.NeedsApproval else ToolResult.Value("{}")
        }
        override suspend fun approve(callId: String): ToolResult {
            approvals += 1
            return ToolResult.Value("{}")
        }
    }

    private class ScriptedAdapter(
        private val scripts: List<List<ModelEvent>>,
        private val onStream: () -> Unit = {},
    ) : ModelAdapter {
        val requests = mutableListOf<ModelRequest>()
        private var i = 0
        override suspend fun probe(profile: runtime.mobileagent.domain.ModelProfile): CapabilityReport {
            error("not used")
        }

        override fun stream(request: ModelRequest, secret: CharArray): Flow<ModelEvent> = flow {
            requests += request
            onStream()
            val events = scripts.getOrElse(i++) { listOf(ModelEvent.Completed) }
            events.forEach { emit(it) }
        }

        override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch {
            error("not used")
        }
    }
}
