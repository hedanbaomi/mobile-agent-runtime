// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.ToolInvocation
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore
import runtime.mobileagent.tooling.ToolExecutionContext

/** Real local app/service/DB seams with synthetic OS facts and FDs; not a device acceptance test. */
class RootRuntimeToolBackendIntegrationTest {
    @Test
    fun grepReadsOnlyItsBoundedWindowsWithoutWholeFileDigestPasses() = fixture { f ->
        val text = "line without hit\n".repeat(32_768) + "needle\n"
        f.fd.seedText("large.txt", text)
        f.reportedFileSize = 1024L * 1024 * 1024
        val context = f.context("run-grep-bounded")
        val executor = RootToolExecutor(RootToolArguments.specs(),
            RootRuntimeToolBackend(f.db, f.coordinator, context, f.execution))
        val call = ToolCall("runtime-grep-bounded", "file_grep", buildJsonObject {
            put("workspace_id", f.workspace.id); put("query", "needle")
            put("max_scanned_bytes", 131072); put("max_millis", 5000)
        }.toString())
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
        f.recordCall(context.taskIdentity, call.callId, "model-grep", call.name, call.argumentsJson)
        f.digestReads = 0
        f.rangeReadBytes = 0L
        val result = runBlocking { executor.approve(call.callId) }
        assertTrue(result is ToolResult.Value, result.toString())
        val body = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
        assertTrue(body.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(0, f.digestReads, "grep must not hash the whole file for each page")
        assertEquals(131072L, f.rangeReadBytes)
        assertTrue(body.getValue("matches").jsonArray.isEmpty())
    }

    @Test
    fun settledServiceCancellationHasAnExplicitErrorAndWithholdsOutput() {
        val outcome = classifyRootShellOutcome(RootProcessGroupEvidence(
            leaderExited = true, exitCode = 1, timedOut = false, cancelled = true,
            leakedDescendants = false, settled = true,
        )).copy(stdout = "withheld-output", stderr = "withheld-diagnostic")
        fixture(outcome) { f ->
            val context = f.context("run-shell-cancelled")
            val executor = RootToolExecutor(RootToolArguments.specs(),
                RootRuntimeToolBackend(f.db, f.coordinator, context, f.execution))
            val call = ToolCall("runtime-shell-cancelled", "shell_exec", buildJsonObject {
                put("workspace_id", f.workspace.id); put("command", "synthetic-cancelled")
            }.toString())
            assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
            f.recordCall(context.taskIdentity, call.callId, "model-shell-cancelled", call.name, call.argumentsJson)
            val result = runBlocking { executor.approve(call.callId) }
            assertTrue(result is ToolResult.Failure, result.toString())
            assertEquals(runtime.mobileagent.skills.tooling.ToolErrorCode.SHELL_CANCELLED,
                (result as ToolResult.Failure).error.code)
            assertFalse(result.toString().contains("withheld-output"))
        }
    }

    @Test
    fun settledShellExitCodesKeepOutputAcrossApprovalDispatchAndProjection() {
        for (exit in listOf(0, 1, 2, 126)) {
            val outcome = classifyRootShellOutcome(RootProcessGroupEvidence(
                leaderExited = true, exitCode = exit, timedOut = false, cancelled = false,
                leakedDescendants = false, settled = true,
            )).copy(stdout = "observed-output-$exit", stderr = "observed-diagnostic-$exit")
            fixture(outcome) { f ->
                val context = f.context("run-shell-$exit")
                val executor = RootToolExecutor(RootToolArguments.specs(),
                    RootRuntimeToolBackend(f.db, f.coordinator, context, f.execution))
                val call = ToolCall("runtime-shell-$exit", "shell_exec", buildJsonObject {
                    put("workspace_id", f.workspace.id); put("command", "synthetic-exit-$exit")
                }.toString())
                assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
                f.recordCall(context.taskIdentity, call.callId, "model-shell-$exit", call.name, call.argumentsJson)
                val result = runBlocking { executor.approve(call.callId) }
                assertTrue(result is ToolResult.Value, result.toString())
                val body = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
                assertEquals(exit, body.getValue("exit_code").jsonPrimitive.int)
                assertEquals(exit == 0, body.getValue("success").jsonPrimitive.boolean)
                assertEquals(outcome.status.name, body.getValue("status").jsonPrimitive.content)
                assertEquals(outcome.stdout, body.getValue("stdout").jsonPrimitive.content)
                assertEquals(outcome.stderr, body.getValue("stderr").jsonPrimitive.content)
                assertEquals(1, f.shellCalls)
                assertNotNull(f.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))
            }
        }
    }

    @Test
    fun unsettledShellResultCannotDiscloseCapturedOutputAsACompletedValue() {
        val outcome = classifyRootShellOutcome(RootProcessGroupEvidence(
            leaderExited = true, exitCode = 1, timedOut = false, cancelled = false,
            leakedDescendants = true, settled = true,
        )).copy(stdout = "withheld-output", stderr = "withheld-diagnostic")
        fixture(outcome) { f ->
            val context = f.context("run-shell-unknown")
            val executor = RootToolExecutor(RootToolArguments.specs(),
                RootRuntimeToolBackend(f.db, f.coordinator, context, f.execution))
            val call = ToolCall("runtime-shell-unknown", "shell_exec", buildJsonObject {
                put("workspace_id", f.workspace.id); put("command", "synthetic-unknown")
            }.toString())
            assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
            f.recordCall(context.taskIdentity, call.callId, "model-shell-unknown", call.name, call.argumentsJson)
            assertTrue(runBlocking { executor.approve(call.callId) } is ToolResult.UnknownOutcome)
            assertEquals(1, f.shellCalls)
        }
    }

