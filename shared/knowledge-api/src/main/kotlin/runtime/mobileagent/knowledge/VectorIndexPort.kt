// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import java.io.File

/**
 * Optional ANN seam for the repository.  The JVM implementation remains the
 * deterministic [CosineIndex]; Android can inject the real USearch JNI
 * implementation without making the data module depend on native code.
 */
interface VectorIndexPort : AutoCloseable {
    val spaceId: String
    val dimension: Int

    /**
     * Number of searchable vectors this handle currently holds.
     *
     * [VectorIndexCache] refuses to publish an index whose reported count does
     * not match the member set it claims, so a silently incomplete build (for
     * example a skipped or corrupt embedding blob) can never be served as a
     * complete generation.  The default `-1` means "unknown" and disables that
     * completeness check for ports that cannot report a count.
     */
    val vectorCount: Int get() = -1

    fun add(id: String, vector: FloatArray)
    fun search(query: FloatArray, topK: Int): List<Pair<String, Float>>

    override fun close() = Unit
}

fun interface VectorIndexFactory {
    fun create(spaceId: String, dimension: Int, capacity: Int): VectorIndexPort
}

/**
 * Identity of a persisted, *derived* ANN snapshot.
 *
 * The USearch binary format stores vectors and numeric keys, but not the
 * chunkId<->key mapping.  That mapping is therefore part of the identity: key
 * `(i + 1)` belongs to `memberIds[i]`.  Two snapshots are interchangeable only
 * when space, dimension and the member id set all match; the [memberIds] order
 * defines the mapping and is preserved by the writer.
 */
data class VectorIndexSnapshotIdentity(
    val spaceId: String,
    val dimension: Int,
    val memberIds: List<String>,
)

/** Fail-closed rejection of a persisted snapshot.  Callers rebuild from SQLite. */
open class VectorIndexSnapshotException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Optional persistence seam for a derived ANN index (docs/KNOWLEDGE.md section
 * 1: build a temporary file, validate space/count/hash, then replace
 * atomically).
 *
 * SQLite stays the single source of truth; a snapshot is a rebuildable cache.
 * Every [loadSnapshot] is therefore fail-closed: a missing, truncated, foreign
 * or mismatched file throws [VectorIndexSnapshotException] and the caller must
 * discard this instance and rebuild from SQLite.  A partially loaded snapshot
 * must never answer a query.
 */
interface VectorIndexSnapshotPort : VectorIndexPort {
    /**
     * Persist graph, scoring vectors and the key<->id mapping to [target]
     * (overwriting).  Returns the identity actually written; callers write to
     * a temporary path and rename it into place.
     */
    fun saveSnapshot(target: File): VectorIndexSnapshotIdentity

    /**
     * Load this (fresh) instance from [source], validating [expected].
     * Throws [VectorIndexSnapshotException] on any mismatch or corruption and
     * leaves the instance unusable for search: callers must not serve it.
     */
    fun loadSnapshot(source: File, expected: VectorIndexSnapshotIdentity)
}