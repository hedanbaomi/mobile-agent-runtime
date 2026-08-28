// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.ImportStateMachine
import runtime.mobileagent.knowledge.MediaKind
import runtime.mobileagent.knowledge.SearchHit
import runtime.mobileagent.knowledge.SourceFormat
import runtime.mobileagent.knowledge.StoredBlob
import runtime.mobileagent.knowledge.TextChunker
import java.security.MessageDigest

class KnowledgeRepository(
    private val db: SqlConnection,
    private val blobs: BlobSink,
) {
    fun ensureDefaultBase(): String {
        val existing = db.query("SELECT id FROM knowledge_bases WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1")
        if (existing.isNotEmpty()) return existing.single().string("id")
        val id = DEFAULT_KB_ID
        db.execute(
            "INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES (?,?,?,?,?,?)",
            listOf(id, "On-device library", null, null, Utc.nowIso(), null),
        )
        return id
    }

    fun importBytes(
        displayName: String,
        mediaType: String,
        bytes: ByteArray,
        visionConfigured: Boolean,
    ): ImportJob {
        require(bytes.size <= MediaKind.MAX_IMPORT_BYTES) { "RESOURCE_LIMIT" }
        val kbId = ensureDefaultBase()
        val format = MediaKind.detect(displayName, mediaType, bytes.copyOf(minOf(bytes.size, 64)))
        val stored = upsertBlob(blobs.put(bytes, mediaType.ifBlank { guessedMime(format) }))
        val documentId = existingDocument(kbId, stored.sha256) ?: EntityId.random().value
        db.execute(
            "INSERT OR REPLACE INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES (?,?,?,?,?,?,?)",
            listOf(documentId, kbId, stored.sha256, displayName, format.name, documentId, null),
        )
        val job = ImportJob(
            id = EntityId.random().value,
            knowledgeBaseId = kbId,
            documentId = documentId,
            hasImages = MediaKind.isImage(format),
            visionConfigured = visionConfigured,
        )
        job.localEmbeddingAvailable = true
        advanceThrough(job, ImportStage.PARSING)
        when (format) {
            SourceFormat.IMAGE -> {
                advanceThrough(job, ImportStage.WAITING_FOR_VISION_MODEL)
            }
            SourceFormat.TEXT, SourceFormat.MARKDOWN -> {
                val text = String(bytes, Charsets.UTF_8)
                val chunks = TextChunker.chunk(text)
                if (chunks.isEmpty()) {
                    job.stage = ImportStage.FAILED
                    job.error = "The file is empty"
                } else {
                    persistChunks(documentId, chunks)
                    advanceThrough(job, ImportStage.READY)
                }
            }
            SourceFormat.PDF -> fail(job, "PDF import is not in this build yet. The file was copied and was not dropped.")
            SourceFormat.OFFICE_ARCHIVE -> fail(job, "DOCX/EPUB import is not in this build yet. The file was copied and was not dropped.")
            SourceFormat.UNKNOWN -> fail(job, "Unsupported file type. The file was copied and was not dropped.")
        }
        persistJob(job, displayName)
        return job
    }

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

    fun search(query: String, topK: Int = 8): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val fts = runCatching {
            db.query(
                "SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text FROM chunks_fts JOIN chunks ON chunks.rowid = chunks_fts.rowid JOIN documents ON documents.active_version_id = chunks.document_version_id WHERE chunks_fts MATCH ? LIMIT ?",
                listOf(quoteFts(query), topK),
            )
        }.getOrDefault(emptyList())
        val rows = fts.ifEmpty {
            db.query(
                "SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text FROM chunks JOIN documents ON documents.active_version_id = chunks.document_version_id WHERE chunks.text LIKE ? LIMIT ?",
                listOf("%$query%", topK),
            )
        }
        return rows.mapIndexed { index, row ->
            SearchHit(
                chunkId = row.string("chunk_id"),
                documentId = row.string("document_id"),
                text = row.string("text"),
                score = 1.0 / (index + 1),
            )
        }
    }

    fun waitingForVisionCount(): Int =
        db.query(
            "SELECT COUNT(*) AS n FROM import_jobs WHERE stage = ?",
            listOf(ImportStage.WAITING_FOR_VISION_MODEL.name),
        ).singleOrNull()?.long("n")?.toInt() ?: 0

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

    private fun persistChunks(documentVersionId: String, chunks: List<String>) {
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
                db.execute("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", listOf(rowid, text))
            }
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
            db.execute(
                "UPDATE blobs SET ref_count = ref_count + 1 WHERE hash = ?",
                listOf(stored.sha256),
            )
        }
        return stored
    }

    private fun quoteFts(query: String): String {
        val cleaned = query.replace("\"", " ").trim()
        return if (cleaned.any { it.isWhitespace() }) "\"$cleaned\"" else cleaned
    }

    private fun guessedMime(format: SourceFormat): String = when (format) {
        SourceFormat.IMAGE -> "image/*"
        SourceFormat.PDF -> "application/pdf"
        SourceFormat.MARKDOWN -> "text/markdown"
        SourceFormat.TEXT -> "text/plain"
        SourceFormat.OFFICE_ARCHIVE -> "application/octet-stream"
        SourceFormat.UNKNOWN -> "application/octet-stream"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_KB_ID = "kb-default"
    }
}
