// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionInput
import runtime.mobileagent.knowledge.VisionOutcome
import runtime.mobileagent.knowledge.VisionSuccess
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.AndroidPdfRendererAdapter
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/**
 * Device-side golden corpus. Unlike the JVM test, this exercises the REAL
 * [AndroidPdfRendererAdapter] (Android PdfRenderer), the REAL bundled SQLite and
 * the REAL USearch JNI index.
 *
 * The Vision destination is still a LOCAL DETERMINISTIC STUB: it proves the
 * rendered page bytes and provenance travel the whole chain, and it is NOT a
 * provider call and NOT an accuracy claim. Real provider behaviour stays
 * unverified. The bundled ONNX embedding boundary is covered separately.
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeGoldenCorpusDeviceTest {

    @Test(timeout = 180_000)
    fun goldenCorpusRunsRealAndroidRasterizerAndBundledIndex() {
        deviceCases().forEach { case ->
            // Each fixture has a different scripted Vision oracle. Isolate its
            // result cache: identical rendered bytes across synthetic fixtures
            // must not reuse an answer from a different oracle.
            val context = fixtureContext("golden-device-${case.id}")
            val db = AndroidContextSqlite(context, "golden-device.db")
            Migrations.apply(db)
            val blobs = CasBlobSink(File(context.filesDir, "cas"))
            val renderer = AndroidPdfRendererAdapter(context)
            val stub = DeviceVisionStub(case)
            val repo = KnowledgeRepository(
                db = db,
                blobs = blobs,
                embedder = HashingTextEmbedder(),
                vision = stub,
                pdfRasterizer = renderer,
                vectorIndexFactory = UsearchVectorIndexFactory(),
            )
            try {
                val job = repo.importBytes(
                    displayName = case.displayName,
                    mediaType = case.mediaType,
                    bytes = case.bytes,
                    visionConfigured = case.needsVision,
                    visionConsent = case.needsVision,
                )
                assertEquals("${case.id}: ${job.error}", ImportStage.READY, job.stage)
                case.searchMarkers.forEach { marker ->
                    assertTrue(
                        "${case.id}: marker $marker was not indexed",
                        repo.search(marker, 8, listOf(job.knowledgeBaseId)).any { it.text.contains(marker) },
                    )
                }
                val query = if (case.needsVision) requireNotNull(case.visionMarker) else case.searchMarkers.first()
                val result = repo.retrieve("device-${case.id}", query, 8, listOf(job.knowledgeBaseId))
                assertTrue("${case.id}: retrieval returned no evidence", result.hits.isNotEmpty())
                result.citations.forEach { citation ->
                    assertFalse("${case.id}: citation did not resolve", repo.locateCitation(citation).removed)
                    assertNotNull("${case.id}: no source bytes for citation", repo.evidenceBytes(citation))
                }
                if (case.needsVision) {
                    assertEquals("${case.id}: Vision request count", case.visionPages.size, stub.calls.size)
                    assertEquals("${case.id}: Vision pages", case.visionPages.toSet(), stub.calls.toSet())
                    val visualHits = result.hits.filter { it.assetId != null && it.documentId == job.documentId }
                    assertTrue("${case.id}: no visual citation carried an asset id", visualHits.isNotEmpty())
                    visualHits.forEach { hit ->
                        val asset = requireNotNull(repo.assetBytes(hit.assetId!!)) { "${case.id}: asset ${hit.assetId} missing" }
                        val bitmap = BitmapFactory.decodeByteArray(asset.second, 0, asset.second.size)
                        assertNotNull("${case.id}: real rasterizer output must be a decodable image", bitmap)
                        bitmap!!.recycle()
                        assertTrue("${case.id}: retained image differs from Vision bytes", stub.sentImages.values.any { it.contentEquals(asset.second) })
                        assertTrue("${case.id}: visual span lost provenance", hit.sourceSpan.orEmpty().contains("part:"))
                    }
                } else {
                    assertTrue("${case.id}: Vision must not run for text-only input", stub.calls.isEmpty())
                }
                when (case.id) {
                    "cross-page-table" -> {
                        val pages = case.nativeMarkers.flatMap { marker ->
                            repo.search(marker, 8, listOf(job.knowledgeBaseId)).filter { it.documentId == job.documentId }.mapNotNull { it.page }
                        }.toSet()
                        assertEquals("cross-page evidence must stay on two pages", setOf(1, 2), pages)
                    }
                    "wide-table" -> {
                        val texts = chunkTexts(db, job.documentId)
                        assertTrue(
                            "wide table header must repeat per chunk",
                            texts.count { it.contains("|Item|Qty|Note|") } >= 2,
                        )
                    }
                }
            } finally {
                repo.closeVectorIndexes()
                db.close()
            }
        }
    }

    @Test(timeout = 180_000)
    fun reopeningOnDeviceDoesNotRepeatVisionWork() {
        val context = fixtureContext("golden-device-reopen")
        val db = AndroidContextSqlite(context, "golden-device.db")
        Migrations.apply(db)
        val blobs = CasBlobSink(File(context.filesDir, "cas"))
        val renderer = AndroidPdfRendererAdapter(context)
        val case = deviceCases().single { it.id == "scanned-2page" }
        val stub = DeviceVisionStub(case)
        fun repository() = KnowledgeRepository(
            db = db,
            blobs = blobs,
            embedder = HashingTextEmbedder(),
            vision = stub,
            pdfRasterizer = renderer,
            vectorIndexFactory = UsearchVectorIndexFactory(),
        )
        val repo = repository()
        val job = repo.importBytes(
            displayName = case.displayName,
            mediaType = case.mediaType,
            bytes = case.bytes,
            visionConfigured = true,
            visionConsent = true,
        )
        assertEquals("device import: ${job.error}", ImportStage.READY, job.stage)
        val marker = requireNotNull(case.visionMarker)
        val before = repo.search(marker, 8, listOf(job.knowledgeBaseId)).map { it.chunkId }.toSet()
        assertTrue(before.isNotEmpty())
        assertEquals(listOf(1, 2), stub.calls)
        repo.closeVectorIndexes()

        val reopened = repository()
        assertEquals(
            "reopened device repository must read the same publication",
            before,
            reopened.search(marker, 8, listOf(job.knowledgeBaseId)).map { it.chunkId }.toSet(),
        )
        assertEquals("reopening must not repeat Vision work", listOf(1, 2), stub.calls)
        reopened.closeVectorIndexes()
    }

    @Test(timeout = 300_000)
    fun packagedOnnxEmbeddingIsARealDistinctBoundary() {
        val context = fixtureContext("golden-device-onnx")
        val pack = runCatching { AndroidModelPackLoader(context).load() }.getOrNull()
        assumeTrue("Bundled ONNX model pack is unavailable; the real embedding boundary stays unverified", pack != null)
        OnnxTextEmbedder(pack!!).use { embedder ->
            val db = AndroidContextSqlite(context, "golden-onnx.db")
            Migrations.apply(db)
            val repo = KnowledgeRepository(
                db = db,
                blobs = CasBlobSink(File(context.filesDir, "cas-onnx")),
                embedder = embedder,
                vectorIndexFactory = UsearchVectorIndexFactory(),
            )
            try {
                val kb = repo.createKnowledgeBase("device onnx golden")
                val astronomy = repo.importBytes(
                    "astro.txt",
                    "text/plain",
                    "Astronomers observe distant galaxies and quasars with a telescope. GOLDENONNXQUASAR".toByteArray(Charsets.UTF_8),
                    visionConfigured = false,
                    knowledgeBaseId = kb,
                )
                val cooking = repo.importBytes(
                    "cook.txt",
                    "text/plain",
                    "A sourdough baker kneads flour and bakes bread in an oven. GOLDENONNXBREAD".toByteArray(Charsets.UTF_8),
                    visionConfigured = false,
                    knowledgeBaseId = kb,
                )
                assertEquals("onnx astronomy import: ${astronomy.error}", ImportStage.READY, astronomy.stage)
                assertEquals("onnx cooking import: ${cooking.error}", ImportStage.READY, cooking.stage)
                assertTrue(repo.search("GOLDENONNXQUASAR", 8, listOf(kb)).any { it.documentId == astronomy.documentId })
                assertTrue(repo.search("GOLDENONNXBREAD", 8, listOf(kb)).any { it.documentId == cooking.documentId })
                val first = embedder.embed("Astronomers observe distant galaxies with a telescope.")
                val second = embedder.embed("A sourdough baker kneads flour and bakes bread.")
                assertEquals(384, first.size)
                assertTrue("real embedding must not collapse unrelated texts", cosine(first, second) < 0.999)
            } finally {
                repo.closeVectorIndexes()
            }
        }
    }

    private fun chunkTexts(db: SqlConnection, documentId: String): List<String> =
        db.query(
            "SELECT chunks.text AS text FROM chunks " +
                "JOIN documents ON documents.active_version_id = chunks.document_version_id " +
                "WHERE documents.id = ? ORDER BY chunks.ordinal",
            listOf(documentId),
        ).map { it.string("text") }

    private fun cosine(first: FloatArray, second: FloatArray): Double =
        first.indices.sumOf { first[it].toDouble() * second[it].toDouble() }

    private class DeviceVisionStub(private val case: DeviceGoldenCase) : VisionBackend {
        val calls = mutableListOf<Int>()
        val sentImages = linkedMapOf<Int, ByteArray>()

        override fun process(input: VisionInput): VisionOutcome {
            assertTrue(input.beforeDispatch())
            val page = input.page ?: -1
            calls += page
            sentImages[calls.size] = input.bytes.copyOf()
            return VisionOutcome.Success(
                VisionSuccess(
                    ocrText = if (case.tableMarkdown.isNotBlank()) "" else "${case.visionMarker} OCR page $page",
                    semanticDescription = "${case.visionMarker} description page $page",
                    tableMarkdown = case.tableMarkdown,
                ),
            )
        }
    }

    private fun fixtureContext(label: String): Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(
            instrumentation.targetContext.cacheDir,
            "$label-${UUID.randomUUID()}",
        ).apply { check(mkdirs() || isDirectory) }
        return object : ContextWrapper(instrumentation.targetContext) {
            override fun getDatabasePath(name: String): File {
                require(name.isNotBlank() && File(name).name == name) { "database name must be a bare file name" }
                val directory = File(root, "databases").apply { check(mkdirs() || isDirectory) }
                return File(directory, name)
            }

            override fun getFilesDir(): File = File(root, "files").apply { check(mkdirs() || isDirectory) }

            override fun getCacheDir(): File = File(root, "cache").apply { check(mkdirs() || isDirectory) }

            override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { check(mkdirs() || isDirectory) }
        }
    }
}

private data class DeviceGoldenCase(
    val id: String,
    val displayName: String,
    val mediaType: String,
    val bytes: ByteArray,
    val needsVision: Boolean,
    val expectedPages: Int,
    val nativeMarkers: List<String> = emptyList(),
    val visionMarker: String? = null,
    val tableMarkdown: String = "",
    val visionPages: List<Int> = emptyList(),
    val searchMarkers: List<String>,
)

private fun deviceCases(): List<DeviceGoldenCase> = listOf(
    DeviceGoldenCase(
        id = "scanned-2page",
        displayName = "device-scan.pdf",
        mediaType = "application/pdf",
        bytes = DeviceGoldenPdf.build(List(2) { DeviceGoldenPdf.Page(image = true) }),
        needsVision = true,
        expectedPages = 2,
        visionMarker = "DEVICESCANOCR",
        visionPages = listOf(1, 2),
        searchMarkers = listOf("DEVICESCANOCR"),
    ),
    DeviceGoldenCase(
        id = "mixed",
        displayName = "device-mixed.pdf",
        mediaType = "application/pdf",
        bytes = DeviceGoldenPdf.build(
            listOf(DeviceGoldenPdf.Page(runs = listOf("DEVICEMIXNATIVE native page caption"), image = true)),
        ),
        needsVision = true,
        expectedPages = 1,
        nativeMarkers = listOf("DEVICEMIXNATIVE"),
        visionMarker = "DEVICEMIXVISION",
        visionPages = listOf(1),
        searchMarkers = listOf("DEVICEMIXNATIVE", "DEVICEMIXVISION"),
    ),
    DeviceGoldenCase(
        id = "vector",
        displayName = "device-vector.pdf",
        mediaType = "application/pdf",
        bytes = DeviceGoldenPdf.build(
            listOf(DeviceGoldenPdf.Page(runs = listOf("DEVICEFORMULA E=mc^2 caption"), vector = true)),
        ),
        needsVision = true,
        expectedPages = 1,
        nativeMarkers = listOf("DEVICEFORMULA"),
        visionMarker = "DEVICEVECTORVISION",
        visionPages = listOf(1),
        searchMarkers = listOf("DEVICEFORMULA", "DEVICEVECTORVISION"),
    ),
    DeviceGoldenCase(
        id = "cross-page-table",
        displayName = "device-cross-page.pdf",
        mediaType = "application/pdf",
        bytes = DeviceGoldenPdf.build(
            listOf(
                DeviceGoldenPdf.Page(runs = listOf("|Item|Qty|\n|---|---|\n|DEVICECROSSP1|1|")),
                DeviceGoldenPdf.Page(runs = listOf("|Item|Qty|\n|---|---|\n|DEVICECROSSP2|2|")),
            ),
        ),
        needsVision = true,
        expectedPages = 2,
        visionMarker = "DEVICECROSSTABLEVISION",
        visionPages = listOf(1, 1, 2, 2),
        nativeMarkers = listOf("DEVICECROSSP1", "DEVICECROSSP2"),
        searchMarkers = listOf("DEVICECROSSP1", "DEVICECROSSP2"),
    ),
    DeviceGoldenCase(
        id = "unicode",
        displayName = "device-unicode.txt",
        mediaType = "text/plain",
        bytes = "DEVICEUNICODE 知识库与图谱检索\nemoji \uD83D\uDE80 rocket\ncombining e\u0301 stays attached\n".toByteArray(Charsets.UTF_8),
        needsVision = false,
        expectedPages = 1,
        nativeMarkers = listOf("DEVICEUNICODE"),
        searchMarkers = listOf("DEVICEUNICODE"),
    ),
    DeviceGoldenCase(
        id = "wide-table",
        displayName = "device-wide-table.pdf",
        mediaType = "application/pdf",
        bytes = DeviceGoldenPdf.build(listOf(DeviceGoldenPdf.Page(image = true))),
        needsVision = true,
        expectedPages = 1,
        visionMarker = "DEVICEWIDEDESC",
        tableMarkdown = deviceWideTableMarkdown(),
        visionPages = listOf(1),
        searchMarkers = listOf("DEVICEWIDEDESC", "DEVICEWIDEROW001", "DEVICEWIDEROW060"),
    ),
)

private fun deviceWideTableMarkdown(): String {
    val rows = (1..60).joinToString("\n") { index ->
        val label = "DEVICEWIDEROW" + index.toString().padStart(3, '0')
        "|$label|$index|note-${index.toString().padStart(6, '0')}|"
    }
    return "|Item|Qty|Note|\n|---|---|---|\n$rows"
}

/** Deterministic, self-authored PDF writer mirroring the project's own test writer shape. */
private object DeviceGoldenPdf {
    // A valid, visible raster page: an SOI/EOI-only JPEG can silently render
    // blank and would exercise broken-image handling instead of a scan.
    private val scanJpeg: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }
            listOf("Scanned knowledge page", "Item       Quantity       Note", "Cobalt     12             retained",
                "Amber      30             evidence", "E = mc^2", "Original raster text and table").forEachIndexed { index, line ->
                canvas.drawText(line, 20f, 50f + index * 65f, paint)
            }
            java.io.ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    data class Page(
        val runs: List<String> = emptyList(),
        val image: Boolean = false,
        val vector: Boolean = false,
        val width: Int = 612,
        val height: Int = 792,
    )

    fun build(pages: List<Page>): ByteArray {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val count = pages.size
        val fontObject = 3 + 2 * count
        val imageObject = fontObject + 1
        fun text(value: String) = value.toByteArray(Charsets.ISO_8859_1)
        val objects = mutableListOf<ByteArray>()
        objects += text("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = (0 until count).joinToString(" ") { "${3 + it} 0 R" }
        objects += text("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $count >>\nendobj\n")
        pages.forEachIndexed { index, page ->
            val resources = if (page.image) {
                "/Font << /F1 $fontObject 0 R >> /XObject << /Im1 $imageObject 0 R >>"
            } else {
                "/Font << /F1 $fontObject 0 R >>"
            }
            objects += text(
                "${3 + index} 0 obj\n<< /Type /Page /Parent 2 0 R " +
                    "/MediaBox [0 0 ${page.width} ${page.height}] /Contents ${3 + count + index} 0 R " +
                    "/Resources << $resources >> >>\nendobj\n",
            )
        }
        pages.forEachIndexed { index, page ->
            val body = content(page)
            objects += text("${3 + count + index} 0 obj\n<< /Length ${body.size} >>\nstream\n") + body +
                text("\nendstream\nendobj\n")
        }
        objects += text("$fontObject 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
        if (pages.any { it.image }) {
            objects += text(
                "$imageObject 0 obj\n<< /Type /XObject /Subtype /Image /Width 600 /Height 600 " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${scanJpeg.size} >>\nstream\n",
            ) + scanJpeg + text("\nendstream\nendobj\n")
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(text("%PDF-1.4\n"))
        val offsets = mutableListOf<Int>()
        objects.forEach { body ->
            offsets += out.size()
            out.write(body)
        }
        val xrefAt = out.size()
        val total = objects.size + 1
        val xref = buildString {
            append("xref\n0 $total\n")
            append("0000000000 65535 f \n")
            offsets.forEach { offset -> append("%010d 00000 n \n".format(offset)) }
            append("trailer\n<< /Size $total /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        }
        out.write(text(xref))
        return out.toByteArray()
    }

    private fun content(page: Page): ByteArray {
        val builder = StringBuilder()
        if (page.runs.isNotEmpty()) {
            builder.append("BT /F1 12 Tf 72 720 Td ")
            page.runs.forEachIndexed { index, run ->
                if (index > 0) builder.append(" 0 -14 Td ")
                builder.append('(').append(escape(run)).append(") Tj ")
            }
            builder.append("ET\n")
        }
        if (page.image) builder.append("q 480 0 0 480 72 180 cm /Im1 Do Q\n")
        if (page.vector) builder.append("0 0 100 100 re f\n")
        return builder.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '(' -> out.append("\\(")
                ')' -> out.append("\\)")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
