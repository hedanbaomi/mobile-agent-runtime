// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded RPC/write execution gate for Root transports (`W1`, `ROOT06/07/08/16`).
 *
 * Ensures that synchronous Binder calls, pipe writes, and `close()` operations cannot block
 * indefinitely when a remote endpoint or pipe buffer is wedged, and that `close()` immediately
 * aborts any in-flight caller wait.
 */
internal class RootBoundedCallGate(
    private val namePrefix: String,
    private val defaultTimeoutMs: Long = 5_000L,
) {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(1L)
    private val activeFutures = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "$namePrefix-${generation.getAndIncrement()}").apply { isDaemon = true }
    }

    val isClosed: Boolean
        get() = closed.get()

    fun <T> callBounded(timeoutMs: Long = defaultTimeoutMs, block: () -> T): T? {
        if (closed.get()) return null
        val future = CompletableFuture<T>()
        activeFutures += future
        if (closed.get()) {
            activeFutures.remove(future)
            return null
        }
        val worker = executor.submit {
            try {
                if (!closed.get()) {
                    future.complete(block())
                } else {
                    future.cancel(true)
                }
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        return try {
            future.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            worker.cancel(true)
            null
        } finally {
            activeFutures.remove(future)
        }
    }

    fun closeBounded(timeoutMs: Long = 1_000L, vararg steps: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        activeFutures.toList().forEach { future ->
            activeFutures.remove(future)
            future.cancel(true)
        }
        val budgetMs = timeoutMs.coerceAtLeast(1L)
        for (step in steps) {
            val closeFuture = CompletableFuture<Unit>()
            val closeWorker = executor.submit {
                try {
                    runCatching { step() }
                } finally {
                    closeFuture.complete(Unit)
                }
            }
            try {
                closeFuture.get(budgetMs, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                closeWorker.cancel(true)
            }
        }
        executor.shutdownNow()
    }

    companion object {
        fun <T> runBoundedOnce(name: String, timeoutMs: Long, block: () -> T): T? {
            val gate = RootBoundedCallGate(name, timeoutMs)
            return try {
                gate.callBounded(timeoutMs, block)
            } finally {
                gate.closeBounded(50L)
            }
        }
    }
}
