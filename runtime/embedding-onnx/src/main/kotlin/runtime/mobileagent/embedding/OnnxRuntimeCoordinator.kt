// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred

internal const val CLOSED_RUNTIME_MESSAGE = "Local embedding runtime is closed"

/**
 * Coordinates in-flight operation leases with idempotent deferred resource teardown.
 *
 * Once [close] is invoked, new [acquire] calls fail immediately with [IllegalStateException].
 * If operations are currently in flight, [onRelease] executes immediately when the final active
 * lease exits [release]; otherwise [close] executes [onRelease] synchronously. [onRelease] runs
 * at most once across the lifetime of this guard.
 */
internal class InFlightCloseGuard(
    private val closedMessage: String = CLOSED_RUNTIME_MESSAGE,
    private val onRelease: () -> Unit,
) {
    private val lock = Any()
    private var activeCount = 0

    @Volatile
    private var closing = false
    private var released = false

    val isClosing: Boolean
        get() = closing

    val activeInFlight: Int
        get() = synchronized(lock) { activeCount }

    fun acquire() {
        synchronized(lock) {
            check(!closing) { closedMessage }
            activeCount++
        }
    }

    fun release() {
        var shouldRelease = false
        synchronized(lock) {
            check(activeCount > 0) { "Unbalanced ONNX inference lease release" }
            activeCount--
            if (closing && activeCount == 0 && !released) {
                released = true
                shouldRelease = true
            }
        }
        if (shouldRelease) {
            onRelease()
        }
    }

    inline fun <T> withLease(block: () -> T): T {
        acquire()
        var primaryFailure: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                release()
            } catch (releaseFailure: Throwable) {
                if (primaryFailure != null && primaryFailure !== releaseFailure) {
                    primaryFailure.addSuppressed(releaseFailure)
                } else {
                    throw releaseFailure
                }
            }
        }
    }

    fun close(onInitiated: () -> Unit = {}) {
        var initiatedNow = false
        var shouldRelease = false
        synchronized(lock) {
            if (!closing) {
                closing = true
                initiatedNow = true
            }
            if (activeCount == 0 && !released) {
                released = true
                shouldRelease = true
            }
        }
        if (initiatedNow) {
            onInitiated()
        }
        if (shouldRelease) {
            onRelease()
        }
    }
}

/**
 * Single-flight handle for one cold [BertWordPieceTokenizer.Encoded] window.
 *
 * A non-null completion carries an immutable vector snapshot from which each caller obtains its
 * own defensive copy; a null completion signals that the previous leader exited via cancellation,
 * interruption, or failure and released ownership so surviving callers can claim the window.
 */
internal class InFlightWindow {
    @Volatile
    private var outcome: FloatArray? = null
    private val latch = CountDownLatch(1)
    private val deferred = CompletableDeferred<FloatArray?>()

    fun finish(vector: FloatArray?) {
        outcome = vector
        latch.countDown()
        deferred.complete(vector)
    }

    fun awaitBlocking(): FloatArray? {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Local embedding interrupted")
        }
        latch.await()
        return outcome?.copyOf()
    }

    suspend fun awaitSuspend(): FloatArray? {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Local embedding interrupted")
        }
        return deferred.await()?.copyOf()
    }
}

internal class WindowResolutionPlan(
    val claimed: List<Pair<BertWordPieceTokenizer.Encoded, InFlightWindow>>,
    val waiting: List<Pair<BertWordPieceTokenizer.Encoded, InFlightWindow>>,
)

/**
 * Unified runtime coordinator for [OnnxTextEmbedder]:
 * 1. Gates admission and defers [OnnxSessionRunner.close] until the last in-flight request exits.
 * 2. Coordinates [EmbeddingWindowCache] reads, writes, and teardown without repopulating a closed cache.
 * 3. Coalesces concurrent cold-window inferences with cancellation/failure ownership handoff.
 */
