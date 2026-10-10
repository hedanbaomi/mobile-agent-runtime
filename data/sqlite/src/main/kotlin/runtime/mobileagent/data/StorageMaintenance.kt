// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.knowledge.BlobSink
import java.io.File

data class StorageUsage(val quotaBytes: Long, val databaseBytes: Long, val casBytes: Long, val indexBytes: Long) {
    val totalBytes: Long get() = databaseBytes + casBytes + indexBytes
    val availableBytes: Long get() = (quotaBytes - totalBytes).coerceAtLeast(0)
    val overQuota: Boolean get() = totalBytes > quotaBytes
}

data class StorageMaintenanceResult(
    val before: StorageUsage,
    val after: StorageUsage,
    val reclaimedBytes: Long,
    val reclaimedDocuments: Int,
    val reclaimedBlobs: Int,
    val pendingBlobs: Int,
)

/** Platform connections include database sidecars rather than reporting only logical pages. */
interface SqlStorageSize { fun allocatedDatabaseBytes(): Long }

data class ForeignKeyCompatibilityIssue(val table: String, val rowId: Long?, val parentTable: String, val foreignKeyId: Long)

internal class StorageMaintenance(private val db: SqlConnection, private val blobs: BlobSink, private val indexes: File?) {
    private var lastIndexScan = 0L
    private var cachedIndexBytes = 0L
    fun usage(refreshIndexes: Boolean = false): StorageUsage {
        val quota = db.query("SELECT value FROM app_prefs WHERE key='knowledge_storage_quota_bytes'")
            .singleOrNull()?.string("value")?.toLongOrNull() ?: DEFAULT_QUOTA_BYTES
        val pages = db.query("PRAGMA page_count").single().long("page_count")
        val pageBytes = db.query("PRAGMA page_size").single().long("page_size")
        // page_count on the writer includes pages not yet flushed from its transaction cache.
        val databaseBytes = maxOf((db as? SqlStorageSize)?.allocatedDatabaseBytes() ?: 0, pages * pageBytes)
        val now = System.nanoTime()
        if (refreshIndexes || lastIndexScan == 0L || now - lastIndexScan >= 5_000_000_000L) {
            cachedIndexBytes = indexes?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
            lastIndexScan = now
        }
        return StorageUsage(quota, databaseBytes, blobs.allocatedBytes(), cachedIndexBytes)
    }

    fun configureQuota(bytes: Long): StorageUsage {
        require(bytes in MIN_QUOTA_BYTES..MAX_QUOTA_BYTES) { "Storage quota must be 64 MiB to 64 GiB" }
        db.execute("INSERT INTO app_prefs(key,value) VALUES('knowledge_storage_quota_bytes',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", listOf(bytes.toString()))
        return usage()
    }

    fun requireCapacity(bytes: Long) {
        require(bytes >= 0)
        check(bytes <= usage(refreshIndexes = true).availableBytes) { "RESOURCE_LIMIT: managed storage quota exceeded; delete content or increase quota" }
    }

