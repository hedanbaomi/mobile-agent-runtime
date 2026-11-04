// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.privileged.RootToolArguments
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolSpec
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceResult

class ToolExecutorFactoryRootTest {
    @Test
    fun rootReplacesShellAndOwnsCancellation() = runBlocking {
        val shell = shellExecutor()
        assertTrue(shell.specs.any { it.name == "shell_exec" })
        val root = FakeRootToolExecutor()
        val factory = ToolExecutorFactory(shell = shell, root = root)

        assertEquals(listOf("root_file_stat"), factory.toolingSpecs.map { it.name })
        assertEquals(mapOf("root" to 1), factory.exposureSummary.ownerToolCounts)
        assertTrue(factory.cancel("runtime-cancel"))
        assertEquals(listOf("runtime-cancel"), root.cancels)
    }

    @Test
    fun shellRemainsTheOnlyProviderWithoutRoot() = runBlocking {
        val shell = shellExecutor()
        val factory = ToolExecutorFactory(shell = shell)

        assertEquals(listOf("shell_exec"), factory.toolingSpecs.map { it.name })
        assertEquals(mapOf("shell" to 1), factory.exposureSummary.ownerToolCounts)
        assertFalse(factory.cancel("unknown"), "Only the shell owner can cancel an active request")
    }

    @Test
    fun webOnlyCompositionIsUnchanged() {
        val factory = ToolExecutorFactory(web = FakeWebToolExecutor("web_search"))
        assertEquals(listOf("web_search"), factory.toolingSpecs.map { it.name })
    }

