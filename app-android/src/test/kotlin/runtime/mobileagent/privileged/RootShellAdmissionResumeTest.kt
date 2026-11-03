// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.RootServiceTerminalOutcome
import runtime.mobileagent.skills.tooling.ShellExecRequest
import runtime.mobileagent.skills.tooling.ShellExecutionStatus
import runtime.mobileagent.skills.tooling.ShellLimits

class RootShellAdmissionResumeTest {
    @Test
    fun unprovenProductionRunnerRejectsBeforeSpawningEvenAnInnocuousCommand() {
        val spawns = AtomicInteger()
        val observer = RootShellGroupRunner(
            killGroup = {}, killPid = {}, settleBudgetMs = 0L,
            childPidResolver = { spawns.incrementAndGet(); -1 },
        )
        val runner = Uid0ProcessGroupShellRunner(observer)
        val request = ShellExecRequest.fromRuntime(
            callId = "model-call", command = "exit 0", cwd = null,
            limits = ShellLimits(timeoutMs = 1000L, maxOutputBytes = 1024L),
            agentId = "agent", snapshotId = "snapshot", selectedAuthority = Authority.ROOT_DIRECT,
            dangerousMode = DangerousMode.ENABLED_CONFIRM_HIGH_RISK, policyVersion = 1L,
            configSnapshotHash = "a".repeat(64), sessionIdentity = "session", toolSchemaVersion = 1,
        )
        val result = runner.runCommand(Authority.ROOT_DIRECT, request)
        assertEquals(0, spawns.get(), "An unproven supervisor must reject before starting any command")
        assertEquals(ShellExecutionStatus.FAILED, result.status)
        assertEquals(RootServiceTerminalOutcome.FAILED, result.terminalOutcome)
        assertFalse(result.requiresDegradedEpoch, "Pre-spawn rejection has no uncertain side effect")
        assertEquals("", result.stdout)
        assertTrue(runner.terminateAll(0L).isEmpty())
    }
}
