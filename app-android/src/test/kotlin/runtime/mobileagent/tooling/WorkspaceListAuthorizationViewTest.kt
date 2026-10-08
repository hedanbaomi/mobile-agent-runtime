// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceFileStat
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceMutation
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceText
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest

/**
 * `workspace_list` must describe this Agent's real authority, not the backend's
 * registration-time descriptor.  These are negative tests: a read-only Agent on
 * a platform-writable workspace, SAF ordinary writes versus atomic
 * replacement, a path-bounded grant that must not become whole-root authority,
 * and the Skill capability intersection.
 */
class WorkspaceListAuthorizationViewTest {

    private class RecordingBackend(
        override val descriptor: WorkspaceDescriptor,
        override val capabilities: Set<CapabilityId>,
        override val expectedVersionCapabilities: Set<CapabilityId> =
            setOf(CapabilityId("file.apply_patch")).intersect(capabilities),
    ) : WorkspaceBackend {
        var writeCalls = 0

        override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> = denied()
        override suspend fun stat(request: WorkspaceStatRequest): WorkspaceResult<WorkspaceFileStat> = denied()
        override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> = denied()
        override suspend fun applyPatch(request: WorkspaceApplyPatchRequest): WorkspaceResult<WorkspaceMutation> = denied()
        override suspend fun createDirectory(request: WorkspaceCreateDirectoryRequest): WorkspaceResult<WorkspaceMutation> = denied()
        override suspend fun move(request: WorkspaceMoveRequest): WorkspaceResult<WorkspaceMutation> = denied()
        override suspend fun delete(request: WorkspaceDeleteRequest): WorkspaceResult<WorkspaceMutation> = denied()

        override suspend fun writeText(request: WorkspaceWriteTextRequest): WorkspaceResult<WorkspaceMutation> {
            writeCalls += 1
            return WorkspaceResult.Success(
                WorkspaceMutation(request.relativePath, WorkspaceEntryType.FILE, byteSize = 1L),
            )
        }

        private fun <T> denied(): WorkspaceResult<T> =
            WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
    }

    @Test
    fun ordinaryMutationSchemasOmitUnsupportedVersionsAndRejectThemBeforeOnceConsumption(): Unit = runBlocking {
        val capabilities = safWriteCapabilities() + CapabilityId(CapabilityId.FILE_DELETE)
        val backend = RecordingBackend(
            WorkspaceDescriptor(SAF_WORKSPACE, "阅读", WorkspaceBackendType.SAF_TREE, writable = true),
            capabilities,
        )
        val grants = capabilities.map { grant("g-${it.value}", it.value, SAF_WORKSPACE) }
            .map { if (it.capability == CapabilityId(CapabilityId.FILE_WRITE_TEXT)) it.copy(lifetime = GrantLifetime.ONCE) else it }
        val context = runContext(grants)
        var consumed = 0
        val executor = executorFor(registryOf(backend), context) { consumed++; true }
        val tools = listOf("file_write_text", "file_create_directory", "file_delete")
        tools.forEach { name ->
            val schema = Json.parseToJsonElement(executor.toolingSpecs.single { it.name == name }.inputSchema).jsonObject
            assertFalse("expected_version" in schema.getValue("properties").jsonObject, name)
            val text = if (name == "file_write_text") ",\"text\":\"body\"" else ""
            val result = executor.invoke(ToolCall("unsupported-$name", name,
                """{"workspace_id":"$SAF_WORKSPACE","relative_path":"note.txt","expected_version":123$text}"""), context)
            assertEquals(ToolResult.Failure(ToolError(ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED)), result)
        }
        assertEquals(0, backend.writeCalls)
        assertEquals(0, consumed, "unsupported preconditions must not consume a one-shot grant")
        val view = listedWorkspaces(executor, context).getValue(SAF_WORKSPACE)
        assertTrue(view.stringList("expected_version_operations").isEmpty())
        assertEquals("target_entry", view.getValue("version_scope").jsonPrimitive.content)
        val accepted = executor.invoke(ToolCall("ordinary-write", "file_write_text",
            """{"workspace_id":"$SAF_WORKSPACE","relative_path":"note.txt","text":"body"}"""), context)
        assertTrue(accepted is ToolResult.Value)
        assertEquals(1, backend.writeCalls)
        assertEquals(1, consumed)
    }