    @Test
    fun factoryTypedSnapshotReachesRealRootApprovalAndRevokedDiscoveryCannotReplay() = fixture { f ->
        val runId = "run-real-factory-typed"
        val context = f.context(runId)
        val root = RootToolExecutor(RootToolArguments.specs(), RootRuntimeToolBackend(f.db, f.coordinator, context, f.execution))
        val factory = runtime.mobileagent.tooling.ToolExecutorFactory(
            root = root, rootWorkspaceIds = setOf(f.workspace.id), boundSnapshotId = context.snapshotId,
        )
        assertEquals(context.snapshotId, factory.beginRun().snapshot.snapshotId)
        val stale = runtime.mobileagent.skills.tooling.ToolInvocation.fromRuntime(
            "model-stale-factory", "other-snapshot", "agent", "workspace_list", "{}")
        val rejected = runBlocking { factory.invoke(stale) } as runtime.mobileagent.skills.tooling.ToolExecution.Failed
        assertEquals(runtime.mobileagent.skills.tooling.ToolErrorCode.SNAPSHOT_STALE, rejected.error.code)

        f.fd.seedText("typed.txt", "real-typed-file")
        val args = """{"workspace_id":"${f.workspace.id}","relative_path":"typed.txt"}"""
        val typed = runtime.mobileagent.skills.tooling.ToolInvocation.fromRuntime(
            "model-real-typed", context.snapshotId, "agent", "file_read_text", args)
        f.recordCall(runId, typed.requestId, typed.callId, typed.name, typed.argumentsJson)
        val pending = runBlocking { factory.invoke(typed) } as runtime.mobileagent.skills.tooling.ToolExecution.Failed
        assertEquals(runtime.mobileagent.skills.tooling.ToolErrorCode.APPROVAL_REQUIRED, pending.error.code)
        val result = runBlocking { factory.approve(typed.requestId) }
        assertTrue(result is ToolResult.Value, result.toString())
        assertTrue((result as ToolResult.Value).json.contains("real-typed-file"))
        val binding = f.db.query("SELECT binding_json FROM root_approval_bindings WHERE invocation_id=?", listOf(typed.requestId))
            .single().string("binding_json")
        assertTrue(binding.contains("\"callId\":\"model-real-typed\""))

        val wrongAgent = runtime.mobileagent.skills.tooling.ToolInvocation.fromRuntime(
            "wrong-agent", context.snapshotId, "other-agent", "file_read_text", args)
        assertTrue(runBlocking { factory.invoke(wrongAgent) } is runtime.mobileagent.skills.tooling.ToolExecution.Failed)
        val discovery = runtime.mobileagent.skills.tooling.ToolInvocation.fromRuntime(
            "typed-discovery", context.snapshotId, "agent", "workspace_list", "{}")
        assertTrue(runBlocking { factory.invoke(discovery) } is runtime.mobileagent.skills.tooling.ToolExecution.Value)
        f.coordinator.detachSession(Authority.ROOT_DIRECT, "typed_discovery_revoked")
        assertTrue(runBlocking { factory.invoke(discovery) } is runtime.mobileagent.skills.tooling.ToolExecution.Failed,
            "The ToolRegistry terminal cache must revalidate the real Root discovery owner")
        assertEquals(1, f.db.query("SELECT request_id FROM root_dispatch_receipts").size)
        runBlocking { factory.createLegacyExecutor().close() }
        assertTrue(runBlocking { factory.invoke(discovery) } is runtime.mobileagent.skills.tooling.ToolExecution.Failed)
    }

