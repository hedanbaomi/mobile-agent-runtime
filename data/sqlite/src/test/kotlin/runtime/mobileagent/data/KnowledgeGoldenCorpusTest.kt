// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import runtime.mobileagent.skills.ToolBroker
import runtime.mobileagent.skills.ToolCall
import runtime.mobileagent.skills.ToolContext
import runtime.mobileagent.skills.ToolResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MediaKind
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.PdfPageRasterizer
import runtime.mobileagent.knowledge.PdfParser
import runtime.mobileagent.knowledge.PdfUnitRasterizer
import runtime.mobileagent.knowledge.ProcessingUnit
import runtime.mobileagent.knowledge.RenderedPdfPage
import runtime.mobileagent.knowledge.SourceFormat
import runtime.mobileagent.knowledge.UnitRegion
import runtime.mobileagent.knowledge.UnitRenderLimits
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VisionSuccess
import runtime.mobileagent.knowledge.sha256Hex

/**
 * Golden-corpus regression for the full local knowledge chain:
 * parse -> Vision port -> chunk -> embed -> index -> search/read -> citation.
 *
 * Vision here is a LOCAL DETERMINISTIC STUB. It proves plumbing and provenance,
 * not recognition accuracy, and it is not a real provider call. Embedding is
 * [runtime.mobileagent.knowledge.HashingTextEmbedder], a deterministic local
 * fixture space, not a semantic model. Real provider and real embedding
 * boundaries are reported separately (device test / handoff).
 */
class KnowledgeGoldenCorpusTest {

