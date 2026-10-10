// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.*

class KnowledgeReadEfficiencyTest {
    private class ApiProbe : TextEmbedder {
        val binding = ApiEmbeddingBinding("repair-provider", "https://api.example.test/v1/embeddings", 1,
            "repair-model", 1, 8, "document text; retrieval purpose")
        override val spaceId: String = binding.spaceId
        override val dimension: Int = binding.dimension
        var calls = 0
        var resolutions = 0
        var available = true
        var fail = false
        fun resolve(space: String): TextEmbedder? {
            resolutions++
            return if (available && space == spaceId) this else null
        }
        override fun embed(text: String): FloatArray {
            calls++
            check(!fail) { "fixture dispatched embedding failed" }
            return FloatArray(dimension) { (it + 1).toFloat() }
        }
    }

    private fun apiRepository(db: SqlConnection, blobs: BlobSink, api: ApiProbe) =
        KnowledgeRepository(db, blobs, apiEmbedderResolver = api::resolve, automaticStorageMaintenance = false)

    private fun rebuilds(db: SqlConnection, kb: String): Long = db.query(
        "SELECT COUNT(*) AS n FROM embedding_operations WHERE kb_id=? AND kind='REBUILD'", listOf(kb),
    ).single().long("n")

    private class Reads(private val delegate: SqlConnection) : SqlConnection by delegate {
        val queries = mutableListOf<String>()
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            queries += sql
            return delegate.query(sql, args)
        }

