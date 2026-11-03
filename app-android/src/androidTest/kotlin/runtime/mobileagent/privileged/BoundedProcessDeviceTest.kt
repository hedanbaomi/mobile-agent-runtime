// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F8: the manager-command deadline must cover partial multi-byte reads, byte overflow and
 * cancellation. A child that writes one UTF-8 leading byte (0xC3) and then sleeps must not hold
 * the caller past its budget.
 */
@RunWith(AndroidJUnit4::class)
class BoundedProcessDeviceTest {
    @Test
    fun partialUtf8ByteCannotExceedTheDeadline() {
        val started = System.nanoTime()
        val result = BoundedProcess.run(
            command = listOf("/system/bin/sh", "-c", "printf '\\303'; sleep 6"),
            timeoutMs = 2_000L,
            maxBytes = 4_096,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertNull("a partial UTF-8 sequence must not be reported as a valid result", result)
        assertTrue("deadline must bound the call, took ${elapsedMs}ms", elapsedMs < 3_500L)
    }

    @Test
    fun positiveControlStillReadsTheOutput() {
        val result = BoundedProcess.run(
            command = listOf("/system/bin/sh", "-c", "echo mar-bounded-process"),
            timeoutMs = 2_000L,
            maxBytes = 4_096,
        )
        assertEquals("mar-bounded-process", result?.text?.trim())
    }

    @Test
    fun overflowIsRejectedInsteadOfTruncated() {
        val result = BoundedProcess.run(
            command = listOf("/system/bin/sh", "-c", "yes mar-bounded-overflow | head -c 20000"),
            timeoutMs = 2_000L,
            maxBytes = 1_024,
        )
        assertNull("output beyond the byte budget must fail closed", result)
    }

    @Test
    fun cancellationInterruptsTheRead() {
        val cancelled = AtomicBoolean(false)
        val started = System.nanoTime()
        val worker = Thread {
            BoundedProcess.run(
                command = listOf("/system/bin/sh", "-c", "sleep 6"),
                timeoutMs = 5_000L,
                maxBytes = 4_096,
                isCancelled = { cancelled.get() },
            )
        }.apply { isDaemon = true; start() }
        Thread.sleep(200L)
        cancelled.set(true)
        worker.join(2_000L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("cancellation must interrupt the read, took ${elapsedMs}ms", !worker.isAlive && elapsedMs < 3_000L)
    }

    @Test
    fun timeoutStillAppliesToSilentChildren() {
        val started = System.nanoTime()
        val result = BoundedProcess.run(
            command = listOf("/system/bin/sh", "-c", "sleep 6"),
            timeoutMs = 1_000L,
            maxBytes = 4_096,
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertNull(result)
        assertTrue("silent child must be killed at the deadline, took ${elapsedMs}ms", elapsedMs < 2_500L)
    }
}
