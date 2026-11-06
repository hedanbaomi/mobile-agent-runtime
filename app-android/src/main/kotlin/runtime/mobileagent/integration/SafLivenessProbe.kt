// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import runtime.mobileagent.skills.tooling.WorkspaceBackend

/**
 * Bounded, off-thread liveness probes for SAF trees.
 *
 * A wedged documents provider can hang a descriptor read indefinitely, and
 * ContentResolver.query does not respond to coroutine cancellation, so the
 * deadline is independent of worker completion: the query runs on a bounded
 * worker and the scheduling coroutine only awaits it up to [probeDeadlineMs].
 * A deadline miss abandons the result but always clears single-flight state,
 * so the caller can re-arm probes rather than silently wedging.
 *
 * Health is stored per backend *instance* (workspace id → the exact backend
 * the result belongs to), so a stale result landing after the registry
 * swapped in a replacement can never mark the replacement down — reads
 * compare identity, writes are guarded by registration to that same
 * instance.  A workspace marked down keeps its registration and self-rearms
 * a retry each [probeIntervalMs] until it reports healthy or is unbound, so
 * recovery does not depend on another UI refresh.
 *
 * Each permit is owned by the probe's real worker, not by the decision
 * window: a wedged provider that ignores interruption keeps occupying its
 * slot until it truly finishes, so repeated probes can never exceed
 * [maxWorkers] live workers.  The decision still lands at the deadline —
 * `inFlight` is released then — only the slot outlives it.
 */
internal class SafLivenessProbe(
    private val scope: CoroutineScope,
    private val isRegisteredBackend: (String, WorkspaceBackend) -> Boolean,
    private val probeIntervalMs: Long = 2_000L,
    private val probeDeadlineMs: Long = 800L,
    private val maxWorkers: Int = 4,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val probedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val down = java.util.concurrent.ConcurrentHashMap<String, WorkspaceBackend>()
    internal val retryJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val retryBackend = java.util.concurrent.ConcurrentHashMap<String, WorkspaceBackend>()
    private val workers = kotlinx.coroutines.sync.Semaphore(maxWorkers)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    /** Whether [backend] — the currently registered instance — is marked down. */
    fun isDown(workspaceId: String, backend: WorkspaceBackend): Boolean =
        down[workspaceId] === backend

    fun schedule(workspaceId: String, backend: WorkspaceBackend) {
        val moment = now()
        val last = probedAt[workspaceId]
        if (last != null && moment - last < probeIntervalMs) return
        probedAt[workspaceId] = moment
        if (!inFlight.add(workspaceId)) return
        scope.launch {
            // Acquiring the permit suspends inside the coroutine rather than
            // abandoning the probe at the caller: a saturated pool only delays
            // this attempt, it can never silently end the retry loop.
            workers.withPermit {
                val worker = async(Dispatchers.IO) {
                    runCatching { backend.descriptor.enabled }.getOrDefault(false)
                }
                try {
                    val alive = withTimeoutOrNull(probeDeadlineMs) { worker.await() } ?: false
                    if (isRegisteredBackend(workspaceId, backend)) {
                        val changed = if (alive) {
                            down.remove(workspaceId, backend)
                        } else {
                            down.put(workspaceId, backend) !== backend
                        }
                        if (changed) _revision.value += 1
                        if (!alive) ensureRetryLoop(workspaceId, backend)
                    }
                } finally {
                    inFlight.remove(workspaceId)
                }
                // The permit outlives the decision window: it is released only
                // when the worker actually finishes — a wedged provider keeps
                // its slot, bounding live workers at maxWorkers no matter how
                // often schedule() is called again.
                worker.await()
            }
        }
    }

    /**
     * One retry loop per backend instance: while the workspace stays down,
     * each interval re-attempts schedule() — throttle, in-flight rejection
     * and pool saturation only defer an attempt to the next interval, they
     * never end the loop.  The loop exits on its own once the entry is
     * healthy (or taken over by another generation), when the backend is no
     * longer the registered instance, or when the job is cancelled — a
     * replacement under the same workspace id never inherits the old
     * backend's timer.
     */
    private fun ensureRetryLoop(workspaceId: String, backend: WorkspaceBackend) {
        retryJobs.compute(workspaceId) { _, existing ->
            if (existing?.isActive == true && retryBackend[workspaceId] === backend) {
                existing
            } else {
                existing?.cancel()
                retryBackend[workspaceId] = backend
                scope.launch {
                    while (coroutineContext.isActive) {
                        delay(probeIntervalMs)
                        if (!isRegisteredBackend(workspaceId, backend)) break
                        if (down[workspaceId] !== backend) break
                        schedule(workspaceId, backend)
                    }
                }
            }
        }
    }
}
