// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import runtime.mobileagent.skills.tooling.ToolResultBudget

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadBatchToolExecutorTest {
    private val specs = listOf(
        ToolSpec("kb_search", "search", """{"type":"object"}""", "", false),
        ToolSpec("read_doc", "read", """{"type":"object"}""", "", false),
        ToolSpec("write_thing", "write", """{"type":"object"}""", "", true),
    )
    private val eligible = setOf("kb_search", "read_doc")

    private fun batch(vararg children: Pair<String, String>): String =
        children.joinToString(prefix = """{"calls":[""", separator = ",", postfix = "]}") { (callId, tool) ->
            """{"call_id":"$callId","tool":"$tool","arguments":{}}"""
        }

    private fun executor(
        delegate: FakeBatchDelegate,
        live: suspend () -> Boolean = { true },
        replay: suspend (ToolCall) -> Boolean = { true },
        reserve: (Int) -> Int = { it },
        eligibleTools: Set<String> = eligible,
        budget: ReadBatchBudget = ReadBatchBudget(),
        deferred: suspend (ToolCall) -> Boolean = replay,
    ): ReadBatchToolExecutor =
        ReadBatchToolExecutor(delegate, live, replay, reserve, eligibleTools, budget, deferred)

    private fun resultsOf(result: ToolResult): List<BatchEntry> {
        val json = (result as? ToolResult.Value ?: fail("expected a value result but got $result")).json
        return Json.parseToJsonElement(json).jsonObject
            .getValue("results").jsonArray
            .map { BatchEntry(it.jsonObject) }
    }

    private fun envelope(result: ToolResult): JsonObject {
        val json = (result as? ToolResult.Value ?: fail("expected a value result but got $result")).json
        return Json.parseToJsonElement(json).jsonObject
    }

    @Test
    fun readBatchKeepsRequestOrderWithBoundedConcurrency() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { gate = CompletableDeferred() }
        val batch = executor(delegate)
        val invocation = backgroundScope.async {
            batch.invoke(
                ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search", "c2" to "read_doc", "c3" to "kb_search")),
            )
        }
        runCurrent()
        assertEquals(2, delegate.dispatched.size, "one window of two children")
        assertEquals(2, delegate.maxInFlight)

        delegate.gate?.complete(Unit)
        advanceUntilIdle()

        val results = resultsOf(invocation.await())
        assertEquals(listOf("c1", "c2", "c3"), results.map { it.callId })
        assertTrue(results.all { it.status == "VALUE" })
        assertEquals(3, delegate.dispatched.size)
        assertEquals(2, delegate.maxInFlight, "concurrency must never exceed the cap")
        assertFalse(batch.hasRunningJobs)
        assertFalse(batch.hasUnknownOutcomes)
    }

    @Test
    fun resultsKeepRequestOrderWhenCompletionOrderDiffers() = runTest {
        val delegate = FakeBatchDelegate(specs).apply {
            handler = { call ->
                if (call.callId == "slow") delay(50)
                ToolResult.Value("""{"call":"${call.callId}"}""")
            }
        }
        val batch = executor(delegate)
        val results = resultsOf(
            batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("slow" to "kb_search", "fast" to "kb_search"))),
        )
        assertEquals(listOf("slow", "fast"), results.map { it.callId })
        assertEquals("""{"call":"slow"}""", results[0].result.toString())
        assertEquals("""{"call":"fast"}""", results[1].result.toString())
    }

    @Test
    fun ineligibleAndSideEffectToolsAreRejectedWithoutDispatch() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val batch = executor(delegate, eligibleTools = setOf("kb_search", "write_thing"))
        val results = resultsOf(
            batch.invoke(
                ToolCall(
                    "b1",
                    READ_BATCH_TOOL_NAME,
                    batch("c1" to "write_thing", "c2" to "unknown_tool", "c3" to "kb_search"),
                ),
            ),
        )
        assertEquals("DENIED", results[0].status)
        assertEquals("CAPABILITY_DENIED", results[0].failureCode)
        assertEquals("DENIED", results[1].status)
        assertEquals("VALUE", results[2].status)
        assertEquals(1, delegate.dispatched.size)
        assertEquals("kb_search", delegate.dispatched.single().name)

        val schema = batch.specs.single { it.name == READ_BATCH_TOOL_NAME }.parametersJson
        assertTrue(schema.contains("\"kb_search\""))
        assertFalse(schema.contains("write_thing"))
    }

    @Test
    fun duplicateChildCallIdsAreRejectedWithoutDoubleDispatch() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val batch = executor(delegate)
        val original = ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search", "c1" to "read_doc"))
        val first = resultsOf(batch.invoke(original))
        assertEquals("VALUE", first[0].status)
        assertEquals("INVALID", first[1].status)
        assertEquals("CALL_ID_REPLAY", first[1].failureCode)
        assertEquals(1, delegate.dispatched.size)

        // The dedup index spans the whole Run, not just one batch.
        val second = resultsOf(batch.invoke(ToolCall("b2", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))))
        assertEquals("INVALID", second[0].status)
        assertEquals(1, delegate.dispatched.size)

        // The outer batch call id is single use: the same request repeats its own
        // outcome without dispatching, and different arguments are rejected.
        val repeated = batch.invoke(original) as? ToolResult.Value ?: fail("expected the cached batch result")
        val firstJson = (batch.invoke(original) as? ToolResult.Value ?: fail("expected the cached batch result")).json
        assertEquals(Json.parseToJsonElement(firstJson), Json.parseToJsonElement(repeated.json))
        assertTrue(batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))) is ToolResult.Invalid)
        assertEquals(1, delegate.dispatched.size)
    }

    @Test
    fun childNeedsApprovalIsReportedAndNeverAutoApproved() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { needsApprovalFor = setOf("read_doc") }
        val batch = executor(delegate)
        val results = resultsOf(
            batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "read_doc", "c2" to "kb_search"))),
        )
        assertEquals("NEEDS_APPROVAL", results[0].status)
        assertEquals("APPROVAL_REQUIRED", results[0].failureCode)
        assertTrue(results[0].failureMessage.contains("approve"))
        assertEquals(false, results[0].automaticReplayAllowed)
        assertEquals("VALUE", results[1].status)
        assertEquals(0, delegate.approveCalls)
        assertEquals(2, delegate.dispatched.size)
    }

    @Test
    fun budgetReservationLimitsDispatchedChildCalls() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val reserved = mutableListOf<Int>()
        val batch = executor(delegate, reserve = { requested ->
            reserved.add(requested)
            1
        })
        val results = resultsOf(
            batch.invoke(
                ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search", "c2" to "kb_search", "c3" to "kb_search")),
            ),
        )
        assertEquals(listOf(3), reserved)
        assertEquals(1, delegate.dispatched.size)
        assertEquals("VALUE", results[0].status)
        assertEquals("BUDGET_EXHAUSTED", results[1].status)
        assertEquals("QUOTA_EXCEEDED", results[1].failureCode)
        assertEquals("BUDGET_EXHAUSTED", results[2].status)
        assertEquals("c3", results[2].callId)
    }

    @Test
    fun tooManyCallsAndMalformedArgumentsAreRejectedWithoutDispatch() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val batch = executor(delegate)
        val tooMany = batch.invoke(
            ToolCall(
                "b1",
                READ_BATCH_TOOL_NAME,
                batch(
                    "c1" to "kb_search",
                    "c2" to "kb_search",
                    "c3" to "kb_search",
                    "c4" to "kb_search",
                    "c5" to "kb_search",
                ),
            ),
        )
        assertTrue(tooMany is ToolResult.Invalid)
        assertTrue(batch.invoke(ToolCall("b2", READ_BATCH_TOOL_NAME, """{"calls":["nope"]}""")) is ToolResult.Invalid)
        assertTrue(batch.invoke(ToolCall("b3", READ_BATCH_TOOL_NAME, """{"calls":[],"extra":1}""")) is ToolResult.Invalid)
        assertTrue(
            batch.invoke(ToolCall("b4", READ_BATCH_TOOL_NAME, """{"calls":[{"call_id":"c9","tool":"kb_search"}]}"""))
                is ToolResult.Invalid,
        )
        advanceUntilIdle()
        assertTrue(delegate.dispatched.isEmpty())

        val schema = batch.specs.single { it.name == READ_BATCH_TOOL_NAME }.parametersJson
        assertTrue(schema.contains("\"maxItems\":4"))
    }

    @Test
    fun cancellationStopsStartedChildrenAndNeverStartsTheRest() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { gate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val batch = executor(delegate)
        val invocation = scope.launch {
            try {
                batch.invoke(
                    ToolCall(
                        "b1",
                        READ_BATCH_TOOL_NAME,
                        batch("c1" to "kb_search", "c2" to "kb_search", "c3" to "kb_search", "c4" to "kb_search"),
                    ),
                )
            } catch (_: CancellationException) {
                // The batch was cancelled; only the executor state matters here.
            }
        }
        runCurrent()
        assertEquals(2, delegate.dispatched.size)
        assertTrue(batch.hasRunningJobs)

        invocation.cancel()
        advanceUntilIdle()

        assertEquals(2, delegate.dispatched.size, "later windows must never start")
        assertFalse(batch.hasRunningJobs)
        // Batch children are side-effect-free reads: a cancelled read changed nothing.
        assertFalse(batch.hasUnknownOutcomes)
        assertEquals(0, batch.unknownOutcomeChildren)
        scope.cancel()
    }

    @Test
    fun authorizationChangeBeforeDispatchDeniesTheBatchWithoutSpendingBudget() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val reserved = mutableListOf<Int>()
        var live = true
        val batch = executor(delegate, live = { live }, reserve = { requested ->
            reserved.add(requested)
            requested
        })
        live = false
        val denied = batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search")))
        assertTrue(denied is ToolResult.Denied)
        assertTrue(delegate.dispatched.isEmpty())
        assertTrue(reserved.isEmpty(), "nothing is dispatched, so nothing is charged")
        assertFalse(batch.hasUnknownOutcomes)
    }

    @Test
    fun cachedBatchContentIsWithheldWhenAChildReplayIsDenied() = runTest {
        val delegate = FakeBatchDelegate(specs)
        var replayAllowed = true
        val batch = executor(delegate, replay = { replayAllowed })
        val call = ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))
        val first = resultsOf(batch.invoke(call))
        assertEquals("""{"ok":true}""", first[0].result.toString())

        replayAllowed = false
        assertFalse(batch.authorizeReplay(call))
        val repeated = batch.invoke(call)
        assertTrue(repeated is ToolResult.Denied)
        assertFalse((repeated as ToolResult.Denied).reason.contains("ok"))
    }

    @Test
    fun unknownChildOutcomeIsReportedForTheRunOutcome() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { handler = { ToolResult.UnknownOutcome("dispatched then lost") } }
        val batch = executor(delegate)
        val results = resultsOf(batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))))
        assertEquals("UNKNOWN_OUTCOME", results[0].status)
        assertEquals("UNKNOWN_OUTCOME", results[0].failureCode)
        assertEquals(false, results[0].automaticReplayAllowed)
        assertTrue(batch.hasUnknownOutcomes)
        assertEquals(1, batch.dispatchedChildren)
    }

    @Test
    fun closeStopsInFlightBatches() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { gate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val batch = executor(delegate)
        scope.launch {
            try {
                batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search", "c2" to "kb_search")))
            } catch (_: CancellationException) {
                // Cancelled by close(); the summary carries the outcome.
            }
        }
        runCurrent()
        assertTrue(batch.hasRunningJobs)

        batch.close()

        assertFalse(batch.hasRunningJobs)
        assertFalse(batch.hasUnknownOutcomes, "a cancelled read is a definite stop")
        assertTrue(batch.invoke(ToolCall("b2", READ_BATCH_TOOL_NAME, batch("c3" to "kb_search"))) is ToolResult.Denied)
        scope.cancel()
    }

    @Test
    fun cachedBatchEnvelopeIsRevalidatedBeforeItIsRepeated() = runTest {
        val delegate = FakeBatchDelegate(specs)
        var replayAllowed = true
        val batch = executor(delegate, replay = { replayAllowed })
        val call = ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))
        val first = resultsOf(batch.invoke(call))
        assertEquals("""{"ok":true}""", first[0].result.toString())

        replayAllowed = false
        // Same call id: the cached envelope must pass the same live revalidation
        // as any other disclosure, not just a liveness check.
        val cached = batch.invoke(call)
        assertTrue(cached is ToolResult.Denied)
        assertFalse((cached as ToolResult.Denied).reason.contains("ok"))
    }

    @Test
    fun revocationWhileLaterChildrenRunWithholdsTheWholeEnvelope() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delegate = FakeBatchDelegate(specs).apply {
            handler = { call ->
                if (call.callId == "c2") {
                    gate.await()
                    ToolResult.Value("""{"child":"c2"}""")
                } else {
                    ToolResult.Value("""{"child":"c1"}""")
                }
            }
        }
        var replayAllowed = true
        val batch = executor(
            delegate,
            replay = { replayAllowed },
            budget = ReadBatchBudget(maxConcurrency = 1),
        )
        val invocation = backgroundScope.async {
            batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search", "c2" to "kb_search")))
        }
        runCurrent()
        // c1 already produced content while c2 is still running.
        assertEquals(2, delegate.dispatched.size)

        replayAllowed = false
        gate.complete(Unit)
        advanceUntilIdle()

        val result = invocation.await()
        assertTrue(result is ToolResult.Denied)
        assertFalse((result as ToolResult.Denied).reason.contains("c1"))
        // Both children returned a definite result, so the Run outcome is known
        // even though the content was withheld.
        assertFalse(batch.hasUnknownOutcomes)
        assertFalse(batch.hasRunningJobs)
    }

    @Test
    fun childExceptionAfterDispatchIsADefiniteFailureForAReadChild() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { handler = { throw IllegalStateException("disconnected") } }
        val batch = executor(delegate)
        val results = resultsOf(batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))))
        assertEquals("FAILED", results[0].status)
        assertEquals("INTERNAL_ERROR", results[0].failureCode)
        assertFalse(batch.hasUnknownOutcomes)
        assertEquals(1, batch.dispatchedChildren)
    }

    @Test
    fun childFailureThatEscapesSettlementStillLiftsTheRunOutcome() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { handler = { throw AssertionError("boom") } }
        val batch = executor(delegate)
        val failure = runCatching {
            batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search")))
        }
        assertTrue(failure.isFailure)
        assertEquals(1, batch.dispatchedChildren)
        assertTrue(batch.hasUnknownOutcomes)
        assertFalse(batch.hasRunningJobs)
    }

    @Test
    fun duplicateBatchCallIdWhileExecutingIsUnknownNotASecondDispatch() = runTest {
        val delegate = FakeBatchDelegate(specs).apply { gate = CompletableDeferred() }
        val batch = executor(delegate)
        val args = batch("c1" to "kb_search")
        val first = backgroundScope.async { batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, args)) }
        runCurrent()

        val second = batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, args))
        assertTrue(second is ToolResult.UnknownOutcome)
        assertEquals(1, delegate.dispatched.size)

        delegate.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, resultsOf(first.await()).size)
        assertEquals(1, delegate.dispatched.size)
    }

    @Test
    fun firstBatchDisclosureUsesTheDeferredHookWhileCachedReplayStaysStrict() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val batch = executor(delegate, replay = { false }, deferred = { true })
        val call = ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))

        // The first envelope of this batch's own execution is a deferred disclosure ...
        val first = resultsOf(batch.invoke(call))
        assertEquals("""{"ok":true}""", first[0].result.toString())

        // ... while repeating the outer call id is a cached replay and stays strict.
        val cached = batch.invoke(call)
        assertTrue(cached is ToolResult.Denied)

        // Non-owned calls proxy the hook; batches cannot be nested.
        assertTrue(batch.authorizeDeferredResult(ToolCall("p1", "kb_search", """{"query":"q"}""")))
        assertFalse(batch.authorizeDeferredResult(ToolCall("p2", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))))
        assertFalse(batch.authorizeReplay(ToolCall("p2", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))))
    }

    @Test
    fun approveProxiesToTheDelegateAndRefusesOwnBatchApproval() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val batch = executor(delegate)
        // Another owner's approval still settles through the delegate.
        assertEquals(ToolResult.Value("""{"ok":true}"""), batch.approve("tool-owned"))
        assertEquals(1, delegate.approveCalls)

        batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search")))
        assertTrue(batch.approve("b1") is ToolResult.Invalid)
        assertEquals(1, delegate.approveCalls)
    }

    @Test
    fun passThroughCallsAndReplayAreForwardedToTheDelegate() = runTest {
        val delegate = FakeBatchDelegate(specs)
        val batch = executor(delegate)
        val call = ToolCall("p1", "kb_search", """{"query":"q"}""")

        assertEquals(ToolResult.Value("""{"ok":true}"""), batch.invoke(call))
        assertEquals(1, delegate.dispatched.size)
        assertTrue(batch.authorizeReplay(call))
        delegate.replayAllowed = false
        assertFalse(batch.authorizeReplay(call))

        assertTrue(batch.reject("unknown-call") is ToolResult.Denied)
        assertTrue(batch.reject("p1") is ToolResult.Denied)
        assertTrue(batch.invoke(ToolCall("p1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))) is ToolResult.Value)
        assertTrue(batch.reject("p1") is ToolResult.Invalid)
        assertTrue(batch.expire("p1") is ToolResult.Invalid)
    }

    @Test
    fun batchEnvelopeStaysWithinTheSerializationBudget() = runTest {
        val payload = buildString {
            append("""{"text":"""")
            repeat(4_000) { append("€") }
            append(""""}""")
        }
        val delegate = FakeBatchDelegate(specs).apply { handler = { ToolResult.Value(payload) } }
        val batch = executor(delegate, budget = ReadBatchBudget(maxResultBytesPerCall = 64, maxTotalResultBytes = 64))
        val results = resultsOf(batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))))
        assertTrue(results[0].truncated)
        assertTrue(results[0].resultBytes <= 64 + 3)
        val text = results[0].resultText ?: fail("a truncated child result must be text")
        assertFalse(text.contains('\uFFFD'))
        assertTrue(payload.startsWith(text))
        assertTrue(ToolResultBudget.withinSerializedBudget((batch.invoke(ToolCall("b1", READ_BATCH_TOOL_NAME, batch("c1" to "kb_search"))) as ToolResult.Value).json))
    }

    private class BatchEntry(val entry: JsonObject) {
        val status: String get() = entry.getValue("status").jsonPrimitive.content

        val callId: String get() = entry.getValue("call_id").jsonPrimitive.content

        val failureCode: String? get() = entry["failure_code"]?.jsonPrimitive?.contentOrNull

        val failureMessage: String get() = entry.getValue("failure_message").jsonPrimitive.content

        val resultBytes: Int get() = entry.getValue("result_bytes").jsonPrimitive.int

        val truncated: Boolean get() = entry.getValue("truncated").jsonPrimitive.boolean

        val result: JsonObject? get() = entry["result"]?.jsonObject

        val resultText: String? get() = entry["result_text"]?.jsonPrimitive?.contentOrNull

        val automaticReplayAllowed: Boolean? get() = entry["automaticReplayAllowed"]?.jsonPrimitive?.booleanOrNull
    }
}