    /** All roots are rechecked under the same CAS lock as publication, on every retry. */
    fun collect(): StorageMaintenanceResult {
        val before = usage(refreshIndexes = true)
        var documents = 0
        var reclaimedBytes = 0L
        db.transaction {
          blobs.withStorageLock {
            reclaimedBytes += blobs.removeTemporaryFiles()
            val protected = blobs.protectedHashes()
            db.query("SELECT id,blob_hash FROM documents WHERE deleted_at IS NOT NULL").forEach { document ->
                val id = document.string("id")
                if (document.string("blob_hash") in protected || unresolved(id)) return@forEach
                purgeDocument(id)
                documents++
            }
            // Successful caches with no surviving asset/job root are disposable. Unknown receipts remain.
            db.execute("DELETE FROM vision_results WHERE status <> 'UNKNOWN_OUTCOME' AND NOT EXISTS(SELECT 1 FROM assets a WHERE a.blob_hash=vision_results.asset_hash) AND NOT EXISTS(SELECT 1 FROM vision_attempts v WHERE v.cache_key=vision_results.cache_key)")
            db.query("SELECT id FROM knowledge_bases WHERE deleted_at IS NOT NULL").forEach { base ->
                val kbId = base.string("id")
                val prefix = runtime.mobileagent.knowledge.sha256Hex(kbId.toByteArray(Charsets.UTF_8)) + "-"
                indexes?.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".idx") }
                    ?.forEach { val bytes = it.length(); if (it.delete()) reclaimedBytes += bytes }
                if (db.query("SELECT id FROM documents WHERE kb_id=? LIMIT 1", listOf(kbId)).isNotEmpty() ||
                    db.query("SELECT token FROM embedding_operations WHERE kb_id=? AND state IN('PREPARED','DISPATCHED','CACHE_READY','UNKNOWN') LIMIT 1", listOf(kbId)).isNotEmpty() ||
                    db.query("SELECT kb_id FROM embedding_query_attempts WHERE kb_id=? LIMIT 1", listOf(kbId)).isNotEmpty()) return@forEach
                db.query("SELECT id FROM import_batches WHERE kb_id=?", listOf(kbId)).forEach {
                    val batch = it.string("id")
                    db.execute("DELETE FROM import_items WHERE batch_id=?", listOf(batch))
                    db.execute("DELETE FROM pipeline_policies WHERE batch_id=?", listOf(batch))
                }
                db.execute("DELETE FROM embedding_operations WHERE kb_id=?", listOf(kbId))
                db.execute("DELETE FROM consent_tickets WHERE kb_id=?", listOf(kbId))
                db.execute("DELETE FROM import_batches WHERE kb_id=?", listOf(kbId))
                db.execute("DELETE FROM generation_members WHERE generation_id IN(SELECT id FROM index_generations WHERE kb_id=?)", listOf(kbId))
                db.execute("DELETE FROM index_generations WHERE kb_id=?", listOf(kbId))
                db.execute("DELETE FROM knowledge_bases WHERE id=?", listOf(kbId))
            }
            val roots = retainedHashes() + protected
            (blobs.storedHashes() + db.query("SELECT hash FROM blobs").map { it.string("hash") }).forEach { hash ->
                if (hash !in roots) db.execute("INSERT OR IGNORE INTO blob_gc_pending(hash) VALUES(?)", listOf(hash))
            }
          }
        }
        var removed = 0
        db.query("SELECT hash FROM blob_gc_pending ORDER BY hash").forEach { row ->
            val hash = row.string("hash")
            db.transaction {
              blobs.withStorageLock {
                if (hash in retainedHashes() || hash in blobs.protectedHashes()) {
                    db.execute("DELETE FROM blob_gc_pending WHERE hash=?", listOf(hash))
                } else {
                    val bytes = blobs.storedByteLength(hash)
                    if (blobs.remove(hash)) {
                    reclaimedBytes += bytes
                    // A crash after unlink rolls back these rows; retry sees an absent file and finishes.
                    db.execute("DELETE FROM blobs WHERE hash=?", listOf(hash))
                    db.execute("DELETE FROM blob_gc_pending WHERE hash=?", listOf(hash))
                    removed++
                    }
                }
              }
            }
        }
        val after = usage(refreshIndexes = true)
        return StorageMaintenanceResult(before, after, reclaimedBytes,
            documents, removed, db.query("SELECT COUNT(*) AS n FROM blob_gc_pending").single().long("n").toInt())
    }

    private fun unresolved(documentId: String): Boolean = db.query(
        "SELECT 1 AS n FROM import_jobs j WHERE j.document_id=? AND (j.stage NOT IN('READY','READY_WITH_VISUAL_GAPS','FAILED','CANCELLED') OR COALESCE(j.error,'') LIKE '%UNKNOWN%') " +
            "UNION ALL SELECT 1 FROM embedding_operations WHERE (document_id=? OR document_version_id IN(SELECT id FROM document_versions WHERE document_id=?)) AND state IN('PREPARED','DISPATCHED','CACHE_READY','UNKNOWN') " +
            "UNION ALL SELECT 1 FROM pipeline_attempts p JOIN import_jobs j ON j.id=p.job_id WHERE j.document_id=? AND p.state IN('READY','DISPATCHED','UNKNOWN_OUTCOME') " +
            "UNION ALL SELECT 1 FROM vision_attempts v JOIN import_jobs j ON j.id=v.job_id WHERE j.document_id=? AND v.status IN('PREPARED','IN_PROGRESS','UNKNOWN_OUTCOME') LIMIT 1",
        listOf(documentId, documentId, documentId, documentId, documentId),
    ).isNotEmpty()

    private fun purgeDocument(id: String) {
        val jobs = db.query("SELECT id FROM import_jobs WHERE document_id=?", listOf(id)).map { it.string("id") }
        // Remove only this deleted document's derived rows. Published versions of live docs remain immutable.
        db.execute("DELETE FROM embedding_operations WHERE document_id=? OR document_version_id IN(SELECT id FROM document_versions WHERE document_id=?) OR job_id IN(SELECT id FROM import_jobs WHERE document_id=?)", listOf(id, id, id))
        jobs.forEach { job ->
            db.execute("DELETE FROM consent_tickets WHERE job_id=?", listOf(job))
            db.execute("DELETE FROM pipeline_retry_permits WHERE unknown_attempt_id IN(SELECT request_id FROM pipeline_attempts WHERE job_id=?)", listOf(job))
            listOf("pipeline_results", "pipeline_attempts", "pipeline_units", "pipeline_plans", "pipeline_publications", "vision_attempts").forEach {
                db.execute("DELETE FROM $it WHERE job_id=?", listOf(job))
            }
            db.execute("DELETE FROM import_items WHERE job_id=?", listOf(job))
            db.execute("DELETE FROM import_jobs WHERE id=?", listOf(job))
        }
        val versions = db.query("SELECT id FROM document_versions WHERE document_id=?", listOf(id)).map { it.string("id") }
        versions.forEach { version ->
            // External-content FTS delete must precede removal of its source row.
            db.query("SELECT rowid,text FROM chunks WHERE document_version_id=?", listOf(version)).forEach {
                // Older restores can contain source rows which were never indexed. The
                // default FTS5 columnsize=1 schema keeps one docsize row per indexed row;
                // deleting nonexistent postings reports CORRUPT_VTAB instead of being a no-op.
                if (db.query("SELECT id FROM chunks_fts_docsize WHERE id=?", listOf(it.long("rowid"))).isNotEmpty()) {
                    db.execute("INSERT INTO chunks_fts(chunks_fts,rowid,text) VALUES('delete',?,?)", listOf(it.long("rowid"), runtime.mobileagent.knowledge.CjkLexical.indexText(it.string("text"))))
                }
            }
            db.execute("DELETE FROM generation_members WHERE document_version_id=?", listOf(version))
            db.execute("DELETE FROM embeddings WHERE chunk_id IN(SELECT id FROM chunks WHERE document_version_id=?)", listOf(version))
            db.execute("DELETE FROM chunks WHERE document_version_id=?", listOf(version))
        }
        db.execute("DELETE FROM assets WHERE document_id=?", listOf(id))
        db.execute("DELETE FROM document_versions WHERE document_id=?", listOf(id))
        db.execute("DELETE FROM documents WHERE id=?", listOf(id))
    }

    private fun retainedHashes(): Set<String> = buildSet {
        // Retained tombstones include paused and unknown jobs; all versions/assets of live docs are roots.
        listOf("SELECT blob_hash AS hash FROM documents", "SELECT blob_hash AS hash FROM assets",
            "SELECT asset_hash AS hash FROM vision_results", "SELECT asset_hash AS hash FROM vision_attempts",
            "SELECT content_hash AS hash FROM pipeline_plans").forEach { sql -> db.query(sql).forEach { add(it.string("hash")) } }
    }

    companion object {
        const val DEFAULT_QUOTA_BYTES = 2L * 1024 * 1024 * 1024
        const val MIN_QUOTA_BYTES = 64L * 1024 * 1024
        const val MAX_QUOTA_BYTES = 64L * 1024 * 1024 * 1024
    }
}
