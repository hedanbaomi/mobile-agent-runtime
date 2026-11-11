// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.integration

/** Close prevents new foreign-thread callbacks and drains admitted callbacks before storage retires. */
internal class RuntimeCallbackGate {
    private val lock = java.lang.Object()
    private var closed = false
    private var active = 0
    fun run(block: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            active++
        }
        try { block() } finally { synchronized(lock) { active--; lock.notifyAll() } }
    }
    /** Caller restores interruption only after the remaining resources have retired. */
    fun closeAndDrain(): Boolean {
        var interrupted = false
        synchronized(lock) {
            closed = true
            while (active > 0) {
                try { lock.wait() } catch (_: InterruptedException) { interrupted = true }
            }
        }
        return interrupted
    }
}

internal fun retireRuntimeCallbacks(gate: RuntimeCallbackGate, job: kotlinx.coroutines.Job, release: () -> Unit) {
    var interrupted = Thread.interrupted()
    try {
        job.cancel()
        interrupted = gate.closeAndDrain() || interrupted
        val completed = java.util.concurrent.CountDownLatch(1)
        job.invokeOnCompletion { completed.countDown() }
        while (completed.count > 0) {
            try { completed.await() } catch (_: InterruptedException) { interrupted = true }
        }
        release()
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
