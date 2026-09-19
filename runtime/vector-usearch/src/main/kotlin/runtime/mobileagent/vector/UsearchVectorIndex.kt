// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.vector

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import runtime.mobileagent.knowledge.VectorIndexFactory
import runtime.mobileagent.knowledge.VectorIndexSnapshotException
import runtime.mobileagent.knowledge.VectorIndexSnapshotIdentity
import runtime.mobileagent.knowledge.VectorIndexSnapshotPort

/**
 * Real USearch-backed Android ANN index.  The native library is built from
 * the official USearch v2.25.1 headers for both arm64-v8a and x86_64; no
 * brute-force fallback is hidden behind this class.  Callers that cannot load
 * the native library should omit the factory and use the explicit JVM index.
 *
 * The USearch binary carries the proximity graph, the vectors and the numeric
 * u64 keys, but *not* the chunkId<->key mapping and not the scoring path, so
 * [saveSnapshot] appends a mapping region after the native payload.  The
 * result is one self-contained file, which lets a caller publish it with a
 * single atomic rename.  [loadSnapshot] verifies the payload hash, the footer
 * and the mapping identity before the handle becomes usable, so a corrupt,
 * truncated or mismatched snapshot is rejected instead of silently serving a
 * partial index.
 */
class UsearchVectorIndex(
    override val spaceId: String,
    override val dimension: Int,
    capacity: Int,
) : VectorIndex, VectorIndexSnapshotPort {
    private val lock = Any()
    @Volatile
    private var pointer: Long = NativeUsearchIndex.create(dimension, capacity)
    private val keys = linkedMapOf<Long, String>()
    private val vectors = linkedMapOf<Long, FloatArray>()

    /**
     * O(1) duplicate detection.  The previous `id !in keys.values` scan made
     * every add O(n), i.e. quadratic (about 10^9 comparisons) for a 50k-chunk
     * index, which dominated the build metric this index is measured on.
     */
    private val uniqueIds = hashSetOf<String>()
    private var nextKey = 1L

    /** Reused scratch buffers so a snapshot read/write copies one vector at a time. */
    private val snapshotBytes = ByteArray(dimension * 4)
    private val snapshotFloats = ByteBuffer.wrap(snapshotBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

    override val vectorCount: Int get() = synchronized(lock) { keys.size }

    override fun add(id: String, vector: FloatArray) {
        synchronized(lock) {
            check(pointer != 0L) { "USearch index is closed" }
            require(vector.size == dimension) { "vector dimension mismatch" }
            require(vector.all { it.isFinite() }) { "vector must be finite" }
            require(id !in uniqueIds) { "duplicate vector id: $id" }
            val key = nextKey
            NativeUsearchIndex.add(pointer, key, vector)
            nextKey++
            keys[key] = id
            vectors[key] = vector.copyOf()
            uniqueIds.add(id)
        }
    }

    override fun search(query: FloatArray, topK: Int): List<Pair<String, Float>> = synchronized(lock) {
        check(pointer != 0L) { "USearch index is closed" }
        require(query.size == dimension) { "query dimension mismatch" }
        require(query.all { it.isFinite() }) { "query must be finite" }
        if (topK <= 0 || keys.isEmpty()) return emptyList()
        return NativeUsearchIndex.search(pointer, query, topK.coerceAtMost(keys.size)).asSequence().mapNotNull { key ->
            val id = keys[key] ?: return@mapNotNull null
            id to cosine(query, vectors.getValue(key))
        }.toList()
    }

    override fun close() = synchronized(lock) {
        if (pointer == 0L) return
        NativeUsearchIndex.close(pointer)
        pointer = 0L
        keys.clear()
        vectors.clear()
        uniqueIds.clear()
    }

    /**
     * Persist this index to [target] as one self-contained file:
     *
     * ```text
     * [ USearch payload ][ key<->chunkId + scoring vectors ][ 60-byte footer ]
     * ```
     *
     * A single file is deliberate: a caller publishes a snapshot with one
     * atomic rename and there is no second file to lose or leave behind.
     * USearch reads exactly the payload it wrote and performs no trailing-bytes
     * check (pinned v2.25.1), so the container can be handed to the native
     * restore unchanged.  Should a future USearch tighten that, [loadSnapshot]
     * fails closed and the repository rebuilds from SQLite.
     *
     * A write failure leaves the in-memory index usable: callers may keep
     * serving it and retry the snapshot later.
     */
    override fun saveSnapshot(target: File): VectorIndexSnapshotIdentity = synchronized(lock) {
        check(pointer != 0L) { "USearch index is closed" }
        val ordered = keys.entries.sortedBy { it.key }.map { it.key to it.value }
        try {
            check(NativeUsearchIndex.hasSequentialKeys(pointer, ordered.size) &&
                ordered.withIndex().all { (index, pair) -> pair.first == index+1L }) { "Snapshot keys are not a complete sequence" }
            NativeUsearchIndex.save(pointer, target.absolutePath)
            val nativeLength = target.length()
            writeMapping(target, ordered)
            val mappingLength = target.length() - nativeLength
            val payloadHash = sha256(target, 0L, nativeLength + mappingLength)
            writeFooter(target, nativeLength, mappingLength, ordered.size, payloadHash)
        } catch (failure: Exception) {
            runCatching { target.delete() }
            throw VectorIndexSnapshotException("vector snapshot could not be written", failure)
        }
        VectorIndexSnapshotIdentity(spaceId, dimension, ordered.map { it.second })
    }

    /**
     * Restore this (fresh) instance from a snapshot, validating [expected].
     *
     * Fail-closed from the first statement: the reserved handle is released up
     * front, and every failure path leaves this instance with no native handle
     * and no mapping, so it reports `vectorCount == 0` and throws on search
     * instead of being mistakable for a complete generation.  Releasing up
     * front also means a 50k restore never holds two native graphs at once.
     * Callers discard the instance and rebuild from SQLite.
     */
    override fun loadSnapshot(source: File, expected: VectorIndexSnapshotIdentity) {
        synchronized(lock) {
            releaseNative()
            var restored = 0L
            var committed = false
            try {
                if (!source.isFile) {
                    throw VectorIndexSnapshotException("vector snapshot is missing: ${source.name}")
                }
                val footer = readFooter(source)
                val payloadHash = sha256(source, 0L, footer.nativeLength + footer.mappingLength)
                if (!payloadHash.contentEquals(footer.payloadHash)) {
                    throw VectorIndexSnapshotException("vector snapshot hash mismatch: ${source.name} is corrupt")
                }
                // Identity, count and dimension are validated against the
                // requested snapshot *before* any vector is allocated, so a
                // corrupt or foreign header cannot size a large allocation.
                val stored = readMapping(source, footer, expected)
                if (stored.spaceId != expected.spaceId ||
                    stored.dimension != expected.dimension ||
                    stored.dimension != dimension ||
                    stored.memberIds.size != expected.memberIds.size ||
                    stored.memberIds.toSet() != expected.memberIds.toSet()
                ) {
                    throw VectorIndexSnapshotException("vector snapshot identity mismatch: ${source.name}")
                }
                restored = try {
                    NativeUsearchIndex.restore(source.absolutePath)
                } catch (failure: Exception) {
                    // JNI raises IllegalStateException for a truncated/foreign
                    // file; callers must see one fail-closed snapshot type.
                    throw VectorIndexSnapshotException("vector snapshot is not a readable USearch index", failure)
                }
                if (restored == 0L) throw VectorIndexSnapshotException("USearch restore returned no handle")
                val restoredCount = NativeUsearchIndex.size(restored)
                if (restoredCount != stored.memberIds.size) {
                    throw VectorIndexSnapshotException(
                        "vector snapshot is incomplete: index holds $restoredCount of ${stored.memberIds.size} vectors",
                    )
                }
                if (!NativeUsearchIndex.hasSequentialKeys(restored, stored.memberIds.size))
                    throw VectorIndexSnapshotException("vector snapshot key mapping is incomplete")
                val restoredDimension = NativeUsearchIndex.dimensions(restored)
                if (restoredDimension != dimension) {
                    throw VectorIndexSnapshotException(
                        "vector snapshot dimension $restoredDimension does not match $dimension",
                    )
                }
                pointer = restored
                committed = true
                keys.clear()
                vectors.clear()
                uniqueIds.clear()
                stored.memberIds.forEachIndexed { index, id ->
                    val key = (index + 1).toLong()
                    keys[key] = id
                    uniqueIds.add(id)
                }
                stored.vectors.forEachIndexed { index, vector -> vectors[(index + 1).toLong()] = vector }
                nextKey = stored.memberIds.size + 1L
            } catch (failure: VectorIndexSnapshotException) {
                throw failure
            } catch (failure: Exception) {
                throw VectorIndexSnapshotException("vector snapshot could not be restored", failure)
            } finally {
                if (!committed) {
                    if (restored != 0L) runCatching { NativeUsearchIndex.close(restored) }
                    releaseNative()
                }
            }
        }
    }

    /** Drops the native handle and every derived mapping; safe when already released. */
    private fun releaseNative() {
        val current = pointer
        pointer = 0L
        keys.clear()
        vectors.clear()
        uniqueIds.clear()
        nextKey = 1L
        if (current != 0L) runCatching { NativeUsearchIndex.close(current) }
    }

    private class SnapshotMap(
        val spaceId: String,
        val dimension: Int,
        val memberIds: List<String>,
        val vectors: List<FloatArray>,
    )

    private class SnapshotFooter(
        val nativeLength: Long,
        val mappingLength: Long,
        val count: Int,
        val payloadHash: ByteArray,
    )

    /** Appends the key<->id mapping and the scoring vectors after the native payload. */
    private fun writeMapping(target: File, ordered: List<Pair<Long, String>>) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(target, true), SNAPSHOT_BUFFER)).use { out ->
            out.writeUTF(spaceId)
            out.writeInt(dimension)
            out.writeInt(ordered.size)
            ordered.forEach { (key, id) ->
                val vector = vectors.getValue(key)
                if (vector.size != dimension) {
                    throw VectorIndexSnapshotException("vector for $id has ${vector.size} of $dimension components")
                }
                out.writeUTF(id)
                snapshotFloats.clear()
                snapshotFloats.put(vector)
                out.write(snapshotBytes, 0, snapshotBytes.size)
            }
        }
    }

    private fun writeFooter(target: File, nativeLength: Long, mappingLength: Long, count: Int, payloadHash: ByteArray) {
        val buffer = ByteBuffer.allocate(SNAPSHOT_FOOTER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(SNAPSHOT_MAGIC)
        buffer.putInt(SNAPSHOT_VERSION)
        buffer.putLong(nativeLength)
        buffer.putLong(mappingLength)
        buffer.putInt(count)
        buffer.put(payloadHash)
        RandomAccessFile(target, "rw").use { file ->
            file.seek(file.length())
            file.write(buffer.array())
        }
    }

    private fun readFooter(source: File): SnapshotFooter {
        val size = source.length()
        if (size < SNAPSHOT_FOOTER_BYTES) {
            throw VectorIndexSnapshotException("vector snapshot is too small to be valid: ${source.name}")
        }
        val bytes = ByteArray(SNAPSHOT_FOOTER_BYTES)
        RandomAccessFile(source, "r").use { file ->
            file.seek(size - SNAPSHOT_FOOTER_BYTES)
            file.readFully(bytes)
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt() != SNAPSHOT_MAGIC) {
            throw VectorIndexSnapshotException("not a vector snapshot: ${source.name}")
        }
        val version = buffer.getInt()
        if (version != SNAPSHOT_VERSION) {
            throw VectorIndexSnapshotException("unsupported vector snapshot version: $version")
        }
        val nativeLength = buffer.getLong()
        val mappingLength = buffer.getLong()
        val count = buffer.getInt()
        val payloadHash = ByteArray(SNAPSHOT_HASH_BYTES)
        buffer.get(payloadHash)
        if (nativeLength <= 0L || mappingLength < 0L || count < 0 ||
            nativeLength + mappingLength + SNAPSHOT_FOOTER_BYTES != size
        ) {
            throw VectorIndexSnapshotException("vector snapshot footer is inconsistent: ${source.name}")
        }
        return SnapshotFooter(nativeLength, mappingLength, count, payloadHash)
    }

    /**
     * Reads the mapping, validating its identity header against [expected]
     * before allocating any vector.
     */
    private fun readMapping(source: File, footer: SnapshotFooter, expected: VectorIndexSnapshotIdentity): SnapshotMap {
        try {
            DataInputStream(BufferedInputStream(FileInputStream(source), SNAPSHOT_BUFFER)).use { input ->
                skipFully(input, footer.nativeLength)
                val storedSpace = input.readUTF()
                val storedDimension = input.readInt()
                val storedCount = input.readInt()
                // Every entry needs at least a 1-char id and one full vector.
                val minimumEntryBytes = dimension * 4L + 3L
                if (storedSpace != expected.spaceId ||
                    storedDimension != dimension ||
                    storedDimension != expected.dimension ||
                    storedCount < 0 ||
                    storedCount != footer.count ||
                    storedCount != expected.memberIds.size ||
                    storedCount.toLong() * minimumEntryBytes > footer.mappingLength
                ) {
                    throw VectorIndexSnapshotException("vector snapshot mapping is not usable for the requested index")
                }
                val ids = ArrayList<String>(storedCount)
                val restoredVectors = ArrayList<FloatArray>(storedCount)
                repeat(storedCount) {
                    ids.add(input.readUTF())
                    val vector = FloatArray(dimension)
                    input.readFully(snapshotBytes)
                    snapshotFloats.clear()
                    snapshotFloats.get(vector)
                    if (vector.any { !it.isFinite() }) {
                        throw VectorIndexSnapshotException("vector snapshot contains a non-finite vector")
                    }
                    restoredVectors.add(vector)
                }
                return SnapshotMap(storedSpace, storedDimension, ids, restoredVectors)
            }
        } catch (failure: VectorIndexSnapshotException) {
            throw failure
        } catch (failure: Exception) {
            throw VectorIndexSnapshotException("vector snapshot mapping is unreadable: ${source.name}", failure)
        }
    }

    /** Streams a byte range so a 50k snapshot is never pulled into heap to hash it. */
    private fun sha256(file: File, from: Long, length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            skipFully(input, from)
            var remaining = length
            val buffer = ByteArray(SNAPSHOT_BUFFER)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw VectorIndexSnapshotException("vector snapshot ended before its payload: ${file.name}")
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest()
    }

    private fun skipFully(input: InputStream, length: Long) {
        var remaining = length
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() < 0) {
                throw VectorIndexSnapshotException("vector snapshot is truncated")
            } else {
                remaining -= 1
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat()
    }

    companion object {
        private const val SNAPSHOT_MAGIC = 0x4D415633
        private const val SNAPSHOT_VERSION = 1
        private const val SNAPSHOT_HASH_BYTES = 32
        private const val SNAPSHOT_FOOTER_BYTES = 4 + 4 + 8 + 8 + 4 + SNAPSHOT_HASH_BYTES
        private const val SNAPSHOT_BUFFER = 1 shl 16
    }
}

