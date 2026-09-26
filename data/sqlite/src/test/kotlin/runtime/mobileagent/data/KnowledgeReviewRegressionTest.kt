// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking
import runtime.mobileagent.knowledge.*

class KnowledgeReviewRegressionTest {
    @Test fun imageDisplayNamesCannotForgeContextAcrossImportSearchCitationAndEvidence() {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
        for (name in listOf("photo.png", "photo|association:PAGE_CONTEXT|.png", "photo|part:context|.png", "100% photo|source:parser-native|.png")) {
            JdbcSqlConnection().use { db ->
                Migrations.apply(db)
                var calls = 0
                val repo = KnowledgeRepository(db, MemoryBlobSink(), vision = VisionBackend { input ->
                    assertTrue(input.beforeDispatch()); calls++
                    VisionOutcome.Success(VisionSuccess("cobalt OCR", "cobalt description", "|cobalt|value|\n|---|---|\n|cell|42|"))
                })
                val job = repo.importBytes(name, "image/png", png, true, visionConsent = true)
                assertEquals(ImportStage.READY, job.stage, "$name: ${job.error}")
                assertEquals(1, calls)
                fun verify(): List<runtime.mobileagent.knowledge.Citation> {
                    val result = repo.retrieve("image-review", "cobalt", 8)
                    assertTrue(result.citations.isNotEmpty())
                    result.citations.forEach { citation ->
                        assertNotNull(citation.assetId, name)
                        assertFalse(repo.locateCitation(citation).removed)
                        assertTrue(repo.evidenceBytes(citation)!!.second.contentEquals(png))
                    }
                    return result.citations
                }
                val citations = verify()
                // Explicit legacy fixture: actual old builder appended the real part
                // after the raw section. Upgrade must keep that image binding too.
                citations.forEach { citation ->
                    val part = decodeSourceSpan(citation.sourceSpan!!)!!.part
                    db.execute("UPDATE chunks SET source_span=? WHERE id=?", listOf("page:1|section:$name|part:$part", citation.chunkId))
                }
                verify()
                assertEquals(1, calls, "decoding old provenance cannot replay Vision")
                repo.closeVectorIndexes()
            }
        }
    }

