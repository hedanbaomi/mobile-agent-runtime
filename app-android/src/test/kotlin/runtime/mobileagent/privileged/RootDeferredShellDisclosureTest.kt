// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.RunProductivityTools
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult

/**
 * R51-R4-1 regression over the real ManagedJobToolExecutor + RootRuntimeToolBackend + SQLite
 * ledger: a background `shell_exec` that exits nonzero is a settled, observable result. Its
 * first `job_output` page and every paged continuation stay disclosable while the same live
 * Root authority holds, while a withheld or unknown receipt is never released again.
 */
class RootDeferredShellDisclosureTest {
    private val harness = RootRuntimeToolBackendIntegrationTest()

    @Test
    fun settledNonZeroBackgroundShellIsReleasedOnTheFirstPageAndOnLaterPages() =
        harness.fixture(nonZeroShell()) { f ->
            val runId = "run-job-nonzero"
            val productivity = productivity(f, runId)
            val jobId = startShellJob(f, productivity, runId, "job-start-nonzero")

            // Deferred settlement keeps the FAILED receipt open until job_output discloses it.
            assertReceipt(f, runId, outcome = "FAILED", closed = 0L, disclosure = "PENDING")
            assertChildState(f, runId, "ROOT_DISPATCH_RESULT_PENDING")

            val first = jobOutput(productivity, "job-out-nonzero-1", jobId, offset = 0, maxBytes = 24)
            assertEquals(true, first.getValue("truncated").jsonPrimitive.boolean)
            assertEquals(24, first.getValue("next_offset_bytes").jsonPrimitive.int)
            val chunk1 = first.getValue("chunk").jsonPrimitive.content
            assertTrue(chunk1.isNotEmpty(), chunk1)

            // The first page is the disclosure decision: the receipt is released exactly once.
            assertReceipt(f, runId, outcome = "FAILED", closed = 1L, disclosure = "RELEASED")
            assertChildState(f, runId, "FAILED")

            val second = jobOutput(productivity, "job-out-nonzero-2", jobId, offset = 24, maxBytes = 4096)
            assertEquals(false, second.getValue("truncated").jsonPrimitive.boolean)
            val content = chunk1 + second.getValue("chunk").jsonPrimitive.content
            assertTrue(content.contains("\"status\":\"FAILED\""), content)
            assertTrue(content.contains("\"success\":false"), content)
            assertTrue(content.contains("\"exit_code\":7"), content)
            assertTrue(content.contains("nonzero-stdout"), content)
            assertTrue(content.contains("nonzero-stderr"), content)

            // A later page revalidates the released receipt instead of re-dispatching the shell.
            val third = jobOutput(productivity, "job-out-nonzero-3", jobId, offset = 24, maxBytes = 4096)
            assertEquals(false, third.getValue("truncated").jsonPrimitive.boolean)
            assertReceipt(f, runId, outcome = "FAILED", closed = 1L, disclosure = "RELEASED")
            assertEquals(1, f.shellCalls)
        }

