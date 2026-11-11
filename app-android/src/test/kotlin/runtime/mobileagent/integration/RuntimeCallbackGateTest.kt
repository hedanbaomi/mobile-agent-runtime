// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.integration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.launch

class RuntimeCallbackGateTest {
    @Test fun interruptedCloseStillDrainsCoroutinesAndReleasesResources() {
        val gate = RuntimeCallbackGate()
        val job = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val interruptionRestored = java.util.concurrent.atomic.AtomicBoolean()
        val child = scope.launch { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val closer = Thread {
            Thread.currentThread().interrupt()
            retireRuntimeCallbacks(gate, job) { disposed.countDown() }
            interruptionRestored.set(Thread.currentThread().isInterrupted)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closer.start()
            assertFalse(disposed.await(100, TimeUnit.MILLISECONDS))
            closer.interrupt()
            release.countDown()
            closer.join(5000)
            assertFalse(closer.isAlive)
            assertTrue(child.isCompleted)
            assertEquals(0L, disposed.count)
            assertTrue(interruptionRestored.get())
        } finally { release.countDown(); closer.join(5000); job.cancel() }
    }

    @Test fun closeDrainsAdmittedCallbacksAndRejectsLateCallbacks() {
        val gate = RuntimeCallbackGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val callback = Thread { gate.run { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val closer = Thread { gate.closeAndDrain(); closed.countDown() }
        try {
            callback.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closer.start()
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            gate.run { fail("Late callback touched closed storage") }
        } finally { release.countDown(); callback.join(5000); closer.join(5000) }
    }
}
