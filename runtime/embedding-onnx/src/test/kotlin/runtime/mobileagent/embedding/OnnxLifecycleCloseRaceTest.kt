// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxLifecycleCloseRaceTest {

    @Test
    fun closeStopsAdmissionImmediatelyAndReleasesRunnerOnlyAfterLastInFlightExits() {
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 6, dimension = 6) { pack ->
            val enteredRun = CountDownLatch(2)
            val finishFirst = CountDownLatch(1)
            val finishSecond = CountDownLatch(1)
            lateinit var runner: DeterministicSessionRunner
            runner = DeterministicSessionRunner(
                hiddenDimension = 6,
                expectedPadId = 3L,
                runOverride = { recorded, runIndex ->
                    enteredRun.countDown()
                    val gate = if (runIndex == 0) finishFirst else finishSecond
                    check(gate.await(5, TimeUnit.SECONDS)) { "Timed out waiting for release gate $runIndex" }
                    runner.synthesizeContextualHiddenStates(recorded)
                },
            )
            val embedder = OnnxTextEmbedder(pack, runner)
            val alphaEncoded = embedder.tokenizer.encode("alpha beta")
            val executor = Executors.newFixedThreadPool(6)
            try {
                val firstInFlight = executor.submit<List<FloatArray>> {
                    embedder.embedBatch(listOf("alpha beta"))
                }
                val secondInFlight = executor.submit<List<FloatArray>> {
                    runBlocking(Dispatchers.IO) {
                        embedder.embedBatchCancellable(listOf("dog fox"))
                    }
                }
                assertTrue("Both requests must enter runner.run", enteredRun.await(5, TimeUnit.SECONDS))
                assertEquals(2, embedder.coordinator.activeInFlight)
                assertEquals(0, runner.closeCalls.get())

                // Trigger close() concurrently from 4 threads while 2 inferences are actively executing.
                val closeBarrier = CountDownLatch(1)
                val closeFutures = List(4) {
                    executor.submit {
                        check(closeBarrier.await(5, TimeUnit.SECONDS))
                        embedder.close()
                    }
                }
                closeBarrier.countDown()
                closeFutures.forEach { it.get(5, TimeUnit.SECONDS) }

                // 1. Admission stops immediately across all entry points while in-flight requests are still running.
                assertTrue(embedder.coordinator.isClosing)
                assertEquals("Runner must NOT close while in-flight requests are active", 0, runner.closeCalls.get())
                assertThrows(IllegalStateException::class.java) { embedder.embed("cat") }
                assertThrows(IllegalStateException::class.java) { embedder.embedBatch(listOf("cat")) }
                assertThrows(IllegalStateException::class.java) { embedder.embedBatch(emptyList()) }
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { embedder.embedBatchCancellable(listOf("cat")) }
                }
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { embedder.embed(listOf("cat")) }
                }
                assertEquals("Rejected calls must never reach runner", 2, runner.runs.size)

                // 2. First in-flight finishes: skips closed-cache write-back cleanly; runner STILL stays open for #2.
                finishFirst.countDown()
                val firstVectors = firstInFlight.get(5, TimeUnit.SECONDS)
                assertEquals(1, firstVectors.size)
                assertEquals(6, firstVectors.single().size)
                assertEquals(1, embedder.coordinator.activeInFlight)
                assertEquals("Runner must remain open until the FINAL in-flight exits", 0, runner.closeCalls.get())

                // 3. Second (final) in-flight exits finally -> runner.close() executes immediately and exactly once.
                finishSecond.countDown()
                val secondVectors = secondInFlight.get(5, TimeUnit.SECONDS)
                assertEquals(1, secondVectors.size)
                assertEquals(6, secondVectors.single().size)
                assertEquals(0, embedder.coordinator.activeInFlight)
                assertEquals(1, runner.closeCalls.get())
                assertTrue(runner.allocatedTensors.all { it.isClosed })

                // 4. Subsequent close() is idempotent and windowCache remained closed without repopulation.
                embedder.close()
                assertEquals(1, runner.closeCalls.get())
                assertThrows(IllegalStateException::class.java) {
                    embedder.coordinator.windowCache.get(alphaEncoded)
                }
            } finally {
                finishFirst.countDown()
                finishSecond.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun idleCloseAndFailingFinalInFlightReleaseRunnerExactlyOncePreservingPrimaryFailure() {
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 4, dimension = 4) { pack ->
            val idleRunner = DeterministicSessionRunner(hiddenDimension = 4)
            val idleEmbedder = OnnxTextEmbedder(pack, idleRunner)
            assertFalse(idleEmbedder.coordinator.isClosing)
            idleEmbedder.close()
            assertEquals("Idle close must release runner synchronously", 1, idleRunner.closeCalls.get())
            idleEmbedder.close()
            assertEquals(1, idleRunner.closeCalls.get())
        }

        // Last in-flight throws an inference error AND runner.close() throws during deferred release:
        // primary inference exception must be preserved, close failure attached as suppressed, and close called once.
        TestOnnxFixtures.withModelPack(padId = 3, hiddenDimension = 4, dimension = 4) { pack ->
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val runner = DeterministicSessionRunner(
                hiddenDimension = 4,
                onCloseRunnerHook = { throw IllegalStateException("deferred runner close error") },
                runOverride = { _, _ ->
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                    throw IllegalArgumentException("primary ORT execution failure")
                },
            )
            val embedder = OnnxTextEmbedder(pack, runner)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<Unit> {
                    val primary = assertThrows(IllegalArgumentException::class.java) {
                        embedder.embed("alpha beta")
                    }
                    assertEquals("primary ORT execution failure", primary.message)
                    assertEquals(1, primary.suppressed.size)
                    assertEquals("deferred runner close error", primary.suppressed.single().message)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                embedder.close()
                assertEquals(0, runner.closeCalls.get())
                proceed.countDown()
                future.get(5, TimeUnit.SECONDS)
                assertEquals(1, runner.closeCalls.get())
                assertTrue(runner.allocatedTensors.all { it.isClosed })
                embedder.close()
                assertEquals("Runner close must remain idempotent even after failure", 1, runner.closeCalls.get())
            } finally {
                proceed.countDown()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun ortSessionRunnerDirectCloseStopsAdmissionRejectsQueuedRunAndClosesBackendOnce() {
        // Verify Android ORT API classes from onnxruntime-android are linked on the runtime classpath.
        assertNotNull(OrtEnvironment::class.java.name)
        assertNotNull(OrtSession::class.java.name)

        val runEntered = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val tensorCreateCalls = AtomicInteger(0)
        val runSessionCalls = AtomicInteger(0)
        val backendCloseCalls = AtomicInteger(0)

        val controlledBackend = object : OrtBackendSession {
            override val inputNames: Set<String> = setOf("input_ids", "attention_mask")

            override fun createLongTensor(data: Array<LongArray>): OnnxBatchTensor {
                check(backendCloseCalls.get() == 0) { "createLongTensor called after backend closed" }
                tensorCreateCalls.incrementAndGet()
                return RecordingBatchTensor(data)
            }

            override fun runSession(inputs: Map<String, OnnxBatchTensor>, outputName: String): Any {
                check(backendCloseCalls.get() == 0) { "runSession called after backend closed" }
                runSessionCalls.incrementAndGet()
                runEntered.countDown()
                check(releaseRun.await(5, TimeUnit.SECONDS))
                return arrayOf(arrayOf(floatArrayOf(1f, 0f)))
            }

            override fun close() {
                backendCloseCalls.incrementAndGet()
            }
        }

        val ortRunner = OrtSessionRunner(controlledBackend)
        assertEquals(setOf("input_ids", "attention_mask"), ortRunner.inputNames)
        val t1 = ortRunner.createLongBatchTensor(arrayOf(longArrayOf(101L, 102L)))
        val t2 = ortRunner.createLongBatchTensor(arrayOf(longArrayOf(1L, 1L)))
        val inputs = mapOf("input_ids" to t1, "attention_mask" to t2)

        val pool = Executors.newFixedThreadPool(3)
        try {
            val activeRun = pool.submit<Any> { ortRunner.run(inputs, "last_hidden_state") }
            assertTrue(runEntered.await(5, TimeUnit.SECONDS))

            // Start a second caller that enters outer lease before close() and queues on runLock.
            val queuedStarted = CountDownLatch(1)
            val queuedRun = pool.submit<Throwable?> {
                queuedStarted.countDown()
                runCatching { ortRunner.run(inputs, "last_hidden_state") }.exceptionOrNull()
            }
            assertTrue(queuedStarted.await(5, TimeUnit.SECONDS))
            while (ortRunner.lifecycle.activeInFlight < 2) {
                Thread.yield()
            }

            // Initiate close() while activeRun holds runLock and queuedRun waits on runLock.
            ortRunner.close()
            assertEquals("Backend must not close while in-flight run is active", 0, backendCloseCalls.get())

            // New createLongBatchTensor and run calls fail immediately without blocking on runLock!
            val immediateReject = pool.submit {
                assertThrows(IllegalStateException::class.java) {
                    ortRunner.createLongBatchTensor(arrayOf(longArrayOf(101L)))
                }
                assertThrows(IllegalStateException::class.java) {
                    ortRunner.run(inputs, "last_hidden_state")
                }
            }
            immediateReject.get(2, TimeUnit.SECONDS)
            assertEquals(2, tensorCreateCalls.get())

            // Unblock activeRun: activeRun succeeds, queuedRun aborts on runLock check without calling runSession,
            // and backend.close() executes once when the final lease exits!
            releaseRun.countDown()
            assertNotNull(activeRun.get(5, TimeUnit.SECONDS))
            val queuedError = queuedRun.get(5, TimeUnit.SECONDS)
            assertTrue(queuedError is IllegalStateException)
            assertEquals("Queued caller must not execute backend.runSession after close()", 1, runSessionCalls.get())
            assertEquals(1, backendCloseCalls.get())

            ortRunner.close()
            assertEquals(1, backendCloseCalls.get())
            assertEquals(setOf("input_ids", "attention_mask"), ortRunner.inputNames)
        } finally {
            releaseRun.countDown()
            t1.close()
            t2.close()
            pool.shutdownNow()
        }
    }
}
