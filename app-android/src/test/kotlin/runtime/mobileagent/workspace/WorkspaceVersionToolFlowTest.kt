// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.SnapshotGrantBinding
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation
import runtime.mobileagent.tooling.ApprovalEngine
import runtime.mobileagent.tooling.ToolExecutionContext
import runtime.mobileagent.tooling.UnifiedWorkspaceToolExecutor
import runtime.mobileagent.tooling.WorkspaceAuditEvent
import runtime.mobileagent.tooling.WorkspaceAuditSink
import runtime.mobileagent.tooling.WorkspaceRegistry

/**
 * Minimal real vertical regression for the workspace version wire contract.
 *
 * The calls cross InternalWorkspaceBackend, SharedWorkspaceBackendAdapter and
 * UnifiedWorkspaceToolExecutor.  Version values are extracted as JsonElement
 * and put back into later model JSON unchanged, so the test exercises the
 * actual JSON boundary as well as the full internal CAS token binding.
 */
class WorkspaceVersionToolFlowTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun executorRoundTripsHighBitVersionThroughJsonAndRejectsStaleMutations(): Unit = runBlocking {
        val harness = harness(tempDir.resolve("version-flow"))
        val testDigest = MessageDigest.getInstance("SHA-256")
            .digest("test".toByteArray(StandardCharsets.UTF_8))
        assertEquals(0x80, testDigest[0].toInt() and 0x80, "test must exercise a SHA-256 high bit")

        val created = invoke(
            harness,
            "model-version-create",
            UnifiedWorkspaceToolExecutor.FILE_WRITE_TEXT,
            writeArguments(text = "test", replace = false),
        )
        val createdVersion = version(created)

        val stated = invoke(
            harness,
            "model-version-stat",
            UnifiedWorkspaceToolExecutor.FILE_STAT,
            fileArguments(),
        )
        assertEquals(createdVersion, version(stated))

        val read = invoke(
            harness,
            "model-version-read",
            UnifiedWorkspaceToolExecutor.FILE_READ_TEXT,
            fileArguments(),
        )
        assertEquals("test", json(read).getValue("text").jsonPrimitive.content)
        assertEquals(createdVersion, version(read))

        val listed = invoke(
            harness,
            "model-version-list",
            UnifiedWorkspaceToolExecutor.FILE_LIST,
            buildJsonObject {
                put("workspace_id", WORKSPACE_ID)
            }.toString(),
        )
        val listedFile = json(listed).getValue("entries").jsonArray.single { entry ->
            entry.jsonObject.getValue("relative_path").jsonPrimitive.content == "note.txt"
        }
        assertEquals(createdVersion, listedFile.jsonObject.getValue("version"))

        val replaced = invoke(
            harness,
            "model-version-replace",
            UnifiedWorkspaceToolExecutor.FILE_WRITE_TEXT,
            writeArguments(text = "test-updated", replace = true, expectedVersion = createdVersion),
        )
        val replacedVersion = version(replaced)
        assertTrue(replacedVersion != createdVersion)

        val staleWrite = invoke(
            harness,
            "model-version-stale-write",
            UnifiedWorkspaceToolExecutor.FILE_WRITE_TEXT,
            writeArguments(text = "stale-write", replace = true, expectedVersion = createdVersion),
        )
        assertFailure(staleWrite, ToolErrorCode.CONFLICT)
        assertEquals("test-updated", storedText(harness.root))

        val patched = invoke(
            harness,
            "model-version-patch",
            UnifiedWorkspaceToolExecutor.FILE_APPLY_PATCH,
            patchArguments(patch = "test-patched", expectedVersion = replacedVersion),
        )
        val patchedVersion = version(patched)
        assertTrue(patchedVersion != replacedVersion)

