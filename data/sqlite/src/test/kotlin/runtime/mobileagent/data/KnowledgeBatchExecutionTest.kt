// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KnowledgeBatchExecutionTest {
    @Test fun interruptedCallerCannotReleaseAnUnfinishedLane() {
        val execution = KnowledgeBatchExecution()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val caller = Thread {
            try {
                execution.run("interrupted", { 1 }) {
                    entered.countDown()
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { /* simulate a slow native exit */ }
                    }
                }
            } catch (_: InterruptedException) { } finally { exited.countDown() }
        }
        try {
            caller.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            assertTrue(execution.isActive("interrupted"))
            assertFalse(execution.run("interrupted", { error("overlapping recovery") }) {})
        } finally {
            release.countDown()
            caller.join(5000)
        }
    }

    @Test fun duplicateDeliveryCannotStartWhileTheOriginalOwnsWorkers() {
        val execution = KnowledgeBatchExecution()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Boolean> { execution.run("batch", { 2 }) { retry ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                retry.set(true)
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(execution.isActive("batch"))
            assertFalse(execution.run("batch", { error("duplicate planned") }) { error("duplicate dispatched") })
            release.countDown()
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertFalse(execution.isActive("batch"))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun failedPreparationAndExecutionReleaseOwnershipForExplicitRetry() {
        val execution = KnowledgeBatchExecution()
        assertThrows(IllegalStateException::class.java) { execution.run("b", { error("prepare") }) {} }
        assertFalse(execution.isActive("b"))
        assertThrows(IllegalStateException::class.java) { execution.run("b", { 1 }) { error("work") } }
        assertFalse(execution.isActive("b"))
        assertFalse(execution.run("b", { 1 }) {})
    }
}