    @Test
    fun mixedBackendSchemaKeepsVersionOnlyAsAnExplicitPerWorkspaceContract(): Unit = runBlocking {
        val capabilities = safWriteCapabilities()
        val internal = RecordingBackend(
            WorkspaceDescriptor("internal-versioned", "Internal", WorkspaceBackendType.INTERNAL, writable = true),
            capabilities, setOf(CapabilityId(CapabilityId.FILE_WRITE_TEXT)),
        )
        val saf = RecordingBackend(
            WorkspaceDescriptor(SAF_WORKSPACE, "阅读", WorkspaceBackendType.SAF_TREE, writable = true), capabilities,
        )
        val registry = registryOf(internal)
        assertTrue(registry.register(saf.descriptor, saf))
        val grants = listOf(internal, saf).flatMap { backend ->
            capabilities.map { grant("${backend.descriptor.id}-${it.value}", it.value, backend.descriptor.id) }
        }
        val context = runContext(grants)
        val executor = executorFor(registry, context)
        val spec = executor.toolingSpecs.single { it.name == "file_write_text" }
        assertTrue("expected_version" in Json.parseToJsonElement(spec.inputSchema).jsonObject.getValue("properties").jsonObject)
        val views = listedWorkspaces(executor, context)
        assertEquals(listOf("file_write_text"), views.getValue("internal-versioned").stringList("expected_version_operations"))
        assertTrue(views.getValue(SAF_WORKSPACE).stringList("expected_version_operations").isEmpty())
        val result = executor.invoke(ToolCall("mixed-saf-version", "file_write_text",
            """{"workspace_id":"$SAF_WORKSPACE","relative_path":"note.txt","text":"body","expected_version":123}"""), context)
        assertEquals(ToolResult.Failure(ToolError(ToolErrorCode.WORKSPACE_VERSION_UNSUPPORTED)), result)
        assertEquals(0, saf.writeCalls)
        assertEquals(0, internal.writeCalls)
    }

    @Test
    fun patchWithoutItsMandatoryVersionContractIsNeverAdvertisedAsAtomic(): Unit = runBlocking {
        val capabilities = safWriteCapabilities() + CapabilityId("file.apply_patch")
        val backend = RecordingBackend(
            WorkspaceDescriptor("incomplete-patch", "Incomplete contract", WorkspaceBackendType.INTERNAL, writable = true),
            capabilities, expectedVersionCapabilities = emptySet(),
        )
        val grants = capabilities.map { grant("patch-${it.value}", it.value, backend.descriptor.id) }
        val context = runContext(grants)
        val executor = executorFor(registryOf(backend), context)
        assertFalse(executor.toolingSpecs.any { it.name == "file_apply_patch" })
        val view = listedWorkspaces(executor, context).getValue(backend.descriptor.id)
        assertFalse(view.bool("atomic_replace"))
        assertFalse("file_apply_patch" in view.stringList("authorized_operations"))
    }

    @Test
    fun readOnlyAgentIsNotToldThatAPlatformWritableWorkspaceIsWritable(): Unit = runBlocking {
        val backend = RecordingBackend(
            descriptor = WorkspaceDescriptor(
                id = SAF_WORKSPACE,
                displayName = "Writable phone folder",
                backendType = WorkspaceBackendType.SAF_TREE,
                writable = true,
            ),
            capabilities = safWriteCapabilities(),
        )
        assertTrue(backend.descriptor.writable, "the platform workspace is genuinely writable")
        val registry = registryOf(backend)
        val grants = listOf(
            grant("g-enum", CapabilityId.WORKSPACE_ENUMERATE, SAF_WORKSPACE),
            grant("g-list", CapabilityId.FILE_LIST, SAF_WORKSPACE),
            grant("g-read", CapabilityId.FILE_READ_TEXT, SAF_WORKSPACE),
        )

        val view = listedWorkspaces(registry, runContext(grants)).getValue(SAF_WORKSPACE)

        assertTrue(view.bool("readable"))
        assertFalse(view.bool("writable"), "a read-only Agent must never be told writable")
        assertEquals(listOf("file_list", "file_read_text"), view.stringList("authorized_operations"))
        assertTrue(view.stringList("create_only_operations").isEmpty())
    }

