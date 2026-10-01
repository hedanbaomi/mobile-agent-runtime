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
        now: () -> Long = { System.currentTimeMillis() },
    ) = SafLivenessProbe(
        scope = scope,
        isRegisteredBackend = registered,
        probeIntervalMs = intervalMs,
        probeDeadlineMs = deadlineMs,
        now = now,
    )

    @Test
    fun `a dead provider marks the workspace down and bumps the revision`() {
        val probe = probe()
        val b = backend { false }
        probe.schedule("w1", b)
        awaitUntil { "w1" in probe.down }
        assertTrue("w1" in probe.down)
        assertEquals(1L, probe.revision.value)
    }

    @Test
    fun `a wedged provider clears in-flight and marks down at the deadline`() {
        val latch = CountDownLatch(1) // never released — provider never answers
        val probe = probe(deadlineMs = 60L)
        val b = backend { latch.await(10, TimeUnit.SECONDS) }
        probe.schedule("w1", b)
        awaitUntil { "w1" in probe.down }
        // In-flight must be cleared even though the worker still lingers;
        // a re-schedule must actually run again rather than being dropped.
        probe.schedule("w1", b)
        awaitUntil { "w1" in probe.down }
        assertEquals(1L, probe.revision.value)
    }

    @Test
    fun `transient-down recovers to healthy without re-registering`() {
        var live = false
        val probe = probe(intervalMs = 0L)
        val b = backend { live }
        probe.schedule("w1", b)
        awaitUntil { "w1" in probe.down }
        live = true
        probe.schedule("w1", b)
        awaitUntil { "w1" !in probe.down && probe.revision.value == 2L }
        assertFalse("w1" in probe.down)
        assertEquals(2L, probe.revision.value)
    }

    @Test
    fun `a stale probe never applies to a replacement backend`() {
        val registered = java.util.concurrent.atomic.AtomicReference<WorkspaceBackend>()
        val old = backend { false }
        val fresh = backend { true }
        registered.set(old)
        val probe = probe(registered = { _, backend -> registered.get() === backend })
        probe.schedule("w1", old)
        // Bind a replacement before the probe result lands, then let it land.
        registered.set(fresh)
        awaitQuietly()
        assertFalse("w1" in probe.down)
        assertEquals(0L, probe.revision.value)
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
