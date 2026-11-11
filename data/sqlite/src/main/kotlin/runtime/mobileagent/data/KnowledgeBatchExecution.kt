// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible

/** Single-flight batch ownership and bounded lane lifetime; repository callbacks own durable state. */
internal class KnowledgeBatchExecution {
    private val active = ConcurrentHashMap.newKeySet<String>()
    fun isActive(batchId: String): Boolean = batchId in active

    fun run(batchId: String, prepare: () -> Int, work: (AtomicBoolean) -> Unit): Boolean {
        if (!active.add(batchId)) return false
        var started = false
        try {
            val lanes = prepare()
            require(lanes in 1..6) { "Invalid batch worker lane count" }
            val retry = AtomicBoolean(false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val worker = scope.async {
                coroutineScope { (1..lanes).map { async { runInterruptible { work(retry) } } }.awaitAll() }
            }
            // Completion waits for all child lanes, including a backend slow to observe interruption.
            worker.invokeOnCompletion { active.remove(batchId); scope.cancel() }
            started = true
            try { runBlocking { worker.await() } }
            catch (failure: Throwable) { worker.cancel(); throw failure }
            return retry.get()
        } finally { if (!started) active.remove(batchId) }
    }
}
