// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxColdWindowSingleFlightTest {

    @Test
    fun interruptedMultiWindowPlanRollsBackBeforeAnotherRequestCanJoin() {
        val runner = DeterministicSessionRunner(hiddenDimension = 1)
        val coordinator = OnnxRuntimeCoordinator(runner)
        val first = BertWordPieceTokenizer.Encoded(longArrayOf(1), longArrayOf(1), longArrayOf(0))
        val second = first.copy(inputIds = longArrayOf(2))
        val interruptOnSecond = object : HashMap<BertWordPieceTokenizer.Encoded, FloatArray>() {
            override fun containsKey(key: BertWordPieceTokenizer.Encoded): Boolean {
                if (key == second) {
                    assertEquals(1, coordinator.inFlightWindowCount)
                    Thread.currentThread().interrupt()
                }
                return super.containsKey(key)
            }
        }
        try {
            assertThrows(InterruptedException::class.java) {
                coordinator.withLease { coordinator.claimOrJoin(linkedSetOf(first, second), interruptOnSecond) }
            }
        } finally {
            Thread.interrupted()
        }
        assertEquals(0, coordinator.inFlightWindowCount)
        val retried = coordinator.withLease {
            coordinator.resolveWindows(setOf(first), 1, {}, { _, _ -> listOf(floatArrayOf(1f)) },
                { error("retry joined an abandoned slot") })
        }
        assertArrayEquals(floatArrayOf(1f), retried.getValue(first), 0f)
        coordinator.close()
        coordinator.close()
        assertEquals(1, runner.closeCalls.get())
    }

    @Test
    fun concurrentEmbedBatchAndCancellableCoalesceColdWindowsToSingleInferenceWithIsolatedCopies() {
        val requests = listOf(
            listOf("alpha beta. common cat.", "dog fox."),
            listOf("common cat.", "alpha beta."),
            listOf("dog fox. alpha beta."),
            listOf("alpha beta.", "common cat.", "dog fox."),
            listOf("common cat."),
            listOf("alpha beta. dog fox."),
            listOf("dog fox."),
            listOf("common cat. alpha beta."),
        )

        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 6, dimension = 6, maxSequenceLength = 12) { pack ->
            // Compute single-threaded reference vectors first
            val expectedPerCaller = requests.map { batch ->
                val refRunner = DeterministicSessionRunner(hiddenDimension = 6, expectedPadId = 3L)
                OnnxTextEmbedder(pack, refRunner).use { ref -> ref.embedBatch(batch) }
            }

            val firstRunEntered = CountDownLatch(1)
            val releaseFirstRun = CountDownLatch(1)
            val pausedOnce = AtomicBoolean(false)
            lateinit var runner: DeterministicSessionRunner
            runner = DeterministicSessionRunner(
                hiddenDimension = 6,
                expectedPadId = 3L,
                runOverride = { recorded, _ ->
                    if (pausedOnce.compareAndSet(false, true)) {
                        firstRunEntered.countDown()
                        check(releaseFirstRun.await(5, TimeUnit.SECONDS))
                    }
                    runner.synthesizeContextualHiddenStates(recorded)
                },
            )

            OnnxTextEmbedder(pack, runner).use { embedder ->
                val pool = Executors.newFixedThreadPool(8)
                val startGate = CountDownLatch(1)
                try {
                    val futures = requests.mapIndexed { idx, batch ->
                        pool.submit<List<FloatArray>> {
                            check(startGate.await(5, TimeUnit.SECONDS))
                            if (idx % 2 == 0) {
                                embedder.embedBatch(batch)
                            } else {
                                runBlocking(Dispatchers.IO) {
                                    embedder.embedBatchCancellable(batch)
                                }
                            }
                        }
                    }
                    startGate.countDown()
                    assertTrue(firstRunEntered.await(5, TimeUnit.SECONDS))
                    // Allow remaining concurrent callers to enter coordinator while leader is mid-flight
                    while (embedder.coordinator.activeInFlight < 4) {
                        Thread.yield()
                    }
                    releaseFirstRun.countDown()

                    val actualPerCaller = futures.map { it.get(5, TimeUnit.SECONDS) }
                    val totalInferredWindows = runner.runs.sumOf { it.inputIds.size }
                    // Across all 8 concurrent requests there are exactly 3 unique sentence windows:
                    // "alpha beta.", "common cat.", "dog fox." -> each must be inferred once!
                    assertEquals("Each unique cold window must be inferred only once", 3, totalInferredWindows)
                    assertEquals(0, embedder.coordinator.inFlightWindowCount)

                    for (callerIdx in requests.indices) {
                        val expectedBatch = expectedPerCaller[callerIdx]
                        val actualBatch = actualPerCaller[callerIdx]
                        assertEquals(expectedBatch.size, actualBatch.size)
                        for (docIdx in expectedBatch.indices) {
                            assertArrayEquals(expectedBatch[docIdx], actualBatch[docIdx], 1e-6f)
                        }
                    }

                    // Defensive copy check: mutating one caller's returned array never affects siblings or warm cache
                    actualPerCaller[0][0][0] = 999.0f
                    assertNotEquals(999.0f, actualPerCaller[3][0][0], 1e-6f)
                    val warmHit = embedder.embed("alpha beta. common cat.")
                    assertArrayEquals(expectedPerCaller[0][0], warmHit, 1e-6f)
                    assertEquals("Warm cache hit must not trigger extra ONNX runs", 3, runner.runs.sumOf { it.inputIds.size })
                } finally {
                    releaseFirstRun.countDown()
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test
    fun cancellingCoroutineWaiterOrInterruptingThreadWaiterIsolatesFailureFromLeaderAndSiblings() {
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 6, dimension = 6) { pack ->
            val expected = OnnxTextEmbedder(pack, DeterministicSessionRunner(hiddenDimension = 6)).use {
                it.embed("alpha beta gamma")
            }

            // 1. Coroutine waiter cancelled via Job.cancel() while leader is paused inside runner.run
            val leaderInRun = CountDownLatch(1)
            val allowLeaderFinish = CountDownLatch(1)
            lateinit var runner1: DeterministicSessionRunner
            runner1 = DeterministicSessionRunner(
                hiddenDimension = 6,
                runOverride = { recorded, _ ->
                    leaderInRun.countDown()
                    check(allowLeaderFinish.await(5, TimeUnit.SECONDS))
                    runner1.synthesizeContextualHiddenStates(recorded)
                },
            )
            OnnxTextEmbedder(pack, runner1).use { embedder ->
                runBlocking {
                    val leader = async(Dispatchers.IO) {
                        embedder.embedBatchCancellable(listOf("alpha beta gamma"))
                    }
                    assertTrue(leaderInRun.await(5, TimeUnit.SECONDS))

                    val cancelledWaiter = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) {
                        embedder.embedBatchCancellable(listOf("alpha beta gamma"))
                    }
                    val survivingCoroutineWaiter = async(Dispatchers.IO) {
                        embedder.embedBatchCancellable(listOf("alpha beta gamma"))
                    }
                    val survivingThreadPool = Executors.newSingleThreadExecutor()
                    val survivingThreadWaiter = survivingThreadPool.submit<List<FloatArray>> {
                        embedder.embedBatch(listOf("alpha beta gamma"))
                    }
                    try {
                        while (embedder.coordinator.totalWaitersJoined < 3) {
                            Thread.yield()
                        }
                        // Cancel waiter BEFORE leader finishes runner.run: must exit promptly without waiting on leader!
                        withTimeout(2_000) {
                            cancelledWaiter.cancelAndJoin()
                        }
                        assertTrue(cancelledWaiter.isCancelled)

                        allowLeaderFinish.countDown()
                        assertArrayEquals(expected, leader.await().single(), 1e-6f)
                        assertArrayEquals(expected, survivingCoroutineWaiter.await().single(), 1e-6f)
                        assertArrayEquals(expected, survivingThreadWaiter.get(5, TimeUnit.SECONDS).single(), 1e-6f)
                        assertEquals(1, runner1.runs.size)
                    } finally {
                        allowLeaderFinish.countDown()
                        survivingThreadPool.shutdownNow()
                    }
                }
            }

            // 2. Synchronous embedBatch waiter interrupted via Thread.interrupt() while leader is paused
            val leaderInRun2 = CountDownLatch(1)
            val allowLeaderFinish2 = CountDownLatch(1)
            lateinit var runner2: DeterministicSessionRunner
            runner2 = DeterministicSessionRunner(
                hiddenDimension = 6,
                runOverride = { recorded, _ ->
                    leaderInRun2.countDown()
                    check(allowLeaderFinish2.await(5, TimeUnit.SECONDS))
                    runner2.synthesizeContextualHiddenStates(recorded)
                },
            )
            OnnxTextEmbedder(pack, runner2).use { embedder ->
                val pool = Executors.newFixedThreadPool(3)
                val waiterThreadRef = AtomicReference<Thread?>()
                val waiterReady = CountDownLatch(1)
                try {
                    val leader = pool.submit<List<FloatArray>> {
                        embedder.embedBatch(listOf("alpha beta gamma"))
                    }
                    assertTrue(leaderInRun2.await(5, TimeUnit.SECONDS))

                    val interruptedWaiter = pool.submit<List<FloatArray>> {
                        waiterThreadRef.set(Thread.currentThread())
                        waiterReady.countDown()
                        embedder.embedBatch(listOf("alpha beta gamma"))
                    }
                    val survivingWaiter = pool.submit<List<FloatArray>> {
                        embedder.embedBatch(listOf("alpha beta gamma"))
                    }
                    assertTrue(waiterReady.await(5, TimeUnit.SECONDS))
                    while (embedder.coordinator.totalWaitersJoined < 2) {
                        Thread.yield()
                    }

                    waiterThreadRef.get()!!.interrupt()
                    val execEx = assertThrows(ExecutionException::class.java) {
                        interruptedWaiter.get(2, TimeUnit.SECONDS)
                    }
                    assertTrue(execEx.cause is InterruptedException)

                    allowLeaderFinish2.countDown()
                    assertArrayEquals(expected, leader.get(5, TimeUnit.SECONDS).single(), 1e-6f)
                    assertArrayEquals(expected, survivingWaiter.get(5, TimeUnit.SECONDS).single(), 1e-6f)
                    assertEquals(1, runner2.runs.size)
                } finally {
                    allowLeaderFinish2.countDown()
                    pool.shutdownNow()
                }
            }
        }
    }

    @Test
    fun leaderCancellationOrInterruptionTransfersColdWindowOwnershipToSurvivingCallers() {
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 6, dimension = 6) { pack ->
            val expectedAlpha = OnnxTextEmbedder(pack, DeterministicSessionRunner(hiddenDimension = 6)).use {
                it.embed("alpha beta delta")
            }
            val expectedTree = OnnxTextEmbedder(pack, DeterministicSessionRunner(hiddenDimension = 6)).use {
                it.embed("tree river stone")
            }

            // 1. Leader coroutine cancelled after claiming cold window while 2 concurrent callers are waiting
            val leaderClaimed = CountDownLatch(1)
            val cancelReady = CountDownLatch(1)
            val firstHookOnly = AtomicBoolean(false)
            val runner1 = DeterministicSessionRunner(
                hiddenDimension = 6,
                afterCreateTensorIndex = 2,
                afterCreateHook = {
                    if (firstHookOnly.compareAndSet(false, true)) {
                        leaderClaimed.countDown()
                        check(cancelReady.await(5, TimeUnit.SECONDS))
                    }
                },
            )
            OnnxTextEmbedder(pack, runner1).use { embedder ->
                val threadPool = Executors.newSingleThreadExecutor()
                try {
                    runBlocking {
                        val leaderJob = async(Dispatchers.IO) {
                            embedder.embedBatchCancellable(listOf("alpha beta delta"))
                        }
                        assertTrue(leaderClaimed.await(5, TimeUnit.SECONDS))

                        val waiterCoroutine = async(Dispatchers.IO) {
                            embedder.embedBatchCancellable(listOf("alpha beta delta"))
                        }
                        val waiterThread = threadPool.submit<List<FloatArray>> {
                            embedder.embedBatch(listOf("alpha beta delta"))
                        }
                        while (embedder.coordinator.totalWaitersJoined < 2) {
                            Thread.yield()
                        }

                        // Cancel leader Job before checkCancellation(), then release hook so leader throws CancellationException!
                        leaderJob.cancel()
                        cancelReady.countDown()
                        assertThrows(CancellationException::class.java) {
                            runBlocking { leaderJob.await() }
                        }

                        // Surviving coroutine & thread waiters must NOT be cancelled; one takes over and runs inference once!
                        assertArrayEquals(expectedAlpha, waiterCoroutine.await().single(), 1e-6f)
                        assertArrayEquals(expectedAlpha, waiterThread.get(5, TimeUnit.SECONDS).single(), 1e-6f)
                        assertEquals("Surviving leader must run ONNX session exactly once", 1, runner1.runs.size)
                        assertTrue(runner1.allocatedTensors.all { it.isClosed })
                        assertEquals(0, embedder.coordinator.inFlightWindowCount)
                    }
                } finally {
                    cancelReady.countDown()
                    threadPool.shutdownNow()
                }
            }

            // 2. Leader thread fails/cancels inside runner.run (attempt #0) -> surviving callers take over on attempt #1
            val leaderInRun = CountDownLatch(1)
            val failLeaderNow = CountDownLatch(1)
            lateinit var runner2: DeterministicSessionRunner
            runner2 = DeterministicSessionRunner(
                hiddenDimension = 6,
                runOverride = { recorded, runIndex ->
                    if (runIndex == 0) {
                        leaderInRun.countDown()
                        check(failLeaderNow.await(5, TimeUnit.SECONDS))
                        throw CancellationException("leader cancelled mid-flight")
                    }
                    runner2.synthesizeContextualHiddenStates(recorded)
                },
            )
            OnnxTextEmbedder(pack, runner2).use { embedder ->
                val pool = Executors.newFixedThreadPool(3)
                try {
                    val failingLeader = pool.submit<List<FloatArray>> {
                        embedder.embedBatch(listOf("tree river stone"))
                    }
                    assertTrue(leaderInRun.await(5, TimeUnit.SECONDS))

                    val waiter1 = pool.submit<List<FloatArray>> {
                        embedder.embedBatch(listOf("tree river stone"))
                    }
                    val waiter2 = pool.submit<List<FloatArray>> {
                        runBlocking(Dispatchers.IO) {
                            embedder.embedBatchCancellable(listOf("tree river stone"))
                        }
                    }
                    while (embedder.coordinator.totalWaitersJoined < 2) {
                        Thread.yield()
                    }

                    failLeaderNow.countDown()
                    val leaderFailure = assertThrows(ExecutionException::class.java) {
                        failingLeader.get(5, TimeUnit.SECONDS)
                    }
                    assertTrue(leaderFailure.cause is CancellationException)

                    assertArrayEquals(expectedTree, waiter1.get(5, TimeUnit.SECONDS).single(), 1e-6f)
                    assertArrayEquals(expectedTree, waiter2.get(5, TimeUnit.SECONDS).single(), 1e-6f)
                    assertEquals(2, runner2.runs.size)
                    assertTrue(runner2.allocatedTensors.all { it.isClosed })
                    assertEquals(0, embedder.coordinator.inFlightWindowCount)
                } finally {
                    failLeaderNow.countDown()
                    pool.shutdownNow()
                }
            }
        }
    }
}
