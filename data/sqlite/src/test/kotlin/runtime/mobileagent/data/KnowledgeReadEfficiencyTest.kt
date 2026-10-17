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

    /** Counts the rows a store hands back to Kotlin, so a summary read and a bulk read are comparable. */
    private class Rows(private val db: SqlConnection) : SqlConnection by db {
        var rows = 0
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            val result = db.query(sql, args)
            rows += result.size
            return result
        }

        fun <T> measure(block: () -> T): Pair<T, Int> {
            val before = rows
            val value = block()
            return value to (rows - before)
        }
    }

    /**
     * One fully staged batch of [members] mixed member states, joined to a real job row.
     *
     * `staging_complete=1` is what makes a still-copying member read as QUEUED, so this fixture
     * exercises the shared effective-state rule of the summary and the detail read.
     */
    private fun seedBatch(db: SqlConnection, batch: String, job: String, members: Int) {
        db.execute("UPDATE import_batches SET total_items=?,staging_complete=1 WHERE id=?", listOf(members, batch))
        db.execute("UPDATE import_jobs SET stage='FAILED' WHERE id=?", listOf(job))
        val states = listOf("PUBLISHED", "PENDING", "COPYING", "QUEUED", "PROCESSING", "WAITING", "FAILED", "CANCELLED")
        db.transaction {
            repeat(members) { index ->
                db.execute(
                    "INSERT INTO import_items(id,batch_id,item_key,relative_path,job_id,kind,state,error) VALUES(?,?,?,?,?,'FILE',?,?)",
                    listOf("$batch-member-$index", batch, "item-$index", "synthetic/$index", job,
                        states[index % states.size], if (index == 3) "UNKNOWN_OUTCOME: receipt pending" else null),
                )
            }
        }
    }

    @Test fun collapsedBatchSummaryReadsEveryCountWithoutMaterializingMemberRows() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("collapsed snapshot")
            val job = repo.importBytes("member.txt", "text/plain", "member source".toByteArray(), false, kb)
            val batch = repo.beginBatch(kb, ImportBatchKind.FILES, "collapsed batch")
            seedBatch(db, batch, job.id, 400)
            val rows = Rows(db)
            val store = KnowledgeBatchDisplayStore(rows)
            val bulk = rows.measure { store.read(kb, listOf(batch)) }
            val summary = rows.measure { store.summaries(kb, listOf(batch)) }
            val bulkDisplay = bulk.first.getValue(batch)
            val batchSummary = summary.first.getValue(batch)
            // Every number the collapsed card shows stays real, so truthfulness never depends on rows.
            assertEquals(1, summary.first.size)
            assertEquals(bulkDisplay.stagingComplete, batchSummary.stagingComplete)
            assertEquals(400, batchSummary.itemCount)
            assertEquals(bulkDisplay.items.size, batchSummary.itemCount)
            assertEquals(bulkDisplay.policy, batchSummary.policy)
            assertEquals("UNKNOWN_OUTCOME: receipt pending", batchSummary.unknownOutcomeReason)
            assertTrue(batchSummary.retryWaiting)
            // The split itself: a collapsed snapshot stops carrying one row per imported member.
            assertEquals(401, bulk.second)
            assertEquals(3, summary.second)
            println("KNOWLEDGE_BATCH_SUMMARY members=400 bulk_rows=${bulk.second} summary_rows=${summary.second}")
            db.execute("UPDATE import_items SET error='unknown_outcome: lowercase' WHERE batch_id=?", listOf(batch))
            db.execute("UPDATE import_items SET error='UNKNOWNxOUTCOME: wildcard' WHERE id=?", listOf("$batch-member-0"))
            assertEquals(null, store.summaries(kb, listOf(batch)).getValue(batch).unknownOutcomeReason,
                "The summary must use the same literal, case-sensitive marker as the member cards")
        }
    }

    @Test fun openedBatchDetailReadsOnlyTheRowsOfThatExactBatch() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("opened detail")
            val job = repo.importBytes("member.txt", "text/plain", "member source".toByteArray(), false, kb)
            val opened = repo.beginBatch(kb, ImportBatchKind.FILES, "opened")
            val other = repo.beginBatch(kb, ImportBatchKind.FILES, "other")
            seedBatch(db, opened, job.id, 400)
            seedBatch(db, other, job.id, 7)
            val rows = Rows(db)
            val store = KnowledgeBatchDisplayStore(rows)
            val detail = rows.measure { store.details(opened) }
            val truth = db.query("SELECT COUNT(*) AS n FROM import_items WHERE batch_id=?", listOf(opened)).single().long("n").toInt()
            assertEquals(400, truth)
            assertEquals(truth, detail.first.size)
            // One query, and never another batch's members.
            assertEquals(400, detail.second)
            assertEquals(7, store.details(other).size)
            assertEquals("UNKNOWN_OUTCOME: receipt pending", detail.first.first { it.error != null }.error)
            assertEquals("QUEUED", detail.first.first { it.state == "QUEUED" }.state)
            assertEquals(400, store.summaries(kb, listOf(opened)).getValue(opened).itemCount)
            println("KNOWLEDGE_BATCH_DETAIL opened=${detail.first.size} other=7 detail_rows=${detail.second}")
        }
    }

    @Test fun summariesMatchTheBulkReadForEmptyBatchListsAndForeignBases() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("summary equivalence")
            val empty = repo.beginBatch(kb, ImportBatchKind.FILES, "empty")
            val foreign = repo.beginBatch(repo.createKnowledgeBase("foreign base"), ImportBatchKind.FILES, "foreign")
            val job = repo.importBytes("member.txt", "text/plain", "member source".toByteArray(), false, kb)
            val populated = repo.beginBatch(kb, ImportBatchKind.FILES, "populated")
            seedBatch(db, populated, job.id, 9)
            val store = KnowledgeBatchDisplayStore(db)
            val requested = listOf(empty, populated, foreign)
            // The bulk read stays the oracle: the summary must agree on every batch it keeps.
            val bulk = store.read(kb, requested)
            val summary = store.summaries(kb, requested)
            assertEquals(bulk.keys, summary.keys)
            bulk.forEach { (batchId, display) ->
                assertEquals(display.items.size, summary.getValue(batchId).itemCount, batchId)
                assertEquals(display.stagingComplete, summary.getValue(batchId).stagingComplete, batchId)
                assertEquals(display.policy, summary.getValue(batchId).policy, batchId)
            }
            assertEquals(0, summary.getValue(empty).itemCount)
            assertEquals(9, summary.getValue(populated).itemCount)
            assertEquals(null, summary.getValue(empty).unknownOutcomeReason)
            assertFalse(summary.getValue(empty).retryWaiting)
            assertTrue(store.details(empty).isEmpty())
            assertTrue(store.summaries(kb, emptyList()).isEmpty())
            assertTrue(store.summaries(null, requested).isEmpty())
            assertEquals(setOf(job.id), store.memberJobIds(kb))
            assertTrue(store.memberJobIds(null).isEmpty())
            println("KNOWLEDGE_BATCH_SUMMARY_EQUIVALENCE batches=${summary.size} members=${summary.getValue(populated).itemCount}")
        }
    }
}
