// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.*

class ShellRunGrantExposureTest {
    private val shell = CapabilityId(CapabilityId.SHELL_EXECUTE)
    private val grant = CapabilityGrant(
        grantId = "fresh-shell", agentId = "agent", capability = shell,
        lifetime = GrantLifetime.PERSISTENT, policyVersion = 1, revision = 1,
    )

    @Test
    fun freshPersistentGrantExposesAndDispatchesWithoutHistoricalSnapshotBinding() = runBlocking {
        val fixture = Fixture(grant)
        val executor = fixture.executor()
        assertEquals(listOf("shell_exec"), executor.specs.map { it.name })
        assertTrue(executor.invoke(ToolCall("call", "shell_exec", "{\"command\":\"pwd\"}")) is ToolResult.Value)
        assertEquals(1, fixture.dispatches)
    }

    @Test
    fun currentRunDoesNotAcquireGrantCreatedAfterPreparation() {
        val fixture = Fixture(grant)
        val executor = fixture.executor(frozen = emptyList())
        assertTrue(executor.specs.isEmpty())
        fixture.live = grant.copy(revision = 2)
        assertTrue(executor.specs.isEmpty())
    }

    @Test
    fun revokedExpiredWrongOwnerAndPolicyDoNotExpose() {
        listOf(
            grant.copy(revokedAt = "2026-01-01T00:00:00Z"),
            grant.copy(expiresAt = "2026-01-01T00:00:00Z"),
            grant.copy(agentId = "other"),
            grant.copy(policyVersion = 2),
            grant.copy(lifetime = GrantLifetime.ONCE),
            grant.copy(lifetime = GrantLifetime.TASK, taskId = "other"),
            grant.copy(lifetime = GrantLifetime.SESSION, sessionId = "other"),
        ).forEach { candidate ->
            assertTrue(Fixture(candidate).executor().specs.isEmpty(), candidate.toString())
        }
    }

    @Test
    fun revocationAfterExposureDeniesBeforeBackend() = runBlocking {
        val fixture = Fixture(grant)
        val executor = fixture.executor()
        assertEquals(1, executor.specs.size)
        fixture.live = grant.copy(revokedAt = "2026-01-01T00:00:00Z", revision = 2)
        assertFalse(executor.invoke(ToolCall("revoked", "shell_exec", "{\"command\":\"pwd\"}")) is ToolResult.Value)
        assertEquals(0, fixture.dispatches)
    }

    private inner class Fixture(var live: CapabilityGrant) {
        var dispatches = 0
        fun executor(frozen: List<CapabilityGrant> = listOf(live)): ShellToolExecutor {
            val authority = AuthorityManager().apply {
                selectAuthority(Authority.SHIZUKU)
                setUserIntent(Authority.SHIZUKU, true)
                setConfigured(Authority.SHIZUKU, true)
                updatePlatformGrant(Authority.SHIZUKU, PlatformGrant.GRANTED)
                updateAvailability(Authority.SHIZUKU, Availability.READY)
                updateConnection(Authority.SHIZUKU, Connection.CONNECTED, "test")
            }
            val danger = DangerousModeManager(InMemoryDangerousModeStateStore(), DangerousBuildPolicy.testOnlyOverride()).apply {
                setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
            }
            val context = ToolExecutionContext(
                agentId = "agent", snapshotId = "old-snapshot", modelCallId = "model",
                sessionIdentity = "session", configSnapshotHash = "config", policyVersion = 1,
                effectiveCapabilities = setOf(shell), canonicalGrants = frozen,
            )
            return ShellToolExecutor(
                authorityManager = authority, dangerousModeManager = danger,
                approvalEngine = ApprovalEngine(), contextProvider = { context },
                resolver = EffectiveCapabilityResolver(grants = CapabilityGrantReader { _, _ -> listOf(live) }),
                backends = mapOf(Authority.SHIZUKU to object : ShellExecutor {
                    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                        dispatches++
                        return ShellExecResult.succeeded(request, 0, "ok", "", 1)
                    }
                    override suspend fun cancel(requestId: String) = true
                }),
                auditSink = object : ShellAuditSink {
                    override suspend fun recordStarted(event: ShellAuditEvent) = true
                    override suspend fun recordCompleted(event: ShellAuditEvent) = true
                },
            )
        }
    }
}