    @Test
    fun withheldSettledFailureCannotBeReleasedByAnyLaterRead() = harness.fixture(nonZeroShell()) { f ->
        val runId = "run-job-withheld"
        val productivity = productivity(f, runId)
        val (jobId, childInvocationId) = startShellJobWithChild(f, productivity, runId, "job-start-withheld")
        assertReceipt(f, runId, outcome = "FAILED", closed = 0L, disclosure = "PENDING")

        // Cancellation/revocation settles the deferred result as WITHHELD before any page.
        runBlocking {
            productivity.executor.withholdDeferredResult(
                ToolCall(childInvocationId, "shell_exec", shellArguments(f)),
            )
        }
        assertReceipt(f, runId, outcome = "FAILED", closed = 1L, disclosure = "WITHHELD")
        assertChildState(f, runId, "COMPLETED_WITHHELD")

        // The durable receipt stays WITHHELD and every disclosure gate keeps denying it.
        assertFalse(f.coordinator.precheckResultDisclosure(Authority.ROOT_DIRECT, childInvocationId))
        assertFalse(
            f.coordinator.authorizeResultDisclosure(
                Authority.ROOT_DIRECT, childInvocationId, ResultDisclosure.DEFERRED,
            ),
        )
        assertFalse(
            f.coordinator.authorizeResultDisclosure(
                Authority.ROOT_DIRECT, childInvocationId, ResultDisclosure.REPLAY,
            ),
        )

        val denied = runBlocking {
            productivity.executor.invoke(
                ToolCall("job-out-withheld", "job_output", jobOutputArguments(jobId)),
            )
        }
        assertTrue(denied is ToolResult.Denied, denied.toString())
        // A later status-only read follows the shared job protocol: no bytes are released.
        val statusOnly = jobOutput(productivity, "job-withheld-status", jobId, offset = 0, maxBytes = 4096)
        assertEquals("", statusOnly.getValue("chunk").jsonPrimitive.content)
        assertTrue(statusOnly.getValue("content_withheld").jsonPrimitive.boolean)
        assertEquals(0, statusOnly.getValue("result_bytes").jsonPrimitive.int)
        val listing = runBlocking {
            productivity.executor.invoke(ToolCall("job-withheld-list", "job_list", "{}"))
        }
        assertTrue(listing is ToolResult.Value, listing.toString())
        val listed = Json.parseToJsonElement((listing as ToolResult.Value).json).jsonObject
            .getValue("jobs").jsonArray.single().jsonObject
        assertFalse(listed.getValue("result_available").jsonPrimitive.boolean)
        // Neither the denied read nor the empty status response can release the withheld receipt.
        assertReceipt(f, runId, outcome = "FAILED", closed = 1L, disclosure = "WITHHELD")
    }

    @Test
    fun shellConsentRevocationWithholdsFirstReadAndStopsReleasedPagination() {
        for (releaseFirstPage in listOf(false, true)) {
            harness.fixture(nonZeroShell()) { f ->
                val runId = "run-job-revoked-$releaseFirstPage"
                val productivity = productivity(f, runId)
                val (jobId, childInvocationId) = startShellJobWithChild(
                    f, productivity, runId, "job-start-revoked-$releaseFirstPage",
                )
                if (releaseFirstPage) {
                    jobOutput(productivity, "job-first-revoked", jobId, offset = 0, maxBytes = 24)
                    assertReceipt(f, runId, outcome = "FAILED", closed = 1L, disclosure = "RELEASED")
                }
                assertNotNull(f.coordinator.setRootShellConsent(Authority.ROOT_DIRECT, false, false, f.manager))
                assertFalse(f.coordinator.precheckResultDisclosure(Authority.ROOT_DIRECT, childInvocationId))
                val output = runBlocking {
                    productivity.executor.invoke(ToolCall("job-after-revoke", "job_output", buildJsonObject {
                        put("job_id", jobId)
                        put("offset_bytes", if (releaseFirstPage) 24 else 0)
                        put("max_bytes", 4096)
                    }.toString()))
                }
                assertTrue(output is ToolResult.Denied, output.toString())
                assertFalse(output.toString().contains("nonzero-stdout"))
                // Previously disclosed bytes remain historical facts; unread bytes are now withheld.
                assertReceipt(f, runId, outcome = "FAILED", closed = 1L,
                    disclosure = if (releaseFirstPage) "RELEASED" else "WITHHELD")
                assertFalse(f.coordinator.authorizeResultDisclosure(
                    Authority.ROOT_DIRECT, childInvocationId, ResultDisclosure.REPLAY,
                ))
                assertEquals(1, f.shellCalls)
            }
        }
    }

