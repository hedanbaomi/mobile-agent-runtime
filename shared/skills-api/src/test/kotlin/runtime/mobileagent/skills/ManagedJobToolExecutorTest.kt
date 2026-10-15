// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagedJobToolExecutorTest {
    private var listSequence = 0
    private val eligible = setOf("kb_search")

    private val delegateSpecs = listOf(
        ToolSpec("kb_search", "search", """{"type":"object"}""", "", false),
        ToolSpec("write_thing", "write", """{"type":"object"}""", "", true),
    )

    private fun startArgs(tool: String = "kb_search", arguments: String = """{"query":"q"}"""): String =
        """{"tool":"$tool","arguments":$arguments}"""

    private suspend fun startJob(
        executor: ManagedJobToolExecutor,
        callId: String = "call-1",
        arguments: String = """{"query":"q"}""",
    ): String {
        val request = ToolCall(callId, "job_start", startArgs(arguments = arguments))
        assertEquals(ToolResult.NeedsApproval, executor.invoke(request))
        return jobIdOf(executor.approve(callId))
    }

    private fun jobIdOf(result: ToolResult): String =
        value(result).getValue("job_id").jsonPrimitive.content

    private fun value(result: ToolResult): JsonObject {
        val value = result as? ToolResult.Value ?: fail("expected a value result but got $result")
        return Json.parseToJsonElement(value.json).jsonObject
    }

    private fun executor(
        delegate: FakeJobDelegate,
        scope: CoroutineScope,
        live: suspend () -> Boolean = { true },
        replay: suspend (ToolCall) -> Boolean = { true },
        reserve: (Int) -> Int = { it },
        eligibleTools: Set<String> = eligible,
        budget: ManagedJobBudget = ManagedJobBudget(),
        deferred: suspend (ToolCall) -> Boolean = replay,
        readOnly: Set<String> = emptySet(),
    ): ManagedJobToolExecutor =
        ManagedJobToolExecutor(
            delegate,
            scope,
            live,
            replay,
            reserve,
            eligibleTools,
            budget,
            deferredResultAuthorized = deferred,
            readOnlyTools = readOnly,
        )

    @Test
    fun everyExposedSchemaIsValidJson() = runTest {
        val jobs = executor(FakeJobDelegate(delegateSpecs), backgroundScope)
        jobs.specs.forEach { spec ->
            assertTrue(Json.parseToJsonElement(spec.parametersJson) is JsonObject, spec.name)
        }
    }

    @Test
    fun jobStartNeedsApprovalAndDispatchesNothing() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope)
        val request = ToolCall("call-1", "job_start", startArgs())

        assertEquals(ToolResult.NeedsApproval, jobs.invoke(request))
        runCurrent()
        assertTrue(delegate.dispatched.isEmpty(), "approval must not dispatch")
        // A repeated call id keeps the same authorization signal, never a dispatch.
        assertEquals(ToolResult.NeedsApproval, jobs.invoke(request))
        assertTrue(delegate.dispatched.isEmpty())

        val jobId = jobIdOf(jobs.approve("call-1"))
        runCurrent()
        assertEquals(1, delegate.dispatched.size)
        assertEquals("kb_search", delegate.dispatched.single().name)
        assertEquals("""{"query":"q"}""", delegate.dispatched.single().argumentsJson)

        val page = value(jobs.invoke(ToolCall("out-1", "job_output", """{"job_id":"$jobId"}""")))
        assertEquals("SUCCEEDED", page.getValue("job_status").jsonPrimitive.content)
        assertFalse(page.getValue("result_pending").jsonPrimitive.boolean)
    }

    @Test
    fun approvalBindingUsesTheOriginalToolAndArguments() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope)
        val arguments = """{"query":"bound-marker"}"""
        assertEquals(ToolResult.NeedsApproval, jobs.invoke(ToolCall("call-1", "job_start", startArgs(arguments = arguments))))

        val jobId = jobIdOf(jobs.approve("call-1"))
        runCurrent()
        val child = delegate.dispatched.single()
        assertEquals("kb_search", child.name)
        assertEquals(arguments, child.argumentsJson)
        assertEquals("call-1/job/$jobId", child.callId)

        // An unknown call id falls through to the delegate, which owns no such approval.
        assertTrue(jobs.approve("not-a-call") is ToolResult.Invalid)
        assertEquals(1, delegate.dispatched.size)
    }

    @Test
    fun rejectBeforeApprovalDispatchesNothing() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope)
        val request = ToolCall("call-1", "job_start", startArgs())
        assertEquals(ToolResult.NeedsApproval, jobs.invoke(request))

        assertTrue(jobs.reject("call-1") is ToolResult.Denied)
        runCurrent()
        assertTrue(delegate.dispatched.isEmpty())
        assertTrue(jobs.invoke(request) is ToolResult.Denied)
        assertTrue(delegate.dispatched.isEmpty())
        assertFalse(jobs.hasUnknownOutcomes)
    }

    @Test
    fun repeatedCallIdReturnsTheSameJobWithoutStartingItAgain() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)
        runCurrent()

        val repeat = value(jobs.invoke(ToolCall("call-1", "job_start", startArgs())))
        assertEquals(jobId, repeat.getValue("job_id").jsonPrimitive.content)
        assertEquals(1, delegate.dispatched.size)

        // Same call id with different arguments is a different request.
        assertTrue(jobs.invoke(ToolCall("call-1", "job_start", startArgs(arguments = """{"query":"other"}"""))) is ToolResult.Invalid)
        assertEquals(1, delegate.dispatched.size)
    }

    @Test
    fun ineligibleAndJobToolsCannotBeBackgrounded() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope)

        assertTrue(jobs.invoke(ToolCall("c1", "job_start", startArgs("write_thing"))) is ToolResult.Invalid)
        assertTrue(jobs.invoke(ToolCall("c2", "job_start", startArgs("job_output"))) is ToolResult.Invalid)
        assertTrue(jobs.invoke(ToolCall("c3", "job_start", startArgs(READ_BATCH_TOOL_NAME))) is ToolResult.Invalid)
        runCurrent()
        assertTrue(delegate.dispatched.isEmpty())

        val schema = jobs.specs.single { it.name == "job_start" }.parametersJson
        assertTrue(schema.contains("\"kb_search\""))
        assertFalse(schema.contains("write_thing"))
        assertFalse(schema.contains("job_output"))

        assertThrows<IllegalArgumentException> {
            ManagedJobToolExecutor(delegate, backgroundScope, { true }, { true }, { it }, setOf("job_cancel"))
        }
        assertThrows<IllegalArgumentException> {
            ManagedJobToolExecutor(delegate, backgroundScope, { true }, { true }, { it }, setOf("unknown_tool"))
        }
    }

    @Test
    fun jobOutputPagesUtf8WithoutSplittingCodePoints() = runTest {
        val payload = """{"text":"€中文—🙂 tail"}"""
        val delegate = FakeJobDelegate(delegateSpecs).apply { handler = { ToolResult.Value(payload) } }
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)
        runCurrent()

        val collected = StringBuilder()
        var offset = 0
        var cursor: String? = null
        var pages = 0
        while (true) {
            assertTrue(pages++ < 200, "paging must terminate")
            val args = if (cursor == null) {
                """{"job_id":"$jobId","offset_bytes":$offset,"max_bytes":2}"""
            } else {
                """{"job_id":"$jobId","cursor":"$cursor","max_bytes":2}"""
            }
            val page = value(jobs.invoke(ToolCall("page-$pages", "job_output", args)))
            assertEquals("SUCCEEDED", page.getValue("job_status").jsonPrimitive.content)
            val chunk = page.getValue("chunk").jsonPrimitive.content
            assertFalse(chunk.contains('\uFFFD'), "a chunk must never contain a broken character")
            collected.append(chunk)
            if (!page.getValue("truncated").jsonPrimitive.boolean) break
            val next = page.getValue("next_offset_bytes").jsonPrimitive.int
            assertTrue(next > offset, "a page must always advance")
            offset = next
            cursor = page.getValue("cursor").jsonPrimitive.content
        }
        assertEquals(payload, collected.toString())
    }

    @Test
    fun cursorIsBoundToItsJobAndRejectsTampering() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { handler = { ToolResult.Value("""{"text":"€€€€€€"}""") } }
        val jobs = executor(delegate, backgroundScope)
        val first = startJob(jobs, "a1")
        val second = startJob(jobs, "b1")
        runCurrent()

        val page = value(jobs.invoke(ToolCall("pa", "job_output", """{"job_id":"$first","max_bytes":4}""")))
        assertTrue(page.getValue("truncated").jsonPrimitive.boolean)
        val cursor = page.getValue("cursor").jsonPrimitive.content

        val crossJob = jobs.invoke(ToolCall("pb", "job_output", """{"job_id":"$second","cursor":"$cursor"}"""))
        assertTrue(crossJob is ToolResult.Invalid)
        val tampered = jobs.invoke(
            ToolCall("pc", "job_output", """{"job_id":"$first","cursor":"c1.$first.0.${"0".repeat(24)}"}"""),
        )
        assertTrue(tampered is ToolResult.Invalid)
        // The valid cursor still resumes the original job content, byte-exactly.
        val resumed = value(jobs.invoke(ToolCall("pd", "job_output", """{"job_id":"$first","cursor":"$cursor"}""")))
        val expected = """{"text":"€€€€€€"}"""
        assertEquals(expected, page.getValue("chunk").jsonPrimitive.content + resumed.getValue("chunk").jsonPrimitive.content)
    }

    @Test
    fun pendingJobReportsResultPendingWithoutContent() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)
        runCurrent()
        assertEquals(1, delegate.dispatched.size)

        val page = value(jobs.invoke(ToolCall("out-1", "job_output", """{"job_id":"$jobId"}""")))
        assertTrue(page.getValue("result_pending").jsonPrimitive.boolean)
        assertFalse(page.getValue("terminal").jsonPrimitive.boolean)
        assertEquals("", page.getValue("chunk").jsonPrimitive.content)
        assertEquals(0, page.getValue("result_bytes").jsonPrimitive.int)
        assertTrue(jobs.hasRunningJobs)
    }

    @Test
    fun jobOutputWaitsForATerminalResultWithinTheWaitBudget() = runTest {
        val payload = """{"ok":"late"}"""
        val delegate = FakeJobDelegate(delegateSpecs).apply {
            handler = {
                delay(100)
                ToolResult.Value(payload)
            }
        }
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)

        val page = value(jobs.invoke(ToolCall("out-1", "job_output", """{"job_id":"$jobId","wait_ms":5000}""")))
        assertEquals("SUCCEEDED", page.getValue("job_status").jsonPrimitive.content)
        assertEquals(payload, page.getValue("chunk").jsonPrimitive.content)
    }

    @Test
    fun revocationWithholdsCachedContentWithoutRewritingTheOutcome() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { handler = { ToolResult.Value("""{"secret":"payload"}""") } }
        var replayAllowed = true
        val jobs = executor(delegate, backgroundScope, replay = { replayAllowed })
        val jobId = startJob(jobs)
        runCurrent()

        val outCall = ToolCall("out-1", "job_output", """{"job_id":"$jobId"}""")
        val disclosed = value(jobs.invoke(outCall))
        assertEquals("""{"secret":"payload"}""", disclosed.getValue("chunk").jsonPrimitive.content)

        replayAllowed = false
        // A fresh page request is refused because the content is no longer
        // authorized, and the buffer is purged with the job stopped.
        val denied = jobs.invoke(ToolCall("out-2", "job_output", """{"job_id":"$jobId"}"""))
        assertTrue(denied is ToolResult.Denied)
        assertFalse((denied as ToolResult.Denied).reason.contains("payload"))
        // Re-disclosure of the earlier answer is denied too.
        assertFalse(jobs.authorizeReplay(outCall))

        val job = listedJob(jobs, jobId)
        assertTrue(job.getValue("content_withheld").jsonPrimitive.boolean)
        assertFalse(job.getValue("result_available").jsonPrimitive.boolean)
        assertEquals(0, job.getValue("result_bytes").jsonPrimitive.int)
        // The job did finish, so the outcome stays known ...
        assertEquals("SUCCEEDED", job.getValue("job_status").jsonPrimitive.content)
        assertFalse(jobs.hasUnknownOutcomes)
        // ... but the withheld bytes never come back.
        val after = value(jobs.invoke(ToolCall("out-3", "job_output", """{"job_id":"$jobId"}""")))
        assertEquals("", after.getValue("chunk").jsonPrimitive.content)
        assertTrue(after.getValue("content_withheld").jsonPrimitive.boolean)
    }

    @Test
    fun jobStartReservesExactlyOneChildCallBeforeDispatch() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val reserved = mutableListOf<Int>()
        val jobs = executor(delegate, backgroundScope, reserve = { requested ->
            reserved.add(requested)
            requested
        })
        val jobId = startJob(jobs)
        assertTrue(reserved.isEmpty(), "an approved but not yet dispatched job spends nothing")
        runCurrent()
        assertEquals(listOf(1), reserved)
        assertEquals(1, delegate.dispatched.size)
        runCurrent()

        repeat(2) {
            val repeated = value(jobs.invoke(ToolCall("call-1", "job_start", startArgs())))
            assertEquals(jobId, repeated.getValue("job_id").jsonPrimitive.content)
        }
        assertEquals(listOf(1), reserved, "a repeated call id never reserves again")
        assertEquals(1, delegate.dispatched.size)
    }

    @Test
    fun deniedApprovalAndCapacityNeverSpendChildBudget() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val reserved = mutableListOf<Int>()
        val jobs = executor(delegate, backgroundScope, reserve = { requested ->
            reserved.add(requested)
            requested
        })
        // Denied approval: no reservation.
        assertEquals(ToolResult.NeedsApproval, jobs.invoke(ToolCall("d1", "job_start", startArgs())))
        assertTrue(jobs.reject("d1") is ToolResult.Denied)
        runCurrent()
        assertTrue(reserved.isEmpty())

        // Capacity denial: no reservation.
        startJob(jobs, "j1")
        startJob(jobs, "j2")
        runCurrent()
        assertEquals(listOf(1, 1), reserved)
        assertTrue(jobs.invoke(ToolCall("j3", "job_start", startArgs())) is ToolResult.Denied)
        assertEquals(listOf(1, 1), reserved)
        assertEquals(2, delegate.dispatched.size)
    }

    @Test
    fun revokedBeforeDispatchSpendsNoChildBudget() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val reserved = mutableListOf<Int>()
        var live = true
        val jobs = executor(
            delegate,
            backgroundScope,
            live = { live },
            reserve = { requested ->
                reserved.add(requested)
                requested
            },
        )
        val jobId = startJob(jobs)
        live = false
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(reserved.isEmpty(), "a job revoked before dispatch spends nothing")
        assertTrue(delegate.dispatched.isEmpty())
        assertFalse(jobs.hasUnknownOutcomes, "nothing ran, so nothing is unknown")
        assertEquals("CANCELLED", listedJob(jobs, jobId).getValue("job_status").jsonPrimitive.content)
    }

    @Test
    fun budgetDenialFailsTheJobWithoutDispatch() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope, reserve = { 0 })
        val jobId = startJob(jobs)
        runCurrent()

        assertTrue(delegate.dispatched.isEmpty())
        val job = listedJob(jobs, jobId)
        assertEquals("FAILED", job.getValue("job_status").jsonPrimitive.content)
        assertEquals("QUOTA_EXCEEDED", job.getValue("failure_code").jsonPrimitive.content)
        assertFalse(jobs.hasUnknownOutcomes)
        assertEquals(0, jobs.dispatchedChildren)
    }

    @Test
    fun delegateFailureAfterDispatchIsAnUnknownOutcome() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { handler = { throw IllegalStateException("boom") } }
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)
        runCurrent()

        assertEquals(1, delegate.dispatched.size)
        val job = listedJob(jobs, jobId)
        assertEquals("UNKNOWN_OUTCOME", job.getValue("job_status").jsonPrimitive.content)
        assertEquals("UNKNOWN_OUTCOME", job.getValue("outcome").jsonPrimitive.content)
        assertEquals("INTERNAL_ERROR", job.getValue("failure_code").jsonPrimitive.content)
        assertFalse(job.getValue("automaticReplayAllowed").jsonPrimitive.boolean)
        assertTrue(jobs.hasUnknownOutcomes)
        assertFalse(jobs.hasRunningJobs)
    }

    @Test
    fun cachedJobOutputIsRevalidatedBeforeItIsRepeated() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { handler = { ToolResult.Value("""{"secret":"payload"}""") } }
        var replayAllowed = true
        val jobs = executor(delegate, backgroundScope, replay = { replayAllowed })
        val jobId = startJob(jobs)
        runCurrent()

        val call = ToolCall("out-1", "job_output", """{"job_id":"$jobId"}""")
        val first = value(jobs.invoke(call))
        assertEquals("""{"secret":"payload"}""", first.getValue("chunk").jsonPrimitive.content)

        replayAllowed = false
        // Same call id and arguments: a cached answer is still a disclosure.
        val cached = jobs.invoke(call)
        assertTrue(cached is ToolResult.Denied)
        assertFalse((cached as ToolResult.Denied).reason.contains("secret"))

        // The purged buffer can never be served through a new call id either.
        val after = value(jobs.invoke(ToolCall("out-9", "job_output", """{"job_id":"$jobId"}""")))
        assertEquals("", after.getValue("chunk").jsonPrimitive.content)
        assertTrue(after.getValue("content_withheld").jsonPrimitive.boolean)
    }

    @Test
    fun duplicateJobOutputWhileWaitingIsUnknownNotASecondRead() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)
        runCurrent()
        val args = """{"job_id":"$jobId","wait_ms":5000}"""
        val first = backgroundScope.async { jobs.invoke(ToolCall("out-1", "job_output", args)) }
        runCurrent()

        val second = jobs.invoke(ToolCall("out-1", "job_output", args))
        assertTrue(second is ToolResult.UnknownOutcome)
        assertEquals(1, delegate.dispatched.size)

        delegate.gate?.complete(Unit)
        runCurrent()
        assertTrue(first.await() is ToolResult.Value)
    }

    @Test
    fun firstDisclosureUsesTheDeferredHookWhileCachedReplayStaysStrict() = runTest {
        val payload = """{"secret":"deferred"}"""
        val delegate = FakeJobDelegate(delegateSpecs).apply { handler = { ToolResult.Value(payload) } }
        val jobs = executor(delegate, backgroundScope, replay = { false }, deferred = { true })
        val jobId = startJob(jobs)
        runCurrent()

        // The first page of this job's own result is a deferred disclosure.
        val firstCall = ToolCall("out-1", "job_output", """{"job_id":"$jobId","max_bytes":4}""")
        val first = value(jobs.invoke(firstCall))
        val firstChunk = first.getValue("chunk").jsonPrimitive.content
        assertTrue(firstChunk.isNotEmpty())
        assertTrue(payload.startsWith(firstChunk))
        assertTrue(first.getValue("truncated").jsonPrimitive.boolean)

        // So is the next page, read through the same execution.
        val cursor = first.getValue("cursor").jsonPrimitive.content
        val second = value(jobs.invoke(ToolCall("out-2", "job_output", """{"job_id":"$jobId","cursor":"$cursor"}""")))
        assertEquals(payload, firstChunk + second.getValue("chunk").jsonPrimitive.content)

        // Repeating the outer call id is a cached replay and stays strict.
        val cached = jobs.invoke(firstCall)
        assertTrue(cached is ToolResult.Denied)

        // Non-owned calls proxy the hook; management tools cannot be nested.
        assertTrue(jobs.authorizeDeferredResult(ToolCall("p1", "kb_search", """{"query":"q"}""")))
        assertFalse(jobs.authorizeDeferredResult(ToolCall("p2", "job_output", """{"job_id":"x"}""")))
        assertFalse(jobs.authorizeReplay(ToolCall("p2", "job_output", """{"job_id":"x"}""")))
    }

    @Test
    fun revocationWhileRunningCancelsTheJobAndMarksAnUnknownOutcome() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        var live = true
        val jobs = executor(delegate, backgroundScope, live = { live })
        val jobId = startJob(jobs)
        runCurrent()
        assertEquals(1, delegate.dispatched.size)
        assertTrue(jobs.hasRunningJobs)
        assertFalse(jobs.hasUnknownOutcomes)
        // The revocation watchdog must still be watching the started child.
        assertEquals("RUNNING", listedJob(jobs, jobId).getValue("job_status").jsonPrimitive.content)

        live = false
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(jobs.hasRunningJobs)
        assertTrue(jobs.hasUnknownOutcomes)
        val job = listedJob(jobs, jobId)
        assertEquals("CANCELLED", job.getValue("job_status").jsonPrimitive.content)
        assertEquals("AUTHORIZATION_REVOKED", job.getValue("status_detail").jsonPrimitive.content)
        assertTrue(job.getValue("content_withheld").jsonPrimitive.boolean)
        assertFalse(job.getValue("automaticReplayAllowed").jsonPrimitive.boolean)
    }

    @Test
    fun jobCancelStopsTheChildAndKeepsAnUnknownOutcome() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)
        runCurrent()

        val cancelled = value(jobs.invoke(ToolCall("cancel-1", "job_cancel", """{"job_id":"$jobId"}""")))
        assertTrue(cancelled.getValue("terminal").jsonPrimitive.boolean)
        assertEquals("CANCELLED", cancelled.getValue("job_status").jsonPrimitive.content)
        assertEquals("UNKNOWN_OUTCOME", cancelled.getValue("outcome").jsonPrimitive.content)
        assertFalse(cancelled.getValue("automaticReplayAllowed").jsonPrimitive.boolean)
        assertFalse(jobs.hasRunningJobs)
        assertTrue(jobs.hasUnknownOutcomes)

        // Idempotent, and never a second dispatch.
        val again = value(jobs.invoke(ToolCall("cancel-2", "job_cancel", """{"job_id":"$jobId"}""")))
        assertEquals("CANCELLED", again.getValue("job_status").jsonPrimitive.content)
        assertEquals(1, delegate.dispatched.size)
        assertTrue(jobs.invoke(ToolCall("cancel-3", "job_cancel", """{"job_id":"job_missing"}""")) is ToolResult.Invalid)
    }

    @Test
    fun cancellingAReadOnlyJobIsADefiniteStop() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val jobs = executor(delegate, backgroundScope, readOnly = setOf("kb_search"))
        val jobId = startJob(jobs)
        runCurrent()

        val cancelled = value(jobs.invoke(ToolCall("cancel-1", "job_cancel", """{"job_id":"$jobId"}""")))
        assertEquals("CANCELLED", cancelled.getValue("job_status").jsonPrimitive.content)
        assertFalse(cancelled.containsKey("outcome"), "a stopped read changed nothing")
        assertFalse(jobs.hasUnknownOutcomes)
        assertEquals(1, jobs.dispatchedChildren)
    }

    @Test
    fun readOnlyJobDeadlineAndFailureDoNotLiftTheRun() = runTest {
        val slow = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val timed = executor(slow, backgroundScope, budget = ManagedJobBudget(jobDeadlineMs = 1_000), readOnly = setOf("kb_search"))
        val timedId = startJob(timed)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("TIMED_OUT", listedJob(timed, timedId).getValue("job_status").jsonPrimitive.content)
        assertFalse(timed.hasUnknownOutcomes)

        val failing = FakeJobDelegate(delegateSpecs).apply { handler = { throw IllegalStateException("boom") } }
        val failed = executor(failing, backgroundScope, readOnly = setOf("kb_search"))
        val failedId = startJob(failed)
        runCurrent()
        assertEquals("FAILED", listedJob(failed, failedId).getValue("job_status").jsonPrimitive.content)
        assertFalse(failed.hasUnknownOutcomes)
    }

    @Test
    fun readOnlyDeclarationCannotWidenASideEffectingDelegateSpec() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val jobs = executor(
            delegate,
            backgroundScope,
            eligibleTools = setOf("kb_search", "write_thing"),
            readOnly = setOf("kb_search", "write_thing"),
        )
        val request = ToolCall("w1", "job_start", startArgs(tool = "write_thing"))
        assertEquals(ToolResult.NeedsApproval, jobs.invoke(request))
        val jobId = jobIdOf(jobs.approve("w1"))
        runCurrent()

        jobs.invoke(ToolCall("cancel-w", "job_cancel", """{"job_id":"$jobId"}"""))
        assertTrue(jobs.hasUnknownOutcomes, "the delegate declares write_thing side-effecting")
    }

    @Test
    fun jobDeadlineIsDistinctFromCancellation() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val jobs = executor(delegate, backgroundScope, budget = ManagedJobBudget(jobDeadlineMs = 1_000))
        val jobId = startJob(jobs)
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()

        val job = listedJob(jobs, jobId)
        assertEquals("TIMED_OUT", job.getValue("job_status").jsonPrimitive.content)
        assertEquals("TIMEOUT", job.getValue("failure_code").jsonPrimitive.content)
        assertTrue(jobs.hasUnknownOutcomes)
        assertFalse(jobs.hasRunningJobs)
    }

    @Test
    fun resultBudgetTruncatesOnACodePointBoundary() = runTest {
        val payload = """{"text":"€€€€€€€€€€"}"""
        val delegate = FakeJobDelegate(delegateSpecs).apply { handler = { ToolResult.Value(payload) } }
        val jobs = executor(
            delegate,
            backgroundScope,
            budget = ManagedJobBudget(maxResultBytesPerJob = 16, maxTotalResultBytes = 16),
        )
        val jobId = startJob(jobs)
        runCurrent()

        val page = value(jobs.invoke(ToolCall("out-1", "job_output", """{"job_id":"$jobId","max_bytes":4096}""")))
        assertTrue(page.getValue("result_truncated").jsonPrimitive.boolean)
        assertFalse(page.getValue("truncated").jsonPrimitive.boolean)
        val chunk = page.getValue("chunk").jsonPrimitive.content
        assertTrue(chunk.isNotEmpty())
        assertTrue(payload.startsWith(chunk))
        assertFalse(chunk.contains('\uFFFD'))
        assertEquals(chunk.toByteArray(Charsets.UTF_8).size, page.getValue("result_bytes").jsonPrimitive.int)
    }

    @Test
    fun capacityLimitsRunningAndTotalJobs() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val jobs = executor(delegate, backgroundScope)
        startJob(jobs, "j1")
        startJob(jobs, "j2")
        runCurrent()
        assertTrue(jobs.invoke(ToolCall("j3", "job_start", startArgs())) is ToolResult.Denied)
        assertEquals(2, delegate.dispatched.size)

        val counts = value(jobs.invoke(ToolCall("list-1", "job_list", "{}")))
        assertEquals(2, counts.getValue("running_jobs").jsonPrimitive.int)
        assertEquals(2, counts.getValue("max_concurrent_jobs").jsonPrimitive.int)
        assertEquals(2, counts.getValue("jobs_started").jsonPrimitive.int)
    }

    @Test
    fun jobsPerRunLimitDeniesFurtherStarts() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(
            delegate,
            backgroundScope,
            budget = ManagedJobBudget(maxConcurrentJobs = 1, maxJobsPerRun = 1),
        )
        startJob(jobs, "j1")
        runCurrent()
        assertTrue(jobs.invoke(ToolCall("j2", "job_start", startArgs())) is ToolResult.Denied)
        assertEquals(1, delegate.dispatched.size)
    }

    @Test
    fun jobListCarriesNoArgumentsOrChildCallIds() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope)
        val secret = "s3cret-query-marker"
        startJob(jobs, arguments = """{"query":"$secret"}""")
        runCurrent()

        val listed = jobs.invoke(ToolCall("list-1", "job_list", "{}"))
        val json = (listed as? ToolResult.Value ?: fail("expected a value result")).json
        assertFalse(json.contains(secret))
        assertFalse(json.contains("query"))
        assertFalse(json.contains(delegate.dispatched.single().callId))
    }

    @Test
    fun nestedDelegateApprovalIsSettledThroughDelegateApprove() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply {
            requireApproval = true
            handler = { ToolResult.Value("""{"approved":true}""") }
        }
        val jobs = executor(delegate, backgroundScope)
        val jobId = startJob(jobs)
        runCurrent()

        assertEquals(1, delegate.dispatched.size)
        assertEquals(1, delegate.approveCalls)
        val page = value(jobs.invoke(ToolCall("out-1", "job_output", """{"job_id":"$jobId"}""")))
        assertEquals("SUCCEEDED", page.getValue("job_status").jsonPrimitive.content)
        assertEquals("""{"approved":true}""", page.getValue("chunk").jsonPrimitive.content)
    }

    @Test
    fun closeCancelsAndJoinsEveryJob() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val jobs = executor(delegate, scope)
        startJob(jobs, "j1")
        startJob(jobs, "j2")
        runCurrent()
        assertEquals(2, delegate.dispatched.size)

        jobs.close()

        assertFalse(jobs.hasRunningJobs)
        assertTrue(jobs.hasUnknownOutcomes)
        assertEquals(0, scope.coroutineContext[Job]!!.children.count { it.isActive })
        assertTrue(jobs.invoke(ToolCall("after", "job_start", startArgs())) is ToolResult.Denied)
        scope.cancel()
    }

    @Test
    fun parentScopeCancellationStopsJobsAndMarksUnknownOutcomes() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs).apply { gate = CompletableDeferred() }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val jobs = executor(delegate, scope)
        val jobId = startJob(jobs)
        runCurrent()

        scope.cancel()
        runCurrent()

        assertFalse(jobs.hasRunningJobs)
        assertTrue(jobs.hasUnknownOutcomes)
        assertEquals("CANCELLED", listedJob(jobs, jobId).getValue("job_status").jsonPrimitive.content)
    }

    @Test
    fun passThroughCallsAndReplayAreForwardedToTheDelegate() = runTest {
        val delegate = FakeJobDelegate(delegateSpecs)
        val jobs = executor(delegate, backgroundScope)
        val call = ToolCall("p1", "kb_search", """{"query":"q"}""")

        assertEquals(ToolResult.Value("""{"ok":true}"""), jobs.invoke(call))
        assertEquals(1, delegate.dispatched.size)
        assertTrue(jobs.authorizeReplay(call))
        delegate.replayAllowed = false
        assertFalse(jobs.authorizeReplay(call))
        delegate.replayAllowed = true
        assertTrue(jobs.authorizeReplay(call))

        // Our own unknown call ids never disclose, and unknown approvals settle downstream.
        assertFalse(jobs.authorizeReplay(ToolCall("nope", "job_output", """{"job_id":"x"}""")))
        assertTrue(jobs.reject("unknown-call") is ToolResult.Denied)
    }

    private suspend fun listedJob(jobs: ManagedJobToolExecutor, jobId: String): JsonObject {
        val listed = jobs.invoke(ToolCall("list-${listSequence++}-$jobId", "job_list", "{}"))
        val json = (listed as? ToolResult.Value ?: fail("expected a value result")).json
        return Json.parseToJsonElement(json).jsonObject
            .getValue("jobs").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("job_id").jsonPrimitive.content == jobId }
    }

    private fun payloadOf(payload: String): ByteArray = payload.toByteArray(Charsets.UTF_8)
}

/** Test double: records dispatches, can gate, can require its own approval. */
private class FakeJobDelegate(specs: List<ToolSpec>) : ToolExecutor {
    override val specs: List<ToolSpec> = specs

    private val lock = Any()
    private val invocations = mutableListOf<ToolCall>()
    private val pending = mutableMapOf<String, ToolCall>()

    var handler: suspend (ToolCall) -> ToolResult = { ToolResult.Value("""{"ok":true}""") }
    var replayAllowed = true
    var requireApproval = false
    var gate: CompletableDeferred<Unit>? = null
    var approveCalls = 0

    val dispatched: List<ToolCall> get() = synchronized(lock) { invocations.toList() }

    override suspend fun invoke(call: ToolCall): ToolResult {
        synchronized(lock) { invocations.add(call) }
        gate?.await()
        if (requireApproval) {
            synchronized(lock) { pending[call.callId] = call }
            return ToolResult.NeedsApproval
        }
        return handler(call)
    }

    override suspend fun approve(callId: String): ToolResult {
        val call = synchronized(lock) { pending.remove(callId) } ?: return ToolResult.Invalid("no pending call")
        synchronized(lock) {
            approveCalls += 1
        }
        return handler(call)
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean = replayAllowed
}