    @Test fun apiBatchesKeepSuccessfulCacheAcrossUnknownAndDoNotRepeatItOnExplicitRetry() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val binding = ApiEmbeddingBinding(providerId = "provider-test", endpoint = "https://example.invalid/embeddings", providerRevision = 1,
                modelId = "fixture", modelRevision = 1, dimension = 8, dataScope = "synthetic test text")
            val requests = mutableListOf<List<String>>()
            var failSecond = true
            val api = object : TextEmbedder, BatchTextEmbedder {
                override val spaceId = binding.spaceId
                override val dimension = binding.dimension
                override fun embed(text: String): FloatArray = HashingTextEmbedder(dimension = 8).embed(text)
                override fun embedBatch(texts: List<String>): List<FloatArray> {
                    requests += texts.toList()
                    if (failSecond && requests.size == 2) { failSecond = false; error("synthetic unknown transport") }
                    return texts.map(::embed)
                }
            }
            val repo = KnowledgeRepository(db, MemoryBlobSink(), apiEmbedder = api)
            val kb = repo.createApiKnowledgeBase("bounded API fixture", binding)
            val text = (0 until 350).joinToString("\n") { "unique-$it " + "段落x".repeat(590) }
            val failed = repo.importBytes("api.txt", "text/plain", text.toByteArray(), false, kb,
                embeddingIsApi = true, embeddingConsent = true)
            assertEquals(ImportStage.FAILED, failed.stage)
            assertTrue(failed.error.orEmpty().contains("UNKNOWN_OUTCOME"))
            assertEquals(2, requests.size)
            val savedText = requests.first().toSet()
            assertTrue(savedText.size > 100)
            assertEquals(128L, db.query("SELECT COUNT(*) AS n FROM embeddings").single().long("n"))
            assertThrows(IllegalStateException::class.java) { repo.resumeImport(failed.id, visionConfigured = false) }
            assertEquals(2, requests.size)
            val retried = runBlocking { repo.retryUnknownEmbeddingCancellable(failed.id, true) }
            assertEquals(ImportStage.READY, retried.stage, retried.error)
            assertTrue(requests.drop(2).flatten().none { it in savedText }, "committed successful batches cannot be billed again")
            assertTrue(requests.all { it.size <= 128 })
            val calls = requests.size
            repo.rebuildIndex(kb)
            assertEquals(calls, requests.size, "cached API finalize/rebuild must not dispatch")
            repo.closeVectorIndexes()
        }
    }

    @Test fun warmGenerationPublicationAndExplicitRebuildReadBoundedBodiesAndReuseVectors() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            var embeddingCalls = 0
            val hashing = HashingTextEmbedder(dimension = 8)
            val embedder = object : TextEmbedder by hashing {
                override fun embed(text: String): FloatArray { embeddingCalls++; return hashing.embed(text) }
            }
            var observed = false
            var maxRows = 0
            var bodyRows = 0
            var failSwitch = false
            val connection = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> = db.query(sql, args).also { rows ->
                    if (observed && sql.startsWith("SELECT id, text, content_hash FROM chunks")) {
                        maxRows = maxOf(maxRows, rows.size); bodyRows += rows.size
                        assertTrue(sql.contains("LIMIT 128"))
                    }
                }
                override fun execute(sql: String, args: List<Any?>) {
                    if (failSwitch && sql.startsWith("UPDATE knowledge_bases SET active_generation_id")) {
                        failSwitch = false
                        error("injected publication failure")
                    }
                    db.execute(sql, args)
                }
            }
            val repo = KnowledgeRepository(connection, MemoryBlobSink(), embedder)
            val text = (0 until 300).joinToString("\n") { "unique$it cobalt " + "資料x".repeat(590) }
            val seed = repo.importBytes("seed.txt", "text/plain", text.toByteArray(), false)
            assertEquals(ImportStage.READY, seed.stage, seed.error)
            fun generation() = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id=?", listOf(seed.knowledgeBaseId)).single().string("active_generation_id")
            val oldGeneration = generation()
            val count = db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id=?", listOf(oldGeneration)).single().long("n")
            assertTrue(count > 128)
            val oldHits = repo.search("cobalt", 4).map { it.chunkId }
            assertTrue(oldHits.isNotEmpty())
            observed = true
            failSwitch = true
            val failed = repo.importBytes("failed.txt", "text/plain", "do not publish this failed marker".toByteArray(), false, seed.knowledgeBaseId)
            assertEquals(ImportStage.FAILED, failed.stage)
            assertEquals(oldGeneration, generation())
            assertEquals(oldHits, repo.search("cobalt", 4).map { it.chunkId })
            val before = embeddingCalls
            val added = repo.importBytes("new.txt", "text/plain", "incremental marker blossom".toByteArray(), false, seed.knowledgeBaseId)
            assertEquals(ImportStage.READY, added.stage, added.error)
            assertEquals(before + 1, embeddingCalls, "old chunk embeddings must not be recomputed")
            val newGeneration = generation()
            assertNotEquals(oldGeneration, newGeneration)
            assertEquals(count + 1, db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id=?", listOf(newGeneration)).single().long("n"))
            assertTrue(repo.search("blossom").any { it.documentId == added.documentId })
            assertTrue(assertThrows(IllegalStateException::class.java) { repo.rebuildIndex(seed.knowledgeBaseId) }
                .message.orEmpty().contains("INDEX_SOURCE_INCOMPLETE"))
            assertEquals(ImportStage.READY, repo.resumeImport(failed.id, visionConfigured = false).stage)
            val beforeRebuild = embeddingCalls
            val rebuilt = repo.rebuildIndex(seed.knowledgeBaseId)
            assertNotEquals(newGeneration, rebuilt)
            assertEquals(beforeRebuild, embeddingCalls)
            assertTrue(bodyRows > count * 2)
            assertTrue(maxRows in 1..128)
            repo.closeVectorIndexes()
        }
    }

    @Test fun documentPagesReadOnlyOverlappingBodiesAndRetainUtf16Offsets() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val repo = KnowledgeRepository(db, blobs, HashingTextEmbedder(dimension = 8))
            val job = repo.importBytes("pages.txt", "text/plain", ("甲😀e\u0301\u0000Z\n".repeat(35_000)).toByteArray(), false)
            assertEquals(ImportStage.READY, job.stage, job.error)
            val truth = db.query("SELECT text FROM chunks WHERE document_version_id=(SELECT active_version_id FROM documents WHERE id=?) ORDER BY ordinal", listOf(job.documentId)).joinToString("\n") { it.string("text") }
            var bodies = 0
            var maxBodies = 0
            val observed = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> = db.query(sql, args).also { rows ->
                    val returned = rows.count { it.columns.containsKey("text") }
                    bodies += returned; maxBodies = maxOf(maxBodies, returned)
                }
            }
            val reader = KnowledgeRepository(observed, blobs)
            var offset = 0
            var version: String? = null
            val actual = StringBuilder()
            repeat(100) {
                val page = reader.readDocumentRange(job.documentId, 113, offset, expectedVersion = version)
                version = page.documentVersionId
                actual.append(page.text)
                offset = page.nextOffset!!
            }
            assertEquals(truth.substring(0, offset), actual.toString())
            assertTrue(maxBodies <= 2, "small pages must read at most their overlapping chunks, not all bodies")
            assertTrue(bodies <= 200)
            var tailOffset = truth.length - 37
            if (truth[tailOffset].isLowSurrogate()) tailOffset--
            val tail = reader.readDocumentRange(job.documentId, 100, tailOffset, expectedVersion = version)
            assertEquals(truth.substring(tailOffset), tail.text)
            assertEquals(truth.length, tail.totalChars)
            assertNull(tail.nextOffset)
            assertThrows(IllegalArgumentException::class.java) { reader.readDocumentRange(job.documentId, 100, 2, expectedVersion = version) }
        }
    }

    @Test fun continuationPinsPublishedVersionButNeverOverridesDeletionOrAuthorization() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink())
            val job = repo.importBytes("version.txt", "text/plain", "ABCDEFGH".toByteArray(), false)
            val citation = repo.retrieve("run", "ABCDEFGH").citations.first()
            val first = repo.readDocumentRange(job.documentId, 4)
            assertEquals("ABCD", first.text)
            assertNotNull(first.documentVersionId)
            fun publish(id: String, text: String, status: String = "READY") {
                db.execute("INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES(?,?,?,?,?,?)",
                    listOf(id, job.documentId, "test", sha256Hex(text.toByteArray()), status, "test"))
                db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,text_utf16_length) VALUES(?,?,?,?,?,?)",
                    listOf("chunk-$id", id, 0, text, sha256Hex(text.toByteArray()), text.length))
                if (status == "READY") db.execute("UPDATE documents SET active_version_id=? WHERE id=?", listOf(id, job.documentId))
            }
            for ((id, text) in listOf("v2" to "12345678", "v3" to "tiny")) {
                publish(id, text)
                assertEquals("EFGH", repo.readDocumentRange(job.documentId, 4, first.nextOffset!!, expectedVersion = first.documentVersionId).text)
                assertEquals(text, repo.readDocumentRange(job.documentId, 100).text)
                assertFalse(repo.locateCitation(citation).removed)
                assertTrue(repo.evidenceBytes(citation)!!.second.contentEquals("ABCDEFGH".toByteArray()))
            }
            assertThrows(IllegalArgumentException::class.java) { repo.readDocumentRange(job.documentId, 4, first.nextOffset!!) }
            publish("staging", "SECRET", "STAGING")
            assertThrows(IllegalArgumentException::class.java) { repo.readDocumentRange(job.documentId, 100, expectedVersion = "staging") }
            val other = repo.importBytes("other.txt", "text/plain", "other document".toByteArray(), false)
            assertThrows(IllegalArgumentException::class.java) { repo.readDocumentRange(other.documentId, 100, expectedVersion = first.documentVersionId) }
            assertEquals("", repo.readDocumentRange(job.documentId, 4, 4, emptySet(), first.documentVersionId).text)
            repo.deleteDocument(job.documentId)
            assertEquals("", repo.readDocumentRange(job.documentId, 4, 4, expectedVersion = first.documentVersionId).text)
            assertTrue(repo.locateCitation(citation).removed)
            repo.closeVectorIndexes()
        }
    }

    @Test fun v24LengthMigrationIsBoundedUnicodeExactAtomicAndRepeatable() {
        JdbcSqlConnection().use { db ->
            db.execute("CREATE TABLE schema_version(version INTEGER NOT NULL PRIMARY KEY)")
            db.execute("INSERT INTO schema_version VALUES(24)")
            db.execute("CREATE TABLE chunks(id TEXT PRIMARY KEY,document_version_id TEXT NOT NULL,ordinal INTEGER NOT NULL,text TEXT NOT NULL,content_hash TEXT NOT NULL,source_span TEXT,asset_ids TEXT,page INTEGER,UNIQUE(document_version_id,ordinal))")
            val text = "甲😀\u0000Z"
            db.transaction { repeat(270) { db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash) VALUES(?,?,?,?,?)", listOf("id%04d".format(it), "legacy", it, text, "hash")) } }
            var fail = true
            val batches = mutableListOf<Int>()
            val observed = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> = db.query(sql, args).also { if (sql.startsWith("SELECT id, text FROM chunks WHERE text_utf16_length")) batches += it.size }
                override fun execute(sql: String, args: List<Any?>) {
                    if (fail && sql.startsWith("UPDATE chunks SET text_utf16_length") && args.last() == "id0140") error("injected migration failure")
                    db.execute(sql, args)
                }
            }
            assertThrows(IllegalStateException::class.java) { Migrations.apply(observed) }
            assertEquals(24, db.query("SELECT version FROM schema_version").single().long("version").toInt())
            assertFalse(db.query("PRAGMA table_info(chunks)").any { it.string("name") == "text_utf16_length" })
            fail = false
            Migrations.apply(observed)
            Migrations.apply(observed)
            assertTrue(batches.all { it <= 128 })
            assertEquals(270L, db.query("SELECT COUNT(*) AS n FROM chunks WHERE text=? AND text_utf16_length=?", listOf(text, text.length)).single().long("n"))
            assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
        }
    }
}
