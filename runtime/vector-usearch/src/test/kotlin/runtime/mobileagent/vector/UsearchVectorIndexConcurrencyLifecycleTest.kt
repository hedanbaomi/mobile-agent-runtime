// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.vector

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsearchVectorIndexConcurrencyLifecycleTest {

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("mar-usearch-conc-").toFile()
        return try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun concurrentAddSearchSaveAndLoadAreStrictlySerializedWithoutRacesOrHandleLeaks() = withTempDir { dir ->
        val backend = HostJvmUsearchBackend()
        val dim = 4
        val writerCount = 3
        val itemsPerWriter = 40
        val searcherCount = 3
        val snapshotterCount = 2
        val totalWorkers = writerCount + searcherCount + snapshotterCount

        UsearchVectorIndex("space-concurrent", dim, writerCount * itemsPerWriter + 16, backend).use { sharedIndex ->
            val pool = Executors.newFixedThreadPool(totalWorkers)
            val startLatch = CountDownLatch(1)
            val writersDone = AtomicBoolean(false)
            val doneLatch = CountDownLatch(totalWorkers)
            val failureRef = AtomicReference<Throwable?>(null)

            try {
                // Writers concurrently inserting vectors
                for (w in 0 until writerCount) {
                    pool.submit {
                        try {
                            startLatch.await()
                            val rng = Random(w * 997 + 17)
                            for (i in 0 until itemsPerWriter) {
                                val vec = FloatArray(dim) { (rng.nextFloat() + 0.05f) }
                                sharedIndex.add("w-$w-$i", vec)
                            }
                        } catch (t: Throwable) {
                            failureRef.compareAndSet(null, t)
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }

                // Searchers continuously querying sharedIndex while writes and snapshots run
                for (s in 0 until searcherCount) {
                    pool.submit {
                        try {
                            startLatch.await()
                            val rng = Random(s * 521 + 3)
                            while (!writersDone.get()) {
                                val query = FloatArray(dim) { rng.nextFloat() + 0.1f }
                                val hits = sharedIndex.search(query, 5)
                                assertTrue(hits.all { it.second.isFinite() })
                            }
                        } catch (t: Throwable) {
                            failureRef.compareAndSet(null, t)
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }

                // Snapshotters concurrently saving sharedIndex and loading into fresh instances
                for (p in 0 until snapshotterCount) {
                    pool.submit {
                        try {
                            startLatch.await()
                            var seq = 0
                            while (!writersDone.get()) {
                                val snapFile = File(dir, "snap-$p-${seq++}.idx")
                                val identity = sharedIndex.saveSnapshot(snapFile)
                                UsearchVectorIndex("space-concurrent", dim, maxOf(1, identity.memberIds.size), backend)
                                    .use { verifyIndex ->
                                        verifyIndex.loadSnapshot(snapFile, identity)
                                        assertEquals(identity.memberIds.size, verifyIndex.vectorCount)
                                        if (identity.memberIds.isNotEmpty()) {
                                            val res = verifyIndex.search(floatArrayOf(1f, 1f, 1f, 1f), 3)
                                            assertTrue(res.isNotEmpty())
                                        }
                                    }
                                snapFile.delete()
                            }
                        } catch (t: Throwable) {
                            failureRef.compareAndSet(null, t)
                        } finally {
                            doneLatch.countDown()
                        }
                    }
                }

                startLatch.countDown()
                // Wait until all writers finish inserting all items, then stop background loops
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (sharedIndex.vectorCount < writerCount * itemsPerWriter &&
                    failureRef.get() == null &&
                    System.nanoTime() < deadline
                ) {
                    Thread.sleep(5)
                }
                writersDone.set(true)
                assertTrue("Workers timed out", doneLatch.await(15, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }

            assertNull("Concurrent workload failed: ${failureRef.get()}", failureRef.get())
            assertEquals(writerCount * itemsPerWriter, sharedIndex.vectorCount)
            assertEquals(0, backend.concurrentAccessViolations.get())
            assertEquals(0, backend.doubleOrUnknownCloseCount.get())
        }
        assertEquals(0, backend.liveHandleCount)
    }

    @Test
    fun concurrentLoadSearchAndSaveOnSameInstanceSerializeCleanly() = withTempDir { dir ->
        val backend = HostJvmUsearchBackend()
        val snapA = File(dir, "genA.idx")
        val snapB = File(dir, "genB.idx")

        val idA = UsearchVectorIndex("space-swap", 2, 4, backend).use { idx ->
            idx.add("a1", floatArrayOf(1f, 0f))
            idx.saveSnapshot(snapA)
        }
        val idB = UsearchVectorIndex("space-swap", 2, 4, backend).use { idx ->
            idx.add("b1", floatArrayOf(0f, 1f))
            idx.add("b2", floatArrayOf(0.7f, 0.7f))
            idx.saveSnapshot(snapB)
        }

        UsearchVectorIndex("space-swap", 2, 4, backend).use { target ->
            target.loadSnapshot(snapA, idA)
            val pool = Executors.newFixedThreadPool(4)
            val start = CountDownLatch(1)
            val done = CountDownLatch(4)
            val failure = AtomicReference<Throwable?>(null)

            try {
                repeat(2) { loaderIdx ->
                    pool.submit {
                        try {
                            start.await()
                            repeat(25) { step ->
                                if ((loaderIdx + step) % 2 == 0) {
                                    target.loadSnapshot(snapA, idA)
                                } else {
                                    target.loadSnapshot(snapB, idB)
                                }
                            }
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)
                        } finally {
                            done.countDown()
                        }
                    }
                }
                repeat(2) { readerIdx ->
                    pool.submit {
                        try {
                            start.await()
                            repeat(35) { step ->
                                val hits = target.search(floatArrayOf(1f, 1f), 2)
                                assertTrue(hits.size in 1..2)
                                val out = File(dir, "re-save-$readerIdx-$step.idx")
                                val saved = target.saveSnapshot(out)
                                assertTrue(saved.memberIds.size in 1..2)
                                out.delete()
                            }
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)
                        } finally {
                            done.countDown()
                        }
                    }
                }
                start.countDown()
                assertTrue(done.await(15, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }

            assertNull("Concurrent reload/search/save failed: ${failure.get()}", failure.get())
            assertEquals(0, backend.concurrentAccessViolations.get())
            assertEquals(0, backend.doubleOrUnknownCloseCount.get())
        }
        assertEquals(0, backend.liveHandleCount)
    }

    @Test
    fun closeIsIdempotentAndRejectsEverySubsequentOperationIncludingLoadSnapshot() = withTempDir { dir ->
        val backend = HostJvmUsearchBackend()
        val validSnapshot = File(dir, "valid.idx")
        val index = UsearchVectorIndex("space-close", 2, 4, backend)
        index.add("item-1", floatArrayOf(1f, 0f))

        // Input validation guards while open
        assertThrows(IllegalArgumentException::class.java) { index.add("bad-dim", floatArrayOf(1f, 2f, 3f)) }
        assertThrows(IllegalArgumentException::class.java) { index.add("bad-nan", floatArrayOf(Float.NaN, 1f)) }
        assertThrows(IllegalArgumentException::class.java) { index.search(floatArrayOf(1f), 1) }
        assertThrows(IllegalArgumentException::class.java) { index.search(floatArrayOf(Float.POSITIVE_INFINITY, 0f), 1) }
        assertEquals(emptyList<Pair<String, Float>>(), index.search(floatArrayOf(1f, 0f), 0))

        val identity = index.saveSnapshot(validSnapshot)

        // Repeated close must release native handle once and set vectorCount to 0
        index.close()
        index.close()
        index.close()
        assertEquals(1, backend.totalCloseCalls.get())
        assertEquals(0, backend.liveHandleCount)
        assertEquals(0, index.vectorCount)

        // All mutating, query, save, and load calls after close() must fail closed with IllegalStateException
        assertThrows(IllegalStateException::class.java) { index.add("item-2", floatArrayOf(0f, 1f)) }
        assertThrows(IllegalStateException::class.java) { index.search(floatArrayOf(1f, 0f), 1) }
        assertThrows(IllegalStateException::class.java) { index.saveSnapshot(File(dir, "after-close.idx")) }
        assertThrows(IllegalStateException::class.java) { index.loadSnapshot(validSnapshot, identity) }
        assertEquals("loadSnapshot after close() must never resurrect a handle", 0, backend.liveHandleCount)
    }
}
