// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.SnapshotGrantBinding
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

    @Test
    fun individualGrantRevocationStopsInFlightBackendAndPreservesUnknown() = runBlocking {
        val fixture = Fixture(grant)
        fixture.waitForCancellation = true
        val executor = fixture.executor()
        val pending = async { executor.invoke(ToolCall("active", "shell_exec", "{\"command\":\"pwd\"}")) }
        withTimeout(2_000) { fixture.started.await() }
        fixture.live = grant.copy(revokedAt = "2026-01-01T00:00:00Z", revision = 2)
        fixture.changes.value++
        assertTrue(withTimeout(2_000) { pending.await() } is ToolResult.UnknownOutcome)
        assertTrue(fixture.cancellations > 0)
        assertEquals(1, fixture.dispatches)
    }

    @Test
    fun onceConsumptionDoesNotCancelItsOwnRequestButLaterRevocationDoes() = runBlocking {
        val fixture = Fixture(grant.copy(lifetime = GrantLifetime.ONCE))
        fixture.waitForCancellation = true
        val executor = fixture.executor(bindOnce = true)
        val pending = async { executor.invoke(ToolCall("once", "shell_exec", "{\"command\":\"pwd\"}")) }
        withTimeout(2_000) { fixture.started.await() }
        assertNotNull(fixture.live.consumedAt)
        assertEquals(0, fixture.cancellations)
        fixture.live = fixture.live.copy(revokedAt = "2026-10-10T00:00:00Z", revision = 3)
        fixture.changes.value++
        assertTrue(withTimeout(2_000) { pending.await() } is ToolResult.UnknownOutcome)
        assertTrue(fixture.cancellations > 0)
    }

    @Test
    fun expiringGrantStopsInFlightBackendWithoutRepositoryPolling() = runBlocking {
        val fixture = Fixture(grant.copy(expiresAt = java.time.Instant.now().plusMillis(500).toString()))
        fixture.waitForCancellation = true
        val executor = fixture.executor()
        val pending = async { executor.invoke(ToolCall("expires", "shell_exec", "{\"command\":\"pwd\"}")) }
        withTimeout(2_000) { fixture.started.await() }
        assertTrue(withTimeout(2_000) { pending.await() } is ToolResult.UnknownOutcome)
        assertTrue(fixture.cancellations > 0)
    }

    private inner class Fixture(var live: CapabilityGrant) {
        var dispatches = 0
        var cancellations = 0
        var waitForCancellation = false
        val started = CompletableDeferred<Unit>()
        val changes = MutableStateFlow(0L)
        fun executor(frozen: List<CapabilityGrant> = listOf(live), bindOnce: Boolean = false): ShellToolExecutor {
            val bindings = frozen.filter { bindOnce && it.lifetime == GrantLifetime.ONCE }.map {
                SnapshotGrantBinding("old-snapshot", it.grantId, it.capability, policyVersion = 1)
            }
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
                snapshotGrantBindings = bindings,
            )
            return ShellToolExecutor(
                authorityManager = authority, dangerousModeManager = danger,
                approvalEngine = ApprovalEngine(), contextProvider = { context },
                resolver = EffectiveCapabilityResolver(grants = CapabilityGrantReader { _, _ -> listOf(live) },
                    bindings = SnapshotGrantBindingReader { bindings }),
                backends = mapOf(Authority.SHIZUKU to object : ShellExecutor {
                    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                        dispatches++
                        started.complete(Unit)
                        if (waitForCancellation) awaitCancellation()
                        return ShellExecResult.succeeded(request, 0, "ok", "", 1)
                    }
                    override suspend fun cancel(requestId: String): Boolean {
                        cancellations++
                        return true
                    }
                }),
                auditSink = object : ShellAuditSink {
                    override suspend fun recordStarted(event: ShellAuditEvent) = true
                    override suspend fun recordCompleted(event: ShellAuditEvent) = true
                },
                grantChanges = changes,
                onceGrantConsumer = { consumed ->
                    if (live != consumed) false else {
                        live = consumed.copy(revision = consumed.revision + 1, consumedAt = java.time.Instant.now().toString())
                        changes.value++
                        true
                    }
                },
            )
        }
    }
}
