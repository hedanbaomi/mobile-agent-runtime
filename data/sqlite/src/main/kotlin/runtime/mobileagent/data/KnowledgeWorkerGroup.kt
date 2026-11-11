// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A synchronous owner cannot release its job/leases before nested workers really exit. */
internal fun runKnowledgeWorkers(count: Int, work: (() -> Unit) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val completed = CountDownLatch(1)
    val worker = scope.async {
        coroutineScope { (1..count).map { async {
            val context = currentCoroutineContext()
            runInterruptible { work { context.ensureActive() } }
        } }.awaitAll() }
    }
    worker.invokeOnCompletion { completed.countDown(); scope.cancel() }
    var interrupted = false
    try {
        runBlocking { worker.await() }
    } catch (failure: Throwable) {
        interrupted = failure is InterruptedException
        worker.cancel()
        throw failure
    } finally {
        while (completed.count > 0) {
            try { completed.await() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
