// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.vector

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Deterministic binary-serializing [UsearchBackend] for host JVM unit tests.
 *
 * Mirrors the exact native handle lifecycle, binary prefix layout (native
 * payload at offset 0 followed by UsearchVectorIndex's mapping + footer),
 * sequential-key invariants, and cosine ranking of `UsearchJni.cpp`, while
 * recording concurrent handle access violations and leaked/double-closed
 * handles so monitor bugs or resource leaks fail immediately.
 */
internal class HostJvmUsearchBackend : UsearchBackend {
    companion object {
        const val NATIVE_MAGIC = 0x55535248
    }

    private class HandleState(
        val dimension: Int,
        val capacity: Int,
    ) {
        val entries = LinkedHashMap<Long, FloatArray>()
        val inFlight = AtomicInteger(0)
        val closed = AtomicBoolean(false)
    }

    private val nextPointer = AtomicLong(1_000L)
    private val handles = ConcurrentHashMap<Long, HandleState>()

    val concurrentAccessViolations = AtomicInteger(0)
    val doubleOrUnknownCloseCount = AtomicInteger(0)
    val totalCloseCalls = AtomicInteger(0)

    @Volatile var failNextSaveAfterBytes: Int? = null
    @Volatile var forceNonSequentialKeysOnSave: Boolean = false
    @Volatile var returnZeroOnNextRestore: Boolean = false
    @Volatile var restoreCountDelta: Int = 0
    @Volatile var restoreDimensionOverride: Int? = null
    @Volatile var forceNonSequentialKeysOnRestore: Boolean = false

    val liveHandleCount: Int get() = handles.size

    private inline fun <T> withHandle(pointer: Long, block: (HandleState) -> T): T {
        check(pointer != 0L) { "USearch index is closed" }
        val state = handles[pointer] ?: error("Invalid or already closed USearch handle: $pointer")
        check(!state.closed.get()) { "USearch handle $pointer used after close" }
        if (state.inFlight.incrementAndGet() > 1) {
            concurrentAccessViolations.incrementAndGet()
        }
        try {
            return block(state)
        } finally {
            state.inFlight.decrementAndGet()
        }
    }

    override fun create(dimension: Int, capacity: Int): Long {
        check(dimension > 0 && capacity > 0) { "USearch dimensions and capacity must be positive" }
        val id = nextPointer.getAndIncrement()
        handles[id] = HandleState(dimension, capacity)
        return id
    }

    override fun add(pointer: Long, key: Long, vector: FloatArray) = withHandle(pointer) { state ->
        check(vector.size == state.dimension) { "USearch vector dimensions differ from the index" }
        state.entries[key] = vector.copyOf()
    }

    override fun search(pointer: Long, query: FloatArray, topK: Int): LongArray = withHandle(pointer) { state ->
        if (topK <= 0) return@withHandle LongArray(0)
        check(query.size == state.dimension) { "USearch query dimensions differ from the index" }
        state.entries.entries
            .sortedByDescending { (_, vec) -> cosine(query, vec) }
            .take(topK)
            .map { it.key }
            .toLongArray()
    }

    override fun save(pointer: Long, path: String) = withHandle(pointer) { state ->
        val partialBytes = failNextSaveAfterBytes
        if (partialBytes != null) {
            failNextSaveAfterBytes = null
            FileOutputStream(File(path), false).use { out ->
                if (partialBytes > 0) out.write(ByteArray(partialBytes) { 0x5A })
                out.flush()
            }
            throw IllegalStateException("Simulated native save I/O failure after $partialBytes bytes")
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(File(path), false))).use { out ->
            out.writeInt(NATIVE_MAGIC)
            out.writeInt(state.dimension)
            out.writeInt(state.entries.size)
            for ((key, vector) in state.entries) {
                out.writeLong(key)
                for (v in vector) out.writeFloat(v)
            }
            out.flush()
        }
    }

    override fun restore(path: String): Long {
        if (returnZeroOnNextRestore) {
            returnZeroOnNextRestore = false
            return 0L
        }
        val file = File(path)
        check(file.isFile && file.length() >= 12L) { "USearch payload too short" }
        RandomAccessFile(file, "r").use { raf ->
            val magic = raf.readInt()
            check(magic == NATIVE_MAGIC) { "Invalid USearch binary magic: $magic" }
            val savedDim = raf.readInt()
            val savedCount = raf.readInt()
            check(savedDim in 1..4096 && savedCount >= 0) { "Invalid USearch header dimensions/count" }
            val requiredBytes = 12L + savedCount.toLong() * (8L + savedDim.toLong() * 4L)
            check(file.length() >= requiredBytes) { "Truncated USearch native payload" }

            val effectiveDim = restoreDimensionOverride ?: savedDim
            restoreDimensionOverride = null
            val id = nextPointer.getAndIncrement()
            val state = HandleState(effectiveDim, maxOf(1, savedCount))
            repeat(savedCount) { idx ->
                val rawKey = raf.readLong()
                val key = if (forceNonSequentialKeysOnRestore && idx == savedCount - 1) rawKey + 99L else rawKey
                val vec = FloatArray(savedDim) { raf.readFloat() }
                state.entries[key] = if (effectiveDim == savedDim) vec else FloatArray(effectiveDim)
            }
            forceNonSequentialKeysOnRestore = false
            if (restoreCountDelta != 0) {
                val delta = restoreCountDelta
                restoreCountDelta = 0
                if (delta > 0) {
                    repeat(delta) { extra -> state.entries[savedCount + extra + 100L] = FloatArray(effectiveDim) }
                } else if (state.entries.isNotEmpty()) {
                    state.entries.remove(state.entries.keys.last())
                }
            }
            handles[id] = state
            return id
        }
    }

    override fun size(pointer: Long): Int = withHandle(pointer) { it.entries.size }

    override fun dimensions(pointer: Long): Int = withHandle(pointer) { it.dimension }

    override fun hasSequentialKeys(pointer: Long, count: Int): Boolean = withHandle(pointer) { state ->
        if (forceNonSequentialKeysOnSave) {
            forceNonSequentialKeysOnSave = false
            return@withHandle false
        }
        if (count < 0 || state.entries.size != count) return@withHandle false
        (1L..count.toLong()).all { state.entries.containsKey(it) }
    }

    override fun close(pointer: Long) {
        if (pointer == 0L) return
        totalCloseCalls.incrementAndGet()
        val removed = handles.remove(pointer)
        if (removed == null || !removed.closed.compareAndSet(false, true)) {
            doubleOrUnknownCloseCount.incrementAndGet()
            error("Double or unknown native handle close: $pointer")
        }
        if (removed.inFlight.get() != 0) {
            concurrentAccessViolations.incrementAndGet()
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
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }
}