    @Test
    fun declaredCorpusParsesToItsDocumentedShape() {
        val cases = KnowledgeGoldenCorpus.cases()
        assertEquals(12, cases.size, "Corpus membership changed; update this expectation with the fixture")
        assertEquals(cases.size, cases.map { it.id }.distinct().size, "Corpus ids must be unique")
        cases.forEach { case ->
            when (case.kind) {
                GoldenKind.CORRUPTED_PDF -> {
                    assertThrows(RuntimeException::class.java) { PdfParser.parse(case.bytes) }
                }
                GoldenKind.UNSUPPORTED_BINARY -> {
                    val header = case.bytes.copyOf(minOf(case.bytes.size, 64))
                    assertEquals(SourceFormat.UNKNOWN, MediaKind.detect(case.displayName, case.mediaType, header))
                }
                else -> {
                    if (case.mediaType == "application/pdf") {
                        val parsed = PdfParser.parse(case.bytes)
                        assertEquals(case.expectedPages, parsed.pages.size, "${case.id}: page count")
                        assertEquals(case.needsVision, parsed.needsVision, "${case.id}: vision classification")
                        case.nativeMarkers.forEach { marker ->
                            assertTrue(parsed.text.contains(marker), "${case.id}: parser dropped native marker $marker")
                        }
                    } else {
                        val text = String(case.bytes, Charsets.UTF_8)
                        case.nativeMarkers.forEach { marker ->
                            assertTrue(text.contains(marker), "${case.id}: fixture lost marker $marker")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun fullChainPublishesSearchableTextPagesAndEvidencedCitations() {
        KnowledgeGoldenCorpus.cases().filterNot { it.failClosed }.forEach { case ->
            val db = JdbcSqlConnection()
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val calls = mutableListOf<Int>()
            val sentImages = linkedMapOf<Int, ByteArray>()
            val vision = localVisionStub(case, calls, sentImages)
            val repo = KnowledgeRepository(
                db,
                blobs,
                vision = vision,
                pdfRasterizer = GoldenRasterizer(),
            )
            try {
                val job = repo.importBytes(
                    displayName = case.displayName,
                    mediaType = case.mediaType,
                    bytes = case.bytes,
                    visionConfigured = case.needsVision,
                    visionConsent = case.needsVision,
                )
                assertEquals(ImportStage.READY, job.stage, "${case.id} failed: ${job.error}")

                val chunkTexts = publishedChunkTexts(db, job.documentId)
                assertTrue(chunkTexts.isNotEmpty(), "${case.id}: published no chunks")
                assertTrue(publishedChunkCount(db, job.documentId) > 0, "${case.id}: no active published version")

                case.searchMarkers.forEach { marker ->
                    val hits = repo.search(marker, 8, listOf(job.knowledgeBaseId))
                    assertTrue(hits.any { it.text.contains(marker) }, "${case.id}: marker $marker was not indexed")
                }
                val full = buildString {
                    var offset = 0
                    var version: String? = null
                    do {
                        val range = repo.readDocumentRange(job.documentId, 4000, offset, expectedVersion = version)
                        append(range.text)
                        version = range.documentVersionId
                        offset = range.nextOffset ?: break
                    } while (true)
                }
                case.nativeMarkers.forEach { marker ->
                    assertTrue(full.contains(marker), "${case.id}: readDocumentText lost $marker")
                }
                assertToolChain(repo, job.documentId, job.knowledgeBaseId, case.searchMarkers.first(), full)

                val result = repo.retrieve("golden-${case.id}", case.searchMarkers.first(), 8, listOf(job.knowledgeBaseId))
                assertTrue(result.hits.isNotEmpty(), "${case.id}: retrieval returned no evidence")
                result.citations.forEach { citation ->
                    val locator = repo.locateCitation(citation)
                    assertFalse(locator.removed, "${case.id}: citation ${citation.citationId} did not resolve")
                    assertNotNull(repo.evidenceBytes(citation), "${case.id}: no source bytes for citation")
                }

                if (case.needsVision) {
                    assertEquals(case.visionPages.size, calls.size, "${case.id}: Vision request count")
                    assertEquals(case.visionPages.toSet(), calls.toSet(), "${case.id}: Vision pages")
                    val visualHits = result.hits.filter { it.assetId != null }
                    assertTrue(visualHits.isNotEmpty(), "${case.id}: no visual citation carried an asset id")
                    visualHits.forEach { hit ->
                        val asset = repo.assetBytes(hit.assetId!!)
                        assertNotNull(asset, "${case.id}: asset ${hit.assetId} missing from CAS")
                        assertTrue(sentImages.values.any { it.contentEquals(asset!!.second) }, "${case.id}: retained page image differs from bytes sent to Vision")
                        assertTrue(hit.sourceSpan.orEmpty().contains("part:"), "${case.id}: visual chunk lost its component span")
                    }
                } else {
                    assertTrue(calls.isEmpty(), "${case.id}: Vision was invoked for a text-only document")
                }

                when (case.kind) {
                    GoldenKind.CROSS_PAGE_TABLE_PDF -> {
                        val pages = case.nativeMarkers.flatMap { marker ->
                            repo.search(marker, 8, listOf(job.knowledgeBaseId)).mapNotNull { it.page }
                        }.toSet()
                        assertEquals(setOf(1, 2), pages, "${case.id}: continuation pages must stay distinct")
                    }
                    GoldenKind.MIXED_PDF -> {
                        val nativeMarker = case.nativeMarkers.single()
                        val nativeHit = repo.search(nativeMarker, 8, listOf(job.knowledgeBaseId))
                            .first { it.text.contains(nativeMarker) }
                        assertTrue(nativeHit.sourceSpan.orEmpty().contains("source:parser-native"), "${case.id}: native span")
                    }
                    GoldenKind.WIDE_TABLE_PDF -> {
                        val header = "|Item|Qty|Note|"
                        assertTrue(chunkTexts.count { it.contains(header) } >= 2, "${case.id}: table header must repeat per chunk")
                        chunkTexts.filter { it.contains('|') }.forEach { chunk ->
                            assertTrue(chunk.length <= 1800, "${case.id}: table chunk exceeded the soft target")
                        }
                    }
                    GoldenKind.UNICODE_TEXT -> {
                        assertTrue(chunkTexts.any { it.contains("知识库") }, "${case.id}: CJK content lost")
                        assertTrue(chunkTexts.any { it.contains("图谱") }, "${case.id}: CJK content lost")
                        chunkTexts.forEach { chunk ->
                            assertFalse(chunk.contains('\uFFFD'), "${case.id}: replacement character published")
                        }
                    }
                    GoldenKind.LONG_TEXT -> {
                        assertTrue(chunkTexts.size >= 20, "${case.id}: long page must fan out into many chunks")
                        assertEquals(16_384, repo.readDocumentText(job.documentId, 100_000).length)
                        assertTrue(
                            repo.readDocumentRange(job.documentId, 16_384).totalChars > 16_384,
                            "${case.id}: readDocumentText cap must not imply the index was truncated",
                        )
                    }
                    else -> Unit
                }
            } finally {
                repo.closeVectorIndexes()
            }
        }
    }

    @Test
    fun reopeningAndReimportingAreStableAndNeverRepeatVisionWork() {
        val case = KnowledgeGoldenCorpus.cases().single { it.id == "scanned-multipage" }
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val calls = mutableListOf<Int>()
        val vision = localVisionStub(case, calls, linkedMapOf())
        fun repository() = KnowledgeRepository(db, blobs, vision = vision, pdfRasterizer = GoldenRasterizer())

        val repo = repository()
        val job = repo.importBytes(
            displayName = case.displayName,
            mediaType = case.mediaType,
            bytes = case.bytes,
            visionConfigured = true,
            visionConsent = true,
        )
        assertEquals(ImportStage.READY, job.stage, "import error: ${job.error}")
        val marker = case.searchMarkers.first()
        val chunksBefore = repo.search(marker, 8, listOf(job.knowledgeBaseId)).map { it.chunkId }.toSet()
        val textBefore = repo.readDocumentText(job.documentId, 16_384)
        val callsAfterFirst = calls.toList()
        assertEquals(listOf(1, 2, 3), callsAfterFirst)
        repo.closeVectorIndexes()

        val reopened = repository()
        assertEquals(chunksBefore, reopened.search(marker, 8, listOf(job.knowledgeBaseId)).map { it.chunkId }.toSet())
        assertEquals(textBefore, reopened.readDocumentText(job.documentId, 16_384))
        assertEquals(callsAfterFirst, calls, "reopening must not repeat Vision work")

        val reimport = reopened.importBytes(
            displayName = case.displayName,
            mediaType = case.mediaType,
            bytes = case.bytes,
            visionConfigured = true,
            visionConsent = true,
        )
        assertEquals(ImportStage.READY, reimport.stage, "reimport error: ${reimport.error}")
        assertEquals(chunksBefore, reopened.search(marker, 8, listOf(job.knowledgeBaseId)).map { it.chunkId }.toSet())
        assertEquals(callsAfterFirst, calls, "re-importing identical bytes must not repeat Vision work")
        reopened.closeVectorIndexes()
    }

    @Test
    fun pauseResumeOfAScannedBatchNeverRepeatsASuccessfulVisionCall() {
        val case = KnowledgeGoldenCorpus.cases().single { it.id == "scanned-multipage" }
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val blobs = MemoryBlobSink()
        val calls = mutableListOf<Int>()
        val holder = AtomicReference<KnowledgeRepository>()
        var batchId = ""
        val vision = VisionBackend { input ->
            assertTrue(input.beforeDispatch())
            calls += input.page ?: -1
            if (calls.size == 1) holder.get().pauseBatch(batchId)
            VisionOutcome.Success(VisionSuccess("GOLDENSCANMULTI OCR page ${input.page}", "GOLDENSCANMULTI desc page ${input.page}"))
        }
        val repo = KnowledgeRepository(db, blobs, vision = vision, pdfRasterizer = GoldenRasterizer())
        holder.set(repo)
        val kb = repo.ensureDefaultBase()
        batchId = repo.beginBatch(kb, ImportBatchKind.FILES, "golden pause resume")
        val copied = repo.importBytes(
            displayName = case.displayName,
            mediaType = case.mediaType,
            bytes = case.bytes,
            visionConfigured = false,
            knowledgeBaseId = kb,
            pauseAt = ImportStage.COPYING,
        )
        repo.bindJobToBatch(batchId, copied, case.displayName)
        repo.authorizeBatchVision(batchId, null)
        repo.processBatch(batchId, visionConfigured = false)
        assertEquals(listOf(1), calls, "pause must land after the first in-flight page")
        assertEquals(ImportBatchState.PAUSED, repo.findBatch(batchId)!!.state)

        repo.resumeBatch(batchId)
        repo.processBatch(batchId, visionConfigured = false)
        assertEquals(listOf(1, 2, 3), calls, "resume must reuse the checkpointed page and never repeat it")
        assertEquals(1, repo.batchProgress(batchId).published, "One source document was published after all three pages")
        assertEquals(ImportBatchState.COMPLETED, repo.findBatch(batchId)!!.state)
        repo.closeVectorIndexes()
    }

    @Test
    fun corruptedAndUnsupportedInputsFailClosedAndKeepTheCopiedBytes() {
        KnowledgeGoldenCorpus.cases().filter { it.failClosed }.forEach { case ->
            val db = JdbcSqlConnection()
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val repo = KnowledgeRepository(db, blobs)
            val job = repo.importBytes(case.displayName, case.mediaType, case.bytes, visionConfigured = false)
            assertEquals(ImportStage.FAILED, job.stage, "${case.id} stage=${job.stage} error=${job.error}")
            assertEquals(0L, publishedChunkCount(db, job.documentId), "${case.id} published chunks despite failing")
            assertTrue(blobs.blobs.containsKey(sha256Hex(case.bytes)), "${case.id}: copied bytes must stay, not be dropped")
            assertTrue(
                repo.search(case.failSearchToken, 8, listOf(job.knowledgeBaseId)).isEmpty(),
                "${case.id}: a failed import must not be searchable",
            )
            repo.closeVectorIndexes()
        }
    }

    @Test
    fun readDocumentRangeWalksTheWholeDocumentWithoutSplittingSurrogates() {
        val case = KnowledgeGoldenCorpus.cases().single { it.id == "unicode-text" }
        val db = JdbcSqlConnection()
        Migrations.apply(db)
        val repo = KnowledgeRepository(db, MemoryBlobSink())
        val job = repo.importBytes(case.displayName, case.mediaType, case.bytes, visionConfigured = false)
        assertEquals(ImportStage.READY, job.stage, "import error: ${job.error}")

        val expected = repo.readDocumentText(job.documentId, 16_384)
        assertEquals(expected.length, repo.readDocumentRange(job.documentId, 0).totalChars)
        val rebuilt = StringBuilder()
        var offset = 0
        var version: String? = null
        while (true) {
            val range = repo.readDocumentRange(job.documentId, 7, offset, expectedVersion = version)
            rebuilt.append(range.text)
            version = range.documentVersionId
            val next = range.nextOffset ?: break
            offset = next
        }
        assertEquals(expected, rebuilt.toString())
        assertFalse(rebuilt.contains('\uFFFD'), "range walking must not emit replacement characters")
        repo.closeVectorIndexes()
    }

    /**
     * LOCAL DETERMINISTIC VISION STUB. Not a provider, not an accuracy test.
     * The returned text is derived only from the corpus marker and page number
     * so the test can prove which page reached the Vision port exactly once.
     */
    private fun localVisionStub(
        case: GoldenCase,
        calls: MutableList<Int>,
        sentImages: MutableMap<Int, ByteArray>,
    ): VisionBackend = VisionBackend { input ->
        assertTrue(input.beforeDispatch())
        val page = input.page ?: -1
        calls += page
        sentImages[calls.size] = input.bytes.copyOf()
        VisionOutcome.Success(
            VisionSuccess(
                ocrText = if (case.visionTableMarkdown.isNotBlank()) "" else "${case.visionMarker} OCR page $page",
                semanticDescription = "${case.visionMarker} description page $page",
                tableMarkdown = case.visionTableMarkdown,
            ),
        )
    }

    private fun assertToolChain(repo: KnowledgeRepository, documentId: String, kbId: String, query: String, expectedText: String) {
        val broker = ToolBroker(setOf("knowledge.search", "knowledge.read"), ToolContext(
            grantedKnowledgeBaseIds = setOf(kbId), documentKnowledgeBaseId = repo::documentKnowledgeBaseId,
            search = { text, ids, limit ->
                val result = repo.retrieve("golden-tools", text, limit, ids)
                assertTrue(result.citations.isNotEmpty())
                result.citations.forEach { assertFalse(repo.locateCitation(it).removed); assertNotNull(repo.evidenceBytes(it)) }
                buildJsonObject { put("citations", JsonArray(result.citations.map { JsonPrimitive(it.citationId) })) }.toString()
            },
            readDocument = { _, _ -> error("Pagination callback must be used") },
            readDocumentRange = { id, max, offset, expectedVersion ->
                val range = repo.readDocumentRange(id, minOf(max, 5000), offset, setOf(kbId), expectedVersion)
                buildJsonObject {
                    put("documentVersionId", range.documentVersionId); put("text", range.text); put("nextOffset", range.nextOffset?.let(::JsonPrimitive) ?: JsonNull)
                }.toString()
            },
        ))
        val search = broker.invoke(ToolCall("search", "knowledge_search", buildJsonObject { put("query", query) }.toString()))
        assertTrue(search is ToolResult.Value, search.toString())
        assertTrue(Json.parseToJsonElement((search as ToolResult.Value).json).jsonObject.getValue("citations").jsonArray.isNotEmpty())
        val actual = StringBuilder()
        var offset = 0
        var version: String? = null
        do {
            val call = ToolCall("read-$offset", "read_document", buildJsonObject {
                put("documentId", documentId); put("offset", offset); put("maxChars", 4000)
                version?.let { put("expectedVersion", it) }
            }.toString())
            val result = broker.invoke(call)
            assertTrue(result is ToolResult.Value, result.toString())
            val value = Json.parseToJsonElement((result as ToolResult.Value).json).jsonObject
            actual.append(value.getValue("text").jsonPrimitive.content)
            version = value.getValue("documentVersionId").jsonPrimitive.content
            val next = value.getValue("nextOffset").jsonPrimitive.intOrNull ?: break
            assertTrue(next > offset)
            offset = next
        } while (true)
        assertEquals(expectedText, actual.toString())
    }

    /** Deterministic local renderer; real Android rasterization is a separate device test. */
    private class GoldenRasterizer : PdfPageRasterizer, PdfUnitRasterizer {
        override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> = pages.map { page ->
            RenderedPdfPage(page, bytes(page, null), "image/png", 64, 64)
        }

        override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage =
            RenderedPdfPage(unit.page, bytes(unit.page, unit.region), "image/png", 48, 48)

        private fun bytes(page: Int, region: UnitRegion?): ByteArray =
            "GOLDEN-RENDER-page-$page-region-${region?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "full"}"
                .toByteArray(Charsets.UTF_8)
    }
}
