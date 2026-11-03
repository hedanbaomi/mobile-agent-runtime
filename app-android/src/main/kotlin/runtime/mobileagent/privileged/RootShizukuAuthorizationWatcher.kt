// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import runtime.mobileagent.domain.RootAuthorityRecord
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.Shizuku

/** Pure facts of one bounded Shizuku observation; `completed=false` means "unknown". */
internal data class RootShizukuLiveFacts(
    val binderAlive: Boolean,
    val preV11: Boolean,
    val permissionGranted: Boolean,
    val serverUid: Int?,
    val serverVersion: Int?,
)

/**
 * Bounded, passive Shizuku sampler: `pingBinder` / `isPreV11` / `getVersion` /
 * `checkSelfPermission` / `getUid`. It never requests permission, binds a service or runs `su`.
 * A timeout or a throwing SDK call yields `null` ("unknown"), which the caller must treat as
 * not-allowed rather than as a stale allow.
 */
internal object RootShizukuLiveSampler {
    private val inFlight = AtomicReference<Thread?>(null)
    private val lock = java.util.concurrent.locks.ReentrantLock()

    /**
     * One bounded sample. Concurrent callers serialize on [lock] for at most their own remaining
     * budget; after acquiring it they either start their own fresh worker or, if a hung worker still
     * owns [inFlight], return unknown without starting another. A single deadline covers lock wait
     * and join; an already in-flight worker is never shared and no result is cached.
     */
    fun sample(
        timeoutMs: Long = 1_500L,
        read: () -> RootShizukuLiveFacts? = ::readFacts,
    ): RootShizukuLiveFacts? {
        if (timeoutMs <= 0L) return null
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val acquired = try {
            lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        if (!acquired) return null
        try {
            if (inFlight.get() != null) return null
            val holder = AtomicReference<RootShizukuLiveFacts?>(null)
            lateinit var worker: Thread
            worker = Thread({
                try {
                    holder.set(read())
                } catch (_: Throwable) {
                    holder.set(null)
                } finally {
                    inFlight.compareAndSet(worker, null)
                }
            }, "mar-shizuku-live-sampler")
            worker.isDaemon = true
            if (!inFlight.compareAndSet(null, worker)) return null
            try {
                worker.start()
            } catch (_: Throwable) {
                inFlight.compareAndSet(worker, null)
                return null
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return null
            val millis = remaining / 1_000_000L
            if (millis <= 0L) return null
            try {
                worker.join(millis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            if (worker.isAlive) return null
            return holder.get()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Fresh authoritative read over the live Shizuku binder. `Shizuku.checkSelfPermission()` caches
     * an earlier GRANTED in the SDK (13.1.5 `Shizuku.java` L865-872), so this uses the AIDL
     * `moe.shizuku.server.IShizukuService` directly: `checkSelfPermission()`, `getUid()`,
     * `getVersion()` on the same binder instance. No permission request, no user-service bind, no su.
     */
    private fun readFacts(): RootShizukuLiveFacts? = runCatching {
        val binder = runCatching { Shizuku.getBinder() }.getOrNull() ?: return@runCatching null
        if (!binder.pingBinder()) {
            return@runCatching RootShizukuLiveFacts(
                binderAlive = false,
                preV11 = false,
                permissionGranted = false,
                serverUid = null,
                serverVersion = null,
            )
        }
        val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
        val version = runCatching { service.version }.getOrNull()
        if (version == null) {
            return@runCatching RootShizukuLiveFacts(
                binderAlive = true,
                preV11 = true,
                permissionGranted = false,
                serverUid = null,
                serverVersion = null,
            )
        }
        val permission = runCatching { service.checkSelfPermission() }.getOrDefault(false)
        val uid = if (permission) runCatching { service.uid }.getOrNull() else null
        // The facts are only current if the same binder instance is still the live one and alive;
        // otherwise the server was replaced/killed during the read and the sample is unknown.
        if (!binder.isBinderAlive) return@runCatching null
        if (runCatching { Shizuku.getBinder() }.getOrNull() !== binder) return@runCatching null
        RootShizukuLiveFacts(
            binderAlive = true,
            preV11 = false,
            permissionGranted = permission,
            serverUid = uid,
            serverVersion = version,
        )
    }.getOrNull()
}

/**
 * Passive watcher for the live `SHIZUKU_ROOT` authorization (the no-event revocation path).
 *
 * Sampling happens outside the coordinator lock; the decision is applied through [applyDecision],
 * which the coordinator runs inside its own monitor after re-verifying the captured session and
 * authorization record (F7 fencing). An unknown sample never fences and never authorizes.
 */
internal class RootShizukuAuthorizationWatcher(
    private val intervalMs: () -> Long,
    private val currentSession: () -> ManagedRootSessionState?,
    private val currentRecord: () -> RootAuthorityRecord?,
    private val isClosed: () -> Boolean,
    private val sample: () -> RootShizukuLiveFacts?,
    /**
     * Applies one observation (null = unknown/timeout). Runs under the coordinator's lock only,
     * after verifying the captured session/transport/record and the closed flag: no fact or
     * decision may be written outside that fence, so a slow old observation can neither pollute a
     * replacement session nor survive close().
     */
    private val applyDecision: (
        ManagedRootSessionState,
        RootAuthorityRecord,
        RootShizukuLiveFacts?,
    ) -> Boolean,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val worker = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(intervalMs().coerceAtLeast(250L))
                } catch (_: InterruptedException) {
                    break
                }
                if (!running.get()) break
                observeOnce()
            }
        }, "mar-root-shizuku-observer").apply { isDaemon = true }
        thread = worker
        worker.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread = null
    }

    /** One bounded observation; returns true only when a fenced decision was applied. */
    fun observeOnce(): Boolean {
        val active = currentSession() ?: return false
        val record = currentRecord() ?: return false
        val facts = sample()
        return applyDecision(active, record, facts)
    }
}