internal class OnnxRuntimeCoordinator(
    runner: OnnxSessionRunner,
    internal val windowCache: EmbeddingWindowCache = EmbeddingWindowCache(),
) : AutoCloseable {
    private val windowLock = Any()
    private val inFlight = HashMap<BertWordPieceTokenizer.Encoded, InFlightWindow>()
    private var cacheClosed = false
    private var joinedWaiters = 0
    internal val leaseGuard = InFlightCloseGuard(onRelease = runner::close)

    val activeInFlight: Int
        get() = leaseGuard.activeInFlight

    val isClosing: Boolean
        get() = leaseGuard.isClosing

    val inFlightWindowCount: Int
        get() = synchronized(windowLock) { inFlight.size }

    val totalWaitersJoined: Int
        get() = synchronized(windowLock) { joinedWaiters }

    inline fun <T> withLease(block: () -> T): T = leaseGuard.withLease(block)

    inline fun resolveWindows(
        uniqueWindows: Set<BertWordPieceTokenizer.Encoded>,
        chunkSize: Int,
        noinline checkCancellation: () -> Unit,
        noinline inferBatch: (List<BertWordPieceTokenizer.Encoded>, () -> Unit) -> List<FloatArray>,
        awaitSlot: (InFlightWindow) -> FloatArray?,
    ): Map<BertWordPieceTokenizer.Encoded, FloatArray> {
        val resolved = HashMap<BertWordPieceTokenizer.Encoded, FloatArray>(uniqueWindows.size)
        while (resolved.size < uniqueWindows.size) {
            checkCancellation()
            val plan = claimOrJoin(uniqueWindows, resolved)
            if (plan.claimed.isNotEmpty()) {
                executeClaimedBatches(plan.claimed, chunkSize, resolved, checkCancellation, inferBatch)
            }
            for ((window, slot) in plan.waiting) {
                if (window in resolved) continue
                checkCancellation()
                val vector = awaitSlot(slot)
                if (vector != null) {
                    resolved[window] = vector
                }
            }
        }
        return resolved
    }

    internal fun claimOrJoin(
        uniqueWindows: Set<BertWordPieceTokenizer.Encoded>,
        resolved: MutableMap<BertWordPieceTokenizer.Encoded, FloatArray>,
    ): WindowResolutionPlan {
        val claimed = ArrayList<Pair<BertWordPieceTokenizer.Encoded, InFlightWindow>>()
        val waiting = ArrayList<Pair<BertWordPieceTokenizer.Encoded, InFlightWindow>>()
        synchronized(windowLock) {
            val canReadCache = !cacheClosed && !leaseGuard.isClosing
            try {
                for (window in uniqueWindows) {
                    if (window in resolved) continue
                    if (canReadCache) {
                        val cached = windowCache.get(window)
                        if (cached != null) {
                            resolved[window] = cached
                            continue
                        }
                    }
                    val existing = inFlight[window]
                    if (existing != null) {
                        waiting += window to existing
                    } else {
                        val ownedKey = copyWindowKey(window)
                        val slot = InFlightWindow()
                        claimed += ownedKey to slot
                        inFlight[ownedKey] = slot
                    }
                }
                val plan = WindowResolutionPlan(claimed, waiting)
                joinedWaiters += waiting.size
                return plan
            } catch (failure: Throwable) {
                // Planning still holds the lock: no other request could have joined these slots.
                for ((key, slot) in claimed) inFlight.remove(key, slot)
                throw failure
            }
        }
    }

    internal fun executeClaimedBatches(
        claimed: List<Pair<BertWordPieceTokenizer.Encoded, InFlightWindow>>,
        chunkSize: Int,
        resolved: MutableMap<BertWordPieceTokenizer.Encoded, FloatArray>,
        checkCancellation: () -> Unit,
        inferBatch: (List<BertWordPieceTokenizer.Encoded>, () -> Unit) -> List<FloatArray>,
    ) {
        var completedCount = 0
        try {
            while (completedCount < claimed.size) {
                checkCancellation()
                val end = minOf(completedCount + chunkSize, claimed.size)
                val chunk = claimed.subList(completedCount, end)
                val vectors = inferBatch(chunk.map { it.first }, checkCancellation)
                completeChunk(chunk, vectors, resolved)
                completedCount = end
            }
        } finally {
            if (completedCount < claimed.size) {
                abandonClaims(claimed.subList(completedCount, claimed.size))
            }
        }
    }

    private fun completeChunk(
        chunk: List<Pair<BertWordPieceTokenizer.Encoded, InFlightWindow>>,
        vectors: List<FloatArray>,
        resolved: MutableMap<BertWordPieceTokenizer.Encoded, FloatArray>,
    ) {
        require(vectors.size == chunk.size) { "ONNX inferred vector count mismatch" }
        val snapshots = Array(chunk.size) { i -> vectors[i].copyOf() }
        synchronized(windowLock) {
            val canWriteCache = !cacheClosed && !leaseGuard.isClosing
            for (i in chunk.indices) {
                val (window, slot) = chunk[i]
                inFlight.remove(window, slot)
                if (canWriteCache) {
                    putCachePreservingInterrupt(window, snapshots[i])
                }
            }
        }
        for (i in chunk.indices) {
            val (window, slot) = chunk[i]
            val snapshot = snapshots[i]
            resolved[window] = snapshot.copyOf()
            slot.finish(snapshot)
        }
    }

    private fun putCachePreservingInterrupt(
        window: BertWordPieceTokenizer.Encoded,
        vector: FloatArray,
    ) {
        val wasInterrupted = Thread.interrupted()
        try {
            windowCache.put(window, vector)
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun abandonClaims(
        remaining: List<Pair<BertWordPieceTokenizer.Encoded, InFlightWindow>>,
    ) {
        synchronized(windowLock) {
            for ((window, slot) in remaining) {
                inFlight.remove(window, slot)
            }
        }
        for ((_, slot) in remaining) {
            slot.finish(null)
        }
    }

    private fun copyWindowKey(window: BertWordPieceTokenizer.Encoded) =
        BertWordPieceTokenizer.Encoded(
            inputIds = window.inputIds.copyOf(),
            attentionMask = window.attentionMask.copyOf(),
            tokenTypeIds = window.tokenTypeIds.copyOf(),
        )

    private fun closeWindowCache() {
        synchronized(windowLock) {
            if (!cacheClosed) {
                cacheClosed = true
                windowCache.close()
            }
        }
    }

    override fun close() {
        leaseGuard.close(onInitiated = ::closeWindowCache)
    }
}
