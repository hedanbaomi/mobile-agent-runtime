// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor

/**
 * SAF liveness probe regressions (round-2 review):
 *  - a wedged provider must not pin single-flight state forever: the probe
 *    deadline abandons the result, clears in-flight, and the workspace is
 *    marked transient-down rather than staying ACTIVE forever;
 *  - transient-down is recoverable: a later healthy probe clears it and
 *    bumps the health revision — no unregister, no WORKSPACE_NOT_FOUND;
 *  - a stale probe result applies only while the registry still holds the
 *    same backend instance, never a replacement bound after launch.
 */
class SafLivenessProbeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun backend(enabled: () -> Boolean) = object : WorkspaceBackend {
        override val descriptor: WorkspaceDescriptor
            get() = WorkspaceDescriptor(
                id = "w", displayName = "w",
                backendType = WorkspaceBackendType.SAF_TREE,
                enabled = enabled(),
            )
    }

    private fun probe(
        registered: (String, WorkspaceBackend) -> Boolean = { _, _ -> true },
        intervalMs: Long = 0L,
        deadlineMs: Long = 100L,
        maxWorkers: Int = 4,
        now: () -> Long = { System.currentTimeMillis() },
    ) = SafLivenessProbe(
        scope = scope,
        isRegisteredBackend = registered,
        probeIntervalMs = intervalMs,
        probeDeadlineMs = deadlineMs,
        maxWorkers = maxWorkers,
        now = now,
    )

    @Test
    fun `a dead provider marks the workspace down and bumps the revision`() {
        val probe = probe()
        val b = backend { false }
        probe.schedule("w1", b)
        awaitUntil { probe.isDown("w1", b) }
        assertTrue(probe.isDown("w1", b))
        assertEquals(1L, probe.revision.value)
    }

    @Test
    fun `a wedged provider clears in-flight and marks down at the deadline`() {
        val latch = CountDownLatch(1) // never released — provider never answers
        val probe = probe(deadlineMs = 60L)
        val b = backend { latch.await(10, TimeUnit.SECONDS); false }
        probe.schedule("w1", b)
        awaitUntil { probe.isDown("w1", b) }
        // In-flight must be cleared even though the worker still lingers;
        // a re-schedule must actually run again rather than being dropped.
        probe.schedule("w1", b)
        awaitUntil { probe.isDown("w1", b) }
        assertEquals(1L, probe.revision.value)
    }

    @Test
    fun `transient-down recovers to healthy without re-registering`() {
        var live = false
        val probe = probe(intervalMs = 0L)
        val b = backend { live }
        probe.schedule("w1", b)
        awaitUntil { probe.isDown("w1", b) }
        live = true
        probe.schedule("w1", b)
        awaitUntil { !probe.isDown("w1", b) && probe.revision.value == 2L }
        assertFalse(probe.isDown("w1", b))
        assertEquals(2L, probe.revision.value)
    }

    @Test
    fun `a stale probe never applies to a replacement backend`() {
        val registered = java.util.concurrent.atomic.AtomicReference<WorkspaceBackend>()
        val release = CountDownLatch(1)
        val old = backend { release.await(10, TimeUnit.SECONDS); false }
        val fresh = backend { true }
        registered.set(old)
        val probe = probe(registered = { _, backend -> registered.get() === backend })
        probe.schedule("w1", old)
        // Swap in the replacement strictly BEFORE the old result can land —
        // the worker is held on the latch until the swap is done.
        registered.set(fresh)
        release.countDown()
        awaitQuietly()
        assertFalse(probe.isDown("w1", old))
        assertFalse(probe.isDown("w1", fresh))
        assertEquals(0L, probe.revision.value)
    }

    @Test
    fun `a down verdict self-rearms until the provider recovers`() {
        var live = false
        val probe = probe(intervalMs = 60L, deadlineMs = 100L)
        val b = backend { live }
        probe.schedule("w1", b)
        awaitUntil { probe.isDown("w1", b) }
        // No further schedule() calls — the armed retry itself must observe
        // the recovery, clear the verdict and bump the revision.
        live = true
        awaitUntil { !probe.isDown("w1", b) && probe.revision.value == 2L }
        assertFalse(probe.isDown("w1", b))
        assertEquals(2L, probe.revision.value)
    }

    @Test
    fun `a saturated pool only delays recovery — the retry loop keeps probing`() {
        // One wedged worker holds the only permit while w2 goes down; the
        // retry loop must not die on pool saturation — once the permit frees,
        // the next interval probes again and clears the verdict.
        val hold = CountDownLatch(1)
        val wedged = backend {
            while (true) {
                try {
                    hold.await()
                    break
                } catch (_: InterruptedException) {
                }
            }
            true
        }
        var live = false
        val probe = probe(intervalMs = 40L, deadlineMs = 30L, maxWorkers = 1)
        probe.schedule("w1", wedged)
        val b = backend { live }
        Thread.sleep(60) // let w1 take the only permit slot
        probe.schedule("w2", b)
        awaitUntil { probe.isDown("w2", b) }
        live = true
        // Without any further schedule() calls the armed loop must survive
        // the saturated pool and still observe the recovery.
        hold.countDown()
        awaitUntil { !probe.isDown("w2", b) && probe.revision.value >= 2L }
        assertFalse(probe.isDown("w2", b))
    }

    @Test
    fun `consecutive fast failures keep the same retry loop alive`() {
        // Every probe fails several times in a row; the loop armed by the
        // first verdict must not be consumed by in-flight rejections or
        // re-armed jobs — it keeps probing until the provider recovers.
        var live = false
        var calls = 0
        val probe = probe(intervalMs = 40L, deadlineMs = 100L)
        val b = backend { calls++; live }
        probe.schedule("w1", b)
        awaitUntil { probe.isDown("w1", b) && calls >= 2 }
        live = true
        awaitUntil { !probe.isDown("w1", b) && probe.revision.value == 2L }
        assertFalse(probe.isDown("w1", b))
        assertTrue(calls >= 3)
    }

    @Test
    fun `a same-id replacement exits the old backend's retry timer`() {
        // Old backend goes down and arms its loop; a replacement registered
        // under the same id must get its own loop — the stale one exits
        // instead of probing a backend that is no longer bound.
        val registered = java.util.concurrent.atomic.AtomicReference<WorkspaceBackend>()
        val old = backend { false }
        val fresh = backend { true }
        registered.set(old)
        val probe = probe(
            registered = { _, backend -> registered.get() === backend },
            intervalMs = 40L,
            deadlineMs = 60L,
        )
        probe.schedule("w1", old)
        awaitUntil { probe.isDown("w1", old) }
        registered.set(fresh)
        probe.schedule("w1", fresh)
        // The replacement is healthy on its own probe — the down entry stays
        // keyed to the old instance (isDown compares identity, so it cannot
        // leak onto fresh), and the old backend's timer self-exits instead of
        // probing a backend that is no longer bound.
        awaitUntil { probe.retryJobs["w1"]?.isActive != true }
        assertFalse(probe.isDown("w1", fresh))
        assertEquals(1L, probe.revision.value)
    }

    @Test
    fun `wedged workers still obey the maxWorkers cap`() {
        val running = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val release = CountDownLatch(1)
        val wedged = backend {
            val n = running.incrementAndGet()
            peak.updateAndGet { maxOf(it, n) }
            try {
                // Ignore interruption: the provider never answers and never
                // honours cancellation — the permit must track this worker,
                // not the decision deadline.
                while (true) {
                    try {
                        release.await()
                        break
                    } catch (_: InterruptedException) {
                    }
                }
                true
            } finally {
                running.decrementAndGet()
            }
        }
        val probe = probe(intervalMs = 0L, deadlineMs = 60L, maxWorkers = 2)
        val ids = (1..6).map { "w$it" }
        // Repeated scheduling after each deadline must never spawn more than
        // maxWorkers live probe workers.
        repeat(3) {
            ids.forEach { probe.schedule(it, wedged) }
            Thread.sleep(140)
        }
        assertEquals(2, peak.get())
        assertEquals(2, running.get())
        release.countDown()
    }

    private fun awaitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
    }

    private fun awaitQuietly() = Thread.sleep(300)
}