    @Test
    fun settingsDangerousModeToggleKeepsTheLiveRootDirectoryUsableForANewRun() = fixture { f ->
        f.coordinator.setRootShellConsent(Authority.ROOT_DIRECT, true, true, f.manager)
        val before = requireNotNull(f.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, true))
        val store = runtime.mobileagent.integration.SqliteDangerousModeStateStore(AuthorityPolicyRepository(f.db))
        val state = store.load()
        assertTrue(store.compareAndSet(state.revision, state.copy(policy = state.policy.copy(dangerousMode = DangerousMode.DISABLED))))
        assertTrue(f.coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
        val current = requireNotNull(f.coordinator.currentExecutionStamp(Authority.ROOT_DIRECT, true))
        assertEquals(before.identity, current.identity)
        assertEquals(before.consentRevision, current.consentRevision)
        assertEquals(before.shellConsentRevision, current.shellConsentRevision)
        assertTrue(current.policyVersion > before.policyVersion)
        val grants = CapabilityGrantRepository(f.db).forAgent("agent", includeRevoked = false)
        val snapshot = f.coordinator.sealNewSnapshot(Authority.ROOT_DIRECT, "after-mode", "agent", grants.mapTo(mutableSetOf()) { it.grantId }) {
            AgentRepository(f.db).createSnapshot("agent", "after-mode")
        }
        val now = Utc.nowIso()
        f.db.execute("INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            listOf("after-mode-conversation", snapshot.id, snapshot.id, "Fixture", now, now))
        val runId = "after-mode-run"
        val runs = RunRepository(f.db)
        RunCoordinator(runs, f.coordinator::admitNewRun).prepare(
            RunRecord(runId, snapshot.id, "after-mode-conversation", createdAt = now), "owner")
        val context = ToolExecutionContext("agent", snapshot.id, "model-context", "after-mode-conversation", runId,
            "config", current.policyVersion, canonicalGrants = grants,
            snapshotGrantBindings = CapabilityGrantRepository(f.db).listSnapshotBindings(snapshot.id))
        val executor = requireNotNull(RootToolExecutorAdmission.create(f.db, f.coordinator, context))
        f.fd.seedText("mode.txt", "still-usable")
        val call = ToolCall("mode-read", "file_read_text",
            """{"workspace_id":"${f.workspace.id}","relative_path":"mode.txt"}""")
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
        f.db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))
        runs.addInvocation(ToolInvocation(call.callId, runId, "model-mode-read", call.name, call.argumentsJson,
            state = "WAITING_APPROVAL", permissionDecision = "PENDING", createdAt = now))
        val result = runBlocking { executor.approve(call.callId) }
        assertTrue(result is ToolResult.Value, result.toString())
        assertTrue((result as ToolResult.Value).json.contains("still-usable"))
    }

    @Test
    fun commonDiscoveryReturnsOnlyLiveRootWorkspaceWithoutLocators() = fixture { f ->
        val context = f.context("run-discovery")
        val executor = RootToolExecutor(RootToolArguments.specs(), RootRuntimeToolBackend(f.db, f.coordinator, context, f.execution))
        val result = runBlocking { executor.invoke(ToolCall("discover", "workspace_list", "{}")) }
        assertTrue(result is ToolResult.Value, result.toString())
        val json = (result as ToolResult.Value).json
        assertTrue(json.contains(f.workspace.id))
        assertTrue(json.contains("display_name"))
        val view = Json.parseToJsonElement(json).jsonObject.getValue("workspaces").jsonArray.single().jsonObject
        assertFalse(view.getValue("atomic_replace").jsonPrimitive.boolean)
        assertTrue("file_apply_patch" in view.getValue("conditional_expected_version_operations").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("file_apply_patch" in view.getValue("expected_version_operations").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(view.getValue("operation_conditions").jsonObject.getValue("file_apply_patch").jsonPrimitive.content.contains("app_private_paths_return_WORKSPACE_VERSION_UNSUPPORTED"))
        assertFalse(json.contains(f.workspace.rootReference))
        assertTrue(runBlocking { executor.invoke(ToolCall("invalid-discovery", "workspace_list", "{\"workspace_id\":\"other\"}")) } is ToolResult.Invalid)
        f.coordinator.detachSession(Authority.ROOT_DIRECT, "discovery_test_disconnect")
        assertTrue(runBlocking { executor.invoke(ToolCall("disconnected-discovery", "workspace_list", "{}")) } is ToolResult.Denied)
    }
    @Test
    fun foregroundSelectionAndOriginalModelInvocationReachTheReceiptedService() = fixture { f ->
        val raw = f.arguments("config.txt", "hello-root")
        val context = f.context("run-write")
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, context, f.execution)
        val executor = RootToolExecutor(RootToolArguments.specs(), backend)
        val call = ToolCall("runtime-write", "file_write_text", raw)
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
        assertNull(f.fd.readText("config.txt"))
        f.recordCall("run-write", "runtime-write", "model-write", call.name, raw)
        val result = runBlocking { executor.approve("runtime-write") }
        assertTrue(result is ToolResult.Value, result.toString())
        assertEquals("hello-root", f.fd.readText("config.txt"))
        val binding = f.db.query("SELECT binding_json FROM root_approval_bindings WHERE invocation_id=?", listOf("runtime-write"))
            .single().string("binding_json")
        assertTrue(binding.contains("\"callId\":\"model-write\""))
        assertEquals(raw, f.db.query("SELECT arguments_json FROM tool_invocations WHERE invocation_id=?", listOf("runtime-write"))
            .single().string("arguments_json"))
        val stored = RunRepository(f.db).invocations("run-write").single()
        assertEquals(stored, RunRepository(f.db).observeRuntimeInvocation(stored.copy(resultJson = (result as ToolResult.Value).json)))
        assertFalse(runBlocking { executor.authorizeReplay(call) }, "Closed receipts cannot authorize cached content")
        assertTrue(f.db.query("SELECT request_id FROM root_dispatch_receipts").isNotEmpty())
    }

    @Test
    fun anIdenticalFileRequestCannotUseAnotherInvocationsApproval() = fixture { f ->
        val raw = f.arguments("exact.txt", "exact")
        val typed = requireNotNull(RootToolArguments.normalize("file_write_text", raw)).typed
        f.recordCall("run-approved", "inv-approved", "model-shared", typed.toolName, raw, "PENDING")
        val grant = f.grants.single { it.capability == typed.requiredCapability }
        assertNotNull(f.coordinator.approveFileInvocation(Authority.ROOT_DIRECT, "run-approved", "inv-approved",
            "model-shared", f.snapshot.id, grant.grantId, typed, sessionIdentity = "conversation"))
        assertNull(f.coordinator.executeFileInvocation(Authority.ROOT_DIRECT, "inv-other", typed, "exact".toByteArray()))
        assertNull(f.fd.readText("exact.txt"))
        val result = f.coordinator.executeFileInvocation(Authority.ROOT_DIRECT, "inv-approved", typed, "exact".toByteArray())
        assertEquals(RootServiceTerminalOutcome.SUCCEEDED, result?.terminalOutcome)
        assertEquals("exact", f.fd.readText("exact.txt"))
    }

    @Test
    fun oldSnapshotAndChangedPendingPayloadNeverGainRootTrust() = fixture { f ->
        val old = f.context("run-old").copy(snapshotId = "snapshot-before-root")
        assertTrue(RootRuntimeToolBackend(f.db, f.coordinator, old, f.execution).exposedTools().isEmpty())
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-tamper"), f.execution)
        val executor = RootToolExecutor(RootToolArguments.specs(), backend)
        val raw = f.arguments("one.txt", "intended")
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(ToolCall("inv-tamper", "file_write_text", raw)) })
        f.recordCall("run-tamper", "inv-tamper", "model-tamper", "file_write_text", f.arguments("two.txt", "changed"))
        assertTrue(runBlocking { executor.approve("inv-tamper") } is ToolResult.Denied)
        assertNull(f.fd.readText("one.txt")); assertNull(f.fd.readText("two.txt"))
        assertTrue(f.db.query("SELECT approval_id FROM root_approval_bindings").isEmpty())
    }

    @Test
    fun cancellationBeforeFirstBackendEntryCannotReviveApprovalOrDispatch() = fixture { f ->
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-precancel"), f.execution)
        val raw = f.arguments("must-not-exist.txt", "cancelled")
        val request = requireNotNull(RootToolArguments.normalize("file_write_text", raw))
        val exposure = backend.exposedTools().single { it.toolName == request.toolName }
        val invocation = RootToolInvocation("inv-precancel", "model-precancel", f.snapshot.id, "agent",
            request.toolName, request.capability, request.workspaceId, exposure.grantId, exposure.pathScope, request)
        runBlocking {
            backend.cancel(invocation.runtimeInvocationId)
            assertTrue(backend.invoke(invocation) is ToolResult.Denied,
                "A cancellation tombstone must precede registration, not just remove a pending entry")
            f.recordCall("run-precancel", invocation.runtimeInvocationId, "model-precancel", request.toolName, raw)
            assertTrue(backend.approve(invocation.runtimeInvocationId) is ToolResult.Denied)
        }
        assertNull(f.fd.readText("must-not-exist.txt"))
        assertTrue(f.db.query("SELECT request_id FROM root_dispatch_receipts").isEmpty())
    }

    @Test
    fun cancelBeforeWirePublicationIsRejectedByRealServiceWithoutFileMutation() = fixture { f ->
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-wire"), f.execution)
        val executor = RootToolExecutor(RootToolArguments.specs(), backend)
        val raw = f.arguments("wire-cancelled.txt", "must-not-write")
        val call = ToolCall("inv-wire", "file_write_text", raw)
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
        f.recordCall("run-wire", call.callId, "model-wire", call.name, raw)
        val reached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        f.beforeSubmit = { reached.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val approval = pool.submit<ToolResult> { runBlocking { executor.approve(call.callId) } }
        try {
            assertTrue(reached.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runBlocking { executor.cancel(call.callId) }
            release.countDown()
            assertTrue(approval.get(5, java.util.concurrent.TimeUnit.SECONDS) is ToolResult.UnknownOutcome)
            assertNull(f.fd.readText("wire-cancelled.txt"), "Cancellation before ACCEPT must prevent the first file mutation")
            val receipt = f.db.query("SELECT outcome FROM root_dispatch_receipts").single()
            assertEquals("REJECTED_BEFORE_ACCEPTED", receipt.string("outcome"))
            assertEquals(0, f.engine.activeSession!!.ledger.acceptedCount())
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test
    fun staleBackendCancellationCannotPoisonCurrentSessionInvocation() = fixture { f ->
        val stale = f.execution.copy(identity = f.execution.identity.copy(identityEpoch = f.execution.identity.identityEpoch + 1))
        val staleBackend = RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-current"), stale)
        assertFalse(runBlocking { staleBackend.cancel("inv-current") }, "Never route an old cancellation by authority alone")
        val executor = RootToolExecutor(RootToolArguments.specs(),
            RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-current"), f.execution))
        val raw = f.arguments("current.txt", "current")
        val call = ToolCall("inv-current", "file_write_text", raw)
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
        f.recordCall("run-current", call.callId, "model-current", call.name, raw)
        assertTrue(runBlocking { executor.approve(call.callId) } is ToolResult.Value)
        assertEquals("current", f.fd.readText("current.txt"))
    }

    @Test
    fun cancellationAfterPendingTakenCannotCreateApprovalOrDispatchReceipt() = fixture { f ->
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-pending"), f.execution)
        val executor = RootToolExecutor(RootToolArguments.specs(), backend)
        val raw = f.arguments("pending.txt", "blocked")
        val call = ToolCall("inv-pending", "file_write_text", raw)
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        backend.afterPendingTaken = { reached.complete(Unit); release.await() }
        runBlocking {
            assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
            f.recordCall("run-pending", call.callId, "model-pending", call.name, raw)
            val approval = kotlinx.coroutines.CoroutineScope(coroutineContext).async { executor.approve(call.callId) }
            try {
                kotlinx.coroutines.withTimeout(5_000) { reached.await() }
                executor.close()
                release.complete(Unit)
                kotlinx.coroutines.withTimeout(5_000) { approval.await() }
                assertTrue(f.db.query("SELECT approval_id FROM root_approval_bindings").isEmpty())
                assertTrue(f.db.query("SELECT request_id FROM root_dispatch_receipts").isEmpty())
                assertNull(f.fd.readText("pending.txt"))
            } finally { release.complete(Unit); approval.cancel(); approval.join() }
        }
    }

    @Test
    fun closeBetweenExecutorRegistrationAndBackendEntryNeverDispatchesInvokeOrApproval() {
        for (approving in listOf(false, true)) fixture { f ->
            val executor = RootToolExecutor(RootToolArguments.specs(),
                RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-entry"), f.execution))
            val raw = f.arguments("entry.txt", "blocked")
            val call = ToolCall("inv-entry", "file_write_text", raw)
            runBlocking {
                if (approving) {
                    assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
                    f.recordCall("run-entry", call.callId, "model-entry", call.name, raw)
                }
                val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
                val release = kotlinx.coroutines.CompletableDeferred<Unit>()
                executor.beforeBackendEntry = { reached.complete(Unit); release.await() }
                val task = async { if (approving) executor.approve(call.callId) else executor.invoke(call) }
                try {
                    kotlinx.coroutines.withTimeout(5_000) { reached.await() }
                    executor.close()
                    release.complete(Unit)
                    assertTrue(kotlinx.coroutines.withTimeout(5_000) { task.await() } is ToolResult.UnknownOutcome)
                    assertTrue(f.db.query("SELECT approval_id FROM root_approval_bindings").isEmpty())
                    assertEquals(0, f.engine.activeSession!!.ledger.acceptedCount())
                    assertNull(f.fd.readText("entry.txt"))
                } finally { release.complete(Unit); task.cancel(); task.join() }
            }
        }
    }

    @Test
    fun unconfirmedRunningCancellationSettlesReceiptBeforeWorkerReturns() = unconfirmedCancellation(false)

    @Test
    fun legacyShellCancellationAlsoSettlesOwnedReceiptWithoutWaitingForWorker() = unconfirmedCancellation(true)

    private fun unconfirmedCancellation(legacy: Boolean) = fixture { f ->
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        f.afterWrite = {
            started.countDown()
            while (release.count > 0) {
                try { check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                catch (_: InterruptedException) { /* Synthetic uninterruptible OS operation. */ }
            }
        }
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-unconfirmed"), f.execution)
        val executor = RootToolExecutor(RootToolArguments.specs(), backend)
        val call = ToolCall("inv-unconfirmed", "file_write_text", f.arguments("uncertain.txt", "payload"))
        assertEquals(ToolResult.NeedsApproval, runBlocking { executor.invoke(call) })
        f.recordCall("run-unconfirmed", call.callId, "model-unconfirmed", call.name, call.argumentsJson)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val work = pool.submit<ToolResult> { runBlocking { executor.approve(call.callId) } }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(if (legacy) f.coordinator.cancellationRoute(f.execution)(call.callId)
                else runBlocking { executor.cancel(call.callId) })
            assertFalse(work.isDone, "Worker must still be blocked when Host records uncertainty")
            val receipt = f.db.query("SELECT stage,outcome,closed FROM root_dispatch_receipts").single()
            assertEquals("TERMINAL", receipt.string("stage"), "Do not wait for a possibly never-arriving response")
            assertEquals("UNKNOWN_OUTCOME", receipt.string("outcome"))
            assertEquals(0L, receipt.long("closed"), "Receipt must remain unclosed while remote worker is still active")
            assertTrue(RootAuthorityRepository(f.db).get(Authority.ROOT_DIRECT).degraded)
            assertFalse(f.coordinator.isAuthorityReadyForDispatch(Authority.ROOT_DIRECT))
            assertNull(f.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true))
            release.countDown()
            assertTrue(work.get(5, java.util.concurrent.TimeUnit.SECONDS) is ToolResult.UnknownOutcome)
            val settledReceipt = f.db.query("SELECT outcome,closed FROM root_dispatch_receipts").single()
            assertEquals("UNKNOWN_OUTCOME", settledReceipt.string("outcome"))
            assertEquals(1L, settledReceipt.long("closed"), "Receipt closes once remote worker and leases drain")
            val recovered = f.coordinator.acknowledgeDegradedRecovery(Authority.ROOT_DIRECT, userConfirmed = true)
            assertNotNull(recovered)
            assertFalse(recovered!!.degraded)
        } finally {
            release.countDown(); pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test
    fun unconfirmedCancellationBeforeWireSubmitNeverSendsEnvelopeOrMutatesFile() = fixture { f ->
        val run = "run-cancel-before-wire-submit"
        val runtimeId = "runtime-cancel-before-wire-submit"
        val raw = f.arguments("never-sent.txt", "must-not-cross-wire")
        val typed = requireNotNull(RootToolArguments.normalize("file_write_text", raw)).typed
        f.recordCall(run, runtimeId, "model-cancel-wire", typed.toolName, raw, "PENDING")
        val grant = f.grants.single { it.capability == typed.requiredCapability }
        assertNotNull(
            f.coordinator.approveFileInvocation(
                Authority.ROOT_DIRECT,
                run,
                runtimeId,
                "model-cancel-wire",
                f.snapshot.id,
                grant.grantId,
                typed,
                sessionIdentity = "conversation",
            ),
        )
        val bridge = f.coordinator.dispatchBridge
        val prepared = requireNotNull(
            bridge.claimApprovedFileDispatch(
                execution = f.execution,
                transport = f.transport,
                rawArgs = typed,
                budget = RootExecutionBudget(),
                invocationId = runtimeId,
            ),
        )
        var wireSubmissions = 0
        f.beforeSubmit = { wireSubmissions += 1 }
        f.coordinator.rootDispatchRepository.cancelUnconfirmedInvocation(f.execution, runtimeId)
        val gate = RootHostControlGate(f.execution.identity)
        val response = bridge.submitAndSettle(
            prepared = prepared,
            hostGate = gate,
            transport = f.transport,
            liveIdentity = f.execution.identity,
            payloadBytes = "must-not-cross-wire".toByteArray(),
            onDegradeRequired = {},
        )
        assertEquals(0, wireSubmissions, "closed/cancelled permit must never submit envelope over transport")
        assertNull(f.fd.readText("never-sent.txt"))
        assertEquals(RootServiceTerminalOutcome.UNKNOWN_OUTCOME, response?.terminalOutcome)
    }

    @Test
    fun backendAndDispatchRepositoryRefuseNewAdmissionsAtCancellationCapacity() = fixture { f ->
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-cancel-capacity"), f.execution)
        val raw = f.arguments("cap.txt", "cap")
        val request = requireNotNull(RootToolArguments.normalize("file_write_text", raw))
        val exposure = backend.exposedTools().single { it.toolName == request.toolName }
        val invocation = RootToolInvocation(
            "runtime-after-cap",
            "model-after-cap",
            f.snapshot.id,
            "agent",
            request.toolName,
            request.capability,
            request.workspaceId,
            exposure.grantId,
            exposure.pathScope,
            request,
        )
        runBlocking {
            for (i in 1..RootControlLedger.MAX_SESSION_REQUESTS) {
                backend.cancel("cancelled-slot-$i")
            }
            assertTrue(
                backend.invoke(invocation) is ToolResult.Denied,
                "backend must fail closed once session cancellation tombstone capacity is saturated",
            )
        }
    }

    @Test
    fun productionRootToolExecutorAdmissionExposesStampedRunToolsAndExecutesEndToEnd() = fixture { f ->
        assertTrue(RootToolExecutorAdmission.PRODUCTION_TOOL_ADMISSION_ENABLED)
        val runs = RunRepository(f.db)
        val stampedRunId = "run-production-admitted"
        RunCoordinator(runs, f.coordinator::admitNewRun).prepare(
            RunRecord(stampedRunId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()), "owner")
        val unstampedRunId = "run-production-unstamped"
        runs.create(RunRecord(unstampedRunId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()))

        val unstampedExecutor = requireNotNull(
            RootToolExecutorAdmission.create(f.db, f.coordinator, f.context(unstampedRunId)),
        )
        assertTrue(unstampedExecutor.specs.isEmpty(), "Unstamped run must not expose Root tools")
        val missingRunExecutor = requireNotNull(
            RootToolExecutorAdmission.create(f.db, f.coordinator, f.context("run-missing")),
        )
        assertTrue(missingRunExecutor.specs.isEmpty(), "Missing run row must not expose Root tools")
        val blankRunExecutor = requireNotNull(
            RootToolExecutorAdmission.create(f.db, f.coordinator, f.context("")),
        )
        assertTrue(blankRunExecutor.specs.isEmpty(), "Blank taskIdentity must not expose Root tools")

        val admittedExecutor = requireNotNull(
            RootToolExecutorAdmission.create(f.db, f.coordinator, f.context(stampedRunId)),
        )
        assertTrue(admittedExecutor.specs.any { it.name == "file_read_text" })
        assertTrue(admittedExecutor.specs.any { it.name == "file_write_text" })
        f.fd.seedText("prod.txt", "production-root-ok")
        val readArgs = buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "prod.txt") }.toString()
        val readCall = ToolCall("prod-read-1", "file_read_text", readArgs)
        assertEquals(ToolResult.NeedsApproval, runBlocking { admittedExecutor.invoke(readCall) })
        f.recordCall(stampedRunId, readCall.callId, "model-prod-read-1", readCall.name, readArgs)
        val readResult = runBlocking { admittedExecutor.approve(readCall.callId) }
        assertTrue(readResult is ToolResult.Value, readResult.toString())
        assertTrue((readResult as ToolResult.Value).json.contains("production-root-ok"))
    }

    @Test
    fun readBatchMintsIndependentChildInvocationsAndReleasesDeferredReceipts() = fixture { f ->
        f.fd.seedText("a.txt", "alpha-content")
        f.fd.seedText("b.txt", "bravo-content")
        val rootExec = RootToolExecutor(RootToolArguments.specs(), RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-batch"), f.execution))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val productivity = runtime.mobileagent.RunProductivityTools(
            core = rootExec,
            scope = scope,
            liveContext = { true },
            reserveCalls = { it },
        )
        val batchArgs = buildJsonObject {
            put("calls", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("call_id", "c1")
                    put("tool", "file_read_text")
                    put("arguments", buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "a.txt") })
                })
                add(buildJsonObject {
                    put("call_id", "c2")
                    put("tool", "file_read_text")
                    put("arguments", buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "b.txt") })
                })
            })
        }.toString()
        val batchCall = ToolCall("batch-root-1", "read_batch", batchArgs)
        assertTrue(runBlocking { productivity.executor.invoke(ToolCall("rchild.job.0.0000000000000000.p", "read_batch", batchArgs)) } is ToolResult.Invalid)
        assertTrue(runBlocking { productivity.executor.invoke(ToolCall("rchild_call.job.0.p", "job_output", "{}")) } is ToolResult.Invalid)
        assertEquals(ToolResult.NeedsApproval, runBlocking { productivity.executor.invoke(batchCall) })
        val parentRuntimeId = requireNotNull(productivity.runtimeInvocationId("batch-root-1"))
        f.recordCall("run-batch", parentRuntimeId, "batch-root-1", "read_batch", batchArgs)
        val settled = runBlocking { productivity.executor.approve("batch-root-1") }
        assertTrue(settled is ToolResult.Value, settled.toString())
        val json = (settled as ToolResult.Value).json
        assertTrue(json.contains("alpha-content") && json.contains("bravo-content"), json)

        // Observe parent read_batch completion: updates parent row to SUCCEEDED and scrubs result_json in Root Run
        val observedParentId = requireNotNull(productivity.runtimeInvocationId("batch-root-1"))
        assertEquals(parentRuntimeId, observedParentId)
        val observedParent = RunRepository(f.db).observeRuntimeInvocation(
            ToolInvocation(
                invocationId = observedParentId,
                runId = "run-batch",
                callId = "batch-root-1",
                name = "read_batch",
                argumentsJson = batchArgs,
                state = "SUCCEEDED",
                resultJson = json,
                permissionDecision = "APPROVED",
                createdAt = Utc.nowIso(),
            ),
        )
        assertEquals("SUCCEEDED", observedParent.state)
        assertEquals(null, observedParent.resultJson)
        assertEquals(null, productivity.runtimeInvocationId("batch-root-1"))

        val childRows = f.db.query(
            "SELECT invocation_id,call_id,name,state FROM tool_invocations WHERE run_id=? AND invocation_id LIKE 'rchild.batch.%' ORDER BY call_id",
            listOf("run-batch"),
        )
        assertEquals(2, childRows.size)
        assertTrue(childRows.all { it.string("state") == "SUCCEEDED" })
        val childIds = childRows.map { it.string("invocation_id") }
        assertEquals(2, childIds.distinct().size)

        val receipts = f.db.query(
            "SELECT invocation_id,stage,closed,disclosure FROM root_dispatch_receipts WHERE run_id=?",
            listOf("run-batch"),
        )
        assertEquals(2, receipts.size)
        assertTrue(receipts.all {
            it.string("stage") == "TERMINAL" && it.long("closed") == 1L && it.string("disclosure") == "RELEASED"
        })

        // Same-run replay succeeds while Root authority is live, then denies after revoke.
        val replayed = runBlocking { productivity.executor.invoke(batchCall) }
        assertEquals(settled, replayed)
        f.coordinator.setUserIntent(Authority.ROOT_DIRECT, false, f.manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
        val afterRevoke = runBlocking { productivity.executor.invoke(batchCall) }
        assertTrue(afterRevoke is ToolResult.Denied, afterRevoke.toString())
    }

    @Test
    fun readBatchWithOnceGrantConsumesOnFirstChildAndRejectsSecondChild() = fixture { f ->
        f.fd.seedText("once1.txt", "first-once-ok")
        f.fd.seedText("once2.txt", "second-once-blocked")
        val now = Utc.nowIso()
        val listGrant = f.grants.first { it.capability == CapabilityId(CapabilityId.FILE_LIST) }
        val baseReadGrant = f.grants.first { it.capability == CapabilityId(CapabilityId.FILE_READ_TEXT) }
        f.db.execute("UPDATE capability_grants SET lifetime='ONCE' WHERE grant_id=?", listOf(baseReadGrant.grantId))
        val onceReadGrant = requireNotNull(CapabilityGrantRepository(f.db).get(baseReadGrant.grantId))
        val onceSnapshot = f.coordinator.sealNewSnapshot(
            Authority.ROOT_DIRECT,
            "snapshot-once",
            "agent",
            setOf(listGrant.grantId, onceReadGrant.grantId),
        ) {
            AgentRepository(f.db).createSnapshot("agent", "snapshot-once")
        }
        val onceContext = ToolExecutionContext(
            "agent",
            onceSnapshot.id,
            "model-context",
            "conversation",
            "run-once-batch",
            "config",
            f.execution.policyVersion,
            canonicalGrants = listOf(listGrant, onceReadGrant),
            snapshotGrantBindings = CapabilityGrantRepository(f.db).listSnapshotBindings(onceSnapshot.id),
        )
        val rootExec = RootToolExecutor(RootToolArguments.specs(), RootRuntimeToolBackend(f.db, f.coordinator, onceContext, f.execution))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val productivity = runtime.mobileagent.RunProductivityTools(
            core = rootExec,
            scope = scope,
            liveContext = { true },
            reserveCalls = { it },
        )
        val batchArgs = buildJsonObject {
            put("calls", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("call_id", "o1")
                    put("tool", "file_read_text")
                    put("arguments", buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "once1.txt") })
                })
                add(buildJsonObject {
                    put("call_id", "o2")
                    put("tool", "file_read_text")
                    put("arguments", buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "once2.txt") })
                })
            })
        }.toString()
        val batchCall = ToolCall("batch-once-1", "read_batch", batchArgs)
        assertEquals(ToolResult.NeedsApproval, runBlocking { productivity.executor.invoke(batchCall) })
        val parentRuntimeId = requireNotNull(productivity.runtimeInvocationId("batch-once-1"))
        val runs = RunRepository(f.db)
        RunCoordinator(runs, f.coordinator::admitNewRun).prepare(
            RunRecord("run-once-batch", onceSnapshot.id, "conversation", createdAt = now),
            "owner",
        )
        f.db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, "run-once-batch"))
        runs.addInvocation(ToolInvocation(parentRuntimeId, "run-once-batch", "batch-once-1", "read_batch", batchArgs, state = "WAITING_APPROVAL", permissionDecision = "PENDING", createdAt = now))

        val settled = runBlocking { productivity.executor.approve("batch-once-1") }
        assertTrue(settled is ToolResult.Value, settled.toString())
        val envelope = Json.parseToJsonElement((settled as ToolResult.Value).json) as kotlinx.serialization.json.JsonObject
        val results = envelope["results"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, results.size)
        val statuses = results.map { ((it as kotlinx.serialization.json.JsonObject)["status"] as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals(listOf("VALUE", "DENIED"), statuses.sorted().reversed(), settled.json)
        assertTrue(settled.json.contains("first-once-ok") || settled.json.contains("second-once-blocked"))
        assertFalse(settled.json.contains("first-once-ok") && settled.json.contains("second-once-blocked"))
        val receipts = f.db.query("SELECT invocation_id,disclosure FROM root_dispatch_receipts WHERE run_id=?", listOf("run-once-batch"))
        assertEquals(1, receipts.size)
        assertEquals("RELEASED", receipts.single().string("disclosure"))
    }

    @Test
    fun jobStartAndJobOutputFollowTwoStageDeferredDisclosureProtocol() = fixture { f ->
        f.fd.seedText("job.txt", "0123456789abcdef-job-payload")
        val rootExec = RootToolExecutor(RootToolArguments.specs(), RootRuntimeToolBackend(f.db, f.coordinator, f.context("run-job"), f.execution))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val productivity = runtime.mobileagent.RunProductivityTools(
            core = rootExec,
            scope = scope,
            liveContext = { true },
            reserveCalls = { it },
        )
        val jobStartArgs = buildJsonObject {
            put("tool", "file_read_text")
            put("arguments", buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "job.txt") })
        }.toString()
        val startCall = ToolCall("job-start-1", "job_start", jobStartArgs)
        assertEquals(ToolResult.NeedsApproval, runBlocking { productivity.executor.invoke(startCall) })
        val parentRuntimeId = requireNotNull(productivity.runtimeInvocationId("job-start-1"))
        f.recordCall("run-job", parentRuntimeId, "job-start-1", "job_start", jobStartArgs)

        val started = runBlocking { productivity.executor.approve("job-start-1") }
        assertTrue(started is ToolResult.Value, started.toString())
        val startEnvelope = Json.parseToJsonElement((started as ToolResult.Value).json) as kotlinx.serialization.json.JsonObject
        val jobId = (startEnvelope["job_id"] as kotlinx.serialization.json.JsonPrimitive).content
        awaitJobCompleted(productivity, "poll-1")

        // Stage 1: before job_output is called, the receipt is PENDING and invocation state is ROOT_DISPATCH_RESULT_PENDING
        val pendingReceipt = f.db.query(
            "SELECT stage,closed,disclosure FROM root_dispatch_receipts WHERE run_id=?",
            listOf("run-job"),
        ).single()
        assertEquals("TERMINAL", pendingReceipt.string("stage"))
        assertEquals(0L, pendingReceipt.long("closed"))
        assertEquals("PENDING", pendingReceipt.string("disclosure"))
        val pendingChild = f.db.query(
            "SELECT state FROM tool_invocations WHERE run_id=? AND invocation_id LIKE 'rchild.job.%'",
            listOf("run-job"),
        ).single()
        assertEquals("ROOT_DISPATCH_RESULT_PENDING", pendingChild.string("state"))

        // Stage 2: first job_output page transitions receipt to RELEASED and invocation to SUCCEEDED
        val outCall1 = ToolCall("job-out-1", "job_output", buildJsonObject {
            put("job_id", jobId)
            put("offset_bytes", 0)
            put("max_bytes", 16)
        }.toString())
        val page1 = runBlocking { productivity.executor.invoke(outCall1) }
        assertTrue(page1 is ToolResult.Value, page1.toString())
        val releasedReceipt = f.db.query(
            "SELECT stage,closed,disclosure FROM root_dispatch_receipts WHERE run_id=?",
            listOf("run-job"),
        ).single()
        assertEquals(1L, releasedReceipt.long("closed"))
        assertEquals("RELEASED", releasedReceipt.string("disclosure"))
        val succeededChild = f.db.query(
            "SELECT state FROM tool_invocations WHERE run_id=? AND invocation_id LIKE 'rchild.job.%'",
            listOf("run-job"),
        ).single()
        assertEquals("SUCCEEDED", succeededChild.string("state"))

        // Second page continues to work while authority is live, and observeRuntimeInvocation scrubs job_output result_json
        val outCall2 = ToolCall("job-out-2", "job_output", buildJsonObject {
            put("job_id", jobId)
            put("offset_bytes", 16)
            put("max_bytes", 512)
        }.toString())
        val page2 = runBlocking { productivity.executor.invoke(outCall2) }
        assertTrue(page2 is ToolResult.Value && (page2 as ToolResult.Value).json.contains("job-payload"), page2.toString())
        val out2RuntimeId = requireNotNull(productivity.runtimeInvocationId("job-out-2"))
        val observedJobOut = RunRepository(f.db).observeRuntimeInvocation(
            ToolInvocation(
                invocationId = out2RuntimeId,
                runId = "run-job",
                callId = "job-out-2",
                name = "job_output",
                argumentsJson = outCall2.argumentsJson,
                state = "SUCCEEDED",
                resultJson = (page2 as ToolResult.Value).json,
                permissionDecision = "NOT_REQUESTED",
                createdAt = Utc.nowIso(),
            ),
        )
        assertEquals("SUCCEEDED", observedJobOut.state)
        assertEquals(null, observedJobOut.resultJson)

        // Start a second job and close before job_output -> transitions PENDING to WITHHELD / COMPLETED_WITHHELD
        val startCall2 = ToolCall("job-start-2", "job_start", jobStartArgs)
        assertEquals(ToolResult.NeedsApproval, runBlocking { productivity.executor.invoke(startCall2) })
        val parentRuntimeId2 = requireNotNull(productivity.runtimeInvocationId("job-start-2"))
        f.db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, "run-job"))
        RunRepository(f.db).addInvocation(ToolInvocation(parentRuntimeId2, "run-job", "job-start-2", "job_start", jobStartArgs, state = "WAITING_APPROVAL", permissionDecision = "PENDING", createdAt = Utc.nowIso()))
        val started2 = runBlocking { productivity.executor.approve("job-start-2") }
        assertTrue(started2 is ToolResult.Value, started2.toString())
        awaitJobCompleted(productivity, "poll-2")
        runBlocking { productivity.executor.close() }
        val withheldReceipt = f.db.query(
            "SELECT disclosure,closed FROM root_dispatch_receipts WHERE run_id=? AND invocation_id != ?",
            listOf("run-job", f.db.query("SELECT invocation_id FROM root_dispatch_receipts WHERE run_id=? AND disclosure='RELEASED'", listOf("run-job")).single().string("invocation_id")),
        ).single()
        assertEquals(1L, withheldReceipt.long("closed"))
        assertEquals("WITHHELD", withheldReceipt.string("disclosure"))
        val withheldChild = f.db.query(
            "SELECT state FROM tool_invocations WHERE run_id=? AND state='COMPLETED_WITHHELD'",
            listOf("run-job"),
        ).single()
        assertEquals("COMPLETED_WITHHELD", withheldChild.string("state"))
    }

    private fun awaitJobCompleted(productivity: runtime.mobileagent.RunProductivityTools, prefix: String) {
        repeat(100) { idx ->
            val listRes = runBlocking { productivity.executor.invoke(ToolCall("$prefix-$idx", "job_list", "{}")) }
            if (listRes is ToolResult.Value && !listRes.json.contains("\"RUNNING\"") && !listRes.json.contains("\"PENDING_APPROVAL\"")) {
                return
            }
            Thread.sleep(10)
        }
    }

    /** Shared with focused regression tests in this module; production code never sees it. */
    internal fun fixture(shellOutcome: RootShellRunOutcome? = null, test: (Fixture) -> Unit) {
        val dir = Files.createTempDirectory("root-production-seam")
        InMemoryRootSqlConnection().use { db ->
            val f = Fixture(db, dir.toFile(), shellOutcome)
            try { test(f) } finally { f.coordinator.close() }
        }
        dir.toFile().deleteRecursively()
    }

    internal class Fixture(val db: SqlConnection, dir: java.io.File, shellOutcome: RootShellRunOutcome?) {
        val signing = "a".repeat(64)
        val boundary = RootControlPlaneBoundary("/data/user/0/runtime.mobileagent", node(9001),
            "/data/user_de/0/runtime.mobileagent", node(9002))
        val fd = InMemoryServiceFdOps("/data/local/root-workspace")
        var afterWrite: () -> Unit = {}
        var digestReads = 0
        var rangeReadBytes = 0L
        var reportedFileSize: Long? = null
        private val workerFd = object : PosixFdOperations by fd {
            override fun fstatSizeBytes(fd: PosixRetainedFd): Long? =
                this@Fixture.fd.fstatSizeBytes(fd)?.let { size ->
                    if (size > 0L) reportedFileSize ?: size else size
                }
            override fun fstatContentDigest(fd: PosixRetainedFd): String? {
                digestReads++
                return this@Fixture.fd.fstatContentDigest(fd)
            }
            override fun readFdRange(fd: PosixRetainedFd, offsetBytes: Long, maxBytes: Int): ByteArray =
                this@Fixture.fd.readFdRange(fd, offsetBytes, maxBytes).also { rangeReadBytes += it.size }
            override fun writeAllAndFsync(fd: PosixRetainedFd, bytes: ByteArray) {
                this@Fixture.fd.writeAllAndFsync(fd, bytes)
                afterWrite()
            }
        }
        var shellCalls = 0
        val engine = RootPrivilegedServiceEngine(
            shellRunner = object : RootShellCommandRunner {
                override fun runCommand(authority: Authority, request: runtime.mobileagent.skills.tooling.ShellExecRequest): RootShellRunOutcome {
                    shellCalls++
                    return requireNotNull(shellOutcome)
                }
                override fun cancel(requestId: String) = true
            },
            selectionPathResolver = { java.nio.file.Paths.get(it) },
            mountPolicyProvider = { b -> runtime.mobileagent.domain.RootTrustedMountRoots.directoryTree(
                setOf(b.credentialEncryptedIdentity.mountId, b.deviceProtectedIdentity.mountId)) },
managerAuthorizationOverride = { RootManagerAuthorization.GRANTED }, fdOps = workerFd, serviceUidProvider = { 0 },
            controlPlaneOverride = boundary).pinTestHost(boundary, signing)
        var beforeSubmit: () -> Unit = {}
        val directTransport = EngineRootChannelTransport(engine, 10148)
        val transport = object : RootChannelTransport by directTransport {
            override fun submitEnvelope(controlSequence: Long, rawEnvelopeJson: String): RootServiceExecuteResponse? {
                beforeSubmit()
                return directTransport.submitEnvelope(controlSequence, rawEnvelopeJson)
            }
        }
        val manager = AuthorityManager(InMemoryAuthorityStateStore())
        val coordinator: RootRuntimeCoordinator
        val execution: RootExecutionStamp
        val workspace: Workspace
        val grants: List<CapabilityGrant>
        val snapshot: AgentSnapshot

        init {
            Migrations.apply(db)
            AuthorityPolicyRepository(db).updatePolicy(0, Authority.ROOT_DIRECT, DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
            val profiles = ProfileRepository(db)
            profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE, "https://example.invalid/v1", revision = 1))
            profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", emptySet(), contextLimit = 16384, outputLimit = 512, revision = 1))
            AgentRepository(db).saveWithPrompt(AgentProfile("agent", "Root Agent", "pending", "model", revision = 0), "Synthetic prompt")
            AgentRepository(db).createSnapshot("agent", "snapshot-before-root")
            coordinator = RootRuntimeCoordinator(FakeTestContext(dir), db, fdOps = fd,
                sessionConnector = object : RootSessionConnector {
                    override fun tryAcquireSession(authority: Authority, identityEpoch: Long, appInstanceId: String,
                        shizukuState: runtime.mobileagent.shizuku.ShizukuAuthorityState?): RootAcquiredSession? {
                        val request = RootServiceHandshakeRequest(authority, identityEpoch, 10148, 0, appInstanceId,
                            "runtime.mobileagent", signing, signing, boundary.credentialEncryptedDataDir, boundary.deviceProtectedDataDir)
                        val identity = transport.handshake(request)?.identity ?: return null
                        return RootAcquiredSession(identity, RootCallerObservation(10148, 0, "runtime.mobileagent", 0, signing), transport, boundary)
                    }
                }, controlPlaneBoundaryOverride = boundary, locatorCipher = TestRootLocatorCipher())
            coordinator.setUserIntent(Authority.ROOT_DIRECT, true, manager, DangerousMode.ENABLED_CONFIRM_HIGH_RISK, null)
            if (shellOutcome != null) coordinator.setRootShellConsent(Authority.ROOT_DIRECT, true, true, manager)
            execution = requireNotNull(coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))
            val selected = RootWorkspaceUiController(db, coordinator).attach(Authority.ROOT_DIRECT,
                "/data/local/root-workspace", true, true, shellOutcome != null, WorkspacePickerTarget("agent"))
            assertTrue(selected is WorkspaceAccessResult.Success, selected.toString())
            workspace = requireNotNull(WorkspaceRepository(db).get((selected as WorkspaceAccessResult.Success).workspace.workspaceId))
            grants = CapabilityGrantRepository(db).forAgent("agent", includeRevoked = false)
            snapshot = coordinator.sealNewSnapshot(Authority.ROOT_DIRECT, "snapshot-root", "agent", grants.mapTo(mutableSetOf()) { it.grantId }) {
                AgentRepository(db).createSnapshot("agent", "snapshot-root")
            }
            val now = Utc.nowIso()
            db.execute("INSERT INTO conversations(id,snapshot_id,agent_snapshot_id,title,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                listOf("conversation", snapshot.id, snapshot.id, "Fixture", now, now))
        }

        fun context(runId: String) = ToolExecutionContext("agent", snapshot.id, "model-context", "conversation", runId,
            "config", execution.policyVersion, canonicalGrants = grants,
            snapshotGrantBindings = CapabilityGrantRepository(db).listSnapshotBindings(snapshot.id))

        fun recordCall(run: String, invocation: String, model: String, tool: String, raw: String, state: String = "WAITING_APPROVAL") {
            val runs = RunRepository(db)
            RunCoordinator(runs, coordinator::admitNewRun).prepare(RunRecord(run, snapshot.id, "conversation", createdAt = Utc.nowIso()), "owner")
            db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, run))
            runs.addInvocation(ToolInvocation(invocation, run, model, tool, raw, state = state, permissionDecision = "PENDING", createdAt = Utc.nowIso()))
        }

        fun arguments(path: String, text: String) = buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", path); put("text", text)
        }.toString()

        companion object {
            fun node(inode: Long) = RootFdNodeIdentity(1, inode, 77, true, false, false, 2, 448, 10148, 10148, "label")
        }
    }
}