/** Test double: records dispatches, can gate, tracks concurrency, can require approval. */
private class FakeBatchDelegate(specs: List<ToolSpec>) : ToolExecutor {
    override val specs: List<ToolSpec> = specs

    private val lock = Any()
    private val invocations = mutableListOf<ToolCall>()
    private var inFlight = 0

    var handler: suspend (ToolCall) -> ToolResult = { ToolResult.Value("""{"ok":true}""") }
    var gate: CompletableDeferred<Unit>? = null
    var needsApprovalFor: Set<String> = emptySet()
    var replayAllowed = true
    var approveCalls = 0
    var maxInFlight = 0

    val dispatched: List<ToolCall> get() = synchronized(lock) { invocations.toList() }

    override suspend fun invoke(call: ToolCall): ToolResult {
        synchronized(lock) {
            invocations.add(call)
            inFlight += 1
            if (inFlight > maxInFlight) maxInFlight = inFlight
        }
        try {
            gate?.await()
            if (call.name in needsApprovalFor) return ToolResult.NeedsApproval
            return handler(call)
        } finally {
            synchronized(lock) { inFlight -= 1 }
        }
    }

    override suspend fun approve(callId: String): ToolResult {
        synchronized(lock) { approveCalls += 1 }
        return handler(ToolCall(callId, "approved", "{}"))
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean = replayAllowed
}
