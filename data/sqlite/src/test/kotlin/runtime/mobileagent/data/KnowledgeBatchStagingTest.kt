// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.MemoryBlobSink

class KnowledgeBatchStagingTest {
    @Test
    fun incompleteMembershipCannotRecoverProcessOrAuthorizeAndRetainsFullTotal() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val kb = repo.ensureDefaultBase()
        val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "selected", "fixture manifest", 2)
        repo.stageBatchBytes(batch, "source-one", "same.txt", "text/plain", "one".toByteArray(), kb, false, false)
        assertEquals(2, repo.findBatch(batch)!!.totalItems)
        assertEquals(1, repo.batchProgress(batch).pending)
        assertEquals(1, repo.batchProgress(batch).copied)
        assertFalse(batch in repo.recoverableBatchIds())
        repo.processBatch(batch, false)
        assertEquals(0, repo.findBatch(batch)!!.published)
        assertThrows(IllegalStateException::class.java) { repo.completeBatchStaging(batch) }
        assertThrows(IllegalStateException::class.java) { repo.authorizeBatchVision(batch, "target") }
        assertEquals(ImportBatchState.COPYING, repo.findBatch(batch)!!.state)
    }

    @Test
    fun pausedStagingSurvivesRepositoryRecreationAndBoundSourceIsNotDuplicated() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val original = KnowledgeRepository(db, blobs)
        val kb = original.ensureDefaultBase()
        val batch = original.beginBatch(kb, ImportBatchKind.FILES, "selected", "immutable selection", 2)
        val first = original.stageBatchBytes(batch, "source-one", "same.txt", "text/plain",
            "one".toByteArray(), kb, false, false)
        original.pauseBatch(batch)
        val restored = KnowledgeRepository(db, blobs)
        restored.refreshBatchProgress(batch)
        assertEquals(ImportBatchState.PAUSED, restored.findBatch(batch)!!.state)
        assertEquals(2, restored.findBatch(batch)!!.totalItems)
        assertTrue(restored.recoverableBatchIds().isEmpty())
        assertThrows(IllegalStateException::class.java) {
            restored.stageBatchBytes(batch, "source-two", "same.txt", "text/plain",
                "two".toByteArray(), kb, false, false)
        }
        restored.resumeBatch(batch)
        val reused = restored.stageBatchBytes(batch, "source-one", "same.txt", "text/plain",
            "one".toByteArray(), kb, false, false)
        assertEquals(first.id, reused.id)
        restored.stageBatchBytes(batch, "source-two", "same.txt", "text/plain",
            "two".toByteArray(), kb, false, false)
        assertEquals(2, restored.listJobs().size)
        restored.completeBatchStaging(batch)
        assertEquals(ImportBatchState.PROCESSING, restored.findBatch(batch)!!.state)
        restored.refreshBatchProgress(batch)
        assertEquals(ImportBatchState.PROCESSING, restored.findBatch(batch)!!.state)
        restored.processBatch(batch, false)
        assertEquals(ImportBatchState.COMPLETED, restored.findBatch(batch)!!.state)
        assertEquals(2, restored.findBatch(batch)!!.published)
    }

    @Test
    fun legacyBatchesRemainCompleteAfterMigration() {
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val batch = repo.beginBatch(repo.ensureDefaultBase(), ImportBatchKind.FILES, "legacy")
        assertEquals(1L, db.query("SELECT staging_complete FROM import_batches WHERE id = ?",
            listOf(batch)).single().long("staging_complete"))
        db.execute("ALTER TABLE import_batches DROP COLUMN staging_manifest")
        db.execute("ALTER TABLE import_batches DROP COLUMN staging_complete")
        db.execute("UPDATE schema_version SET version = 19")
        Migrations.apply(db)
        assertEquals(20, Migrations.VERSION)
        assertEquals(1L, db.query("SELECT staging_complete FROM import_batches WHERE id = ?",
            listOf(batch)).single().long("staging_complete"))
    }
}
