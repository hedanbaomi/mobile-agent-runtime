// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.domain.RootTypedArguments
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.skills.tooling.ToolSpec

class RootToolExecutorTest {
    // ---- normalization -------------------------------------------------------

    @Test
    fun writeArgumentsPreserveTheExactStringVersionToken() {
        val token = "v1:opaque+token"
        val request = requireNotNull(
            RootToolArguments.normalize(
                "file_write_text",
                """{"workspace_id":"root-ws","relative_path":"notes/a.txt","text":"héllo","replace":true,"expected_version":"$token"}""",
            ),
        )
        val typed = request.typed as RootTypedArguments.FileWriteText
        assertEquals(token, typed.expectedVersion, "A version token must never be converted and truncated")
        assertTrue(typed.replaceExisting)
        val payload = request.payload as RootToolPayload.Text
        assertEquals("héllo", payload.text)
        assertEquals(6, payload.byteLength)
        assertEquals(typed.contentSha256, payload.sha256)
        assertEquals(request.canonicalSha256, typed.canonicalSha256())
    }

    @Test
    fun statAndMetadataRemainDistinctTools() {
        val stat = requireNotNull(
            RootToolArguments.normalize("file_stat", """{"workspace_id":"root-ws","relative_path":"a"}"""),
        )
        val metadata = requireNotNull(
            RootToolArguments.normalize("file_get_metadata", """{"workspace_id":"root-ws","relative_path":"a"}"""),
        )
        assertEquals(CapabilityId(CapabilityId.FILE_STAT), stat.capability)
        assertEquals(CapabilityId(CapabilityId.FILE_METADATA_READ), metadata.capability)
        assertTrue(stat.typed is RootTypedArguments.FileStat)
        assertTrue(metadata.typed is RootTypedArguments.FileGetMetadata)
    }

    @Test
    fun invalidShapesTypesUnknownKeysAndDuplicateKeysAreRejected() {
        val prefix = "{\"workspace_id\":\"root-ws\",\"relative_path\":\"a.txt\",\"text\":\"x\""
        assertNull(
            RootToolArguments.normalize("file_write_text", """$prefix,"expected_version":7}"""),
            "A numeric expected_version is not the typed string token",
        )
        assertNull(
            RootToolArguments.normalize("file_write_text", """$prefix,"unexpected":"value"}"""),
        )
        assertNull(
            RootToolArguments.normalize(
                "file_read_text",
                """{"workspace_id":"root-ws","relative_path":"a.txt","offset_bytes":"5"}""",
            ),
        )
        assertNull(
            RootToolArguments.normalize(
                "file_read_text",
                """{"workspace_id":"root-ws","workspace_id":"other","relative_path":"a.txt"}""",
            ),
            "Duplicate JSON keys must fail closed",
        )
        assertNull(RootToolArguments.normalize("file_read_text", """{"workspace_id":"root-ws","relative_path":"../secret"}"""))
        assertNull(RootToolArguments.normalize("file_glob", """{"workspace_id":"root-ws"}"""))
        assertNull(RootToolArguments.normalize("file_read_text", "[]"))
    }

    @Test
    fun oversizedPayloadsAreRejectedBeforeAnyDispatch() = runBlocking {
        val hugeText = "a".repeat(262_145)
        assertNull(
            RootToolArguments.normalize(
                "file_write_text",
                """{"workspace_id":"root-ws","relative_path":"a.txt","text":"$hugeText"}""",
            ),
        )
        val hugeUnknown = "a".repeat(600_000)
        assertNull(
            RootToolArguments.normalize(
                "file_read_text",
                """{"workspace_id":"root-ws","relative_path":"a.txt","extra":"$hugeUnknown"}""",
            ),
        )
        val backend = FakeRootBackend(listOf(exposure("file_write_text")))
        val executor = RootToolExecutor(specs("file_write_text"), backend)
        val result = executor.invoke(
            ToolCall(
                "runtime-oversized",
                "file_write_text",
                """{"workspace_id":"root-ws","relative_path":"a.txt","text":"$hugeText"}""",
            ),
        )
        assertTrue(result is ToolResult.Invalid)
        assertTrue(backend.invocations.isEmpty(), "An invalid payload must never reach the backend")
    }