    @Test
    fun unknownBackgroundShellIsNeverReleasedByAnyLaterRead() = harness.fixture(unknownShell()) { f ->
        val runId = "run-job-unknown"
        val productivity = productivity(f, runId)
        val (jobId, childInvocationId) = startShellJobWithChild(f, productivity, runId, "job-start-unknown")

        // An unknown outcome is already withheld at settlement, before job_output asks for it.
        awaitUnknownJob(productivity)
        val receipt = awaitClosedReceipt(f, runId)
        assertEquals("UNKNOWN_OUTCOME", receipt.string("outcome"))
        assertEquals(1L, receipt.long("closed"))
        assertEquals("WITHHELD", receipt.string("disclosure"))
        assertChildState(f, runId, "UNKNOWN_OUTCOME")

        assertFalse(f.coordinator.precheckResultDisclosure(Authority.ROOT_DIRECT, childInvocationId))
        assertFalse(
            f.coordinator.authorizeResultDisclosure(
                Authority.ROOT_DIRECT, childInvocationId, ResultDisclosure.DEFERRED,
            ),
        )
        assertFalse(
            f.coordinator.authorizeResultDisclosure(
                Authority.ROOT_DIRECT, childInvocationId, ResultDisclosure.REPLAY,
            ),
        )

        val result = runBlocking {
            productivity.executor.invoke(
                ToolCall("job-out-unknown", "job_output", jobOutputArguments(jobId)),
            )
        }
        assertTrue(result is ToolResult.UnknownOutcome, result.toString())
        assertReceipt(f, runId, outcome = "UNKNOWN_OUTCOME", closed = 1L, disclosure = "WITHHELD")
    }

    @Test
    fun workspaceViewsDiscloseStrictAtomicMoveAndPatchConditions() = harness.fixture { f ->
        val backend = RootRuntimeToolBackend(
            f.db, f.coordinator, f.context("run-workspace-views"), f.execution,
        )
        val view = backend.workspaceViews().single { it.id == f.workspace.id }
        assertTrue("file_move" in view.authorizedOperations, view.authorizedOperations.toString())
        assertFalse(view.atomicReplace)
        assertEquals(
            "no_replace_and_all_directory_moves_require_kernel_atomic_no_replace_rename; " +
                "emulated_storage_may_reject_these_moves; " +
                "replace_existing_true_supports_regular_file_entry_replace_only; " +
                "no_non_atomic_fallback",
            view.operationConditions.getValue("file_move"),
        )
        assertEquals(
            "requires_uid0_exclusive_parent_chain; app_private_paths_return_WORKSPACE_VERSION_UNSUPPORTED",
            view.operationConditions.getValue("file_apply_patch"),
        )
    }

    private fun productivity(
        f: RootRuntimeToolBackendIntegrationTest.Fixture,
        runId: String,
    ): RunProductivityTools = RunProductivityTools(
        core = RootToolExecutor(
            RootToolArguments.specs(),
            RootRuntimeToolBackend(f.db, f.coordinator, f.context(runId), f.execution),
        ),
        scope = CoroutineScope(Dispatchers.Unconfined),
        liveContext = { true },
        reserveCalls = { it },
    )

    private fun startShellJob(
        f: RootRuntimeToolBackendIntegrationTest.Fixture,
        productivity: RunProductivityTools,
        runId: String,
        startCallId: String,
    ): String = startShellJobWithChild(f, productivity, runId, startCallId).first

    private fun startShellJobWithChild(
        f: RootRuntimeToolBackendIntegrationTest.Fixture,
        productivity: RunProductivityTools,
        runId: String,
        startCallId: String,
    ): Pair<String, String> {
        val jobStartArgs = buildJsonObject {
            put("tool", "shell_exec")
            put("arguments", buildJsonObject {
                put("workspace_id", f.workspace.id)
                put("command", "synthetic-nonzero")
            })
        }.toString()
        val startCall = ToolCall(startCallId, "job_start", jobStartArgs)
        assertEquals(ToolResult.NeedsApproval, runBlocking { productivity.executor.invoke(startCall) })
        val parentRuntimeId = requireNotNull(productivity.runtimeInvocationId(startCallId))
        f.recordCall(runId, parentRuntimeId, startCallId, "job_start", jobStartArgs)

        val started = runBlocking { productivity.executor.approve(startCallId) }
        assertTrue(started is ToolResult.Value, started.toString())
        val jobId = Json.parseToJsonElement((started as ToolResult.Value).json)
            .jsonObject.getValue("job_id").jsonPrimitive.content
        awaitJobCompleted(productivity, "$startCallId-poll")
        val childInvocationId = f.db.query(
            "SELECT invocation_id FROM tool_invocations WHERE run_id=? AND invocation_id LIKE 'rchild.job.%'",
            listOf(runId),
        ).single().string("invocation_id")
        return jobId to childInvocationId
    }