        val stalePatch = invoke(
            harness,
            "model-version-stale-patch",
            UnifiedWorkspaceToolExecutor.FILE_APPLY_PATCH,
            patchArguments(patch = "stale-patch", expectedVersion = replacedVersion),
        )
        assertFailure(stalePatch, ToolErrorCode.CONFLICT)
        assertEquals("test-patched", storedText(harness.root))
    }

    @Test
    fun committedWriteWithUnprojectableVersionIsUnknownAndContentRemainsCommitted(): Unit = runBlocking {
        val root = tempDir.resolve("unknown-outcome-flow")
        val realBackend = InternalWorkspaceBackend(root, workspaceId = WORKSPACE_ID)
        val faultyBackend = VersionProjectionFaultBackend(realBackend)
        val harness = harness(root, faultyBackend)

        val result = invoke(
            harness,
            "model-version-unknown",
            UnifiedWorkspaceToolExecutor.FILE_WRITE_TEXT,
            writeArguments(text = "test", replace = false),
        )

        assertTrue(result is ToolExecution.Unknown, "a committed write with no projected version is ambiguous")
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, (result as ToolExecution.Unknown).error.code)
        assertEquals("test", storedText(root))
    }

    private data class Harness(
        val root: Path,
        val context: ToolExecutionContext,
        val executor: UnifiedWorkspaceToolExecutor,
    )

    private fun harness(
        root: Path,
        backend: InternalWorkspaceBackendApi = InternalWorkspaceBackend(root, workspaceId = WORKSPACE_ID),
    ): Harness {
        Files.createDirectories(root)
        val adapter = SharedWorkspaceBackendAdapter(backend)
        val registry = WorkspaceRegistry()
        assertTrue(registry.register(adapter.descriptor, adapter))
        val context = workspaceContext()
        val executor = UnifiedWorkspaceToolExecutor(
            registry = registry,
            approvalEngine = ApprovalEngine(),
            contextProvider = { context },
            auditSink = acceptingAuditSink(),
        )
        return Harness(root, context, executor)
    }

    private fun workspaceContext(): ToolExecutionContext {
        val grants = listOf(
            grant("grant-version-list", CapabilityId(CapabilityId.FILE_LIST), null),
            grant("grant-version-stat", CapabilityId(CapabilityId.FILE_STAT), NOTE_PATH),
            grant("grant-version-read", CapabilityId(CapabilityId.FILE_READ_TEXT), NOTE_PATH),
            grant("grant-version-write", CapabilityId(CapabilityId.FILE_WRITE_TEXT), NOTE_PATH),
            grant("grant-version-patch", CapabilityId("file.apply_patch"), NOTE_PATH),
        )
        val snapshotId = "snapshot-version-flow"
        return ToolExecutionContext(
            agentId = AGENT_ID,
            snapshotId = snapshotId,
            modelCallId = "model-version-flow",
            sessionIdentity = "session-version-flow",
            configSnapshotHash = "config-version-flow",
            policyVersion = POLICY_VERSION,
            effectiveCapabilities = grants.map { it.capability }.toSet(),
            canonicalGrants = grants,
            snapshotGrantBindings = grants.map { grant ->
                SnapshotGrantBinding(
                    snapshotId = snapshotId,
                    grantId = grant.grantId,
                    capability = grant.capability,
                    workspaceId = grant.workspaceId,
                    pathScope = grant.pathScope,
                    policyVersion = grant.policyVersion,
                )
            },
        )
    }

    private fun grant(grantId: String, capability: CapabilityId, pathScope: String?): CapabilityGrant =
        CapabilityGrant(
            grantId = grantId,
            agentId = AGENT_ID,
            capability = capability,
            workspaceId = WORKSPACE_ID,
            pathScope = pathScope,
            policyVersion = POLICY_VERSION,
            revision = 1,
        )

    private suspend fun invoke(
        harness: Harness,
        callId: String,
        name: String,
        argumentsJson: String,
    ): ToolExecution = harness.executor.invoke(
        ToolInvocation.fromRuntime(
            callId = callId,
            snapshotId = harness.context.snapshotId,
            agentId = harness.context.agentId,
            name = name,
            argumentsJson = argumentsJson,
        ),
        harness.context,
    )

    private fun fileArguments(): String = buildJsonObject {
        put("workspace_id", WORKSPACE_ID)
        put("relative_path", NOTE_PATH)
    }.toString()

    private fun writeArguments(
        text: String,
        replace: Boolean,
        expectedVersion: JsonElement? = null,
    ): String = buildJsonObject {
        put("workspace_id", WORKSPACE_ID)
        put("relative_path", NOTE_PATH)
        put("text", text)
        put("replace", replace)
        expectedVersion?.let { put("expected_version", it) }
    }.toString()

    private fun patchArguments(patch: String, expectedVersion: JsonElement): String = buildJsonObject {
        put("workspace_id", WORKSPACE_ID)
        put("relative_path", NOTE_PATH)
        put("patch", patch)
        put("format", "replace")
        put("expected_version", expectedVersion)
    }.toString()

    private fun json(result: ToolExecution): JsonObject = when (result) {
        is ToolExecution.Value -> Json.parseToJsonElement(result.json).jsonObject
        is ToolExecution.Failed -> fail("expected JSON value but got ${result.error.code}")
        is ToolExecution.Unknown -> fail("expected JSON value but got ${result.error.code}")
    }

    /** Keep the JsonElement itself for the next request; only validate its wire shape here. */
    private fun version(result: ToolExecution): JsonElement {
        val value = json(result).getValue("version")
        assertFalse(value.jsonPrimitive.isString)
        val number = value.jsonPrimitive.longOrNull
        assertTrue(number != null && number >= 0L, "version must be a non-negative JSON integer")
        return value
    }

    private fun assertFailure(result: ToolExecution, expected: ToolErrorCode) {
        assertTrue(result is ToolExecution.Failed, "expected $expected but got $result")
        assertEquals(expected, (result as ToolExecution.Failed).error.code)
    }

    private fun storedText(root: Path): String =
        String(Files.readAllBytes(root.resolve(NOTE_PATH)), StandardCharsets.UTF_8)

    private fun acceptingAuditSink(): WorkspaceAuditSink = object : WorkspaceAuditSink {
        override suspend fun record(event: WorkspaceAuditEvent): Boolean = true
    }

    /** Changes only the post-write token returned to the adapter. */
    private class VersionProjectionFaultBackend(
        private val delegate: InternalWorkspaceBackend,
    ) : InternalWorkspaceBackendApi by delegate {
        override fun write(
            relativePath: String,
            content: ByteArray,
            expectedVersion: String?,
            replaceExisting: Boolean,
        ): InternalWorkspaceResult<InternalWorkspaceWrite> = when (
            val result = delegate.write(relativePath, content, expectedVersion, replaceExisting)
        ) {
            is InternalWorkspaceResult.Failure -> result
            is InternalWorkspaceResult.Success -> InternalWorkspaceResult.Success(
                result.value.copy(version = "not-a-hex-version"),
            )
        }
    }

    private companion object {
        const val AGENT_ID = "agent-version-flow"
        const val WORKSPACE_ID = "workspace-version-flow"
        const val NOTE_PATH = "note.txt"
        const val POLICY_VERSION = 1L
    }
}
