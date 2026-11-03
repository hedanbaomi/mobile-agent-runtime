// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.privileged

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.RootApprovalTools
import runtime.mobileagent.skills.ResultDisclosure
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolResult
import runtime.mobileagent.skills.tooling.ToolInvocation

class RootCancellationResumeTest {
    @Test
    fun closeBetweenCancellationSettlementAndDeliveryRetainsOriginalResponsibility() = runBlocking {
        IndependentRemoteBackend().use { backend ->
            val executor = executor(backend)
            val settled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            executor.afterCancellationSettlement = { settled.complete(Unit); release.await() }
            val invocation = invocation()
            val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
            val job = launch { executor.invoke(invocation) }
            try {
                withTimeout(5_000) { backend.started.await() }
                job.cancel()
                withTimeout(5_000) { settled.await() }
                withTimeout(5_000) { executor.close() }
                release.complete(Unit)
                withTimeout(5_000) { job.join() }
                assertEquals(listOf(invocation.requestId), backend.cancels)
                assertTrue(backend.remoteStopped.await(5, TimeUnit.SECONDS))
                assertFalse(executor.authorizeReplay(call))
                assertFalse(executor.authorizeDeferredResult(call))
                assertTrue(executor.invoke(call) !is ToolResult.Value)
                assertEquals(1, backend.dispatches.get())
            } finally {
                release.complete(Unit)
                backend.releaseRemote.countDown()
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellingInvokeStopsIndependentRemoteOnceByRuntimeId() = runBlocking {
        IndependentRemoteBackend().use { backend ->
            val executor = executor(backend)
            val invocation = invocation()
            val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
            val job = launch { executor.invoke(invocation) }
            try {
                withTimeout(5_000) { backend.started.await() }
                job.cancelAndJoin()
                assertEquals(listOf(invocation.requestId), backend.cancels,
                    "Interrupting the Host waiter must explicitly cancel the independent remote")
                assertTrue(backend.remoteStopped.await(5, TimeUnit.SECONDS))
                assertTrue(executor.invoke(call) is ToolResult.UnknownOutcome)
                assertFalse(executor.authorizeReplay(call))
                assertFalse(executor.authorizeDeferredResult(call))
                assertFalse(executor.cancel(invocation.callId))
                assertEquals(1, backend.dispatches.get())
                assertEquals(0, backend.disclosures.get())
                assertEquals(listOf(invocation.requestId), backend.cancels)
            } finally {
                backend.releaseRemote.countDown()
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellingApprovalStopsIndependentRemoteByOriginalRuntimeId() = runBlocking {
        IndependentRemoteBackend(needsApproval = true).use { backend ->
            val executor = executor(backend)
            val invocation = invocation()
            executor.invoke(invocation)
            val job = launch { executor.approve(invocation.callId) }
            try {
                withTimeout(5_000) { backend.started.await() }
                job.cancelAndJoin()
                assertEquals(listOf(invocation.requestId), backend.cancels)
                assertEquals(listOf(invocation.requestId), backend.approvals)
                val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
                assertTrue(executor.invoke(call) is ToolResult.UnknownOutcome)
                assertFalse(executor.authorizeReplay(call))
                assertFalse(executor.authorizeDeferredResult(call))
                assertTrue(executor.approve(invocation.requestId) is ToolResult.Invalid)
                assertEquals(1, backend.dispatches.get())
            } finally {
                backend.releaseRemote.countDown()
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun explicitCancelRacingCoroutineCancellationSendsOnlyOnce() = runBlocking {
        IndependentRemoteBackend().use { backend ->
            backend.cancelGate = CompletableDeferred()
            val executor = executor(backend)
            val invocation = invocation()
            val job = launch { executor.invoke(invocation) }
            var explicit: kotlinx.coroutines.Deferred<Boolean>? = null
            try {
                withTimeout(5_000) { backend.started.await() }
                explicit = async { executor.cancel(invocation.callId) }
                withTimeout(5_000) { backend.cancelStarted.await() }
                job.cancel()
                withTimeout(5_000) { job.join() }
                backend.cancelGate!!.complete(Unit)
                withTimeout(5_000) { explicit.await() }
                assertEquals(listOf(invocation.requestId), backend.cancels)
                assertEquals(1, backend.dispatches.get())
                val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
                assertTrue(executor.invoke(call) is ToolResult.UnknownOutcome)
                assertFalse(executor.authorizeReplay(call))
                assertFalse(executor.authorizeDeferredResult(call))
            } finally {
                backend.cancelGate!!.complete(Unit)
                backend.releaseRemote.countDown()
                explicit?.cancelAndJoin()
                job.cancelAndJoin()
            }
        }
    }

    @Test
    fun explicitCancelWithholdsLateSuccessfulResponse() = runBlocking {
        IndependentRemoteBackend().use { backend ->
            val executor = executor(backend)
            val invocation = invocation()
            val job = async { executor.invoke(ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)) }
            try {
                withTimeout(5_000) { backend.started.await() }
                assertTrue(executor.cancel(invocation.requestId))
                assertTrue(withTimeout(5_000) { job.await() } is ToolResult.UnknownOutcome,
                    "A late successful remote response must not overwrite cancellation")
                assertFalse(executor.authorizeReplay(ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)))
            } finally { backend.releaseRemote.countDown(); job.cancelAndJoin() }
        }
    }

    @Test
    fun closeCancelsRemoteBeforeDiscardingItsIdentity() = runBlocking {
        IndependentRemoteBackend().use { backend ->
            val executor = executor(backend)
            val invocation = invocation()
            val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
            val job = async { executor.invoke(call) }
            try {
                withTimeout(5_000) { backend.started.await() }
                executor.close()
                assertEquals(listOf(invocation.requestId), backend.cancels)
                backend.releaseRemote.countDown()
                assertTrue(withTimeout(5_000) { job.await() } is ToolResult.UnknownOutcome)
                assertTrue(executor.invoke(call) !is ToolResult.Value)
                assertEquals(1, backend.dispatches.get(), "A closed executor must never dispatch again")
            } finally { backend.releaseRemote.countDown(); job.cancelAndJoin() }
        }
    }

    @Test
    fun failedOrThrowingCancelNeverDisclosesOrRetries() = runBlocking {
        for (throws in listOf(false, true)) IndependentRemoteBackend().use { backend ->
            backend.cancelFailure = if (throws) IllegalStateException("synthetic cancel failure") else null
            backend.cancelConfirmed = false
            val executor = executor(backend)
            val invocation = invocation()
            val call = ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)
            val task = async { executor.invoke(call) }
            try {
                withTimeout(5_000) { backend.started.await() }
                assertFalse(executor.cancel(invocation.requestId))
                backend.releaseRemote.countDown()
                assertTrue(withTimeout(5_000) { task.await() } is ToolResult.UnknownOutcome)
                executor.close()
                assertFalse(executor.authorizeReplay(call))
                assertFalse(executor.authorizeDeferredResult(call))
                assertEquals(listOf(invocation.requestId), backend.cancels)
            } finally { backend.releaseRemote.countDown(); task.cancelAndJoin() }
        }
    }

    @Test
    fun closeWaitsForAlreadyClaimedCancellationWithoutSendingAgain() = runBlocking {
        IndependentRemoteBackend().use { backend ->
            backend.cancelGate = CompletableDeferred()
            val executor = executor(backend)
            val invocation = invocation()
            val task = launch { executor.invoke(invocation) }
            var explicit: kotlinx.coroutines.Deferred<Boolean>? = null
            var closing: kotlinx.coroutines.Job? = null
            try {
                withTimeout(5_000) { backend.started.await() }
                explicit = async { executor.cancel(invocation.requestId) }
                withTimeout(5_000) { backend.cancelStarted.await() }
                closing = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { executor.close() }
                assertFalse(closing.isCompleted)
                backend.cancelGate!!.complete(Unit)
                withTimeout(5_000) { closing.join(); explicit.await() }
                assertEquals(listOf(invocation.requestId), backend.cancels)
            } finally {
                backend.cancelGate!!.complete(Unit); backend.releaseRemote.countDown()
                closing?.join(); explicit?.cancelAndJoin(); task.cancelAndJoin()
            }
        }
    }

    @Test
    fun concurrentClosesShareCompletionEvenWhenCancellationFails() = runBlocking {
        for (throws in listOf(false, true)) IndependentRemoteBackend().use { backend ->
            backend.cancelGate = CompletableDeferred()
            backend.cancelConfirmed = false
            backend.cancelFailure = if (throws) IllegalStateException("synthetic cancel failure") else null
            val executor = executor(backend)
            val invocation = invocation()
            val work = launch { executor.invoke(invocation) }
            var first: kotlinx.coroutines.Job? = null
            var second: kotlinx.coroutines.Job? = null
            try {
                withTimeout(5_000) { backend.started.await() }
                first = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { executor.close() }
                withTimeout(5_000) { backend.cancelStarted.await() }
                second = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { executor.close() }
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted, "Second close must share first close's cancellation completion")
                backend.cancelGate!!.complete(Unit)
                withTimeout(5_000) { first.join(); second.join() }
                assertEquals(listOf(invocation.requestId), backend.cancels)
                assertFalse(executor.authorizeReplay(ToolCall(invocation.requestId, invocation.name, invocation.argumentsJson)))
            } finally {
                backend.cancelGate!!.complete(Unit); backend.releaseRemote.countDown()
                first?.join(); second?.join(); work.cancelAndJoin()
            }
        }
    }

    @Test
    fun closeDoesNotBlockCallerOrSerializeSynchronousCancellationsOnSingleThreadDispatcher() = runBlocking {
        val started = CountDownLatch(2)
        val bothCancelsEntered = CountDownLatch(2)
        val releaseExec = CountDownLatch(1)
        val blockedBeforeSecondArrival = AtomicInteger()
        val backend = object : RootToolBackend {
            override fun exposedTools() = listOf(RootToolExposure(
                "file_read_text", requireNotNull(RootApprovalTools.capability("file_read_text")), "root-ws", "grant-1",
            ))

            override suspend fun invoke(invocation: RootToolInvocation): ToolResult =
                runInterruptible(Dispatchers.IO) {
                    started.countDown()
                    check(releaseExec.await(5, TimeUnit.SECONDS))
                    ToolResult.Value("{\"text\":\"ok\"}")
                }

            override suspend fun approve(runtimeInvocationId: String): ToolResult = ToolResult.Denied("unused")
            override suspend fun reject(runtimeInvocationId: String): ToolResult = ToolResult.Denied("unused")
            override suspend fun expire(runtimeInvocationId: String): ToolResult = ToolResult.Denied("unused")
            override suspend fun authorizeDisclosure(invocation: RootToolInvocation, disclosure: ResultDisclosure) = false

            override suspend fun cancel(runtimeInvocationId: String): Boolean {
                bothCancelsEntered.countDown()
                if (!bothCancelsEntered.await(300, TimeUnit.MILLISECONDS)) {
                    blockedBeforeSecondArrival.incrementAndGet()
                }
                releaseExec.countDown()
                return true
            }
        }
        val executor = executor(backend)
        val call1 = ToolCall("call-1", "file_read_text", """{"workspace_id":"root-ws","relative_path":"a"}""")
        val call2 = ToolCall("call-2", "file_read_text", """{"workspace_id":"root-ws","relative_path":"b"}""")
        val job1 = launch(Dispatchers.IO) { executor.invoke(call1) }
        val job2 = launch(Dispatchers.IO) { executor.invoke(call2) }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            executor.close()
            assertEquals(
                0,
                blockedBeforeSecondArrival.get(),
                "close() must fan out synchronous backend.cancel calls off the caller thread instead of serializing them",
            )
        } finally {
            releaseExec.countDown()
            job1.cancelAndJoin()
            job2.cancelAndJoin()
        }
    }

    private fun executor(backend: RootToolBackend) =
        RootToolExecutor(RootToolArguments.specsFor(listOf("file_read_text")), backend)

    private fun invocation() = ToolInvocation.fromRuntime(
        callId = "model-correlation-not-runtime-id", snapshotId = "snapshot-1", agentId = "agent-1",
        name = "file_read_text", argumentsJson = """{"workspace_id":"root-ws","relative_path":"a"}""",
    )
}

/** The remote worker is not a child coroutine or the Host's interrupted thread. */
private class IndependentRemoteBackend(private val needsApproval: Boolean = false) : RootToolBackend, AutoCloseable {
    private val remote = Executors.newSingleThreadExecutor()
    val started = CompletableDeferred<Unit>()
    val releaseRemote = CountDownLatch(1)
    val remoteStopped = CountDownLatch(1)
    val dispatches = AtomicInteger()
    val disclosures = AtomicInteger()
    val cancels = CopyOnWriteArrayList<String>()
    val approvals = CopyOnWriteArrayList<String>()
    val cancelStarted = CompletableDeferred<Unit>()
    var cancelGate: CompletableDeferred<Unit>? = null
    var cancelConfirmed = true
    var cancelFailure: Exception? = null

    override fun exposedTools() = listOf(RootToolExposure(
        "file_read_text", requireNotNull(RootApprovalTools.capability("file_read_text")), "root-ws", "grant-1",
    ))

    override suspend fun invoke(invocation: RootToolInvocation): ToolResult =
        if (needsApproval) ToolResult.NeedsApproval else executeRemote()
    override suspend fun approve(runtimeInvocationId: String): ToolResult {
        approvals += runtimeInvocationId
        return executeRemote()
    }
    override suspend fun reject(runtimeInvocationId: String): ToolResult = ToolResult.Denied("denied")
    override suspend fun expire(runtimeInvocationId: String): ToolResult = ToolResult.Denied("expired")

    private suspend fun executeRemote(): ToolResult {
        dispatches.incrementAndGet()
        val response = remote.submit<ToolResult> {
            started.complete(Unit)
            try {
                check(releaseRemote.await(10, TimeUnit.SECONDS)) { "Remote test gate timed out" }
                ToolResult.Value("private-remote-content")
            } finally {
                remoteStopped.countDown()
            }
        }
        return runInterruptible(Dispatchers.IO) { response.get() }
    }

    override suspend fun authorizeDisclosure(invocation: RootToolInvocation, disclosure: ResultDisclosure): Boolean {
        disclosures.incrementAndGet()
        return true
    }

    override suspend fun cancel(runtimeInvocationId: String): Boolean {
        // A cancelled caller cannot skip this suspension: cleanup must be NonCancellable.
        yield()
        cancels += runtimeInvocationId
        cancelStarted.complete(Unit)
        cancelGate?.await()
        cancelFailure?.let { throw it }
        releaseRemote.countDown()
        return cancelConfirmed
    }

    override fun close() {
        releaseRemote.countDown()
        remote.shutdown()
        check(remote.awaitTermination(5, TimeUnit.SECONDS)) { "Remote test worker leaked" }
    }
}