    private fun shellArguments(f: RootRuntimeToolBackendIntegrationTest.Fixture): String =
        buildJsonObject {
            put("workspace_id", f.workspace.id)
            put("command", "synthetic-nonzero")
        }.toString()

    private fun jobOutputArguments(jobId: String): String = buildJsonObject {
        put("job_id", jobId)
        put("offset_bytes", 0)
        put("max_bytes", 4096)
    }.toString()

    private fun jobOutput(
        productivity: RunProductivityTools,
        callId: String,
        jobId: String,
        offset: Int,
        maxBytes: Int,
    ): JsonObject {
        val result = runBlocking {
            productivity.executor.invoke(ToolCall(callId, "job_output", buildJsonObject {
                put("job_id", jobId)
                put("offset_bytes", offset)
                put("max_bytes", maxBytes)
            }.toString()))
        }
        assertTrue(result is ToolResult.Value, result.toString())
        return Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
    }

    private fun awaitJobCompleted(productivity: RunProductivityTools, prefix: String) {
        repeat(500) { idx ->
            val list = runBlocking { productivity.executor.invoke(ToolCall("$prefix-$idx", "job_list", "{}")) }
            if (list is ToolResult.UnknownOutcome) return
            if (list is ToolResult.Value &&
                !list.json.contains("\"RUNNING\"") && !list.json.contains("\"PENDING_APPROVAL\"")
            ) {
                return
            }
            Thread.sleep(10)
        }
        throw AssertionError("background job did not reach a terminal state")
    }

    private fun awaitUnknownJob(productivity: RunProductivityTools) {
        var attempts = 0
        while (attempts < 300) {
            val result = runBlocking {
                productivity.executor.invoke(ToolCall("poll-unknown-$attempts", "job_list", "{}"))
            }
            if (result is ToolResult.UnknownOutcome) return
            attempts += 1
            Thread.sleep(10)
        }
        throw AssertionError("background job never reported its unknown outcome")
    }

    private fun awaitClosedReceipt(
        f: RootRuntimeToolBackendIntegrationTest.Fixture,
        runId: String,
    ): SqlRow {
        repeat(300) {
            val row = f.db.query(
                "SELECT stage,outcome,closed,disclosure FROM root_dispatch_receipts WHERE run_id=?",
                listOf(runId),
            ).singleOrNull()
            if (row != null && row.long("closed") == 1L) return row
            Thread.sleep(10)
        }
        throw AssertionError("Root dispatch receipt for $runId never closed")
    }

    private fun assertReceipt(
        f: RootRuntimeToolBackendIntegrationTest.Fixture,
        runId: String,
        outcome: String,
        closed: Long,
        disclosure: String,
    ) {
        val row = f.db.query(
            "SELECT stage,outcome,closed,disclosure FROM root_dispatch_receipts WHERE run_id=?",
            listOf(runId),
        ).single()
        assertEquals("TERMINAL", row.string("stage"))
        assertEquals(outcome, row.string("outcome"))
        assertEquals(closed, row.long("closed"))
        assertEquals(disclosure, row.string("disclosure"))
    }

    private fun assertChildState(
        f: RootRuntimeToolBackendIntegrationTest.Fixture,
        runId: String,
        state: String,
    ) {
        val row = f.db.query(
            "SELECT state FROM tool_invocations WHERE run_id=? AND invocation_id LIKE 'rchild.job.%'",
            listOf(runId),
        ).single()
        assertEquals(state, row.string("state"))
    }

    private fun nonZeroShell(): RootShellRunOutcome = classifyRootShellOutcome(
        RootProcessGroupEvidence(
            leaderExited = true,
            exitCode = 7,
            timedOut = false,
            cancelled = false,
            leakedDescendants = false,
            settled = true,
        ),
    ).copy(stdout = "nonzero-stdout", stderr = "nonzero-stderr")

    private fun unknownShell(): RootShellRunOutcome = classifyRootShellOutcome(
        RootProcessGroupEvidence(
            leaderExited = true,
            exitCode = 7,
            timedOut = true,
            cancelled = false,
            leakedDescendants = false,
            settled = true,
        ),
    ).copy(stdout = "unknown-stdout", stderr = "unknown-stderr")
}
