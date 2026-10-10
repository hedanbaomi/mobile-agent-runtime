// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.knowledge.*
import java.nio.file.Path

class StorageMaintenanceTest {
    @Test fun deletionDuringInflightLocalEmbeddingCannotRepublishDocument(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val sink = FileBlobSink(path.toFile())
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val actual = HashingTextEmbedder()
            val blocking = object : TextEmbedder by actual {
                override fun embed(text: String): FloatArray {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return actual.embed(text)
                }
            }
            val repo = KnowledgeRepository(db, sink, embedder = blocking)
            val kb = repo.createKnowledgeBase("delete while importing")
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val import = pool.submit<ImportJob> { repo.importBytes("inflight.txt", "text/plain", "late local embedding source".toByteArray(), false, kb) }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val id = db.query("SELECT id FROM documents WHERE kb_id=?", listOf(kb)).single().string("id")
                repo.deleteDocument(id)
                assertTrue(sink.protectedHashes().isNotEmpty())
                release.countDown()
                runCatching { import.get(5, java.util.concurrent.TimeUnit.SECONDS) }
                repo.collectStorage()
                assertTrue(db.query("SELECT id FROM documents WHERE id=? AND deleted_at IS NULL", listOf(id)).isEmpty())
                assertTrue(db.query("SELECT document_id FROM import_jobs WHERE document_id=? AND stage IN('READY','READY_WITH_VISUAL_GAPS')", listOf(id)).isEmpty())
                assertTrue(sink.protectedHashes().isEmpty())
                assertTrue(sink.storedHashes().isEmpty())
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }
    @Test fun overQuotaDeletionReusesExistingVectorsAndReclaimsSource(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val sink = FileBlobSink(path.resolve("cas").toFile())
            val indexes = path.resolve("indexes").toFile().also { it.mkdirs() }
            val repo = KnowledgeRepository(db, sink, vectorIndexDirectory = indexes)
            val kb = repo.createKnowledgeBase("overquota deletion")
            val first = repo.importBytes("first.txt", "text/plain", "first retained source".toByteArray(), false, kb)
            val second = repo.importBytes("second.txt", "text/plain", "second retained source".toByteArray(), false, kb)
            val firstHash = db.query("SELECT blob_hash FROM documents WHERE id=?", listOf(first.documentId)).single().string("blob_hash")
            java.io.RandomAccessFile(java.io.File(indexes, "retained-other.idx"), "rw").use { it.setLength(StorageMaintenance.MIN_QUOTA_BYTES) }
            repo.collectStorage()
            assertTrue(repo.configureStorageQuota(StorageMaintenance.MIN_QUOTA_BYTES).overQuota)
            repo.deleteDocument(first.documentId)
            assertNull(sink.get(firstHash))
            assertTrue(db.query("SELECT id FROM documents WHERE id=?", listOf(first.documentId)).isEmpty())
            assertTrue(repo.search("second", knowledgeBaseIds = listOf(kb)).any { it.documentId == second.documentId })
        }
    }

    @Test fun abandonedTemporaryFilesReportActualReclaimedBytes(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val sink = FileBlobSink(path.toFile())
            val repo = KnowledgeRepository(db, sink)
            repo.storageUsage() // Cache inventory before a simulated process crash leaves a file.
            val dir = path.resolve("aa").toFile().also { it.mkdirs() }
            java.io.File(dir, "${"a".repeat(64)}-123.tmp").writeBytes(ByteArray(123))
            val result = repo.collectStorage()
            assertEquals(123L, result.reclaimedBytes)
            assertEquals(0L, result.after.casBytes)
        }
    }
    @Test fun sharedBlobAndPublishedCitationRemainWhileDeletedDocumentIsReclaimed(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val sink = FileBlobSink(path.toFile())
            val repo = KnowledgeRepository(db, sink)
            val a = repo.createKnowledgeBase("A")
            val b = repo.createKnowledgeBase("B")
            val bytes = "immutable shared source with retained citation".toByteArray()
            val first = repo.importBytes("a.txt", "text/plain", bytes, false, a)
            val second = repo.importBytes("b.txt", "text/plain", bytes, false, b)
            val version = db.query("SELECT active_version_id FROM documents WHERE id=?", listOf(second.documentId)).single().string("active_version_id")
            val citation = repo.retrieve("synthetic-run", "retained", knowledgeBaseIds = listOf(b)).citations.first()
            // Publish a later version: the old citation must keep its original source and chunk.
            db.execute("INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES('new-version',?,'synthetic',?,'READY','synthetic')", listOf(second.documentId, "c".repeat(64)))
            db.execute("UPDATE documents SET active_version_id='new-version' WHERE id=?", listOf(second.documentId))
            repo.collectStorage()
            assertFalse(repo.locateCitation(citation).removed)
            assertArrayEquals(bytes, repo.evidenceBytes(citation)!!.second)
            db.execute("UPDATE documents SET active_version_id=? WHERE id=?", listOf(version, second.documentId))
            repo.deleteDocument(first.documentId)
            assertTrue(db.query("SELECT id FROM documents WHERE id=?", listOf(first.documentId)).isEmpty())
            assertArrayEquals(bytes, sink.get(sha256Hex(bytes)))
            assertTrue(db.query("SELECT id FROM document_versions WHERE id=?", listOf(version)).isNotEmpty())
            assertTrue(repo.search("retained", knowledgeBaseIds = listOf(b)).isNotEmpty())
            repo.deleteDocument(second.documentId)
            assertTrue(repo.locateCitation(citation).removed)
            assertNull(sink.get(sha256Hex(bytes)))
            assertTrue(db.query("SELECT id FROM chunks").isEmpty())
            assertTrue(db.query("SELECT chunk_id FROM embeddings").isEmpty())
            assertTrue(repo.search("retained", knowledgeBaseIds = listOf(b)).isEmpty())
        }
    }

    @Test fun gcRechecksRootsAndRetriesCrashJournalWithoutDeletingInflightBlob(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val firstHandle = FileBlobSink(path.toFile())
            val secondHandle = FileBlobSink(path.toFile())
            val repo = KnowledgeRepository(db, secondHandle)
            val bytes = "not yet published".toByteArray()
            val stored = firstHandle.put(bytes, "text/plain")
            firstHandle.protect(stored.sha256).use {
                db.execute("INSERT INTO blob_gc_pending(hash) VALUES(?)", listOf(stored.sha256))
                repo.collectStorage()
                assertArrayEquals(bytes, firstHandle.get(stored.sha256))
            }
            val result = repo.collectStorage()
            assertEquals(bytes.size.toLong(), result.reclaimedBytes)
            assertNull(firstHandle.get(stored.sha256))
            assertEquals(0, result.pendingBlobs)
            // Simulate death after unlink but before the journal's SQL completion.
            db.execute("INSERT INTO blob_gc_pending(hash) VALUES(?)", listOf(stored.sha256))
            assertEquals(0, repo.collectStorage().pendingBlobs)
        }
    }

    @Test fun unknownAndPausedRootsArePreservedAndQuotaDoesNotBlockReadOrReclaim() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val sink = MemoryBlobSink()
            val repo = KnowledgeRepository(db, sink)
            val paused = repo.importBytes("paused.txt", "text/plain", "paused source".toByteArray(), false, pauseAt = ImportStage.COPYING)
            val hash = sink.storedHashes().single()
            repo.collectStorage()
            assertNotNull(sink.get(hash))
            db.execute("UPDATE import_jobs SET error='UNKNOWN_OUTCOME: uncertain provider' WHERE id=?", listOf(paused.id))
            repo.deleteDocument(paused.documentId)
            assertNotNull(sink.get(hash))
            assertTrue(db.query("SELECT id FROM documents WHERE id=?", listOf(paused.documentId)).isNotEmpty())
            val usage = repo.configureStorageQuota(StorageMaintenance.MIN_QUOTA_BYTES)
            assertEquals(StorageMaintenance.MIN_QUOTA_BYTES, usage.quotaBytes)
            assertThrows(IllegalArgumentException::class.java) { repo.configureStorageQuota(0) }
            val capacity = StorageMaintenance(db, sink, null)
            assertThrows(IllegalStateException::class.java) { capacity.requireCapacity(usage.quotaBytes) }
            repo.listKnowledgeBases()
            repo.collectStorage()
        }
    }

    @Test fun foreignKeyScanPreservesLegacyUserRows() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            db.execute("PRAGMA foreign_keys=OFF")
            db.execute("UPDATE schema_version SET version=31")
            db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format) VALUES('legacy','missing',?,'keep','TXT')", listOf("a".repeat(64)))
            db.execute("PRAGMA foreign_keys=ON")
            Migrations.apply(db)
            assertEquals("keep", db.query("SELECT display_name FROM documents WHERE id='legacy'").single().string("display_name"))
            assertTrue(Migrations.foreignKeyCompatibility(db).any { it.table == "documents" })
        }
    }

    @Test fun databaseWriterAndGcUseTheSameLockOrder() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val sink = MemoryBlobSink()
            val repo = KnowledgeRepository(db, sink)
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val writer = pool.submit {
                    db.transaction {
                        started.countDown()
                        check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        StorageMaintenance(db, sink, null).requireCapacity(4096)
                    }
                }
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
                val sweep = pool.submit { repo.collectStorage() }
                release.countDown()
                writer.get(3, java.util.concurrent.TimeUnit.SECONDS)
                sweep.get(3, java.util.concurrent.TimeUnit.SECONDS)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun deletedBaseReclaimsItsDisposableIndexFiles(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink(), vectorIndexDirectory = path.toFile())
            val kb = repo.createKnowledgeBase("indexed")
            val index = path.resolve(sha256Hex(kb.toByteArray()) + "-" + "a".repeat(64) + ".idx").toFile()
            index.writeBytes(ByteArray(200))
            assertEquals(200, repo.storageUsage().indexBytes)
            repo.deleteKnowledgeBase(kb)
            assertFalse(index.exists())
            assertEquals(0, repo.storageUsage().indexBytes)
        }
    }

    @Test fun concurrentImportsShareRealCasDatabaseAndIndexQuota(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val cas = path.resolve("cas").toFile()
            val indexes = path.resolve("indexes").toFile().also { it.mkdirs() }
            java.io.File(indexes, "unrelated-retained.idx").writeBytes(ByteArray(200_000))
            val sink = FileBlobSink(cas)
            val first = KnowledgeRepository(db, sink, vectorIndexDirectory = indexes)
            val second = KnowledgeRepository(db, FileBlobSink(cas), vectorIndexDirectory = indexes)
            first.configureStorageQuota(StorageMaintenance.MIN_QUOTA_BYTES)
            val base = first.storageUsage()
            val filler = sink.put(ByteArray((base.quotaBytes - base.totalBytes - 800_000).toInt()), "application/octet-stream")
            sink.protect(filler.sha256).use {
                val kb = first.createKnowledgeBase("shared admission")
                val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                val start = java.util.concurrent.CountDownLatch(1)
                try {
                    val results = listOf(first, second).mapIndexed { index, repository ->
                        pool.submit<Boolean> {
                            check(start.await(2, java.util.concurrent.TimeUnit.SECONDS))
                            try {
                                repository.importBytes("source-$index.txt", "text/plain", ByteArray(600_000) { (index + 65).toByte() }, false, kb, pauseAt = ImportStage.COPYING)
                                true
                            } catch (error: IllegalStateException) {
                                assertTrue(error.message.orEmpty().contains("RESOURCE_LIMIT"))
                                false
                            }
                        }
                    }
                    start.countDown()
                    assertEquals(1, results.count { it.get(15, java.util.concurrent.TimeUnit.SECONDS) })
                    assertEquals(1, db.query("SELECT COUNT(*) AS n FROM documents WHERE kb_id=?", listOf(kb)).single().long("n"))
                    val admitted = db.query("SELECT blob_hash FROM documents WHERE kb_id=?", listOf(kb)).single().string("blob_hash")
                    assertEquals(600_000, sink.get(admitted)!!.size)
                    assertTrue(first.storageUsage().totalBytes <= base.quotaBytes)
                    assertEquals(200_000, first.storageUsage().indexBytes)
                } finally { start.countDown(); pool.shutdownNow() }
            }
        }
    }

    @Test fun crashAfterUnlinkRollsBackSqlAndRecoveryCompletes(@TempDir path: Path) {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val sink = FileBlobSink(path.toFile())
            val blob = sink.put("crash resumable orphan".toByteArray(), "text/plain")
            var inject = true
            val crash = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    if (inject && sql.startsWith("DELETE FROM blobs WHERE hash")) { inject = false; error("injected crash after unlink") }
                    db.execute(sql, args)
                }
            }
            assertThrows(IllegalStateException::class.java) { KnowledgeRepository(crash, sink).collectStorage() }
            assertNull(sink.get(blob.sha256))
            assertEquals(1, db.query("SELECT COUNT(*) AS n FROM blob_gc_pending").single().long("n"))
            assertEquals(0, KnowledgeRepository(db, sink).collectStorage().pendingBlobs)
        }
    }
}