    @Test
    fun safCreateAuthorityIsCreateOnlyAndAtomicReplaceStaysFalse(): Unit = runBlocking {
        val backend = RecordingBackend(
            descriptor = WorkspaceDescriptor(
                id = SAF_WORKSPACE,
                displayName = "Create-capable phone folder",
                backendType = WorkspaceBackendType.SAF_TREE,
                writable = true,
            ),
            capabilities = safWriteCapabilities(),
        )
        val registry = registryOf(backend)
        val grants = listOf(
            grant("g-enum", CapabilityId.WORKSPACE_ENUMERATE, SAF_WORKSPACE),
            grant("g-read", CapabilityId.FILE_READ_TEXT, SAF_WORKSPACE),
            grant("g-write", CapabilityId.FILE_WRITE_TEXT, SAF_WORKSPACE),
            grant("g-mkdir", CapabilityId.FILE_CREATE_DIRECTORY, SAF_WORKSPACE),
        )

        val view = listedWorkspaces(registry, runContext(grants)).getValue(SAF_WORKSPACE)

        assertTrue(view.bool("writable"))
        assertEquals(
            listOf("file_create_directory", "file_read_text", "file_write_text"),
            view.stringList("authorized_operations"),
        )
        // SAF can update an existing document with replace=true, without claiming
        // atomic replacement or conditional patch support.
        assertEquals(
            listOf("file_create_directory"),
            view.stringList("create_only_operations"),
        )
        assertFalse(view.bool("atomic_replace"))
        assertFalse(view.stringList("authorized_operations").contains("file_apply_patch"))
        assertFalse(view.scopes().containsKey("file_apply_patch"))
        assertTrue(view.scope("file_write_text").bool("whole_workspace"))
        assertTrue(view.scope("file_write_text").stringList("path_scopes").isEmpty())
    }

    @Test
    fun atomicReplaceFollowsTheDeterministicConditionalPatchCapability(): Unit = runBlocking {
        val backend = RecordingBackend(
            descriptor = WorkspaceDescriptor(
                id = "internal-atomic",
                displayName = "Internal workspace",
                backendType = WorkspaceBackendType.INTERNAL,
                writable = true,
            ),
            capabilities = setOf(
                CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
                CapabilityId(CapabilityId.FILE_LIST),
                CapabilityId(CapabilityId.FILE_READ_TEXT),
                CapabilityId(CapabilityId.FILE_WRITE_TEXT),
                CapabilityId("file.apply_patch"),
            ),
        )
        val registry = registryOf(backend)
        val grants = listOf(
            grant("g-enum", CapabilityId.WORKSPACE_ENUMERATE, "internal-atomic"),
            grant("g-read", CapabilityId.FILE_READ_TEXT, "internal-atomic"),
            grant("g-write", CapabilityId.FILE_WRITE_TEXT, "internal-atomic"),
            grant("g-patch", "file.apply_patch", "internal-atomic"),
        )

        val view = listedWorkspaces(registry, runContext(grants)).getValue("internal-atomic")

        assertTrue(view.bool("atomic_replace"))
        assertTrue(view.stringList("create_only_operations").isEmpty())
        assertTrue(view.stringList("authorized_operations").contains("file_apply_patch"))
    }

