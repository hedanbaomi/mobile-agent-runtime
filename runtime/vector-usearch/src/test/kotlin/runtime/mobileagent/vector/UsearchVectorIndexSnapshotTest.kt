// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.vector

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import runtime.mobileagent.knowledge.VectorIndexSnapshotException
import runtime.mobileagent.knowledge.VectorIndexSnapshotIdentity

class UsearchVectorIndexSnapshotTest {

    companion object {
        private const val FOOTER_BYTES = 60
        private const val HASH_BYTES = 32
    }

    private inline fun <T> withTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("mar-usearch-snap-").toFile()
        return try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun recomputeFooterHash(bytes: ByteArray) {
        val payloadLen = bytes.size - FOOTER_BYTES
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, payloadLen))
        System.arraycopy(digest, 0, bytes, bytes.size - HASH_BYTES, HASH_BYTES)
    }

    @Test
    fun realUsearchSnapshotRoundTripPreservesRankingAndSubsequentInsertSequence() = withTempDir { dir ->
        val backend = HostJvmUsearchBackend()
        val factory = UsearchVectorIndexFactory(backend)
        val snapshot = File(dir, "index.snapshot")

        val savedIdentity = (factory.create("space-v1", 3, 8) as UsearchVectorIndex).use { built ->
            built.add("chunk-a", floatArrayOf(1f, 0f, 0f))
            built.add("chunk-b", floatArrayOf(0f, 1f, 0f))
            built.add("chunk-c", floatArrayOf(0.8f, 0.6f, 0f))
            built.saveSnapshot(snapshot)
        }

        assertEquals(VectorIndexSnapshotIdentity("space-v1", 3, listOf("chunk-a", "chunk-b", "chunk-c")), savedIdentity)
        assertEquals(0, backend.liveHandleCount)

        UsearchVectorIndex("space-v1", 3, 8, backend).use { restored ->
            restored.loadSnapshot(snapshot, savedIdentity)
            assertEquals(3, restored.vectorCount)
            val top = restored.search(floatArrayOf(1f, 0f, 0f), 3)
            assertEquals(listOf("chunk-a", "chunk-c", "chunk-b"), top.map { it.first })
            assertEquals(1.0f, top[0].second, 1e-5f)
            assertEquals(0.8f, top[1].second, 1e-5f)

            // Adding a new item after restore must advance nextKey cleanly and enforce duplicate-id guard
            assertThrows(IllegalArgumentException::class.java) {
                restored.add("chunk-a", floatArrayOf(0f, 0f, 1f))
            }
            restored.add("chunk-d", floatArrayOf(0f, 0f, 1f))
            assertEquals(4, restored.vectorCount)
            assertEquals("chunk-d", restored.search(floatArrayOf(0f, 0f, 1f), 1).single().first)
        }
        assertEquals(0, backend.liveHandleCount)
    }

    @Test
    fun corruptAndTruncatedSnapshotsFailClosedWithoutLeakingHandlesOrAffectingLiveIndex() = withTempDir { dir ->
        val backend = HostJvmUsearchBackend()
        val validSnapshot = File(dir, "valid.snapshot")

        // Keep an active in-memory index alive throughout candidate load failures to prove isolation
        UsearchVectorIndex("space-live", 4, 8, backend).use { liveIndex ->
            liveIndex.add("doc-1", floatArrayOf(0.6f, 0.8f, 0f, 0f))
            liveIndex.add("doc-2", floatArrayOf(0f, 0f, 1f, 0f))
            val expected = liveIndex.saveSnapshot(validSnapshot)
            val baseBytes = validSnapshot.readBytes()
            val footerOffset = baseBytes.size - FOOTER_BYTES

            fun assertCandidateRejected(file: File, identity: VectorIndexSnapshotIdentity = expected) {
                val candidate = UsearchVectorIndex(identity.spaceId, identity.dimension, 8, backend)
                assertThrows(VectorIndexSnapshotException::class.java) {
                    candidate.loadSnapshot(file, identity)
                }
                assertEquals(0, candidate.vectorCount)
                assertThrows(IllegalStateException::class.java) {
                    candidate.search(floatArrayOf(1f, 0f, 0f, 0f), 1)
                }
                assertThrows(IllegalStateException::class.java) {
                    candidate.add("doc-x", floatArrayOf(1f, 0f, 0f, 0f))
                }
                candidate.close()
                // Only liveIndex's single native handle remains open!
                assertEquals("Candidate failure must not leak native handles", 1, backend.liveHandleCount)
                assertEquals(2, liveIndex.vectorCount)
                assertEquals("doc-1", liveIndex.search(floatArrayOf(0.6f, 0.8f, 0f, 0f), 1).single().first)
            }

            // 1. Missing file & truncated (< 60B footer and mid-payload truncation)
            assertCandidateRejected(File(dir, "missing.snapshot"))
            assertCandidateRejected(File(dir, "tiny.snapshot").apply { writeBytes(ByteArray(24)) })
            assertCandidateRejected(File(dir, "half.snapshot").apply {
                validSnapshot.copyTo(this, overwrite = true)
                RandomAccessFile(this, "rw").use { it.setLength((baseBytes.size / 2).toLong()) }
            })

            // 2. Bad footer magic, bad version, inconsistent nativeLength
            assertCandidateRejected(File(dir, "bad-magic.snapshot").apply {
                writeBytes(baseBytes.copyOf().also { it[footerOffset] = 0x00 })
            })
            assertCandidateRejected(File(dir, "bad-version.snapshot").apply {
                writeBytes(baseBytes.copyOf().also {
                    ByteBuffer.wrap(it, footerOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(99)
                })
            })
            assertCandidateRejected(File(dir, "bad-layout.snapshot").apply {
                writeBytes(baseBytes.copyOf().also {
                    ByteBuffer.wrap(it, footerOffset + 8, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(999_999L)
                })
            })

            // 3. Bit-flip in native payload & bit-flip in mapping payload (SHA-256 mismatch)
            assertCandidateRejected(File(dir, "tamper-native.snapshot").apply {
                writeBytes(baseBytes.copyOf().also { it[4] = (it[4].toInt() xor 0xFF).toByte() })
            })
            assertCandidateRejected(File(dir, "tamper-mapping.snapshot").apply {
                writeBytes(baseBytes.copyOf().also { it[footerOffset - 6] = (it[footerOffset - 6].toInt() xor 0xFF).toByte() })
            })

            // 4. Identity mismatches (spaceId, dimension, memberIds)
            assertCandidateRejected(validSnapshot, expected.copy(spaceId = "other-space"))
            assertCandidateRejected(validSnapshot, expected.copy(dimension = 8))
            assertCandidateRejected(validSnapshot, expected.copy(memberIds = listOf("doc-1", "doc-999")))
        }
        assertEquals(0, backend.liveHandleCount)
    }

    @Test
    fun nonFiniteMappingAndNativeRestoreValidationFailuresReleaseTemporaryHandles() = withTempDir { dir ->
        val backend = HostJvmUsearchBackend()
        val snapshot = File(dir, "base.snapshot")
        val identity = UsearchVectorIndex("space-check", 2, 4, backend).use { index ->
            index.add("item-1", floatArrayOf(0.25f, 0.75f))
            index.add("item-2", floatArrayOf(0.75f, 0.25f))
            index.saveSnapshot(snapshot)
        }
        val bytes = snapshot.readBytes()
        val footerOffset = bytes.size - FOOTER_BYTES

        // Corrupt the last stored vector float in mapping with Float.NaN and recompute SHA-256
        val nanBytes = bytes.copyOf()
        ByteBuffer.wrap(nanBytes, footerOffset - 4, 4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN)
        recomputeFooterHash(nanBytes)
        val nanFile = File(dir, "nan-vector.snapshot").apply { writeBytes(nanBytes) }
        UsearchVectorIndex("space-check", 2, 4, backend).use { victim ->
            val ex = assertThrows(VectorIndexSnapshotException::class.java) { victim.loadSnapshot(nanFile, identity) }
            assertTrue(ex.message!!.contains("non-finite"))
            assertEquals(0, victim.vectorCount)
        }

        // Corrupt native magic while keeping mapping + SHA-256 valid so backend.restore throws
        val badNativeBytes = bytes.copyOf().also { it[0] = 0x00; recomputeFooterHash(it) }
        val badNativeFile = File(dir, "bad-native.snapshot").apply { writeBytes(badNativeBytes) }
        UsearchVectorIndex("space-check", 2, 4, backend).use { victim ->
            assertThrows(VectorIndexSnapshotException::class.java) { victim.loadSnapshot(badNativeFile, identity) }
        }

        // Post-restore checks: zero handle, count mismatch, non-sequential keys, dimension mismatch
        val postRestoreHooks: List<() -> Unit> = listOf(
            { backend.returnZeroOnNextRestore = true },
            { backend.restoreCountDelta = -1 },
            { backend.forceNonSequentialKeysOnRestore = true },
            { backend.restoreDimensionOverride = 8 },
        )
        for (armFailure in postRestoreHooks) {
            armFailure()
            val victim = UsearchVectorIndex("space-check", 2, 4, backend)
            assertThrows(VectorIndexSnapshotException::class.java) { victim.loadSnapshot(snapshot, identity) }
            assertEquals(0, victim.vectorCount)
            assertEquals("Restored handle must be closed on post-restore validation failure", 0, backend.liveHandleCount)
            victim.close()
        }
    }

    @Test
    fun failedSaveSnapshotDeletesPartialFileAndPreservesInMemoryState() = withTempDir { dir ->
        val backend = HostJvmUsearchBackend()
        UsearchVectorIndex("space-save", 3, 4, backend).use { index ->
            index.add("k1", floatArrayOf(1f, 0f, 0f))
            index.add("k2", floatArrayOf(0f, 1f, 0f))

            // 1. Mid-write failure after writing 28 partial bytes -> must delete partial file and preserve memory
            val partialTarget = File(dir, "partial.snapshot")
            backend.failNextSaveAfterBytes = 28
            assertThrows(VectorIndexSnapshotException::class.java) {
                index.saveSnapshot(partialTarget)
            }
            assertFalse("Half-written snapshot file must be deleted", partialTarget.exists())
            assertEquals(2, index.vectorCount)
            assertEquals("k1", index.search(floatArrayOf(1f, 0f, 0f), 1).single().first)

            // 2. Non-sequential key invariant failure before write -> no leftover file, memory intact
            val preWriteTarget = File(dir, "prewrite.snapshot").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            backend.forceNonSequentialKeysOnSave = true
            assertThrows(VectorIndexSnapshotException::class.java) {
                index.saveSnapshot(preWriteTarget)
            }
            assertFalse("Failed target file must be cleaned up", preWriteTarget.exists())

            // 3. Subsequent retry on the same live index succeeds cleanly
            val cleanTarget = File(dir, "retry-ok.snapshot")
            val saved = index.saveSnapshot(cleanTarget)
            assertTrue(cleanTarget.isFile && cleanTarget.length() > FOOTER_BYTES)
            assertEquals(listOf("k1", "k2"), saved.memberIds)
        }
        assertEquals(0, backend.liveHandleCount)
    }
}
