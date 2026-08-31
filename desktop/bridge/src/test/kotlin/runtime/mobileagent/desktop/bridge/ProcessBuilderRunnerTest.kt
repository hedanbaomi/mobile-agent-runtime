// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.desktop.bridge

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProcessBuilderRunnerTest {
    @Test
    fun timeoutTerminatesChildAndReportsUnknownOutcome() {
        ProcessBuilderRunner().use { runner ->
            val request = ProcessRequest(
                argv = javaCommand(),
                timeoutMs = 100,
                stdoutCapBytes = 32,
                stderrCapBytes = 32,
            )
            val result = runner.run(request)
            assertEquals(ProcessOutcome.UNKNOWN_OUTCOME, result.outcome)
            assertTrue(result.timedOut)
            assertEquals(null, result.exitCode)
        }
    }

    @Test
    fun cancellationTerminatesChildAndMarksUnknownOutcome() {
        val cancelled = AtomicBoolean(false)
        ProcessBuilderRunner().use { runner ->
            val request = ProcessRequest(
                argv = javaCommand(),
                timeoutMs = 10_000,
                cancelRequested = { cancelled.get() },
            )
            Thread {
                Thread.sleep(100)
                cancelled.set(true)
            }.start()
            val result = runner.run(request)
            assertEquals(ProcessOutcome.UNKNOWN_OUTCOME, result.outcome)
            assertTrue(result.cancelled)
            assertEquals(null, result.exitCode)
        }
    }

    private fun javaCommand(): List<String> = listOf(
        Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").contains("Windows")) "java.exe" else "java").toString(),
        "-cp",
        System.getProperty("java.class.path"),
        "runtime.mobileagent.desktop.bridge.SleepMainKt",
    )
}
