// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import runtime.mobileagent.domain.EntityId
import runtime.mobileagent.domain.Utc
import runtime.mobileagent.knowledge.BlobSink
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.knowledge.CitationMap
import runtime.mobileagent.knowledge.CjkLexical
import runtime.mobileagent.knowledge.CosineIndex
import runtime.mobileagent.knowledge.EvidenceLocator
import runtime.mobileagent.knowledge.ExtractedAsset
import runtime.mobileagent.knowledge.ExtractedPage
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportJob
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.ImportStateMachine
import runtime.mobileagent.knowledge.MediaKind
import runtime.mobileagent.knowledge.OfficeParser
import runtime.mobileagent.knowledge.ParsedPublication
import runtime.mobileagent.knowledge.PdfParser
import runtime.mobileagent.knowledge.ReciprocalRankFusion
import runtime.mobileagent.knowledge.RetrievalResult
import runtime.mobileagent.knowledge.SearchHit
import runtime.mobileagent.knowledge.SourceFormat
import runtime.mobileagent.knowledge.StoredBlob
import runtime.mobileagent.knowledge.TextChunker
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.knowledge.VISION_PROMPT_VERSION
import runtime.mobileagent.knowledge.VISION_SCHEMA_VERSION
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionCacheKey
import runtime.mobileagent.knowledge.VisionInput
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.ZipSafety
import runtime.mobileagent.knowledge.sha256Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KnowledgeRepository(
    private val db: SqlConnection,
    private val blobs: BlobSink,
    private val embedder: TextEmbedder = HashingTextEmbedder(),
    private val vision: VisionBackend? = null,
    private val visionModelFingerprint: String = "vision-unconfigured",
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
        visionConsent: Boolean = false,
        embeddingIsApi: Boolean = false,
        embeddingConsent: Boolean = false,
    ): ImportJob {
        require(bytes.size <= MediaKind.MAX_IMPORT_BYTES) { "RESOURCE_LIMIT" }
        val kbId = knowledgeBaseId ?: ensureDefaultBase()
        requireKb(kbId)
        val format = MediaKind.detect(displayName, mediaType, bytes.copyOf(minOf(bytes.size, 64)))
        val stored = blobs.put(bytes, mediaType.ifBlank { guessedMime(format) })
        val existingId = existingDocument(kbId, stored.sha256)
        if (existingId != null) {
            val prior = db.query("SELECT active_version_id, deleted_at FROM documents WHERE id = ?", listOf(existingId)).single()
            if (prior.string("deleted_at").isBlank()) {
                if (isPublishedReady(existingId, kbId)) {
                    val job = ImportJob(
                        id = EntityId.random().value,
                        knowledgeBaseId = kbId,
                        documentId = existingId,
                        stage = ImportStage.READY,
                        visionConfigured = visionConfigured,
                        visionConsent = visionConsent,
                        embeddingIsApi = embeddingIsApi,
                        embeddingConsent = embeddingConsent,
                        localEmbeddingAvailable = true,
                    )
                    persistJob(job, displayName)
                    return job
                }
                val job = ImportJob(
                    id = EntityId.random().value,
                    knowledgeBaseId = kbId,
                    documentId = existingId,
                    hasImages = MediaKind.isImage(format),
                    visionConfigured = visionConfigured,
                    visionConsent = visionConsent,
                    embeddingIsApi = embeddingIsApi,
                    embeddingConsent = embeddingConsent,
                    localEmbeddingAvailable = true,
                )
                persistJob(job, displayName)
                if (pauseAt == ImportStage.COPYING) {
                    job.stage = ImportStage.COPYING
                    persistJob(job, displayName)
                    return job
                }
                return continueImport(job, displayName, bytes, format)
            }
        }
        db.transaction {
            upsertBlob(stored)
            val documentId = existingId ?: EntityId.random().value
            db.execute(
                "INSERT OR REPLACE INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES (?,?,?,?,?,?,?)",
                listOf(documentId, kbId, stored.sha256, displayName, format.name, null, null),
            )
            syncBlobRef(stored.sha256)
        }
        val documentId = existingDocument(kbId, stored.sha256)!!
        val job = ImportJob(
            id = EntityId.random().value,
            knowledgeBaseId = kbId,
            documentId = documentId,
            hasImages = MediaKind.isImage(format),
            visionConfigured = visionConfigured,
            visionConsent = visionConsent,
            embeddingIsApi = embeddingIsApi,
            embeddingConsent = embeddingConsent,
        )
        job.localEmbeddingAvailable = true
        persistJob(job, displayName)
        if (pauseAt == ImportStage.COPYING) {
            job.stage = ImportStage.COPYING
            persistJob(job, displayName)
            return job
        }
        return continueImport(job, displayName, bytes, format)
    }

    fun resumeImport(jobId: String, bytes: ByteArray? = null, visionConfigured: Boolean): ImportJob = synchronized(indexLock) {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        val documentId = row.string("document_id")
        val kbId = row.string("kb_id")
        requireKb(kbId)
        val document = db.query("SELECT blob_hash, deleted_at, format FROM documents WHERE id = ?", listOf(documentId)).singleOrNull()
            ?: error("document not found")
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val stage = ImportStage.valueOf(row.string("stage"))
        check(stage != ImportStage.CANCELLED && stage != ImportStage.READY) { "import job is not resumable" }
        val expected = document.string("blob_hash")
        val payload = when {
            bytes != null -> {
                val actual = sha256Hex(bytes)
                check(actual == expected) { "resume bytes do not match the stored CAS blob" }
                bytes
            }
            else -> blobs.get(expected) ?: error("CAS blob is missing")
        }
        val displayName = row.string("display_name")
        val recordedFormat = document.string("format")
        val format = recordedFormat.takeIf { it.isNotBlank() }?.let { runCatching { SourceFormat.valueOf(it) }.getOrNull() }
            ?: MediaKind.detect(displayName, "", payload.copyOf(minOf(payload.size, 64)))
        val job = ImportJob(
            id = jobId,
            knowledgeBaseId = kbId,
            documentId = documentId,
            stage = stage,
            hasImages = row.long("has_images") != 0L,
            visionConfigured = visionConfigured,
            visionConsent = row.string("vision_consent").let { it == "1" || it.equals("true", true) } ||
                runCatching { row.long("vision_consent") != 0L }.getOrDefault(false),
            embeddingIsApi = runCatching { row.long("embedding_is_api") != 0L }.getOrDefault(false),
            embeddingConsent = runCatching { row.long("embedding_consent") != 0L }.getOrDefault(false),
            localEmbeddingAvailable = true,
            error = row.string("error").ifBlank { null },
        )
        return continueImport(job, displayName, payload, format)
    }

    fun grantVisionConsent(jobId: String): ImportJob {
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        val documentId = row.string("document_id")
        val document = db.query("SELECT blob_hash, format, deleted_at FROM documents WHERE id = ?", listOf(documentId)).single()
        check(document.string("deleted_at").isBlank()) { "document deleted" }
        val bytes = blobs.get(document.string("blob_hash")) ?: error("CAS blob is missing")
        val format = runCatching { SourceFormat.valueOf(document.string("format")) }.getOrDefault(SourceFormat.UNKNOWN)
        val job = ImportJob(
            id = jobId,
            knowledgeBaseId = row.string("kb_id"),
            documentId = documentId,
            stage = ImportStage.QUEUED,
            hasImages = true,
            visionConfigured = true,
            visionConsent = true,
            localEmbeddingAvailable = true,
        )
        return continueImport(job, row.string("display_name"), bytes, format)
    }

    fun retryUnknownVision(jobId: String, acknowledgeDuplicateCharge: Boolean): ImportJob {
        check(acknowledgeDuplicateCharge) {
            "Retry may bill the Vision provider twice. Acknowledge the duplicate-charge risk."
        }
        val row = db.query("SELECT * FROM import_jobs WHERE id = ?", listOf(jobId)).singleOrNull()
            ?: error("import job not found")
        val documentId = row.string("document_id")
        db.execute(
            "DELETE FROM vision_results WHERE status = ? AND asset_hash IN (SELECT blob_hash FROM assets WHERE document_id = ?)",
            listOf("UNKNOWN_OUTCOME", documentId),
        )
        return grantVisionConsent(jobId)
    }

    fun locateCitation(citation: Citation): EvidenceLocator {
        val missing = EvidenceLocator(citation.documentId, "Source removed", citation.page, citation.assetId, citation.sourceSpan, null, removed = true)
        val document = db.query(
            "SELECT display_name, blob_hash, deleted_at, active_version_id FROM documents WHERE id = ?",
            listOf(citation.documentId),
        ).singleOrNull()
        if (document == null || document.string("deleted_at").isNotBlank()) return missing
        val versionId = citation.documentVersionId.ifBlank { document.string("active_version_id") }
        if (versionId.isBlank()) return missing
        val version = db.query(
            "SELECT id FROM document_versions WHERE id = ? AND document_id = ?",
            listOf(versionId, citation.documentId),
        ).singleOrNull()
        if (version == null) return missing
        if (citation.chunkId.isNotBlank()) {
            val chunk = db.query(
                "SELECT id, page, asset_ids FROM chunks WHERE id = ? AND document_version_id = ?",
                listOf(citation.chunkId, versionId),
            ).singleOrNull() ?: return missing
            val chunkAssets = chunk.string("asset_ids").split(',').filter { it.isNotBlank() }
            if (citation.assetId != null && citation.assetId !in chunkAssets) return missing
            val page = chunk.string("page").toIntOrNull() ?: citation.page
            if (citation.assetId != null) {
                val asset = db.query(
                    "SELECT blob_hash, page, document_version_id FROM assets WHERE id = ? AND document_id = ?",
                    listOf(citation.assetId, citation.documentId),
                ).singleOrNull() ?: return missing
                val assetVersion = asset.string("document_version_id")
                if (assetVersion.isNotBlank() && assetVersion != versionId) return missing
                return EvidenceLocator(
                    documentId = citation.documentId,
                    displayName = document.string("display_name"),
                    page = asset.string("page").toIntOrNull() ?: page,
                    assetId = citation.assetId,
                    sourceSpan = citation.sourceSpan,
                    blobHash = asset.string("blob_hash"),
                    removed = false,
                )
            }
            return EvidenceLocator(
                documentId = citation.documentId,
                displayName = document.string("display_name"),
                page = page,
                assetId = null,
                sourceSpan = citation.sourceSpan,
                blobHash = document.string("blob_hash"),
                removed = false,
            )
        }
        return missing
    }

    fun assetBytes(assetId: String): Pair<String, ByteArray>? {
        val asset = db.query("SELECT blob_hash FROM assets WHERE id = ?", listOf(assetId)).singleOrNull() ?: return null
        val hash = asset.string("blob_hash")
        val bytes = blobs.get(hash) ?: return null
        val media = db.query("SELECT media_type FROM blobs WHERE hash = ?", listOf(hash)).singleOrNull()?.string("media_type")
            ?: "application/octet-stream"
        return media to bytes
    }

    fun search(query: String, topK: Int = 8, knowledgeBaseIds: List<String>? = null): List<SearchHit> =
        retrieve("search", query, topK, knowledgeBaseIds).hits

    fun retrieve(runId: String, query: String, topK: Int = 8, knowledgeBaseIds: List<String>? = null): RetrievalResult {
        if (query.isBlank()) return RetrievalResult(emptyList(), emptyList(), listOf("empty query"))
        val warnings = mutableListOf<String>()
        val bases = knowledgeBaseIds ?: listKnowledgeBases().map { it.first }
        val lexical = mutableListOf<SearchHit>()
        val vector = mutableListOf<SearchHit>()
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
            val pin = pinnedReadyGeneration(kbId)
            if (pin == null) {
                warnings += "Knowledge base $kbId has no READY generation"
                continue
            }
            lexical += lexicalHits(kbId, query, 40, pin)
            vector += vectorHits(kbId, query, 40, pin)
        }
        val hits = ReciprocalRankFusion.merge(listOf(lexical, vector)).take(topK)
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

    fun readDocumentText(documentId: String, maxChars: Int): String {
        val document = db.query("SELECT active_version_id, deleted_at FROM documents WHERE id = ?", listOf(documentId)).singleOrNull()
            ?: return ""
        if (document.string("deleted_at").isNotBlank()) return ""
        val version = document.string("active_version_id")
        val text = db.query(
            "SELECT text FROM chunks WHERE document_version_id = ? ORDER BY ordinal",
            listOf(version),
        ).joinToString("\n") { it.string("text") }
        val cap = maxChars.coerceIn(0, 16_384)
        return text.take(cap)
    }

    fun deleteDocument(documentId: String) {
        synchronized(indexLock) {
            val row = db.query("SELECT kb_id, blob_hash, deleted_at FROM documents WHERE id = ?", listOf(documentId)).singleOrNull() ?: return
            if (row.string("deleted_at").isNotBlank()) return
            db.transaction {
                db.execute("UPDATE documents SET deleted_at = ? WHERE id = ?", listOf(Utc.nowIso(), documentId))
                db.execute(
                    "UPDATE import_jobs SET stage = ?, error = ? WHERE document_id = ? AND stage NOT IN (?,?)",
                    listOf(ImportStage.CANCELLED.name, "document deleted", documentId, ImportStage.READY.name, ImportStage.CANCELLED.name),
                )
                syncBlobRef(row.string("blob_hash"))
            }
            rebuildIndex(row.string("kb_id"))
        }
    }

    fun deleteKnowledgeBase(kbId: String) {
        synchronized(indexLock) {
            db.transaction {
                db.query("SELECT id, blob_hash FROM documents WHERE kb_id = ? AND deleted_at IS NULL", listOf(kbId)).forEach { row ->
                    db.execute("UPDATE documents SET deleted_at = ? WHERE id = ?", listOf(Utc.nowIso(), row.string("id")))
                    db.execute(
                        "UPDATE import_jobs SET stage = ?, error = ? WHERE document_id = ? AND stage NOT IN (?,?)",
                        listOf(ImportStage.CANCELLED.name, "knowledge base deleted", row.string("id"), ImportStage.READY.name, ImportStage.CANCELLED.name),
                    )
                    syncBlobRef(row.string("blob_hash"))
                }
                db.execute("UPDATE knowledge_bases SET deleted_at = ?, active_generation_id = NULL WHERE id = ?", listOf(Utc.nowIso(), kbId))
            }
        }
    }

    fun rebuildIndex(kbId: String): String = synchronized(indexLock) {
        requireKb(kbId)
        db.transaction { rebuildUnlocked(kbId) }
    }

    fun repairIndexes() {
        synchronized(indexLock) {
            db.query("SELECT id, active_version_id, blob_hash FROM documents WHERE deleted_at IS NULL AND active_version_id IS NOT NULL").forEach { doc ->
                val versionId = doc.string("active_version_id")
                val exists = db.query("SELECT id FROM document_versions WHERE id = ?", listOf(versionId))
                if (exists.isEmpty()) {
                    db.execute(
                        "INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES (?,?,?,?,?,?)",
                        listOf(versionId, doc.string("id"), PARSER_FINGERPRINT, doc.string("blob_hash"), "READY", Utc.nowIso()),
                    )
                }
            }
            listKnowledgeBases().forEach { (kbId, _) ->
                val live = db.query(
                    "SELECT COUNT(*) AS n FROM documents WHERE kb_id = ? AND deleted_at IS NULL AND active_version_id IS NOT NULL",
                    listOf(kbId),
                ).single().long("n")
                if (live == 0L) return@forEach
                val pin = pinnedReadyGeneration(kbId)
                val members = if (pin == null) 0L else {
                    db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id = ?", listOf(pin)).single().long("n")
                }
                if (pin == null || members == 0L) {
                    db.transaction { rebuildUnlocked(kbId) }
                }
            }
        }
    }

    private fun rebuildUnlocked(kbId: String): String {
        val generationId = EntityId.random().value
        val versions = db.query(
            "SELECT id, active_version_id FROM documents WHERE kb_id = ? AND deleted_at IS NULL AND active_version_id IS NOT NULL",
            listOf(kbId),
        )
        var vectors = 0
        db.execute(
            "INSERT INTO index_generations(id,kb_id,space_id,manifest_hash,state,vector_count,fts_version,created_at) VALUES (?,?,?,?,?,?,?,?)",
            listOf(generationId, kbId, embedder.spaceId, generationId, "BUILDING", 0, 1, Utc.nowIso()),
        )
        versions.forEach { doc ->
            val versionId = doc.string("active_version_id")
            db.query("SELECT id, ordinal, text FROM chunks WHERE document_version_id = ? ORDER BY ordinal", listOf(versionId)).forEach { chunk ->
                val chunkId = chunk.string("id")
                val text = chunk.string("text")
                val rowid = db.query("SELECT rowid AS rid FROM chunks WHERE id = ?", listOf(chunkId)).single().long("rid")
                runCatching { db.execute("DELETE FROM chunks_fts WHERE rowid = ?", listOf(rowid)) }
                runCatching { db.execute("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", listOf(rowid, CjkLexical.indexText(text))) }
                val vector = embedder.embed(text)
                check(vector.size == embedder.dimension) { "embedding dimension mismatch" }
                db.execute(
                    "INSERT OR REPLACE INTO embeddings(chunk_id,space_id,vector_blob,content_hash) VALUES (?,?,?,?)",
                    listOf(chunkId, embedder.spaceId, floatsToBytes(vector), sha256Hex(text.toByteArray())),
                )
                val stored = db.query(
                    "SELECT vector_blob FROM embeddings WHERE chunk_id = ? AND space_id = ?",
                    listOf(chunkId, embedder.spaceId),
                ).singleOrNull() ?: error("embedding missing after rebuild")
                val blob = stored.columns["vector_blob"]
                val bytes = when (blob) {
                    is ByteArray -> blob
                    is java.sql.Blob -> blob.getBytes(1, blob.length().toInt())
                    else -> error("embedding blob unreadable")
                }
                check(bytes.size == embedder.dimension * 4) { "embedding byte length mismatch" }
                db.execute(
                    "INSERT OR IGNORE INTO generation_members(generation_id,chunk_id,space_id,document_version_id) VALUES (?,?,?,?)",
                    listOf(generationId, chunkId, embedder.spaceId, versionId),
                )
                vectors += 1
            }
        }
        val actualVectors = db.query(
            "SELECT COUNT(*) AS n FROM generation_members g JOIN embeddings e ON e.chunk_id = g.chunk_id AND e.space_id = g.space_id WHERE g.generation_id = ?",
            listOf(generationId),
        ).single().long("n").toInt()
        if (actualVectors != vectors) {
            db.execute("UPDATE index_generations SET state = ? WHERE id = ?", listOf("FAILED", generationId))
            error("rebuild vector count mismatch")
        }
        db.execute(
            "UPDATE index_generations SET state = ?, vector_count = ?, manifest_hash = ? WHERE id = ?",
            listOf("READY", vectors, sha256Hex("$generationId:$vectors".toByteArray()), generationId),
        )
        db.execute(
            "UPDATE knowledge_bases SET active_generation_id = ?, embedding_space_id = ? WHERE id = ?",
            listOf(generationId, embedder.spaceId, kbId),
        )
        return generationId
    }

    private fun continueImport(job: ImportJob, displayName: String, bytes: ByteArray, format: SourceFormat): ImportJob {
        advanceThrough(job, ImportStage.PARSING)
        when (format) {
            SourceFormat.IMAGE -> {
                try {
                    indexPublication(job, bytes, standaloneImage(bytes, displayName))
                } catch (t: Throwable) {
                    fail(job, t.message ?: "image import failed")
                }
            }
            SourceFormat.TEXT, SourceFormat.MARKDOWN -> {
                try {
                    indexTextDocument(job, bytes, format)
                } catch (t: Throwable) {
                    fail(job, t.message ?: "indexing failed")
                }
            }
            SourceFormat.PDF -> {
                try {
                    indexPublication(job, bytes, PdfParser.parse(bytes))
                } catch (t: Throwable) {
                    fail(job, t.message ?: "PDF import failed")
                }
            }
            SourceFormat.OFFICE_ARCHIVE -> {
                val inspection = ZipSafety.inspect(bytes)
                if (!inspection.ok) {
                    fail(job, inspection.reason)
                } else {
                    try {
                        indexPublication(job, bytes, OfficeParser.parse(displayName, bytes))
                    } catch (t: Throwable) {
                        fail(job, t.message ?: "DOCX/EPUB import failed")
                    }
                }
            }
            SourceFormat.UNKNOWN -> fail(job, "Unsupported file type. The file was copied and was not dropped.")
        }
        persistJob(job, displayName)
        return job
    }

    private fun standaloneImage(bytes: ByteArray, displayName: String): ParsedPublication {
        val mime = when {
            displayName.lowercase().endsWith(".png") -> "image/png"
            displayName.lowercase().endsWith(".jpg") || displayName.lowercase().endsWith(".jpeg") -> "image/jpeg"
            else -> "image/*"
        }
        return ParsedPublication(
            format = SourceFormat.IMAGE,
            text = "",
            pages = listOf(ExtractedPage(1, "", needsVision = true)),
            assets = listOf(
                ExtractedAsset("image-1", "IMAGE", 1, displayName, bytes, mime, ""),
            ),
            needsVision = true,
            parserFingerprint = "image-v1",
        )
    }

    private fun indexTextDocument(job: ImportJob, bytes: ByteArray, format: SourceFormat) {
        val text = String(bytes, Charsets.UTF_8)
        job.hasImages = format == SourceFormat.MARKDOWN && MediaKind.markdownReferencesImages(text)
        if (job.hasImages) {
            advanceThrough(job, ImportStage.WAITING_FOR_VISION_MODEL)
            if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                job.error = "Markdown references images. They were not downloaded and the document is not READY."
            } else if (job.stage == ImportStage.AWAITING_UPLOAD_CONSENT) {
                job.error = "Markdown image files are not fetched automatically. Import the image files or grant Vision after they exist in CAS."
            }
            return
        }
        publishChunks(job, bytes, textChunks = TextChunker.chunk(text).map { IndexedChunk(it, null, emptyList(), null) }, fingerprint = PARSER_FINGERPRINT)
    }

    private fun indexPublication(job: ImportJob, bytes: ByteArray, parsed: ParsedPublication) {
        val processable = parsed.assets.filter { it.kind == "IMAGE" && it.bytes.isNotEmpty() }
        val blocked = parsed.assets.filter {
            it.kind == "EXTERNAL" || it.kind == "MISSING" || it.kind == "PAGE" ||
                (it.kind == "IMAGE" && it.bytes.isEmpty())
        }
        job.hasImages = parsed.needsVision || processable.isNotEmpty() || blocked.isNotEmpty()
        val visionTexts = mutableListOf<IndexedChunk>()
        if (job.hasImages) {
            advanceThrough(job, if (job.visionConfigured) ImportStage.AWAITING_UPLOAD_CONSENT else ImportStage.WAITING_FOR_VISION_MODEL)
            if (job.stage == ImportStage.WAITING_FOR_VISION_MODEL) {
                job.error = "Visual content is waiting for a Vision model and is not READY."
                return
            }
            if (job.stage == ImportStage.AWAITING_UPLOAD_CONSENT && !job.visionConsent) {
                job.error = "Vision upload has not been approved. No image bytes left the device."
                return
            }
            job.visionConsent = true
            if (blocked.isNotEmpty()) {
                fail(
                    job,
                    "Visual pages or external/missing images cannot be processed without local raster bytes. Nothing was downloaded.",
                )
                return
            }
            if (processable.isEmpty()) {
                fail(job, "needsVision is set but there is no processable page or image asset. The document is not READY.")
                return
            }
            advanceThrough(job, ImportStage.VISION_PROCESSING)
            when (val outcome = processAssets(job, processable)) {
                is VisionBatch.Failed -> {
                    fail(job, outcome.message)
                    return
                }
                is VisionBatch.Unknown -> {
                    job.stage = ImportStage.FAILED
                    job.error = "UNKNOWN_OUTCOME: Vision result is uncertain and was not billed as success. Retry is manual."
                    return
                }
                is VisionBatch.Ok -> visionTexts += outcome.chunks
            }
        }
        val pageChunks = parsed.pages.filter { it.text.isNotBlank() && !it.needsVision }.flatMap { page ->
            TextChunker.chunk(page.text).map { IndexedChunk(it, page.page, emptyList(), "page:${page.page}") }
        }
        val chunks = (pageChunks + visionTexts).ifEmpty {
            if (!parsed.needsVision && parsed.text.isNotBlank()) {
                TextChunker.chunk(parsed.text).map { IndexedChunk(it, 1, emptyList(), null) }
            } else {
                emptyList()
            }
        }
        if (chunks.isEmpty()) {
            fail(job, "The file produced no indexable text. Visual items were not dropped.")
            return
        }
        publishChunks(job, bytes, chunks, parsed.parserFingerprint, processable)
    }

    private sealed interface VisionBatch {
        data class Ok(val chunks: List<IndexedChunk>) : VisionBatch
        data class Failed(val message: String) : VisionBatch
        data object Unknown : VisionBatch
    }

    private fun processAssets(job: ImportJob, assets: List<ExtractedAsset>): VisionBatch {
        val backend = vision ?: return VisionBatch.Failed("Vision model is configured in profile but no backend is bound")
        val chunks = mutableListOf<IndexedChunk>()
        assets.forEach { asset ->
            if (asset.bytes.isEmpty() || asset.kind != "IMAGE") {
                return VisionBatch.Failed("Visual asset ${asset.localId} is not rasterizable and was not downloaded")
            }
            val stored = blobs.put(asset.bytes, asset.mediaType)
            upsertBlob(stored)
            val assetId = EntityId.random().value
            val contextHash = VisionCacheKey.contextHash(asset.surroundingText, asset.page, asset.section)
            val input = VisionInput(
                assetHash = stored.sha256,
                contextHash = contextHash,
                modelFingerprint = visionModelFingerprint,
                bytes = asset.bytes,
                mediaType = asset.mediaType,
                surroundingText = asset.surroundingText,
                page = asset.page,
                section = asset.section,
            )
            db.execute(
                "INSERT OR REPLACE INTO assets(id,document_id,document_version_id,blob_hash,page,section,kind,surrounding_text_hash) VALUES (?,?,?,?,?,?,?,?)",
                listOf(assetId, job.documentId, null, stored.sha256, asset.page, asset.section, asset.kind, contextHash),
            )
            val cached = db.query(
                "SELECT status, ocr_text, description, table_markdown, result_type FROM vision_results WHERE cache_key = ?",
                listOf(input.cacheKey),
            ).singleOrNull()
            val outcome = when (cached?.string("status")) {
                "SUCCESS" -> VisionOutcome.Success(
                    runtime.mobileagent.knowledge.VisionSuccess(
                        ocrText = cached.string("ocr_text"),
                        semanticDescription = cached.string("description"),
                        tableMarkdown = cached.string("table_markdown"),
                        type = cached.string("result_type").ifBlank { "image" },
                    ),
                )
                "UNKNOWN_OUTCOME" -> VisionOutcome.UnknownOutcome
                else -> backend.process(input)
            }
            when (outcome) {
                is VisionOutcome.UnknownOutcome -> {
                    persistVision(input.cacheKey, stored.sha256, contextHash, "UNKNOWN_OUTCOME", "", "", "", "")
                    return VisionBatch.Unknown
                }
                is VisionOutcome.Failed -> {
                    persistVision(input.cacheKey, stored.sha256, contextHash, "FAILED", "", outcome.message, "", "")
                    return VisionBatch.Failed(outcome.message)
                }
                is VisionOutcome.Success -> {
                    persistVision(
                        input.cacheKey,
                        stored.sha256,
                        contextHash,
                        "SUCCESS",
                        outcome.result.ocrText,
                        outcome.result.semanticDescription,
                        outcome.result.tableMarkdown,
                        outcome.result.type,
                    )
                    val body = buildString {
                        append("Visual evidence")
                        asset.page?.let { append(" page $it") }
                        append(": ")
                        append(outcome.result.semanticDescription)
                        if (outcome.result.ocrText.isNotBlank()) {
                            append('\n')
                            append(outcome.result.ocrText)
                        }
                        if (outcome.result.tableMarkdown.isNotBlank()) {
                            append('\n')
                            append(outcome.result.tableMarkdown)
                        }
                        if (asset.surroundingText.isNotBlank()) {
                            append('\n')
                            append(asset.surroundingText)
                        }
                    }
                    chunks += IndexedChunk(body, asset.page, listOf(assetId), asset.section)
                }
            }
        }
        return VisionBatch.Ok(chunks)
    }

    private fun persistVision(
        cacheKey: String,
        assetHash: String,
        contextHash: String,
        status: String,
        ocr: String,
        description: String,
        tableMarkdown: String,
        type: String,
    ) {
        db.execute(
            "INSERT OR REPLACE INTO vision_results(cache_key,asset_hash,context_hash,model_fingerprint,prompt_version,schema_version,status,ocr_text,description,table_markdown,result_type,processed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                cacheKey,
                assetHash,
                contextHash,
                visionModelFingerprint,
                VISION_PROMPT_VERSION,
                VISION_SCHEMA_VERSION,
                status,
                ocr,
                description,
                tableMarkdown,
                type,
                Utc.nowIso(),
            ),
        )
    }

    private data class IndexedChunk(
        val text: String,
        val page: Int?,
        val assetIds: List<String>,
        val span: String?,
    )

    private fun publishChunks(
        job: ImportJob,
        bytes: ByteArray,
        textChunks: List<IndexedChunk>,
        fingerprint: String,
        assets: List<ExtractedAsset> = emptyList(),
    ) {
        if (job.embeddingIsApi && !job.embeddingConsent) {
            advanceThrough(job, ImportStage.AWAITING_EMBEDDING_CONSENT)
            if (job.stage == ImportStage.AWAITING_EMBEDDING_CONSENT) {
                job.error = "API embedding was not approved. No text left the device."
            }
            return
        }
        val chunks = textChunks.ifEmpty {
            fail(job, "The file is empty")
            return
        }
        val versionId = EntityId.random().value
        val contentHash = sha256Hex(bytes)
        synchronized(indexLock) {
            db.transaction {
                db.execute(
                    "INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES (?,?,?,?,?,?)",
                    listOf(versionId, job.documentId, fingerprint, contentHash, "STAGING", Utc.nowIso()),
                )
                persistChunks(versionId, chunks)
                persistEmbeddings(versionId)
                db.execute("UPDATE assets SET document_version_id = ? WHERE document_id = ? AND (document_version_id IS NULL OR document_version_id = '')", listOf(versionId, job.documentId))
                db.execute("UPDATE document_versions SET status = ? WHERE id = ?", listOf("READY", versionId))
                db.execute("UPDATE documents SET active_version_id = ?, deleted_at = NULL WHERE id = ?", listOf(versionId, job.documentId))
                rebuildUnlocked(job.knowledgeBaseId)
            }
        }
        advanceThrough(job, ImportStage.READY)
    }

    private fun persistChunks(documentVersionId: String, chunks: List<IndexedChunk>) {
        val existing = db.query("SELECT id, rowid AS rid FROM chunks WHERE document_version_id = ?", listOf(documentVersionId))
        existing.forEach { row ->
            runCatching { db.execute("DELETE FROM chunks_fts WHERE rowid = ?", listOf(row.long("rid"))) }
        }
        db.execute("DELETE FROM chunks WHERE document_version_id = ?", listOf(documentVersionId))
        chunks.forEachIndexed { ordinal, chunk ->
            val id = EntityId.random().value
            val hash = sha256Hex(chunk.text.toByteArray(Charsets.UTF_8))
            db.execute(
                "INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page) VALUES (?,?,?,?,?,?,?,?)",
                listOf(
                    id,
                    documentVersionId,
                    ordinal,
                    chunk.text,
                    hash,
                    chunk.span,
                    chunk.assetIds.joinToString(","),
                    chunk.page,
                ),
            )
            val rowid = db.query("SELECT rowid AS rid FROM chunks WHERE id = ?", listOf(id)).single().long("rid")
            runCatching {
                db.execute("INSERT INTO chunks_fts(rowid, text) VALUES (?, ?)", listOf(rowid, CjkLexical.indexText(chunk.text)))
            }
        }
    }

    private fun persistEmbeddings(documentVersionId: String) {
        val rows = db.query("SELECT id, ordinal, text FROM chunks WHERE document_version_id = ? ORDER BY ordinal", listOf(documentVersionId))
        rows.forEach { row ->
            val vector = embedder.embed(row.string("text"))
            check(vector.size == embedder.dimension) { "embedding dimension mismatch" }
            db.execute(
                "INSERT OR REPLACE INTO embeddings(chunk_id,space_id,vector_blob,content_hash) VALUES (?,?,?,?)",
                listOf(row.string("id"), embedder.spaceId, floatsToBytes(vector), row.string("id")),
            )
        }
    }

    private fun lexicalHits(kbId: String, query: String, topK: Int, generation: String): List<SearchHit> {
        val tokenized = CjkLexical.indexText(query)
        val fts = runCatching {
            db.query(
                """
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id,
                       chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
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
                SELECT chunks.id AS chunk_id, documents.id AS document_id, chunks.text AS text, chunks.document_version_id AS version_id,
                       chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
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
                assetId = row.string("asset_ids").split(',').firstOrNull { it.isNotBlank() },
                page = row.string("page").toIntOrNull(),
                sourceSpan = row.string("source_span").ifBlank { null },
            )
        }
    }

    private fun vectorHits(kbId: String, query: String, topK: Int, generation: String): List<SearchHit> {
        val queryVec = embedder.embed(query)
        val index = CosineIndex(embedder.dimension)
        val members = db.query(
            """
            SELECT embeddings.chunk_id AS chunk_id, embeddings.vector_blob AS vector_blob, chunks.text AS text,
                   documents.id AS document_id, chunks.document_version_id AS version_id,
                   chunks.page AS page, chunks.asset_ids AS asset_ids, chunks.source_span AS source_span
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
            if (bytes.size != embedder.dimension * 4) return@forEach
            index.add(row.string("chunk_id"), bytesToFloats(bytes, embedder.dimension))
            byId[row.string("chunk_id")] = SearchHit(
                chunkId = row.string("chunk_id"),
                documentId = row.string("document_id"),
                text = row.string("text"),
                score = 0.0,
                knowledgeBaseId = kbId,
                documentVersionId = row.string("version_id"),
                assetId = row.string("asset_ids").split(',').firstOrNull { it.isNotBlank() },
                page = row.string("page").toIntOrNull(),
                sourceSpan = row.string("source_span").ifBlank { null },
            )
        }
        return index.search(queryVec, topK).mapIndexed { indexRank, (id, score) ->
            byId.getValue(id).copy(score = score.toDouble())
        }
    }

    private fun pinnedReadyGeneration(kbId: String): String? {
        val generationId = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id = ?", listOf(kbId))
            .singleOrNull()?.string("active_generation_id")?.ifBlank { null } ?: return null
        val state = db.query("SELECT state FROM index_generations WHERE id = ?", listOf(generationId))
            .singleOrNull()?.string("state")
        return if (state == "READY") generationId else null
    }

    private fun isPublishedReady(documentId: String, kbId: String): Boolean {
        val versionId = db.query("SELECT active_version_id FROM documents WHERE id = ? AND deleted_at IS NULL", listOf(documentId))
            .singleOrNull()?.string("active_version_id")?.ifBlank { null } ?: return false
        val versionReady = db.query("SELECT status FROM document_versions WHERE id = ?", listOf(versionId))
            .singleOrNull()?.string("status") == "READY"
        if (!versionReady) return false
        val chunks = db.query("SELECT COUNT(*) AS n FROM chunks WHERE document_version_id = ?", listOf(versionId)).single().long("n")
        if (chunks == 0L) return false
        val embeddings = db.query(
            "SELECT COUNT(*) AS n FROM embeddings e JOIN chunks c ON c.id = e.chunk_id WHERE c.document_version_id = ? AND e.space_id = ?",
            listOf(versionId, embedder.spaceId),
        ).single().long("n")
        if (embeddings != chunks) return false
        val pin = pinnedReadyGeneration(kbId) ?: return false
        val members = db.query(
            "SELECT COUNT(*) AS n FROM generation_members WHERE generation_id = ? AND document_version_id = ?",
            listOf(pin, versionId),
        ).single().long("n")
        return members == chunks
    }

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
            "INSERT OR REPLACE INTO import_jobs(id,kb_id,document_id,display_name,stage,has_images,error,updated_at,vision_consent,embedding_is_api,embedding_consent) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            listOf(
                job.id,
                job.knowledgeBaseId,
                job.documentId,
                displayName,
                job.stage.name,
                if (job.hasImages) 1 else 0,
                job.error,
                Utc.nowIso(),
                if (job.visionConsent) 1 else 0,
                if (job.embeddingIsApi) 1 else 0,
                if (job.embeddingConsent) 1 else 0,
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
                "INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES (?,?,?,?,0)",
                listOf(stored.sha256, stored.byteLength, stored.mediaType, stored.localRef),
            )
        }
        return stored
    }

    private fun syncBlobRef(hash: String) {
        val live = db.query(
            "SELECT COUNT(*) AS n FROM documents WHERE blob_hash = ? AND deleted_at IS NULL",
            listOf(hash),
        ).single().long("n")
        db.execute("UPDATE blobs SET ref_count = ? WHERE hash = ?", listOf(live, hash))
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