    @Test
    fun pathScopedWriteIsNeverReportedAsWholeWorkspaceAuthority(): Unit = runBlocking {
        val backend = RecordingBackend(
            descriptor = WorkspaceDescriptor(
                id = "internal-scoped",
                displayName = "Scoped workspace",
                backendType = WorkspaceBackendType.INTERNAL,
                writable = true,
            ),
            capabilities = setOf(
                CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
                CapabilityId(CapabilityId.FILE_LIST),
                CapabilityId(CapabilityId.FILE_READ_TEXT),
                CapabilityId(CapabilityId.FILE_WRITE_TEXT),
            ),
        )
        val registry = registryOf(backend)
        val grants = listOf(
            grant("g-enum", CapabilityId.WORKSPACE_ENUMERATE, "internal-scoped"),
            grant("g-read", CapabilityId.FILE_READ_TEXT, "internal-scoped"),
            grant("g-write", CapabilityId.FILE_WRITE_TEXT, "internal-scoped", pathScope = "docs"),
        )
        val context = runContext(grants)
        val executor = executorFor(registry, context)

        val view = listedWorkspaces(executor, context).getValue("internal-scoped")

        assertTrue(view.bool("writable"))
        assertFalse(
            view.scope("file_write_text").bool("whole_workspace"),
            "a path-bounded grant must not become whole-root authority",
        )
        assertEquals(listOf("docs"), view.scope("file_write_text").stringList("path_scopes"))
        assertTrue(view.scope("file_read_text").bool("whole_workspace"))

        // The reported scope is the one dispatch actually honors.
        val inside = executor.invoke(writeCall("write-inside", "docs/note.txt"), context)
        assertTrue(inside is ToolResult.Value, "an in-scope write must reach the backend")
        val outside = executor.invoke(writeCall("write-outside", "outside.txt"), context)
        assertTrue(outside is ToolResult.Failure)
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, (outside as ToolResult.Failure).error.code)
        assertEquals(1, backend.writeCalls, "an out-of-scope write must never reach the backend")
    }

    @Test
    fun skillContextStillIntersectsTheAgentCapabilitySet(): Unit = runBlocking {
        val backend = RecordingBackend(
            descriptor = WorkspaceDescriptor(
                id = "internal-skill",
                displayName = "Skill workspace",
                backendType = WorkspaceBackendType.INTERNAL,
                writable = true,
            ),
            capabilities = setOf(
                CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
                CapabilityId(CapabilityId.FILE_LIST),
                CapabilityId(CapabilityId.FILE_READ_TEXT),
                CapabilityId(CapabilityId.FILE_WRITE_TEXT),
            ),
        )
        val registry = registryOf(backend)
        val grants = listOf(
            grant("g-enum", CapabilityId.WORKSPACE_ENUMERATE, "internal-skill"),
            grant("g-list", CapabilityId.FILE_LIST, "internal-skill"),
            grant("g-read", CapabilityId.FILE_READ_TEXT, "internal-skill"),
            grant("g-write", CapabilityId.FILE_WRITE_TEXT, "internal-skill"),
            grant("g-skill-read", CapabilityId.FILE_READ_TEXT, "internal-skill", skillInstallId = SKILL),
            // Enumeration itself is capability-gated, so the Skill envelope must
            // carry it to observe anything; the intersection still narrows the
            // operations the Agent may use inside that Skill.
            grant("g-skill-enum", CapabilityId.WORKSPACE_ENUMERATE, "internal-skill", skillInstallId = SKILL),
        )

        val agentView = listedWorkspaces(registry, runContext(grants)).getValue("internal-skill")
        assertTrue(agentView.bool("writable"))
        assertEquals(
            listOf("file_list", "file_read_text", "file_write_text"),
            agentView.stringList("authorized_operations"),
        )

        val skillView = listedWorkspaces(registry, runContext(grants, skillId = SKILL)).getValue("internal-skill")
        assertFalse(skillView.bool("writable"), "the Skill envelope must not widen the Agent authority")
        assertEquals(listOf("file_read_text"), skillView.stringList("authorized_operations"))
    }

    @Test
    fun enumerationProjectionDoesNotConsumeOneShotFileGrant(): Unit = runBlocking {
        val backend = RecordingBackend(
            WorkspaceDescriptor("internal-scoped", "One-shot workspace", WorkspaceBackendType.INTERNAL, writable = true),
            setOf(CapabilityId(CapabilityId.WORKSPACE_ENUMERATE), CapabilityId(CapabilityId.FILE_WRITE_TEXT)),
        )
        val grants = listOf(
            grant("g-enum", CapabilityId.WORKSPACE_ENUMERATE, "internal-scoped"),
            grant("g-write-once", CapabilityId.FILE_WRITE_TEXT, "internal-scoped").copy(lifetime = GrantLifetime.ONCE),
        )
        val context = runContext(grants)
        val consumed = mutableSetOf<String>()
        val executor = executorFor(registryOf(backend), context) { consumed.add(it.grantId) }
        val view = listedWorkspaces(executor, context).getValue("internal-scoped")
        assertTrue(view.bool("writable"))
        assertTrue(consumed.isEmpty(), "describing an available operation must not spend its ONCE grant")
        assertTrue(executor.invoke(writeCall("once-first", "first.txt"), context) is ToolResult.Value)
        assertEquals(setOf("g-write-once"), consumed)
        assertTrue(executor.invoke(writeCall("once-second", "second.txt"), context) is ToolResult.Failure)
        assertEquals(1, backend.writeCalls)
    }

    @Test
    fun actualEnumerationStillConsumesItsOwnOneShotGrant(): Unit = runBlocking {
        val backend = RecordingBackend(
            WorkspaceDescriptor("internal-scoped", "One-shot enumeration", WorkspaceBackendType.INTERNAL, writable = true),
            setOf(CapabilityId(CapabilityId.WORKSPACE_ENUMERATE), CapabilityId(CapabilityId.FILE_READ_TEXT)),
        )
        val context = runContext(listOf(
            grant("g-enum-once", CapabilityId.WORKSPACE_ENUMERATE, "internal-scoped").copy(lifetime = GrantLifetime.ONCE),
            grant("g-read", CapabilityId.FILE_READ_TEXT, "internal-scoped"),
        ))
        val consumed = mutableSetOf<String>()
        val executor = executorFor(registryOf(backend), context) { consumed.add(it.grantId) }
        listedWorkspaces(executor, context)
        assertEquals(setOf("g-enum-once"), consumed)
        assertTrue(executor.invoke(ToolCall("list-again", UnifiedWorkspaceToolExecutor.WORKSPACE_LIST, "{}"), context) is ToolResult.Failure)
    }

    private fun registryOf(backend: WorkspaceBackend): WorkspaceRegistry {
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(backend.descriptor, backend))
        return registry
    }

    private fun executorFor(
        registry: WorkspaceRegistry,
        context: ToolExecutionContext,
        onceConsumer: (CapabilityGrant) -> Boolean = { false },
    ): UnifiedWorkspaceToolExecutor = UnifiedWorkspaceToolExecutor(
        registry = registry,
        approvalEngine = ApprovalEngine(),
        contextProvider = { context },
        onceGrantConsumer = onceConsumer,
        auditSink = object : WorkspaceAuditSink {
            override suspend fun record(event: WorkspaceAuditEvent): Boolean = true
        },
    )

    private suspend fun listedWorkspaces(
        registry: WorkspaceRegistry,
        context: ToolExecutionContext,
    ): Map<String, JsonObject> = listedWorkspaces(executorFor(registry, context), context)

    private suspend fun listedWorkspaces(
        executor: UnifiedWorkspaceToolExecutor,
        context: ToolExecutionContext,
    ): Map<String, JsonObject> {
        val result = executor.invoke(
            ToolCall("list-workspaces", UnifiedWorkspaceToolExecutor.WORKSPACE_LIST, "{}"),
            context,
        )
        assertTrue(result is ToolResult.Value, "workspace_list must succeed: $result")
        return Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
            .getValue("workspaces").jsonArray
            .associateBy { it.jsonObject.getValue("workspace_id").jsonPrimitive.content }
            .mapValues { it.value.jsonObject }
    }

    private fun writeCall(callId: String, relativePath: String): ToolCall = ToolCall(
        callId,
        UnifiedWorkspaceToolExecutor.FILE_WRITE_TEXT,
        """{"workspace_id":"internal-scoped","relative_path":"$relativePath","text":"body"}""",
    )

    private fun runContext(
        grants: List<CapabilityGrant>,
        skillId: String? = null,
    ): ToolExecutionContext = ToolExecutionContext(
        agentId = AGENT,
        snapshotId = SNAPSHOT,
        modelCallId = "model-workspace-view",
        sessionIdentity = "session-workspace-view",
        configSnapshotHash = "config-workspace-view",
        policyVersion = POLICY,
        effectiveCapabilities = grants.map { it.capability }.toSet(),
        canonicalGrants = grants,
        snapshotGrantBindings = grants.map { grant ->
            SnapshotGrantBinding(
                snapshotId = SNAPSHOT,
                grantId = grant.grantId,
                capability = grant.capability,
                workspaceId = grant.workspaceId,
                pathScope = grant.pathScope,
                policyVersion = grant.policyVersion,
            )
        },
        skillId = skillId,
        skillRevision = skillId?.let { 1L },
        trustedSkillEnvelope = skillId != null,
    )

    private fun grant(
        grantId: String,
        capability: String,
        workspaceId: String,
        skillInstallId: String? = null,
        pathScope: String? = null,
    ): CapabilityGrant = CapabilityGrant(
        grantId = grantId,
        agentId = AGENT,
        capability = CapabilityId(capability),
        skillInstallId = skillInstallId,
        workspaceId = workspaceId,
        pathScope = pathScope,
        policyVersion = POLICY,
        revision = 1,
    )

    private fun safWriteCapabilities(): Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.WORKSPACE_ENUMERATE),
        CapabilityId(CapabilityId.FILE_LIST),
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
        CapabilityId(CapabilityId.FILE_WRITE_TEXT),
        CapabilityId(CapabilityId.FILE_CREATE_DIRECTORY),
    )

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.content.toBooleanStrict()

    private fun JsonObject.stringList(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.scopes(): JsonObject = getValue("operation_scopes").jsonObject

    private fun JsonObject.scope(toolName: String): JsonObject = scopes().getValue(toolName).jsonObject

    private companion object {
        const val AGENT = "agent-workspace-view"
        const val SNAPSHOT = "snapshot-workspace-view"
        const val SKILL = "skill-workspace-view"
        const val POLICY = 1L
        const val SAF_WORKSPACE = "saf-writable-view"
    }
}