    @Test
    fun invalidArgumentErrorsAreStaticAndDoNotEchoPayloads() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_write_text")))
        val executor = RootToolExecutor(specs("file_write_text"), backend)
        val result = executor.invoke(
            ToolCall(
                "runtime-invalid",
                "file_write_text",
                """{"workspace_id":"root-ws","relative_path":"a.txt","text":"/data/local/tmp/secret","expected_version":9}""",
            ),
        )
        assertTrue(result is ToolResult.Invalid)
        val reason = (result as ToolResult.Invalid).reason
        assertFalse(reason.contains("/data"))
        assertFalse(reason.contains("secret"))
        assertTrue(backend.invocations.isEmpty())
    }

    // ---- exposure ------------------------------------------------------------

    @Test
    fun onlyImplementedGrantedToolsAreExposedIncludingMetadata() {
        val catalogList = RootToolArguments.specsFor(listOf("file_read_text", "file_get_metadata", "file_list"))
        val unsupported = ToolSpec(
            "file_glob",
            "not implemented",
            requireNotNull(RootToolArguments.spec("file_list")).inputSchema,
            CapabilityId(CapabilityId.FILE_LIST),
            false,
        )
        val tampered = ToolSpec(
            "file_set_metadata",
            "tampered schema",
            """{"type":"object"}""",
            CapabilityId(CapabilityId.FILE_METADATA_WRITE),
            true,
        )
        val backend = FakeRootBackend(listOf(exposure("file_read_text"), exposure("file_get_metadata")))
        val executor = RootToolExecutor(catalogList + unsupported + tampered, backend)

        assertEquals(setOf("file_read_text", "file_get_metadata"), executor.exposedToolNames)
        assertEquals(setOf("file_set_metadata", "file_list", "file_glob"), executor.rejectedToolNames)
        val legacy = executor.specs.associateBy { it.name }
        assertEquals(CapabilityId.FILE_METADATA_READ, legacy.getValue("file_get_metadata").capability)
        assertFalse(legacy.getValue("file_get_metadata").sideEffect)
        assertEquals(CapabilityId.FILE_READ_TEXT, legacy.getValue("file_read_text").capability)
    }

    @Test
    fun writeToolsRequireAWritableExposure() {
        val backend = FakeRootBackend(
            listOf(
                exposure("file_delete", writable = false),
                exposure("file_read_text"),
            ),
        )
        val executor = RootToolExecutor(specs("file_delete", "file_read_text"), backend)
        assertEquals(setOf("file_read_text"), executor.exposedToolNames)
    }

    @Test
    fun unexposedWorkspaceIsRefusedBeforeBackend() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")))
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val result = executor.invoke(
            ToolCall("runtime-other-ws", "file_read_text", """{"workspace_id":"other-ws","relative_path":"a"}"""),
        )
        assertTrue(result is ToolResult.Failure)
        assertEquals(ToolErrorCode.OPERATION_UNAVAILABLE, (result as ToolResult.Failure).error.code)
        assertTrue(backend.invocations.isEmpty())
    }

    // ---- exact runtime invocation -------------------------------------------

    @Test
    fun legacyInvocationUsesTheRuntimeIdAndNeverInventsAModelId() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")))
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val call = ToolCall("runtime-invocation-1", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        assertTrue(executor.invoke(call) is ToolResult.Value)
        assertEquals(1, backend.invocations.size)
        val seen = backend.invocations.single()
        assertEquals("runtime-invocation-1", seen.runtimeInvocationId)
        assertNull(seen.modelCallId)
        assertEquals("root-ws", seen.workspaceId)
        assertEquals("grant-1", seen.grantId)
        assertFalse(seen.toString().contains("root-ws"), "Invocation logging must not expose workspace/path facts")
        assertTrue(executor.invoke(call) is ToolResult.Value)
        assertEquals(1, backend.invocations.size, "A repeated runtime id must replay without redispatch")
    }

    @Test
    fun typedInvocationRetainsBothModelAndRuntimeIds() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")))
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-call-1",
            snapshotId = "snapshot-1",
            agentId = "agent-1",
            name = "file_read_text",
            argumentsJson = """{"workspace_id":"root-ws","relative_path":"a"}""",
        )
        val execution = executor.invoke(invocation)
        assertTrue(execution is ToolExecution.Value)
        val seen = backend.invocations.single()
        assertEquals(invocation.requestId, seen.runtimeInvocationId)
        assertEquals("model-call-1", seen.modelCallId)
        assertEquals("snapshot-1", seen.snapshotId)
        assertEquals("agent-1", seen.agentId)
    }

    @Test
    fun reusedRuntimeIdWithDifferentArgumentsFailsClosed() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")))
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        executor.invoke(ToolCall("runtime-reused", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}"""))
        val second = executor.invoke(
            ToolCall("runtime-reused", "file_read_text", """{"workspace_id":"root-ws","relative_path":"b"}"""),
        )
        assertTrue(second is ToolResult.Invalid)
        assertEquals(1, backend.invocations.size)
    }

    @Test
    fun concurrentDispatchOfOneRuntimeIdIsVisibleAsUnknown() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val backend = FakeRootBackend(listOf(exposure("file_read_text"))).apply { this.gate = gate }
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val call = ToolCall("runtime-in-flight", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        coroutineScope {
            val first = async { executor.invoke(call) }
            backend.started.await()
            val second = executor.invoke(call)
            assertTrue(second is ToolResult.UnknownOutcome)
            gate.complete(Unit)
            assertTrue(first.await() is ToolResult.Value)
        }
        assertEquals(1, backend.invocations.size)
    }

    // ---- approval / replay / cancel -----------------------------------------

    @Test
    fun approvalUsesTheExactRuntimeInvocationNotArgumentScanning() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")), invokeResult = ToolResult.NeedsApproval)
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val call = ToolCall("runtime-approve-1", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
        assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
        assertEquals(1, backend.invocations.size)

        backend.invokeResult = ToolResult.Value("""{"ok":true}""")
        val approved = executor.approve("runtime-approve-1")
        assertTrue(approved is ToolResult.Value)
        assertEquals(listOf("runtime-approve-1"), backend.approvals)
        assertEquals(1, backend.invocations.size, "Approval must settle the bound invocation, not rediscover it")
    }

    @Test
    fun approvalByUniqueModelIdStillRoutesTheRuntimeId() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")), invokeResult = ToolResult.NeedsApproval)
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-approve",
            snapshotId = "snapshot-1",
            agentId = "agent-1",
            name = "file_read_text",
            argumentsJson = """{"workspace_id":"root-ws","relative_path":"a"}""",
        )
        executor.invoke(invocation)
        backend.invokeResult = ToolResult.Value("""{"ok":true}""")
        val approved = executor.approve("model-approve")
        assertTrue(approved is ToolResult.Value)
        assertEquals(listOf(invocation.requestId), backend.approvals)
    }

    @Test
    fun ambiguousModelCallIdNeverSelectsAnApproval() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")), invokeResult = ToolResult.NeedsApproval)
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        repeat(2) { index ->
            executor.invoke(
                ToolInvocation.fromRuntime(
                    callId = "model-duplicate",
                    snapshotId = "snapshot-1",
                    agentId = "agent-1",
                    name = "file_read_text",
                    argumentsJson = """{"workspace_id":"root-ws","relative_path":"a$index"}""",
                ),
            )
        }
        val result = executor.approve("model-duplicate")
        assertTrue(result is ToolResult.Invalid)
        assertTrue(backend.approvals.isEmpty())
    }

    @Test
    fun rejectAndExpireUseTheRuntimeId() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")), invokeResult = ToolResult.NeedsApproval)
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val call = ToolCall("runtime-reject", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        assertEquals(ToolResult.NeedsApproval, executor.invoke(call))
        assertTrue(executor.reject("runtime-reject") is ToolResult.Denied)
        assertEquals(listOf("runtime-reject"), backend.rejects)

        val second = ToolCall("runtime-expire", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        assertEquals(ToolResult.NeedsApproval, executor.invoke(second))
        assertTrue(executor.expire("runtime-expire") is ToolResult.Denied)
        assertEquals(listOf("runtime-expire"), backend.expiries)
    }

    @Test
    fun replayRequiresTheExactCallAndDelegatesTheExactInvocation() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")))
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val call = ToolCall("runtime-replay", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        executor.invoke(call)
        assertTrue(executor.authorizeReplay(call))
        assertEquals(1, backend.disclosures.size)
        assertEquals(ResultDisclosure.REPLAY, backend.disclosures.single().second)
        assertEquals("runtime-replay", backend.disclosures.single().first.runtimeInvocationId)

        // Reordered JSON is the same typed request; a different path is not.
        val reordered = ToolCall("runtime-replay", "file_read_text", """{"relative_path":"a","workspace_id":"root-ws"}""")
        assertTrue(executor.authorizeReplay(reordered))
        val otherPath = ToolCall("runtime-replay", "file_read_text", """{"workspace_id":"root-ws","relative_path":"b"}""")
        assertFalse(executor.authorizeReplay(otherPath))
        assertFalse(executor.authorizeReplay(ToolCall("runtime-replay", "file_stat", call.argumentsJson)))
        assertEquals(2, backend.disclosures.size, "A mismatched call must not reach disclosure revalidation")

        assertTrue(executor.authorizeDeferredResult(call))
        assertEquals(ResultDisclosure.DEFERRED, backend.disclosures.last().second)
        backend.disclosureAllowed = false
        assertFalse(executor.authorizeReplay(call))
    }

    @Test
    fun deferredDisclosureNeverContinuesAMutation() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_write_text")))
        val executor = RootToolExecutor(specs("file_write_text"), backend)
        val call = ToolCall(
            "runtime-deferred-write",
            "file_write_text",
            """{"workspace_id":"root-ws","relative_path":"a.txt","text":"x"}""",
        )
        assertTrue(executor.invoke(call) is ToolResult.Value)
        assertFalse(executor.authorizeDeferredResult(call))
        assertTrue(backend.disclosures.isEmpty(), "A mutation result must not reach deferred disclosure")
        assertTrue(executor.authorizeReplay(call), "A normal replay check may still be revalidated")
    }

    @Test
    fun cancelRoutesTheExactRuntimeId() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")))
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val call = ToolCall("runtime-cancel", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        executor.invoke(call)
        assertTrue(executor.cancel("runtime-cancel"))
        assertEquals(listOf("runtime-cancel"), backend.cancels)
        assertFalse(executor.cancel("runtime-cancel"), "A cancelled binding is not silently cancelled twice")
        assertTrue(executor.invoke(call) is ToolResult.UnknownOutcome)
    }

    @Test
    fun backendFailureWithoutProofIsUnknownAndStatic() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text"))).apply {
            failInvoke = IllegalStateException("failed reading /data/local/tmp/secret")
        }
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val call = ToolCall("runtime-failure", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        val result = executor.invoke(call)
        assertTrue(result is ToolResult.UnknownOutcome)
        assertFalse((result as ToolResult.UnknownOutcome).reason.contains("/data"))
        assertTrue(executor.invoke(call) is ToolResult.UnknownOutcome)
        assertEquals(1, backend.invocations.size, "An unknown outcome is terminal and never redispatched")
    }

    @Test
    fun typedFailureMappingUsesStableCodesWithoutFreeText() = runBlocking {
        val backend = FakeRootBackend(listOf(exposure("file_read_text")), invokeResult = ToolResult.Denied("PERMISSION_DENIED"))
        val executor = RootToolExecutor(specs("file_read_text"), backend)
        val invocation = ToolInvocation.fromRuntime(
            "model-denied",
            "snapshot-1",
            "agent-1",
            "file_read_text",
            """{"workspace_id":"root-ws","relative_path":"a"}""",
        )
        val denied = executor.invoke(invocation)
        assertTrue(denied is ToolExecution.Failed)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, (denied as ToolExecution.Failed).error.code)

        backend.invokeResult = ToolResult.UnknownOutcome("/data/local/tmp/secret")
        val unknown = executor.invoke(
            ToolInvocation.fromRuntime(
                "model-unknown",
                "snapshot-1",
                "agent-1",
                "file_read_text",
                """{"workspace_id":"root-ws","relative_path":"b"}""",
            ),
        )
        assertTrue(unknown is ToolExecution.Unknown)
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, (unknown as ToolExecution.Unknown).error.code)
        assertTrue(unknown.error.message == ToolErrorCode.UNKNOWN_OUTCOME.name)
    }

    private fun specs(vararg names: String): List<ToolSpec> = RootToolArguments.specsFor(names.toList())

    private fun exposure(
        toolName: String,
        workspaceId: String = "root-ws",
        grantId: String = "grant-1",
        writable: Boolean = true,
    ): RootToolExposure = RootToolExposure(
        toolName = toolName,
        capability = requireNotNull(RootApprovalTools.capability(toolName)),
        workspaceId = workspaceId,
        grantId = grantId,
        pathScope = "notes",
        writableWorkspace = writable,
    )
}

private class FakeRootBackend(
    private val tools: List<RootToolExposure>,
    var invokeResult: ToolResult = ToolResult.Value("""{"ok":true}"""),
    var disclosureAllowed: Boolean = true,
) : RootToolBackend {
    val started = CompletableDeferred<Unit>()
    var gate: CompletableDeferred<Unit>? = null
    var failInvoke: Throwable? = null
    val invocations = mutableListOf<RootToolInvocation>()
    val approvals = mutableListOf<String>()
    val rejects = mutableListOf<String>()
    val expiries = mutableListOf<String>()
    val cancels = mutableListOf<String>()
    val disclosures = mutableListOf<Pair<RootToolInvocation, ResultDisclosure>>()

    override fun exposedTools(): List<RootToolExposure> = tools

    override suspend fun invoke(invocation: RootToolInvocation): ToolResult {
        invocations += invocation
        started.complete(Unit)
        gate?.await()
        failInvoke?.let { throw it }
        return invokeResult
    }

    override suspend fun approve(runtimeInvocationId: String): ToolResult {
        approvals += runtimeInvocationId
        return invokeResult
    }

    override suspend fun reject(runtimeInvocationId: String): ToolResult {
        rejects += runtimeInvocationId
        return ToolResult.Denied("APPROVAL_DENIED")
    }

    override suspend fun expire(runtimeInvocationId: String): ToolResult {
        expiries += runtimeInvocationId
        return ToolResult.Denied("TIMEOUT")
    }

    override suspend fun authorizeDisclosure(
        invocation: RootToolInvocation,
        disclosure: ResultDisclosure,
    ): Boolean {
        disclosures += invocation to disclosure
        return disclosureAllowed
    }

    override suspend fun cancel(runtimeInvocationId: String): Boolean {
        cancels += runtimeInvocationId
        return true
    }
}