    @Test
    fun duplicateRootAndWebToolNameFailsClosed() {
        val factory = ToolExecutorFactory(
            web = FakeWebToolExecutor("file_read_text"),
            root = FakeRootToolExecutor(name = "file_read_text"),
        )
        val failure = runCatching { factory.toolingSpecs }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "Duplicate owners must fail, not pick a route")
    }

    @Test
    fun typedInvocationUsesTheRootSeamWithBothIds() = runBlocking {
        val root = FakeRootToolExecutor()
        val factory = ToolExecutorFactory(root = root, rootWorkspaceIds = setOf("root-ws"))
        val snapshotId = factory.beginRun().snapshot.snapshotId
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-call",
            snapshotId = snapshotId,
            agentId = "agent",
            name = "root_file_stat",
            argumentsJson = """{"workspace_id":"root-ws"}""",
        )
        val execution = factory.invoke(invocation)
        assertTrue(execution is ToolExecution.Value)
        val seen = root.typedCalls.single()
        assertEquals("model-call", seen.callId)
        assertEquals(invocation.requestId, seen.requestId)
        assertEquals(7, factory.toolingSpecs.single().schemaVersion, "Typed version metadata survives the legacy composition bridge")
    }

    @Test
    fun legacyInvokeAndApprovalRouteToTheRootOwner() = runBlocking {
        val root = FakeRootToolExecutor().apply { legacyResult = ToolResult.NeedsApproval }
        val factory = ToolExecutorFactory(root = root, rootWorkspaceIds = setOf("root-ws"))
        val call = ToolCall("runtime-legacy", "root_file_stat", """{"workspace_id":"root-ws"}""")
        assertEquals(ToolResult.NeedsApproval, factory.invoke(call))
        val approved = factory.approve("runtime-legacy")
        assertTrue(approved is ToolResult.Value)
        assertEquals(listOf("runtime-legacy"), root.approvals)
    }

    @Test
    fun workspaceIdAliasIsCanonicalizedBeforeTheRootSeam() = runBlocking {
        val root = FakeRootToolExecutor(name = "file_read_text")
        val factory = ToolExecutorFactory(root = root, rootWorkspaceIds = setOf("root-ws"))
        val call = ToolCall(
            "alias-equal",
            "file_read_text",
            """{"workspaceId":"root-ws","workspace_id":"root-ws","relative_path":"a"}""",
        )
        assertTrue(factory.invoke(call) is ToolResult.Value)
        val seen = root.legacyCalls.single()
        assertTrue(seen.argumentsJson.contains("\"workspace_id\":\"root-ws\""))
        assertFalse(seen.argumentsJson.contains("workspaceId"))
    }

    @Test
    fun conflictingWorkspaceIdAliasesAreRejectedBeforeApprovalOrDispatch() = runBlocking {
        val root = FakeRootToolExecutor(name = "file_read_text")
        val factory = ToolExecutorFactory(root = root, rootWorkspaceIds = setOf("root-ws"))
        val call = ToolCall(
            "alias-conflict",
            "file_read_text",
            """{"workspaceId":"root-ws","workspace_id":"other-ws","relative_path":"a"}""",
        )
        val result = factory.invoke(call)
        assertTrue(result is ToolResult.Invalid, result.toString())
        assertTrue(root.legacyCalls.isEmpty(), "a conflicting alias must never reach a backend or approval")
        assertTrue(root.typedCalls.isEmpty())
    }

    @Test
    fun missingWorkspaceIdIsInvalidRequest() = runBlocking {
        val root = FakeRootToolExecutor(name = "file_read_text")
        val factory = ToolExecutorFactory(root = root, rootWorkspaceIds = setOf("root-ws"))
        val result = factory.invoke(ToolCall("missing-id", "file_read_text", """{"relative_path":"a"}"""))
        assertEquals(ToolResult.Invalid("INVALID_REQUEST"), result)
        assertTrue(root.legacyCalls.isEmpty())
    }

    @Test
    fun unknownWorkspaceIdIsWorkspaceNotFound() = runBlocking {
        val root = FakeRootToolExecutor(name = "file_read_text")
        val factory = ToolExecutorFactory(root = root, rootWorkspaceIds = setOf("root-ws"))
        val result = factory.invoke(ToolCall("unknown-id", "file_read_text", """{"workspace_id":"other-ws","relative_path":"a"}"""))
        assertTrue(result is ToolResult.Failure, result.toString())
        assertEquals(ToolErrorCode.WORKSPACE_NOT_FOUND, (result as ToolResult.Failure).error.code)
        assertTrue(root.legacyCalls.isEmpty())
    }

    @Test
    fun frozenOfflineRootWorkspaceNeverFallsBackToAnotherProvider() = runBlocking {
        // The only provider that owns this name is an ordinary provider, but the
        // frozen Root binding reserves the exact id.  The request must be refused
        // as an unavailable Root authority, never sent to the ordinary executor.
        val other = FakeWebToolExecutor("file_read_text")
        val factory = ToolExecutorFactory(web = other, rootWorkspaceIds = setOf("root-ws"))
        val result = factory.invoke(ToolCall("offline-root", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}"""))
        assertTrue(result is ToolResult.Failure, result.toString())
        assertEquals(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE, (result as ToolResult.Failure).error.code)
        assertEquals(0, other.calls)
    }

    @Test
    fun degradedDiscoveryKeepsTheHealthyWorkspaceAndStatesAvailability() = runBlocking {
        val harness = discoveryHarness()
        val root = FakeRootToolExecutor(
            name = "workspace_list",
            legacyResult = ToolResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)),
        )
        val factory = ToolExecutorFactory(workspace = harness.executor, root = root, rootWorkspaceIds = setOf("root-ws"))
        val result = factory.invoke(ToolCall("mixed-discovery", "workspace_list", "{}"))
        assertTrue(result is ToolResult.Value, result.toString())
        val body = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
        assertTrue(body.getValue("success").jsonPrimitive.boolean)
        assertTrue(body.getValue("degraded").jsonPrimitive.boolean)
        val ids = body.getValue("workspaces").jsonArray
            .map { it.jsonObject.getValue("workspace_id").jsonPrimitive.content }
        assertEquals(listOf(DISCOVERY_WORKSPACE), ids)
        val availability = body.getValue("availability").jsonObject
        val rootState = availability.getValue("root").jsonObject
        assertFalse(rootState.getValue("available").jsonPrimitive.boolean)
        assertEquals("AUTHORITY_TEMPORARILY_UNAVAILABLE", rootState.getValue("error").jsonPrimitive.content)
        assertTrue(availability.getValue("workspace").jsonObject.getValue("available").jsonPrimitive.boolean)
        assertFalse(result.json.contains("root-ws"), "an offline Root binding must not leak its locator")
        assertFalse(result.json.contains("grant"), "availability must not leak grant/cache facts")
    }

    @Test
    fun partialDiscoveryReplayAndDeferredUseOnlyTheValueContributor() = runBlocking {
        val harness = discoveryHarness()
        val root = FakeRootToolExecutor(
            name = "workspace_list",
            legacyResult = ToolResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)),
            authorizeAllowed = false,
        )
        val factory = ToolExecutorFactory(workspace = harness.executor, root = root, rootWorkspaceIds = setOf("root-ws"))
        val call = ToolCall("mixed-disclosure", "workspace_list", "{}")
        assertTrue(factory.invoke(call) is ToolResult.Value)
        assertTrue(factory.authorizeReplay(call), "a failed owner must not veto the healthy Value's replay")
        assertTrue(factory.executor.authorizeDeferredResult(call), "the Value contributor must release its own deferred result")
        assertTrue(factory.executor.precheckDeferredResult(call))
    }

    @Test
    fun typedPartialDiscoveryCachesAndDisclosesThroughItsContributor() = runBlocking {
        val harness = discoveryHarness()
        val root = FakeRootToolExecutor(
            name = "workspace_list",
            typedResult = ToolExecution.Failed(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)),
            authorizeAllowed = false,
        )
        val factory = ToolExecutorFactory(workspace = harness.executor, root = root,
            rootWorkspaceIds = setOf("root-ws"), boundSnapshotId = "snapshot")
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-partial-discovery",
            snapshotId = "snapshot",
            agentId = "agent",
            name = "workspace_list",
            argumentsJson = "{}",
        )
        val first = factory.invoke(invocation)
        assertTrue(first is ToolExecution.Value, first.toString())
        val call = ToolCall(invocation.requestId, "workspace_list", "{}")
        assertTrue(factory.authorizeReplay(call))
        assertTrue(factory.executor.authorizeDeferredResult(call))
        val second = factory.invoke(invocation)
        assertTrue(second is ToolExecution.Value, "typed cache replay must consult the Value contributor, not the failed owner")
    }

    @Test
    fun revokedValueContributorCannotBeReleasedThroughTheOtherSide() = runBlocking {
        val harness = discoveryHarness()
        val root = FakeRootToolExecutor(
            name = "workspace_list",
            legacyResult = ToolResult.Failure(ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE)),
        )
        val factory = ToolExecutorFactory(workspace = harness.executor, root = root, rootWorkspaceIds = setOf("root-ws"))
        val call = ToolCall("mixed-revoked", "workspace_list", "{}")
        assertTrue(factory.invoke(call) is ToolResult.Value)
        assertTrue(factory.executor.authorizeDeferredResult(call))
        harness.revoke()
        assertFalse(factory.executor.authorizeDeferredResult(call), "the revoked Value side must not be released via deferred disclosure")
        assertFalse(factory.authorizeReplay(call), "the revoked Value side must not be released via replay")
    }

    @Test
    fun fullDiscoveryStillRequiresEverySuccessfulContributor() = runBlocking {
        val harness = discoveryHarness()
        val denyingRoot = FakeRootToolExecutor(
            name = "workspace_list",
            legacyResult = ToolResult.Value(ROOT_DISCOVERY_VALUE),
            authorizeAllowed = false,
        )
        val factory = ToolExecutorFactory(workspace = harness.executor, root = denyingRoot, rootWorkspaceIds = setOf("root-live"))
        val call = ToolCall("full-discovery-deny", "workspace_list", "{}")
        assertTrue(factory.invoke(call) is ToolResult.Value)
        assertFalse(factory.authorizeReplay(call), "every successful contributor must still authorize")
    }

    @Test
    fun fullDiscoveryReplaySucceedsWhenEverySuccessfulContributorAuthorizes() = runBlocking {
        val harness = discoveryHarness()
        val root = FakeRootToolExecutor(
            name = "workspace_list",
            legacyResult = ToolResult.Value(ROOT_DISCOVERY_VALUE),
            authorizeAllowed = true,
        )
        val factory = ToolExecutorFactory(workspace = harness.executor, root = root, rootWorkspaceIds = setOf("root-live"))
        val call = ToolCall("full-discovery-allow", "workspace_list", "{}")
        assertTrue(factory.invoke(call) is ToolResult.Value)
        assertTrue(factory.authorizeReplay(call))
    }

    @Test
    fun mergedSchemaOmitsConditionsAndFormatsNotSharedByEveryBackend() {
        val rootDelete = RootToolArguments.spec("file_delete")!!.toLegacy()
        val ordinaryDelete = LegacyToolSpec(
            "file_delete", "ordinary", ModelToolArguments.workspaceSchema("file_delete"), "", true, 4,
        )
        val deleteSchema = Json.parseToJsonElement(
            ModelToolArguments.mergeSpecs(listOf(rootDelete, ordinaryDelete)).single().inputSchema,
        ).jsonObject
        assertFalse("expected_version" in deleteSchema.getValue("properties").jsonObject)
        assertFalse("expectedVersion" in deleteSchema.getValue("properties").jsonObject)

        val rootPatch = RootToolArguments.spec("file_apply_patch")!!.toLegacy()
        val ordinaryPatch = LegacyToolSpec(
            "file_apply_patch", "ordinary", ModelToolArguments.workspaceSchema("file_apply_patch"), "", true, 4,
        )
        val patchSchema = Json.parseToJsonElement(
            ModelToolArguments.mergeSpecs(listOf(rootPatch, ordinaryPatch)).single().inputSchema,
        ).jsonObject
        val allowedFormats = patchSchema.getValue("properties").jsonObject
            .getValue("format").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("unified_diff"), allowedFormats)
        assertTrue("expected_version" in patchSchema.getValue("properties").jsonObject)
    }

    @Test
    fun workspaceIdSelectionIsStrictAboutAliasConflicts() {
        assertEquals(ModelToolArguments.WorkspaceIdSelection.Present("ws"),
            ModelToolArguments.workspaceIdSelection("""{"workspace_id":"ws"}"""))
        assertEquals(ModelToolArguments.WorkspaceIdSelection.Present("ws"),
            ModelToolArguments.workspaceIdSelection("""{"workspaceId":"ws"}"""))
        assertEquals(ModelToolArguments.WorkspaceIdSelection.Present("ws"),
            ModelToolArguments.workspaceIdSelection("""{"workspaceId":"ws","workspace_id":"ws"}"""))
        assertEquals(ModelToolArguments.WorkspaceIdSelection.Malformed,
            ModelToolArguments.workspaceIdSelection("""{"workspaceId":"a","workspace_id":"b"}"""))
        assertEquals(ModelToolArguments.WorkspaceIdSelection.Malformed,
            ModelToolArguments.workspaceIdSelection("""{"workspaceId":7}"""))
        assertEquals(ModelToolArguments.WorkspaceIdSelection.Absent,
            ModelToolArguments.workspaceIdSelection("""{"relative_path":"a"}"""))
        assertNull(ModelToolArguments.canonicalWorkspaceArguments("""{"workspaceId":"a","workspace_id":"b"}"""))
    }

    private fun ToolSpec.toLegacy(): LegacyToolSpec = LegacyToolSpec(
        name = name,
        description = description,
        parametersJson = inputSchema,
        capability = capability?.value.orEmpty(),
        sideEffect = sideEffect,
        schemaVersion = schemaVersion,
    )

    private fun shellExecutor(): ShellToolExecutor {
        val authority = AuthorityManager().apply {
            selectAuthority(Authority.SHIZUKU)
            setUserIntent(Authority.SHIZUKU, true)
            setConfigured(Authority.SHIZUKU, true)
            updatePlatformGrant(Authority.SHIZUKU, PlatformGrant.GRANTED)
            updateAvailability(Authority.SHIZUKU, Availability.READY)
            updateConnection(Authority.SHIZUKU, Connection.CONNECTED, "fixture")
        }
        val dangerous = DangerousModeManager(
            InMemoryDangerousModeStateStore(),
            DangerousBuildPolicy.testOnlyOverride(),
        ).apply { setPolicy(DangerousMode.ENABLED_AUTONOMOUS) }
        val context = ToolExecutionContext(
            agentId = "agent",
            snapshotId = "snapshot",
            modelCallId = "model",
            sessionIdentity = "session",
            configSnapshotHash = "frozen",
            policyVersion = 1,
        )
        return ShellToolExecutor(
            authority,
            dangerous,
            ApprovalEngine(),
            { context },
            backends = emptyMap(),
            agentShellAuthorization = { true },
        )
    }

    private fun discoveryHarness(): DiscoveryHarness {
        val backend = DiscoveryBackend()
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(backend.descriptor, backend))
        return DiscoveryHarness(registry)
    }

    private fun discoveryContext(): ToolExecutionContext = ToolExecutionContext(
        agentId = "agent",
        snapshotId = "snapshot",
        modelCallId = "model",
        sessionIdentity = "session",
        configSnapshotHash = "frozen",
        policyVersion = 1,
        effectiveCapabilities = setOf(CapabilityId(CapabilityId.WORKSPACE_ENUMERATE)),
        canonicalGrants = listOf(discoveryGrant()),
        snapshotGrantBindings = listOf(
            SnapshotGrantBinding(
                snapshotId = "snapshot",
                grantId = "grant-enumerate",
                capability = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
                workspaceId = DISCOVERY_WORKSPACE,
                pathScope = null,
                policyVersion = 1,
            ),
        ),
    )

    private fun discoveryGrant(): CapabilityGrant = CapabilityGrant(
        grantId = "grant-enumerate",
        agentId = "agent",
        capability = CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
        skillInstallId = null,
        workspaceId = DISCOVERY_WORKSPACE,
        pathScope = null,
        policyVersion = 1,
        revision = 1,
    )

    private inner class DiscoveryHarness(registry: WorkspaceRegistry) {
        var context: ToolExecutionContext = discoveryContext()
        val executor: UnifiedWorkspaceToolExecutor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = object : WorkspaceAuditSink {
                override suspend fun record(event: WorkspaceAuditEvent): Boolean = true
            },
        )

        fun revoke() {
            context = context.copy(
                effectiveCapabilities = emptySet(),
                canonicalGrants = emptyList(),
                snapshotGrantBindings = emptyList(),
            )
        }
    }

    private class DiscoveryBackend : WorkspaceBackend {
        override val descriptor = WorkspaceDescriptor(
            DISCOVERY_WORKSPACE, "Discovery", WorkspaceBackendType.INTERNAL, writable = false,
        )
        override val capabilities: Set<CapabilityId> = setOf(CapabilityId(CapabilityId.WORKSPACE_ENUMERATE))
        override suspend fun list(request: runtime.mobileagent.skills.tooling.WorkspaceListRequest):
            WorkspaceResult<runtime.mobileagent.skills.tooling.WorkspaceListing> =
            WorkspaceResult.Failure(ToolError(ToolErrorCode.OPERATION_UNAVAILABLE))
    }

    private companion object {
        const val DISCOVERY_WORKSPACE = "internal-discovery"
        const val ROOT_DISCOVERY_VALUE = """{"success":true,"workspaces":[{"workspace_id":"root-live"}]}"""
    }
}

private fun fakeLegacySpec(name: String): LegacyToolSpec =
    LegacyToolSpec(name, "fake $name", """{"type":"object"}""", "", false, schemaVersion = 7)

private class FakeRootToolExecutor(
    name: String = "root_file_stat",
    var legacyResult: ToolResult = ToolResult.Value("""{"legacy":true}"""),
    var typedResult: ToolExecution = ToolExecution.Value("""{"typed":true}"""),
    var authorizeAllowed: Boolean = false,
) : ToolExecutor, TypedToolExecutor {
    override val specs: List<LegacyToolSpec> = listOf(fakeLegacySpec(name))
    val typedCalls = mutableListOf<ToolInvocation>()
    val legacyCalls = mutableListOf<ToolCall>()
    val approvals = mutableListOf<String>()
    val cancels = mutableListOf<String>()

    override suspend fun invoke(call: ToolCall): ToolResult {
        legacyCalls += call
        return legacyResult
    }

    override suspend fun approve(callId: String): ToolResult {
        approvals += callId
        return ToolResult.Value("""{"approved":true}""")
    }

    override suspend fun invoke(invocation: ToolInvocation): ToolExecution {
        typedCalls += invocation
        return typedResult
    }

    override suspend fun authorizeReplay(call: ToolCall): Boolean = authorizeAllowed

    override suspend fun cancel(requestId: String): Boolean {
        cancels += requestId
        return true
    }
}

private class FakeWebToolExecutor(private val name: String) : ToolExecutor {
    var calls: Int = 0
    override val specs: List<LegacyToolSpec> = listOf(fakeLegacySpec(name))

    override suspend fun invoke(call: ToolCall): ToolResult {
        calls += 1
        return ToolResult.Value("""{"web":true}""")
    }

    override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("not supported")
}
