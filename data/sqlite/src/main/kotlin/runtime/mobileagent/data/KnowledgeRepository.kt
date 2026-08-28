// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.CitationMap
import runtime.mobileagent.knowledge.CjkLexical
import runtime.mobileagent.knowledge.CosineIndex
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.ImportStateMachine
import runtime.mobileagent.knowledge.MediaKind
import runtime.mobileagent.knowledge.ReciprocalRankFusion
import runtime.mobileagent.knowledge.RetrievalResult
import runtime.mobileagent.knowledge.SearchHit
import runtime.mobileagent.knowledge.SourceFormat
import runtime.mobileagent.knowledge.StoredBlob
import runtime.mobileagent.knowledge.TextChunker
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.ZipSafety
import runtime.mobileagent.knowledge.sha256Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KnowledgeRepository(
    private val db: SqlConnection,
    private val blobs: BlobSink,
    private val embedder: TextEmbedder = HashingTextEmbedder(),
) {
    private val indexLock = Any()

    fun ensureDefaultBase(): String {
        val existing = db.query("SELECT id FROM knowledge_bases WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1")
        if (existing.isNotEmpty()) return existing.single().string("id")
        return createKnowledgeBase("On-device library", DEFAULT_KB_ID)
    }

    fun createKnowledgeBase(name: String, id: String = EntityId.random().value): String {
        db.execute(
            "INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES (?,?,?,?,?,?)",
            listOf(id, name, null, embedder.spaceId, Utc.nowIso(), null),
        )
        return id
    }

    fun listKnowledgeBases(): List<Pair<String, String>> =
        db.query("SELECT id, name FROM knowledge_bases WHERE deleted_at IS NULL ORDER BY created_at")
            .map { it.string("id") to it.string("name") }

    fun importBytes(
        displayName: String,
        mediaType: String,
        bytes: ByteArray,
        visionConfigured: Boolean,
        knowledgeBaseId: String? = null,
        pauseAt: ImportStage? = null,
    ): ImportJob {
        require(bytes.size <= MediaKind.MAX_IMPORT_BYTES) { "RESOURCE_LIMIT" }
        val kbId = knowledgeBaseId ?: ensureDefaultBase()
        requireKb(kbId)
        val format = MediaKind.detect(displayName, mediaType, bytes.copyOf(minOf(bytes.size, 64)))
        val stored = blobs.put(bytes, mediaType.ifBlank { guessedMime(format) })
        val existingId = existingDocument(kbId, stored.sha256)
        if (existingId != null) {
            val prior = db.query("SELECT active_version_id, deleted_at FROM documents WHERE id = ?", listOf(existingId)).single()
            if (prior.string("deleted_at").isBlank() && prior.string("active_version_id").isNotBlank()) {
                val job = ImportJob(
                    id = EntityId.random().value,
                    knowledgeBaseId = kbId,
                    documentId = existingId,
                    stage = ImportStage.READY,
                    visionConfigured = visionConfigured,
                    localEmbeddingAvailable = true,
                )
                persistJob(job, displayName)
                return job
            }
        }
        upsertBlob(stored)
        val documentId = existingId ?: EntityId.random().value
        db.execute(
            "INSERT OR REPLACE INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES (?,?,?,?,?,?,?)",
            listOf(documentId, kbId, stored.sha256, displayName, format.name, null, null),
        )
        val job = ImportJob(
            id = EntityId.random().value,
            knowledgeBaseId = kbId,
            documentId = documentId,
            hasImages = MediaKind.isImage(format),
            visionConfigured = visionConfigured,
        )
        job.localEmbeddingAvailable = true
        advanceThrough(job, ImportStage.COPYING)
        persistJob(job, displayName)
        if (pauseAt == ImportStage.COPYING) return job
        return continueImport(job, displayName, bytes, format)
    }

    fun resumeImport(jobId: String, bytes: ByteArray, visionConfigured: Boolean): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).single()
        val displayName = row.string("display_name")
        val format = MediaKind.detect(displayName, "", bytes.copyOf(minOf(bytes.size, 64)))
        val job = ImportJob(
            id = jobId,
            knowledgeBaseId = row.string("kb_id"),
            documentId = row.string("document_id"),
            stage = ImportStage.valueOf(row.string("stage")),
            hasImages = row.long("has_images") != 0L,
            visionConfigured = visionConfigured,
            localEmbeddingAvailable = true,
            error = row.string("error").ifBlank { null },
        )
        return continueImport(job, displayName, bytes, format)
    }

    fun search(query: String, topK: Int = 8, knowledgeBaseIds: List<String>? = null): List<SearchHit> =
        retrieve("search", query, topK, knowledgeBaseIds).hits

    fun retrieve(runId: String, query: String, topK: Int = 8, knowledgeBaseIds: List<String>? = null): RetrievalResult {
        if (query.isBlank()) return RetrievalResult(emptyList(), emptyList(), listOf("empty query"))
        val warnings = mutableListOf<String>()
        val bases = knowledgeBaseIds ?: listKnowledgeBases().map { it.first }
        val fused = mutableListOf<SearchHit>()
        for (kbId in bases) {
            val kb = db.query("SELECT * FROM knowledge_bases WHERE id = ? AND deleted_at IS NULL", listOf(kbId)).singleOrNull()
            if (kb == null) {
                warnings += "Knowledge base $kbId is missing or deleted"
                continue
            }
            val space = kb.string("embedding_space_id")
            if (space.isNotBlank() && space != embedder.spaceId) {
                warnings += "Knowledge base $kbId uses space $space; query embedder is ${embedder.spaceId}"
                continue
            }
            val lexical = lexicalHits(kbId, query, 40)
            val vector = vectorHits(kbId, query, 40)
            fused += ReciprocalRankFusion.merge(listOf(lexical, vector)).take(topK)
        }
        val hits = fused.take(topK)
        if (hits.isEmpty()) warnings += "No in-scope evidence"
        return RetrievalResult(hits, CitationMap.bind(runId, hits), warnings)
    }

    fun waitingForVisionCount(): Int =
        db.query(
            "SELECT COUNT(*) AS n FROM import_jobs WHERE stage = ?",
            listOf(ImportStage.WAITING_FOR_VISION_MODEL.name),
        ).singleOrNull()?.long("n")?.toInt() ?: 0

    fun listJobs(): List<Triple<ImportJob, String, String>> =
        db.query("SELECT * FROM import_jobs ORDER BY updated_at DESC").map { row ->
            Triple(
                ImportJob(
                    id = row.string("id"),
                    knowledgeBaseId = row.string("kb_id"),
                    documentId = row.string("document_id"),
                    stage = ImportStage.valueOf(row.string("stage")),
                    hasImages = row.long("has_images") != 0L,
                    error = row.string("error").ifBlank { null },
                ),
                row.string("display_name"),
                row.string("updated_at"),
            )
        }

    fun blobRefCount(hash: String): Long =
        db.query("SELECT ref_count FROM blobs WHERE hash = ?", listOf(hash)).singleOrNull()?.long("ref_count") ?: 0L

    fun deleteDocument(documentId: String) {
        synchronized(indexLock) {
            val row = db.query("SELECT kb_id, blob_hash FROM documents WHERE id = ?", listOf(documentId)).singleOrNull() ?: return
            db.execute("UPDATE documents SET deleted_at = ? WHERE id = ?", listOf(Utc.nowIso(), documentId))
            decrementBlob(row.string("blob_hash"))
            rebuildIndex(row.string("kb_id"))
        }
    }

    fun deleteKnowledgeBase(kbId: String) {
        synchronized(indexLock) {
            db.query("SELECT id FROM documents WHERE kb_id = ? AND deleted_at IS NULL", listOf(kbId)).forEach { row ->
                val doc = db.query("SELECT blob_hash FROM documents WHERE id = ?", listOf(row.string("id"))).single()
                db.execute("UPDATE documents SET deleted_at = ? WHERE id = ?", listOf(Utc.nowIso(), row.string("id")))
                decrementBlob(doc.string("blob_hash"))
            }
            db.execute("UPDATE knowledge_bases SET deleted_at = ?, active_generation_id = NULL WHERE id = ?", listOf(Utc.nowIso(), kbId))
        }
    }

    fun rebuildIndex(kbId: String): String = synchronized(indexLock) {
        requireKb(kbId)
        val generationId = EntityId.random().value
        val versions = db.query(
            "SELECT id, active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL AND active_version_id IS NOT NULL",
            listOf(kbId),
        )
        var vectors = 0
        db.transaction {
            db.execute(
                "INSERT INTO index_generations(id,kb_id,space_id,manifest_hash,state,vector_count,fts_version,created_at) VALUES (?,?,?,?,?,?,?,?)",
                listOf(generationId, kbId, embedder.spaceId, generationId, "BUILDING", 0, 1, Utc.nowIso()),
            )
            versions.forEach { doc ->
                val versionId = doc.string("active_version_id")
                db.query("SELECT id FROM chunks WHERE document_version_id = ?", listOf(versionId)).forEach { chunk ->
                    db.execute(
                        "INSERT OR IGNORE INTO generation_members(generation_id,chunk_id,space_id,document_version_id) VALUES (?,?,?,?)",
                        listOf(generationId, chunk.string("id"), embedder.spaceId, versionId),
                    )
                    vectors += 1
                }
            }
            db.execute(
                "UPDATE index_generations SET state = ?, vector_count = ?, manifest_hash = ? WHERE id = ?",
                listOf("READY", vectors, sha256Hex("$generationId:$vectors".toByteArray()), generationId),
            )
            db.execute(
                "UPDATE knowledge_bases SET active_generation_id = ?, embedding_space_id = ? WHERE id = ?",
                listOf(generationId, embedder.spaceId, kbId),
            )
        }
        generationId
    }

    private fun continueImport(job: ImportJob, displayName: String, bytes: ByteArray, format: SourceFormat): ImportJob {
        advanceThrough(job, ImportStage.PARSING)
        when (format) {
            SourceFormat.IMAGE -> advanceThrough(job, ImportStage.WAITING_FOR_VISION_MODEL)
            SourceFormat.TEXT, SourceFormat.MARKDOWN -> indexTextDocument(job, bytes, format)
            SourceFormat.PDF -> fail(job, "PDF import is not in this build yet. The file was copied and was not dropped.")
            SourceFormat.OFFICE_ARCHIVE -> {
                val inspection = ZipSafety.inspect(bytes)
                if (!inspection.ok) {
                    fail(job, inspection.reason)
                } else {
                    fail(job, "DOCX/EPUB import is not in this build yet. ${inspection.reason}.")
                }
            }
            SourceFormat.UNKNOWN -> fail(job, "Unsupported file type. The file was copied and was not dropped.")
        }
        persistJob(job, displayName)
        return job
    }

    private fun indexTextDocument(job: ImportJob, bytes: ByteArray, format: SourceFormat) {
        val text = String(bytes, Charsets.UTF_8)
        job.hasImages = format == SourceFormat.MARKDOWN && MediaKind.markdownReferencesImages(text)
        if (job.hasImages) {
            advanceThrough(job, ImportStage.WAITING_FOR_VISION_MODEL)
            if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                job.error = "Markdown references images. They were not downloaded and the document is not READY."
            }
            return
        }
        val chunks = TextChunker.chunk(text)
        if (chunks.isEmpty()) {
            fail(job, "The file is empty")
            return
        }
        val versionId = EntityId.random().value
        val contentHash = sha256Hex(bytes)
        db.execute(
            "INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES (?,?,?,?,?,?)",
            listOf(versionId, job.documentId, PARSER_FINGERPRINT, contentHash, "READY", Utc.nowIso()),
        )
        db.execute("UPDATE documents SET active_version_id = ?, deleted_at = NULL WHERE id = ?", listOf(versionId, job.documentId))
        persistChunks(versionId, chunks)
        persistEmbeddings(versionId)
        advanceThrough(job, ImportStage.READY)
        rebuildIndex(job.knowledgeBaseId)
    }

    private fun persistChunks(documentVersionId: String, chunks: List<String>) {
        val existing = db.query("SELECT id, rowid AS rid FROM chunks WHERE document_version_id = ?", listOf(documentVersionId))
        existing.forEach { row ->
            runCatching { db.execute("DELETE FROM chunks_fts WHERE rowid = ?", listOf(row.long("rid"))) }
        }
        db.execute("DELETE FROM chunks WHERE document_version_id = ?", listOf(documentVersionId))
        chunks.forEachIndexed { ordinal, text ->
            val id = EntityId.random().value
            val hash = sha256Hex(text.toByteArray(Charsets.UTF_8))
            db.execute(
                "INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash) VALUES (?,?,?,?,?)",
                listOf(id, documentVersionId, ordinal, text, hash),
            )
            val rowid = db.query("SELECT rowid AS rid FROM chunks WHERE id = ?", listOf(id)).single().long("rid")
            runCatching {
                db.execute("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", listOf(rowid, CjkLexical.indexText(text)))
            }
        }
    }

    private fun persistEmbeddings(documentVersionId: String) {
        val rows = db.query("SELECT id, ordinal, text FROM chunks WHERE document_version_id = ? ORDER BY ordinal", listOf(documentVersionId))
        rows.forEach { row ->
            val vector = embedder.embed(row.string("text"))
            db.execute(
                "INSERT OR REPLACE INTO embeddings(chunk_id,space_id,vector_blob,content_hash) VALUES (?,?,?,?)",
                listOf(row.string("id"), embedder.spaceId, floatsToBytes(vector), row.string("id")),
            )
        }
    }

    private fun lexicalHits(kbId: String, query: String, topK: Int): List<SearchHit> {
        val generation = activeGeneration(kbId) ?: return emptyList()
        val tokenized = CjkLexical.indexText(query)
        val fts = runCatching {
            db.query(
                """
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id
                FROM chunks_fts
                JOIN chunks ON chunks.rowid = chunks_fts.rowid
                JOIN generation_members ON generation_members.chunk_id = chunks.id AND generation_members.generation_id = ?
                JOIN documents ON documents.active_version_id = chunks.document_version_id
                WHERE documents.kb_id = ? AND documents.deleted_at IS NULL AND chunks_fts MATCH ?
                LIMIT ?
                """.trimIndent(),
                listOf(generation, kbId, quoteFts(tokenized.ifBlank { query }), topK),
            )
        }.getOrDefault(emptyList())
        val rows = fts.ifEmpty {
            db.query(
                """
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id
                FROM chunks
                JOIN generation_members ON generation_members.chunk_id = chunks.id AND generation_members.generation_id = ?
                JOIN documents ON documents.active_version_id = chunks.document_version_id
                WHERE documents.kb_id = ? AND documents.deleted_at IS NULL AND chunks.text LIKE ?
                LIMIT ?
                """.trimIndent(),
                listOf(generation, kbId, "%$query%", topK),
            )
        }
        return rows.mapIndexed { index, row ->
            SearchHit(
                chunkId = row.string("chunk_id"),
                documentId = row.string("document_id"),
                text = row.string("text"),
                score = 1.0 / (index + 1),
                knowledgeBaseId = kbId,
                documentVersionId = row.string("version_id"),
            )
        }
    }

    private fun vectorHits(kbId: String, query: String, topK: Int): List<SearchHit> {
        val generation = activeGeneration(kbId) ?: return emptyList()
        val queryVec = embedder.embed(query)
        val index = CosineIndex(embedder.dimension)
        val members = db.query(
            """
            SELECT embeddings.chunk_id AS chunk_id, embeddings.vector_blob AS vector_blob, chunks.text AS text,
                   documents.id AS document_id, chunks.document_version_id AS version_id
            FROM generation_members
            JOIN embeddings ON embeddings.chunk_id = generation_members.chunk_id AND embeddings.space_id = generation_members.space_id
            JOIN chunks ON chunks.id = generation_members.chunk_id
            JOIN documents ON documents.active_version_id = chunks.document_version_id
            WHERE generation_members.generation_id = ? AND documents.kb_id = ? AND documents.deleted_at IS NULL
            """.trimIndent(),
            listOf(generation, kbId),
        )
        val byId = linkedMapOf<String, SearchHit>()
        members.forEach { row ->
            val blob = row.columns["vector_blob"]
            val bytes = when (blob) {
                is ByteArray -> blob
                is java.sql.Blob -> blob.getBytes(1, blob.length().toInt())
                else -> return@forEach
            }
            index.add(row.string("chunk_id"), bytesToFloats(bytes, embedder.dimension))
            byId[row.string("chunk_id")] = SearchHit(
                chunkId = row.string("chunk_id"),
                documentId = row.string("document_id"),
                text = row.string("text"),
                score = 0.0,
                knowledgeBaseId = kbId,
                documentVersionId = row.string("version_id"),
            )
        }
        return index.search(queryVec, topK).mapIndexed { indexRank, (id, score) ->
            byId.getValue(id).copy(score = score.toDouble())
        }
    }

    private fun activeGeneration(kbId: String): String? =
        db.query("SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kbId))
            .singleOrNull()?.string("active_generation_id")?.ifBlank { null }

    private fun requireKb(kbId: String) {
        val row = db.query("SELECT deleted_at FROM knowledge_bases WHERE id = ?", listOf(kbId)).singleOrNull()
            ?: error("knowledge base not found")
        check(row.string("deleted_at").isBlank()) { "knowledge base deleted" }
    }

    private fun fail(job: ImportJob, message: String) {
        job.stage = ImportStage.FAILED
        job.error = message
    }

    private fun advanceThrough(job: ImportJob, target: ImportStage) {
        var guard = 0
        while (job.stage != target && guard++ < 24) {
            val prev = job.stage
            ImportStateMachine.advance(job)
            if (job.stage == prev) break
        }
    }

    private fun persistJob(job: ImportJob, displayName: String) {
        db.execute(
            "INSERT OR REPLACE INTO import_jobs(id,kb_id,document_id,display_name,stage,has_images,error,updated_at) VALUES (?,?,?,?,?,?,?,?)",
            listOf(
                job.id,
                job.knowledgeBaseId,
                job.documentId,
                displayName,
                job.stage.name,
                if (job.hasImages) 1 else 0,
                job.error,
                Utc.nowIso(),
            ),
        )
    }

    private fun existingDocument(kbId: String, hash: String): String? =
        db.query("SELECT id FROM documents WHERE kb_id = ? AND blob_hash = ?", listOf(kbId, hash))
            .singleOrNull()?.string("id")

    private fun upsertBlob(stored: StoredBlob): StoredBlob {
        val existing = db.query("SELECT ref_count FROM blobs WHERE hash = ?", listOf(stored.sha256)).singleOrNull()
        if (existing == null) {
            db.execute(
                "INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES (?,?,?,?,1)",
                listOf(stored.sha256, stored.byteLength, stored.mediaType, stored.localRef),
            )
        } else {
            db.execute("UPDATE blobs SET ref_count = ref_count + 1 WHERE hash = ?", listOf(stored.sha256))
        }
        return stored
    }

    private fun decrementBlob(hash: String) {
        db.execute("UPDATE blobs SET ref_count = MAX(ref_count - 1, 0) WHERE hash = ?", listOf(hash))
    }

    private fun quoteFts(query: String): String {
        val cleaned = query.replace("\"", " ").trim()
        if (cleaned.isEmpty()) return "\"\""
        return if (cleaned.any { it.isWhitespace() }) {
            cleaned.split(Regex("\\s+")).joinToString(" OR ") { token -> "\"$token\"" }
        } else {
            cleaned
        }
    }

    private fun guessedMime(format: SourceFormat): String = when (format) {
        SourceFormat.IMAGE -> "image/*"
        SourceFormat.PDF -> "application/pdf"
        SourceFormat.MARKDOWN -> "text/markdown"
        SourceFormat.TEXT -> "text/plain"
        SourceFormat.OFFICE_ARCHIVE -> "application/octet-stream"
        SourceFormat.UNKNOWN -> "application/octet-stream"
    }

    private fun floatsToBytes(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun bytesToFloats(bytes: ByteArray, dimension: Int): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buf.float }
    }

    companion object {
        const val DEFAULT_KB_ID = "kb-default"
        const val PARSER_FINGERPRINT = "text-utf8-v1"
    }
}
