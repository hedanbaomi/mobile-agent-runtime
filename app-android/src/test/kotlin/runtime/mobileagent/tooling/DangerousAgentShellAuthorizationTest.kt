// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.*

class DangerousAgentShellAuthorizationTest {
    @Test fun globalConsentExposesAndExecutesTheBuiltInAgentToolWithoutAnotherGrant() = runBlocking {
        val f = Fixture()
        val executor = f.executor()
        assertEquals(listOf("shell_exec"), executor.specs.map { it.name })
        assertTrue(executor.invoke(call("one", "pm list packages")) is ToolResult.Value)
        assertEquals(listOf("pm list packages"), f.commands)
    }

    @Test fun standaloneAndSkillExecutorsDoNotAcquireTheAgentConsent() {
        val f = Fixture()
        assertTrue(f.executor(authorizeAgent = false).specs.isEmpty())
        assertTrue(f.executor(skill = true).specs.isEmpty())
    }

    @Test fun disablingAndReenablingConsentCannotReviveAnOldRun() = runBlocking {
        val f = Fixture()
        val old = f.executor()
        f.danger.setPolicy(DangerousMode.DISABLED)
        assertTrue(old.invoke(call("off")) is ToolResult.Denied)
        f.danger.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        assertTrue(old.invoke(call("old")) is ToolResult.Denied)
        assertTrue(f.executor().invoke(call("new")) is ToolResult.Value)
        assertEquals(1, f.commands.size)
    }

    @Test fun anUnexposedRunNeverAcquiresLaterConsent() = runBlocking {
        val f = Fixture(DangerousMode.DISABLED)
        val old = f.executor()
        f.danger.setPolicy(DangerousMode.ENABLED_AUTONOMOUS)
        assertTrue(old.specs.isEmpty())
        assertTrue(old.invoke(call("later")) is ToolResult.Denied)
        assertTrue(f.commands.isEmpty())
    }

    @Test fun livePolicyRevocationAndDisconnectDenyBeforeDispatch() = runBlocking {
        val f = Fixture()
        val executor = f.executor()
        f.liveAuthorization = false
        assertTrue(executor.invoke(call("revoked")) is ToolResult.Denied)
        f.liveAuthorization = true
        f.authority.updateConnection(Authority.SHIZUKU, Connection.DISCONNECTED, "fixture")
        assertTrue(executor.invoke(call("disconnected")) is ToolResult.Denied)
        assertTrue(f.commands.isEmpty())
    }

    @Test fun revocationStopsInFlightShellAndNeverReplaysItsUnknownResult() = runBlocking {
        for (revoke in listOf<(Fixture) -> Unit>(
            { it.danger.setPolicy(DangerousMode.DISABLED) },
            { it.authority.updatePlatformGrant(Authority.SHIZUKU, PlatformGrant.REVOKED) },
            { it.authority.selectAuthority(Authority.WIRED_ADB) },
        )) {
            val f = Fixture()
            f.waitForCancellation = true
            val executor = f.executor()
            val call = call("active")
            val pending = async { executor.invoke(call) }
            withTimeout(2_000) { f.started.await() }
            revoke(f)
            assertTrue(withTimeout(2_000) { pending.await() } is ToolResult.UnknownOutcome)
            assertTrue(f.cancelled.isNotEmpty())
            assertEquals(1, f.commands.size)
            assertTrue(executor.invoke(call) is ToolResult.UnknownOutcome)
            assertFalse(executor.authorizeReplay(call))
        }
    }

    @Test fun confirmHighRiskStillRequiresApprovalAndUnknownIsNeverReexecuted() = runBlocking {
        val f = Fixture(DangerousMode.ENABLED_CONFIRM_HIGH_RISK)
        val executor = f.executor()
        assertEquals(ToolResult.NeedsApproval, executor.invoke(call("high", "rm -rf /data/local/tmp/fixture")))
        assertTrue(f.commands.isEmpty())
        assertTrue(executor.approve("high") is ToolResult.Value)
        assertEquals(1, f.commands.size)
        f.unknown = true
        assertTrue(executor.invoke(call("unknown")) is ToolResult.UnknownOutcome)
        assertTrue(executor.invoke(call("unknown")) is ToolResult.UnknownOutcome)
        assertEquals(2, f.commands.size)
        assertFalse(executor.authorizeReplay(call("unknown")))
    }

    private fun call(id: String, command: String = "pwd") = ToolCall(id, "shell_exec", "{\"command\":\"$command\"}")

    private class Fixture(mode: DangerousMode = DangerousMode.ENABLED_AUTONOMOUS) {
        val commands = mutableListOf<String>()
        var liveAuthorization = true
        var unknown = false
        var waitForCancellation = false
        val started = CompletableDeferred<Unit>()
        val cancelled = mutableListOf<String>()
        val authority = AuthorityManager().apply {
            selectAuthority(Authority.SHIZUKU)
            setUserIntent(Authority.SHIZUKU, true)
            setConfigured(Authority.SHIZUKU, true)
            updatePlatformGrant(Authority.SHIZUKU, PlatformGrant.GRANTED)
            updateAvailability(Authority.SHIZUKU, Availability.READY)
            updateConnection(Authority.SHIZUKU, Connection.CONNECTED, "fixture")
        }
        val danger = DangerousModeManager(InMemoryDangerousModeStateStore(), DangerousBuildPolicy.testOnlyOverride()).apply {
            setPolicy(mode)
        }
        fun executor(authorizeAgent: Boolean = true, skill: Boolean = false): ShellToolExecutor {
            val context = ToolExecutionContext("agent", "snapshot", "model", "session",
                configSnapshotHash = "frozen", policyVersion = 1,
                skillId = if (skill) "skill" else null, trustedSkillEnvelope = skill)
            return ShellToolExecutor(authority, danger, ApprovalEngine(), { context },
                backends = mapOf(Authority.SHIZUKU to object : ShellExecutor {
                    override suspend fun execute(request: ShellExecRequest): ShellExecResult {
                        commands += request.command
                        started.complete(Unit)
                        if (waitForCancellation) awaitCancellation()
                        return if (unknown) ShellExecResult.unknownOutcome(request, 1)
                            else ShellExecResult.succeeded(request, 0, "fixture", "", 1)
                    }
                    override suspend fun cancel(requestId: String): Boolean {
                        cancelled += requestId
                        return true
                    }
                }),
                auditSink = object : ShellAuditSink {
                    override suspend fun recordStarted(event: ShellAuditEvent) = true
                    override suspend fun recordCompleted(event: ShellAuditEvent) = true
                },
                agentShellAuthorization = { authorizeAgent && liveAuthorization })
        }
    }
}