        fun fullMemberReads(): Int = queries.count { sql ->
            // FTS and top-K provenance also begin with chunks.id. Only the
            // ID-only generation projection materializes the entire member set.
            sql.substringBefore("FROM").trim() == "SELECT chunks.id AS chunk_id" &&
                sql.substringAfter("FROM").trimStart().startsWith("generation_members")
        }
    }

    @Test fun batchProgressAggregatesUnknownReceiptsInOneReadAndPreservesStagingCounts() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink())
            val kb = repo.createKnowledgeBase("batch counters")
            val job = repo.importBytes("source.txt", "text/plain", "paused source".toByteArray(), false, kb, pauseAt = ImportStage.COPYING)
            val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "batch")
            db.execute("UPDATE import_batches SET total_items=405,staging_complete=0 WHERE id=?", listOf(batch))
            db.execute("UPDATE import_jobs SET error='unknown_outcome: receipt pending' WHERE id=?", listOf(job.id))
            val states = listOf("PUBLISHED","PENDING","COPYING","QUEUED","PROCESSING","WAITING","FAILED","CANCELLED")
            db.transaction { repeat(400) { index ->
                db.execute("INSERT INTO import_items(id,batch_id,item_key,relative_path,job_id,kind,state) VALUES(?,?,?,?,?,'FILE',?)",
                    listOf("counter-$index", batch, "item-$index", "synthetic", job.id, states[index % states.size]))
            } }
            val reads = Reads(db)
            val store = KnowledgeBatchProgressStore(reads)
            val copying = store.progress(batch)
            assertEquals(1, reads.queries.size)
            assertEquals(405, copying.total)
            assertEquals(55, copying.pending)
            assertEquals(50, copying.copying)
            assertEquals(100, copying.unknown)
            db.execute("UPDATE import_batches SET staging_complete=1 WHERE id=?", listOf(batch))
            val staged = store.progress(batch)
            assertEquals(400, staged.total)
            assertEquals(0, staged.copying)
            assertEquals(100, staged.queued)
            assertEquals(50, staged.failed)
        }
    }

    @Test fun warmQueriesDoNotMaterializeMembersAndDirectSqlMembershipWritesInvalidateCache() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val reads = Reads(db)
            val repo = KnowledgeRepository(reads, MemoryBlobSink())
            val kb = repo.createKnowledgeBase("warm membership")
            repo.importBytes("first.txt", "text/plain", "first alpha evidence".toByteArray(), false, kb)
            repo.importBytes("second.txt", "text/plain", "second alpha evidence".toByteArray(), false, kb)
            reads.queries.clear()
            repo.search("alpha", knowledgeBaseIds = listOf(kb))
            assertEquals(1, reads.fullMemberReads(), "A cold query must load its generation members once")
            val builds = repo.vectorIndexStats().builds
            reads.queries.clear()
            repo.search("alpha", knowledgeBaseIds = listOf(kb))
            assertEquals(0, reads.fullMemberReads(), "A warm query must reuse the checked member set")
            assertEquals(builds, repo.vectorIndexStats().builds)
            val member = db.query("SELECT * FROM generation_members WHERE generation_id=(SELECT active_generation_id FROM knowledge_bases WHERE id=?) LIMIT 1", listOf(kb)).single()
            val revision = EmbeddingInputRevision.current(db, kb)
            db.execute("DELETE FROM generation_members WHERE generation_id=? AND chunk_id=?", listOf(member.string("generation_id"), member.string("chunk_id")))
            assertTrue(EmbeddingInputRevision.current(db, kb) > revision)
            reads.queries.clear()
            assertFalse(repo.search("alpha", knowledgeBaseIds = listOf(kb)).any { it.chunkId == member.string("chunk_id") })
            assertEquals(1, reads.fullMemberReads(), "Committed direct-SQL mutation must reload generation members")
            assertTrue(repo.vectorIndexStats().builds > builds)
        }
    }

    @Test fun ftsSingleSyntaxTokenRemainsLiteralWithoutFallingBackToLike() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val reads = Reads(db)
            val repo = KnowledgeRepository(reads, MemoryBlobSink())
            val kb = repo.createKnowledgeBase("literal FTS")
            repo.importBytes("literal.txt", "text/plain", "foo-bar OR title:alpha literal evidence".toByteArray(), false, kb)
            val generation = repo.generationPins(listOf(kb))[kb]!!.generationId!!
            val store = KnowledgeRetrievalStore(reads) { }
            for (query in listOf("foo-bar", "OR", "title:alpha")) {
                reads.queries.clear()
                assertTrue(store.lexicalHits(kb, query, 8, generation).isNotEmpty(), query)
                assertFalse(reads.queries.any { it.contains("chunks.text LIKE") }, query)
            }
        }
    }

    @Test fun unchangedStartupRepairSkipsDocumentScanButExplicitRepairStillScans() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val reads = Reads(db)
            val repo = KnowledgeRepository(reads, MemoryBlobSink(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("repair checkpoint")
            repo.importBytes("source.txt", "text/plain", "stable published evidence".toByteArray(), false, kb)
            repo.repairIndexes(onlyChanged = true)
            reads.queries.clear()
            repo.repairIndexes(onlyChanged = true)
            assertFalse(reads.queries.any { it.contains("FROM documents") || it.contains("JOIN documents") })
            repo.repairIndexes()
            assertTrue(reads.queries.any { it.contains("FROM documents") || it.contains("JOIN documents") })
            db.execute("UPDATE knowledge_bases SET active_generation_id=NULL WHERE id=?", listOf(kb))
            reads.queries.clear()
            repo.repairIndexes(onlyChanged = true)
            assertTrue(reads.queries.any { it.contains("FROM documents") || it.contains("JOIN documents") })
            assertNotNull(repo.generationPins(listOf(kb))[kb]!!.generationId)
        }
    }

    @Test fun repairCheckpointCannotHideSourceMutationAfterCheckedRevision() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("repair race")
            val state = KnowledgeIndexRepairState(db)
            val checked = EmbeddingInputRevision.current(db, kb)
            repo.importBytes("new.txt", "text/plain", "newly committed source".toByteArray(), false, kb)
            val localSpace = HashingTextEmbedder().spaceId
            state.completed(kb, localSpace, checked)
            assertTrue(kb in state.candidates(localSpace, onlyChanged = true))
        }
    }

    @Test fun validApiGenerationCheckpointSkipsRepeatedStartupScansAdapterAndRebuild() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val reads = Reads(db)
            val blobs = MemoryBlobSink()
            val api = ApiProbe()
            val repo = apiRepository(reads, blobs, api)
            val kb = repo.createApiKnowledgeBase("API checkpoint", api.binding)
            assertEquals(ImportStage.READY, repo.importBytes("api.txt", "text/plain", "native API source".toByteArray(),
                false, kb, embeddingIsApi = true, embeddingConsent = true).stage)
            val generation = repo.generationPins(listOf(kb))[kb]!!.generationId
            val calls = api.calls
            assertTrue(calls > 0)
            repo.repairIndexes(onlyChanged = true)
            assertEquals(0L, rebuilds(db, kb)) // Not even a cache-only REBUILD operation was created.
            assertEquals(calls, api.calls)
            val resolutions = api.resolutions
            reads.queries.clear()
            apiRepository(reads, blobs, api).repairIndexes(onlyChanged = true)
            assertEquals(resolutions, api.resolutions)
            assertEquals(calls, api.calls)
            assertEquals(0L, rebuilds(db, kb))
            assertEquals(generation, repo.generationPins(listOf(kb))[kb]!!.generationId)
            assertFalse(reads.queries.any { it.contains("FROM documents") || it.contains("JOIN documents") })
        }
    }

    @Test fun unavailableApiAdapterLeavesMissingVectorsDirtyThenAvailableStartupRepairsAndCheckpoints() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val api = ApiProbe()
            val repo = apiRepository(db, blobs, api)
            val kb = repo.createApiKnowledgeBase("API recover", api.binding)
            repo.importBytes("api.txt", "text/plain", "recoverable native source".toByteArray(),
                false, kb, embeddingIsApi = true, embeddingConsent = true)
            repo.repairIndexes(onlyChanged = true)
            val generation = repo.generationPins(listOf(kb))[kb]!!.generationId!!
            val revision = EmbeddingInputRevision.current(db, kb)
            db.execute("DELETE FROM embeddings WHERE chunk_id IN(SELECT chunk_id FROM generation_members WHERE generation_id=?)", listOf(generation))
            assertEquals(revision, EmbeddingInputRevision.current(db, kb)) // Cache writes deliberately do not alter source identity.
            val checkpoints = KnowledgeIndexRepairState(db)
            val localSpace = HashingTextEmbedder().spaceId
            assertTrue(kb in checkpoints.candidates(localSpace, onlyChanged = true))
            api.available = false
            val calls = api.calls
            apiRepository(db, blobs, api).repairIndexes(onlyChanged = true)
            assertEquals(calls, api.calls)
            assertEquals(0L, rebuilds(db, kb))
            assertTrue(kb in checkpoints.candidates(localSpace, onlyChanged = true))
            api.available = true
            apiRepository(db, blobs, api).repairIndexes(onlyChanged = true)
            assertEquals(1L, rebuilds(db, kb))
            assertTrue(api.calls > calls)
            assertNotEquals(generation, repo.generationPins(listOf(kb))[kb]!!.generationId)
            assertFalse(kb in checkpoints.candidates(localSpace, onlyChanged = true))
            assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM embeddings WHERE space_id=?", listOf(api.spaceId)).single().long("n"))
            val repairedCalls = api.calls
            val repairedResolutions = api.resolutions
            apiRepository(db, blobs, api).repairIndexes(onlyChanged = true)
            assertEquals(repairedCalls, api.calls)
            assertEquals(repairedResolutions, api.resolutions)
            assertEquals(1L, rebuilds(db, kb))
        }
    }

    @Test fun failedApiRepairRemainsDirtyAndUnknownDoesNotAutomaticallyDispatchAgain() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val api = ApiProbe()
            val repo = apiRepository(db, blobs, api)
            val kb = repo.createApiKnowledgeBase("API unknown", api.binding)
            repo.importBytes("api.txt", "text/plain", "unknown receipt source".toByteArray(),
                false, kb, embeddingIsApi = true, embeddingConsent = true)
            repo.repairIndexes(onlyChanged = true)
            db.execute("DELETE FROM embeddings WHERE space_id=?", listOf(api.spaceId))
            api.fail = true
            assertThrows(IllegalStateException::class.java) { repo.repairIndexes(onlyChanged = true) }
            assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM embedding_operations WHERE kb_id=? AND state='UNKNOWN'", listOf(kb)).single().long("n"))
            val calls = api.calls
            api.fail = false
            apiRepository(db, blobs, api).repairIndexes(onlyChanged = true)
            assertEquals(calls, api.calls)
            assertTrue(kb in KnowledgeIndexRepairState(db).candidates(HashingTextEmbedder().spaceId, onlyChanged = true))
            assertEquals(1L, rebuilds(db, kb))
        }
    }

    @Test fun missingApiGenerationMemberRebuildsFromCachedVectorsWithoutDispatch() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val api = ApiProbe()
            val repo = apiRepository(db, blobs, api)
            val kb = repo.createApiKnowledgeBase("API members", api.binding)
            repo.importBytes("api.txt", "text/plain", "cached native member source".toByteArray(),
                false, kb, embeddingIsApi = true, embeddingConsent = true)
            repo.repairIndexes(onlyChanged = true)
            val generation = repo.generationPins(listOf(kb))[kb]!!.generationId!!
            db.execute("DELETE FROM generation_members WHERE generation_id=?", listOf(generation))
            val calls = api.calls
            apiRepository(db, blobs, api).repairIndexes(onlyChanged = true)
            assertEquals(calls, api.calls)
            assertEquals(1L, rebuilds(db, kb))
            val repaired = repo.generationPins(listOf(kb))[kb]!!.generationId!!
            assertNotEquals(generation, repaired)
            assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id=?", listOf(repaired)).single().long("n"))
            assertFalse(kb in KnowledgeIndexRepairState(db).candidates(HashingTextEmbedder().spaceId, onlyChanged = true))
        }
    }
}