/** Factory to inject into KnowledgeRepository from the Android app container. */
class UsearchVectorIndexFactory : VectorIndexFactory {
    override fun create(spaceId: String, dimension: Int, capacity: Int): VectorIndex =
        UsearchVectorIndex(spaceId, dimension, capacity)
}

private object NativeUsearchIndex {
    init {
        System.loadLibrary("usearch_jni")
    }

    fun create(dimension: Int, capacity: Int): Long = nativeCreate(dimension, capacity)

    fun add(pointer: Long, key: Long, vector: FloatArray) = nativeAdd(pointer, key, vector)

    fun search(pointer: Long, query: FloatArray, topK: Int): LongArray = nativeSearch(pointer, query, topK)

    fun save(pointer: Long, path: String) = nativeSave(pointer, path)

    fun restore(path: String): Long = nativeRestore(path)

    fun size(pointer: Long): Int = nativeSize(pointer)

    fun dimensions(pointer: Long): Int = nativeDimensions(pointer)
    fun hasSequentialKeys(pointer: Long, count: Int): Boolean = nativeHasSequentialKeys(pointer, count)

    fun close(pointer: Long) = nativeClose(pointer)

    private external fun nativeCreate(dimension: Int, capacity: Int): Long
    private external fun nativeAdd(pointer: Long, key: Long, vector: FloatArray)
    private external fun nativeSearch(pointer: Long, query: FloatArray, topK: Int): LongArray
    private external fun nativeSave(pointer: Long, path: String)
    private external fun nativeRestore(path: String): Long
    private external fun nativeSize(pointer: Long): Int
    private external fun nativeDimensions(pointer: Long): Int
    private external fun nativeHasSequentialKeys(pointer: Long, count: Int): Boolean
    private external fun nativeClose(pointer: Long)
}
