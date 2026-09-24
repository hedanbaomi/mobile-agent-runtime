// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.diagnostics

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * R2 QA P2: a terminal run used to log only `state`/`reasonCode`, so a stream
 * truncation, a malformed provider response and a tool-validation failure were
 * indistinguishable from the log alone.  `run_state.failureCode` carries the
 * closed `MessageErrorCode` classification next to the terminal state.
 */
class RunStateFailureCodeTest {
    private class Preferences : DiagnosticPreferenceStore {
        private var enabled = true
        override fun isEnabled(): Boolean = enabled
        override fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    private fun store(root: File): RollingDiagnosticLogStore = RollingDiagnosticLogStore(
        rootDirectory = root,
        preferences = Preferences(),
        buildInfo = DiagnosticBuildInfo("test-revision", false, 26, "2026-09-23T00:00:00Z", "test-fingerprint"),
        sessionId = "session",
        nowUtc = { "2026-09-23T00:00:00.000Z" },
        processId = { 1 },
        threadName = { "main" },
    )

    private fun runStateLine(root: File): String =
        File(root, RollingDiagnosticLogStore.CURRENT_FILE_NAME).readLines()
            .single { it.contains("\"event\":\"run_state\"") }

    @Test
    fun terminalRunCarriesClosedFailureClassification(@TempDir root: File) {
        val recorded = store(root).recordRunState(
            RunStateRecord(
                state = DiagnosticTerminalState.FAILED,
                requestRef = "run-ref",
                sessionRef = "session-ref",
                reasonCode = "failed",
                failureCode = "INVALID_RESPONSE",
                modelRounds = 2,
                toolCalls = 1,
            ),
        )

        assertTrue(recorded)
        val line = runStateLine(root)
        assertTrue("\"failureCode\":\"INVALID_RESPONSE\"" in line, line)
        assertTrue("\"modelRounds\":2" in line, line)
    }

    @Test
    fun unknownFailureCodeOmitsTheFieldWithoutDroppingTheEvent(@TempDir root: File) {
        val recorded = store(root).recordRunState(
            RunStateRecord(
                state = DiagnosticTerminalState.FAILED,
                reasonCode = "failed",
                failureCode = "NOT_A_MESSAGE_CODE",
            ),
        )

        assertTrue(recorded, "an unknown classification must not drop the whole terminal event")
        val line = runStateLine(root)
        assertFalse("failureCode" in line, line)
        assertTrue("\"state\":\"failed\"" in line, line)
    }

    @Test
    fun watchdogTimeoutIsClassifiedAsTimeout(@TempDir root: File) {
        store(root).recordRunState(
            RunStateRecord(
                state = DiagnosticTerminalState.TIMED_OUT,
                reasonCode = "watchdog_timeout",
                failureCode = "TIMEOUT",
            ),
        )

        val line = runStateLine(root)
        assertTrue("\"failureCode\":\"TIMEOUT\"" in line, line)
        assertTrue("\"reasonCode\":\"WATCHDOG_TIMEOUT\"" in line, line)
    }
}
