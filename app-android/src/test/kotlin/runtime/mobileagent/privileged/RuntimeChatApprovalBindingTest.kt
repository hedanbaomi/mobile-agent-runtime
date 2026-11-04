// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.nio.file.Files
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.RunCoordinator
import runtime.mobileagent.WorkspacePickerTarget
import runtime.mobileagent.agent.*
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.*
import runtime.mobileagent.integration.RootWorkspaceUiController
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.provider.*
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.tooling.AuthorityManager
import runtime.mobileagent.tooling.InMemoryAuthorityStateStore
import runtime.mobileagent.tooling.ToolExecutionContext

/** Runtime and real local Root/SQLite chain, with synthetic OS facts. Not Android UI/device evidence. */
class RuntimeChatApprovalBindingTest {
    @Test
    fun genericSensitiveArgumentsAreRejectedBeforeAnyInvocationWrite() = fixture { f ->
        val runs = RunRepository(f.db)
        val runId = "sensitive-approval"
        runs.create(RunRecord(runId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()))
        // Deliberately synthetic, never an actual credential.
        val text = "sk-" + "synthetic-noncredential-fixture"
        assertThrows(IllegalArgumentException::class.java) {
            runtime.mobileagent.persistChatApprovalInvocation(runs, runId, "sensitive-invocation",
                ToolCall("model", "file_write_text", f.arguments("never.txt", text)))
        }
        assertTrue(runs.invocations(runId).isEmpty())
        assertTrue(f.db.query("SELECT request_id FROM root_dispatch_receipts").isEmpty())
        assertNull(f.fd.readText("never.txt"))
    }

    @Test
    fun knownProviderSecretIsRejectedEvenWhenJsonEscaped() = fixture { f ->
        val runs = RunRepository(f.db)
        val runId = "known-secret-approval"
        runs.create(RunRecord(runId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()))
        val sample = "synthetic-active-provider-value"
        val raw = f.arguments("never.txt", sample).replace("synthetic", "\\u0073ynthetic")
        assertThrows(IllegalArgumentException::class.java) {
            runtime.mobileagent.persistChatApprovalInvocation(runs, runId, "known-invocation",
                ToolCall("model", "file_write_text", raw), listOf(sample))
        }
        assertTrue(runs.invocations(runId).isEmpty())
        assertTrue(f.db.query("SELECT request_id FROM root_dispatch_receipts").isEmpty())
    }

    @Test
    fun escapedSensitiveFieldNameIsRejectedBeforePersistence() = fixture { f ->
        val runs = RunRepository(f.db)
        val runId = "escaped-field-approval"
        runs.create(RunRecord(runId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()))
        val raw = """{"headers":{"\u0061pi_key":"synthetic-noncredential-value"}}"""
        assertThrows(IllegalArgumentException::class.java) {
            runtime.mobileagent.persistChatApprovalInvocation(runs, runId, "escaped-field-invocation",
                ToolCall("model", "http_request", raw))
        }
        assertTrue(runs.invocations(runId).isEmpty())
    }

    @Test
    fun ordinaryApprovalPreservesOriginalInvocationBytes() =
        runtimeApprovalScenario(tamper = "none")

    @Test
    fun changedDurableArgumentsStillDenyTheOriginalRuntimeApproval() =
        runtimeApprovalScenario(tamper = "arguments")

    @Test
    fun anotherInvocationCannotReceiveTheOriginalRuntimeApproval() =
        runtimeApprovalScenario(tamper = "identity")

    @Test
    fun missingRuntimeIdentityCannotCreateAnApprovalRow() = fixture { f ->
        val runs = RunRepository(f.db)
        assertThrows(IllegalArgumentException::class.java) {
            runtime.mobileagent.persistChatApprovalInvocation(runs, "unadmitted-run", null,
                ToolCall("model", "file_write_text", f.arguments("never.txt", "synthetic")))
        }
        assertTrue(f.db.query("SELECT invocation_id FROM tool_invocations").isEmpty())
        assertTrue(f.db.query("SELECT request_id FROM root_dispatch_receipts").isEmpty())
    }

