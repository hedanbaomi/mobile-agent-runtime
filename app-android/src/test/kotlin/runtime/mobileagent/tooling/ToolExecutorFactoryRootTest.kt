// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolExecutor
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.ToolSpec as LegacyToolSpec
import runtime.mobileagent.skills.tooling.Availability
import runtime.mobileagent.skills.tooling.Connection
import runtime.mobileagent.skills.tooling.PlatformGrant
import runtime.mobileagent.skills.tooling.ToolExecution
import runtime.mobileagent.skills.tooling.ToolInvocation

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
        val factory = ToolExecutorFactory(root = root)
        val snapshotId = factory.beginRun().snapshot.snapshotId
        val invocation = ToolInvocation.fromRuntime(
            callId = "model-call",
            snapshotId = snapshotId,
            agentId = "agent",
            name = "root_file_stat",
            argumentsJson = "{}",
        )
        val execution = factory.invoke(invocation)
        assertTrue(execution is ToolExecution.Value)
        val seen = root.typedCalls.single()
        assertEquals("model-call", seen.callId)
        assertEquals(invocation.requestId, seen.requestId)
    }

    @Test
    fun legacyInvokeAndApprovalRouteToTheRootOwner() = runBlocking {
        val root = FakeRootToolExecutor().apply { legacyResult = ToolResult.NeedsApproval }
        val factory = ToolExecutorFactory(root = root)
        val call = ToolCall("runtime-legacy", "root_file_stat", "{}")
        assertEquals(ToolResult.NeedsApproval, factory.invoke(call))
        val approved = factory.approve("runtime-legacy")
        assertTrue(approved is ToolResult.Value)
        assertEquals(listOf("runtime-legacy"), root.approvals)
    }

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
}

private fun fakeLegacySpec(name: String): LegacyToolSpec =
    LegacyToolSpec(name, "fake $name", """{"type":"object"}""", "", false)

private class FakeRootToolExecutor(
    name: String = "root_file_stat",
    var legacyResult: ToolResult = ToolResult.Value("""{"legacy":true}"""),
) : ToolExecutor, TypedToolExecutor {
    override val specs: List<LegacyToolSpec> = listOf(fakeLegacySpec(name))
    val typedCalls = mutableListOf<ToolInvocation>()
    val approvals = mutableListOf<String>()
    val cancels = mutableListOf<String>()

    override suspend fun invoke(call: ToolCall): ToolResult = legacyResult

    override suspend fun approve(callId: String): ToolResult {
        approvals += callId
        return ToolResult.Value("""{"approved":true}""")
    }

    override suspend fun invoke(invocation: ToolInvocation): ToolExecution {
        typedCalls += invocation
        return ToolExecution.Value("""{"typed":true}""")
    }

    override suspend fun cancel(requestId: String): Boolean {
        cancels += requestId
        return true
    }
}

private class FakeWebToolExecutor(private val name: String) : ToolExecutor {
    override val specs: List<LegacyToolSpec> = listOf(fakeLegacySpec(name))

    override suspend fun invoke(call: ToolCall): ToolResult = ToolResult.Value("""{"web":true}""")

    override suspend fun approve(callId: String): ToolResult = ToolResult.Invalid("not supported")
}