    @Test
    fun reusedModelCallIdAcrossRoundsPreservesDistinctRuntimeInvocationIdAndApproval() = fixture { f ->
        val runId = "run-reused-model-call-id"
        val duplicateModelId = "dup-call-1"
        val raw1 = f.arguments("round1.txt", "first round text")
        val raw2 = f.arguments("round2.txt", "second round text")
        val runs = RunRepository(f.db)
        RunCoordinator(runs, f.coordinator::admitNewRun).prepare(
            RunRecord(runId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()), "owner")
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context(runId), f.execution)
        val root = RootToolExecutor(RootToolArguments.specs(), backend)
        var activeRuntimeId: String? = null
        val executor = object : ToolExecutor {
            override val specs = root.specs
            override suspend fun invoke(call: ToolCall): ToolResult {
                val invocation = runtime.mobileagent.skills.tooling.ToolInvocation.fromRuntime(
                    callId = call.callId,
                    snapshotId = f.snapshot.id,
                    agentId = "agent",
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                )
                activeRuntimeId = invocation.requestId
                root.invoke(invocation)
                return ToolResult.NeedsApproval
            }
            override suspend fun approve(callId: String) = root.approve(callId)
            override suspend fun reject(callId: String) = root.reject(callId)
        }
        val firstRuntimeId = runBlocking {
            assertEquals(ToolResult.NeedsApproval, executor.invoke(ToolCall(duplicateModelId, "file_write_text", raw1)))
            val recordedFirst = activeRuntimeId
            assertTrue(executor.reject(duplicateModelId) is ToolResult.Denied)
            recordedFirst
        }
        var round = 0
        val adapter = object : ModelAdapter {
            override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
            override fun stream(request: ModelRequest, secret: CharArray) = flow {
                if (round++ == 0) emit(ModelEvent.ToolCallDelta(duplicateModelId, "file_write_text", raw2))
                else emit(ModelEvent.TextDelta("done"))
                emit(ModelEvent.Completed)
            }
        }
        val approvedRuntimeIds = mutableListOf<String?>()
        val resultStatuses = mutableListOf<String>()
        runBlocking {
            AgentRuntime(adapter, executor = executor, onApprove = { call ->
                val runtimeId = activeRuntimeId
                approvedRuntimeIds += runtimeId
                runtime.mobileagent.persistChatApprovalInvocation(runs, runId, runtimeId, call)
                true
            }).run(AgentRuntimeRequest(
                AgentRun(runId, f.snapshot.id, "conversation"),
                EffectivePrompt("Root fixture", "", emptyList(), emptyList(), emptyList(), "write second file"),
                "model", "synthetic-secret".toCharArray(), true,
            )).collect { event ->
                if (event is RuntimeEvent.ToolApprovalRequested) {
                    f.db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))
                }
                if (event is RuntimeEvent.ToolResultProduced) {
                    resultStatuses += event.status
                }
            }
        }
        assertEquals(listOf("VALUE"), resultStatuses)
        assertNotEquals(firstRuntimeId, approvedRuntimeIds.single())
        assertNull(f.fd.readText("round1.txt"))
        assertEquals("second round text", f.fd.readText("round2.txt"))
    }

    @Test
    fun reusedModelCallIdFollowedByUndispatchedInvalidCallPreservesPriorCompletedInvocation() = fixture { f ->
        val runId = "run-reused-model-call-invalid-third"
        val duplicateModelId = "dup-call-x"
        val runs = RunRepository(f.db)
        RunCoordinator(runs).prepare(
            RunRecord(runId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()), "owner")
        f.fd.seedText("a.txt", "alpha")
        f.fd.seedText("b.txt", "beta")
        val argA = buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "a.txt") }.toString()
        val argB = buildJsonObject { put("workspace_id", f.workspace.id); put("relative_path", "b.txt") }.toString()
        val argCInvalid = buildJsonObject { put("workspace_id", f.workspace.id) }.toString()
        val typedReadSpec = RootToolArguments.specsFor(listOf("file_read_text")).single()
        val readSpec = runtime.mobileagent.skills.ToolSpec(
            name = typedReadSpec.name,
            description = typedReadSpec.description,
            parametersJson = typedReadSpec.inputSchema,
            capability = typedReadSpec.capability?.value.orEmpty(),
            sideEffect = typedReadSpec.sideEffect,
        )
        val nonRootExecutor = object : ToolExecutor {
            override val specs = listOf(readSpec)
            override suspend fun invoke(call: ToolCall): ToolResult {
                val path = Json.parseToJsonElement(call.argumentsJson).jsonObject["relative_path"]!!.jsonPrimitive.content
                return ToolResult.Value(buildJsonObject { put("text", f.fd.readText(path) ?: "") }.toString())
            }
            override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("unused")
        }
        val agentRun = AgentRun(runId, f.snapshot.id, "conversation")
        val runTools = runtime.mobileagent.RunTools(
            snapshot = f.snapshot,
            run = agentRun,
            runExecutor = nonRootExecutor,
        )
        var round = 0
        val adapter = object : ModelAdapter {
            override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
            override fun stream(request: ModelRequest, secret: CharArray) = flow {
                when (round++) {
                    0 -> emit(ModelEvent.ToolCallDelta(duplicateModelId, "file_read_text", argA))
                    1 -> emit(ModelEvent.ToolCallDelta(duplicateModelId, "file_read_text", argB))
                    2 -> emit(ModelEvent.ToolCallDelta(duplicateModelId, "file_read_text", argCInvalid))
                    else -> emit(ModelEvent.TextDelta("done"))
                }
                emit(ModelEvent.Completed)
            }
        }
        val invocations = linkedMapOf<String, ToolInvocation>()
        val observed = linkedMapOf<String, String>()
        val resolvedIds = mutableListOf<String>()
        runBlocking {
            try {
                AgentRuntime(adapter, executor = runTools.executor).run(AgentRuntimeRequest(
                    agentRun,
                    EffectivePrompt("Root fixture", "", emptyList(), emptyList(), emptyList(), "read files"),
                    "model", "synthetic-secret".toCharArray(), true,
                )).collect { event ->
                    when (event) {
                        is RuntimeEvent.RequestPrepared -> observed.clear()
                        is RuntimeEvent.ToolCallObserved -> observed[event.callId] = event.argumentsJson
                        is RuntimeEvent.ToolResultProduced -> {
                            val runtimeInvocationId = runTools.runtimeInvocationId(event.callId)
                                ?: runtime.mobileagent.skills.tooling.InternalRequestIds.new()
                            resolvedIds += runtimeInvocationId
                            val invocation = (invocations[runtimeInvocationId] ?: ToolInvocation(
                                runtimeInvocationId, runId, event.callId, event.name,
                                observed[event.callId] ?: "{}", createdAt = Utc.nowIso(),
                            )).copy(
                                state = if (event.status == "VALUE") "SUCCEEDED" else "FAILED",
                                resultJson = event.resultJson,
                                updatedAt = Utc.nowIso(),
                            )
                            runs.observeRuntimeInvocation(invocation)
                            invocations[runtimeInvocationId] = invocation
                        }
                        else -> Unit
                    }
                }
            } finally {
                runTools.executor.close()
            }
        }
        assertEquals(3, resolvedIds.size)
        assertEquals(3, resolvedIds.toSet().size, "Undispatched invalid call C must not reuse A or B runtimeInvocationId")
        val storedById = runs.invocations(runId).associateBy { it.invocationId }
        assertEquals(3, storedById.size)
        assertEquals("SUCCEEDED", storedById.getValue(resolvedIds[0]).state)
        assertEquals("SUCCEEDED", storedById.getValue(resolvedIds[1]).state)
        assertTrue(storedById.getValue(resolvedIds[1]).resultJson.orEmpty().contains("beta"))
        assertEquals("FAILED", storedById.getValue(resolvedIds[2]).state)
    }

    private fun runtimeApprovalScenario(tamper: String) = fixture { f ->
        // Ordinary content remains byte-exact; sensitive arguments are rejected, not rewritten.
        val activeSample = "synthetic-active-provider-value"
        val text = "ordinary fixture text"
        val raw = f.arguments("binding.txt", text)
        val runId = "runtime-chat-binding"
        val runtimeId = "runtime-invocation-binding"
        val modelId = "model-call-binding"
        val runs = RunRepository(f.db)
        RunCoordinator(runs, f.coordinator::admitNewRun).prepare(
            RunRecord(runId, f.snapshot.id, "conversation", createdAt = Utc.nowIso()), "owner")
        val backend = RootRuntimeToolBackend(f.db, f.coordinator, f.context(runId), f.execution)
        val root = RootToolExecutor(RootToolArguments.specs(), backend)
        // The Android RunTools routing shell is replaced only to provide its opaque invocation ID.
        val executor = object : ToolExecutor {
            override val specs = root.specs
            override suspend fun invoke(call: ToolCall) = root.invoke(call.copy(callId = runtimeId))
            override suspend fun approve(callId: String) = root.approve(runtimeId)
        }
        var round = 0
        val adapter = object : ModelAdapter {
            override suspend fun probe(profile: ModelProfile): CapabilityReport = error("unused")
            override suspend fun embed(request: EmbeddingRequest, secret: CharArray): EmbeddingBatch = error("unused")
            override fun stream(request: ModelRequest, secret: CharArray) = flow {
                if (round++ == 0) emit(ModelEvent.ToolCallDelta(modelId, "file_write_text", raw))
                else emit(ModelEvent.TextDelta("done"))
                emit(ModelEvent.Completed)
            }
        }
        val events = mutableListOf<RuntimeEvent>()
        runBlocking {
            AgentRuntime(adapter, executor = executor, onApprove = { call ->
                runtime.mobileagent.persistChatApprovalInvocation(runs, runId,
                    if (tamper == "identity") "other-invocation" else runtimeId,
                    if (tamper == "arguments") call.copy(argumentsJson = f.arguments("binding.txt", "changed")) else call)
                true
            }).run(AgentRuntimeRequest(
                AgentRun(runId, f.snapshot.id, "conversation"),
                EffectivePrompt("Root fixture", "", emptyList(), emptyList(), emptyList(), "write fixture"),
                "model", activeSample.toCharArray(), true,
            )).collect { event ->
                events += event
                if (event is RuntimeEvent.ToolApprovalRequested) {
                    f.db.execute("UPDATE runs SET state=? WHERE run_id=?", listOf(RunStatus.WAITING_TOOL_APPROVAL.name, runId))
                }
            }
        }
        val approval = events.filterIsInstance<RuntimeEvent.ToolApprovalRequested>().single()
        assertFalse(approval.toString().contains(activeSample))
        assertEquals(raw, approval.argumentsJson)
        val modelApproval = events.filterIsInstance<RuntimeEvent.ModelEvent>()
            .map { it.event }.filterIsInstance<ModelEvent.ToolApprovalRequired>().single()
        assertFalse(modelApproval.toString().contains(activeSample))
        if (tamper == "none") {
            assertEquals("VALUE", events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single().status)
            assertEquals(raw, runs.invocations(runId).single().argumentsJson)
            assertEquals(text, f.fd.readText("binding.txt"))
            assertEquals(1, f.db.query("SELECT request_id FROM root_dispatch_receipts").size)
        } else {
            assertEquals("DENIED", events.filterIsInstance<RuntimeEvent.ToolResultProduced>().single().status)
            assertNull(f.fd.readText("binding.txt"))
            assertTrue(f.db.query("SELECT request_id FROM root_dispatch_receipts").isEmpty())
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val dir = Files.createTempDirectory("runtime-chat-approval-binding")
        try {
            InMemoryRootSqlConnection().use { db ->
                val f = Fixture(db, dir.toFile())
                try { test(f) } finally { f.coordinator.close() }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    private class Fixture(val db: SqlConnection, dir: java.io.File) {
        val signing = "a".repeat(64)
        val boundary = RootControlPlaneBoundary("/data/user/0/runtime.mobileagent", node(9001),
            "/data/user_de/0/runtime.mobileagent", node(9002))
        val fd = InMemoryServiceFdOps("/data/local/root-workspace")
        val engine = RootPrivilegedServiceEngine(
            selectionPathResolver = { java.nio.file.Paths.get(it) },
            mountPolicyProvider = { b -> runtime.mobileagent.domain.RootTrustedMountRoots.directoryTree(
                setOf(b.credentialEncryptedIdentity.mountId, b.deviceProtectedIdentity.mountId)) },
managerAuthorizationOverride = { RootManagerAuthorization.GRANTED }, fdOps = fd, serviceUidProvider = { 0 },
            controlPlaneOverride = boundary).pinTestHost(boundary, signing)
        val transport = EngineRootChannelTransport(engine, 10148)
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
            profiles.createProvider(ProviderProfile("provider", "Fixture", ApiFormat.OPENAI_COMPATIBLE,
                "https://example.invalid/v1", revision = 1))
            profiles.createModel(ModelProfile("model", "provider", ModelRole.CHAT, "synthetic", emptySet(),
                contextLimit = 16384, outputLimit = 512, revision = 1))
            AgentRepository(db).saveWithPrompt(AgentProfile("agent", "Root Agent", "pending", "model", revision = 0), "Synthetic prompt")
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
            execution = requireNotNull(coordinator.currentExecutionStamp(Authority.ROOT_DIRECT))
            val selected = RootWorkspaceUiController(db, coordinator).attach(Authority.ROOT_DIRECT,
                "/data/local/root-workspace", true, true, false, WorkspacePickerTarget("agent"))
            assertTrue(selected is WorkspaceAccessResult.Success)
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

        fun arguments(path: String, text: String) = buildJsonObject {
            put("workspace_id", workspace.id); put("relative_path", path); put("text", text)
        }.toString()

        companion object {
            fun node(inode: Long) = RootFdNodeIdentity(1, inode, 77, true, false, false, 2, 448, 10148, 10148, "label")
        }
    }
}
