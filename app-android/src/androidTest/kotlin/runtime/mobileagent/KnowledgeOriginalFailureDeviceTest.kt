// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.knowledge.*
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.AndroidPdfRendererAdapter
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Private opt-in sources are never packaged. Vision is a local stub, not an accuracy test. */
@RunWith(AndroidJUnit4::class)
class KnowledgeOriginalFailureDeviceTest {
    @Test fun localStagesAndResumeForOriginalFailures() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicitly staged private source PDFs", args.getString("originalFailureRegression") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (args.getString("originalFailurePhase") == "verify-restart") {
            verifyProcessRestart(instrumentation.targetContext)
            return
        }
        val sourceDir = requireNotNull(args.getString("originalFailureDir"))
        require(sourceDir.matches(Regex("/data/local/tmp/mar-original-[0-9]+")))
        val root = File(instrumentation.targetContext.cacheDir, "original-regression-${UUID.randomUUID()}")
        check(root.mkdirs())
        val context = isolatedContext(instrumentation.targetContext, root)
        val report = File(root, "stages.jsonl")
        for (sample in 1..3) {
            val name = "sample-$sample.pdf"
            // Only fixed generated labels under the validated test directory enter shell.
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat $sourceDir/$name"),
            ).use { it.readBytes() }
            assertTrue("Missing staged sample $sample", bytes.size > 5)
            val started = SystemClock.elapsedRealtime()
            val parsed = PdfParser.parse(bytes)
            assertTrue(parsed.pages.isNotEmpty())
            var visionCalls = 0
            var rendered = 0
            val renderer = AndroidPdfRendererAdapter(context)
            val countedRenderer = object : PdfPageRasterizer, PdfUnitRasterizer {
                override fun render(pdfBytes: ByteArray, pages: List<Int>): List<RenderedPdfPage> =
                    renderer.render(pdfBytes, pages).also { rendered += it.size }
                override fun renderUnit(pdfBytes: ByteArray, unit: ProcessingUnit, limits: UnitRenderLimits): RenderedPdfPage? =
                    renderer.renderUnit(pdfBytes, unit, limits)?.also { rendered++ }
            }
            val dbName = "sample-$sample.db"
            var db = AndroidContextSqlite(context, dbName)
            Migrations.apply(db)
            val blobs = CasBlobSink(File(context.filesDir, "cas-$sample"))
            val target = VisionBinding("local-test", "stub", "https://fixture.invalid", 1)
            fun repository() = KnowledgeRepository(db, blobs, vision = VisionBackend { input ->
                visionCalls++
                assertTrue(input.beforeDispatch())
                val bitmap = BitmapFactory.decodeByteArray(input.bytes, 0, input.bytes.size)
                assertNotNull("Real page raster required", bitmap)
                bitmap!!.recycle()
                VisionOutcome.Success(VisionSuccess("", "LOCAL_VISION_STUB_SAMPLE_$sample"))
            }, visionBinding = { target }, pdfRasterizer = countedRenderer, vectorIndexFactory = UsearchVectorIndexFactory(),
                vectorIndexDirectory = File(root, "index-$sample"))
            var repo = repository()
            val staged = repo.importBytes(name, "application/pdf", bytes, visionConfigured = true)
            val job = if (staged.stage == ImportStage.AWAITING_UPLOAD_CONSENT)
                repo.grantVisionConsent(staged.id, target.fingerprint) else staged
            assertEquals("sample=$sample error=${job.error}", ImportStage.READY, job.stage)
            val callsAtReady = visionCalls
            val query = Regex("[A-Za-z]{8,}").find(parsed.text)?.value ?: "LOCAL_VISION_STUB_SAMPLE_$sample"
            val before = repo.retrieve("original-before-$sample", query, knowledgeBaseIds = listOf(job.knowledgeBaseId))
            assertTrue(before.hits.isNotEmpty())
            before.citations.forEach { citation ->
                val located = repo.locateCitation(citation)
                assertFalse(located.removed)
                assertTrue(citation.page == null || citation.page!! in 1..parsed.pages.size)
                val source = requireNotNull(repo.evidenceBytes(citation))
                if (citation.assetId == null) assertEquals(sha256Hex(bytes), sha256Hex(source.second))
            }
            val chunks = db.query("SELECT COUNT(*) AS n FROM chunks").single().long("n")
            repo.closeVectorIndexes()
            db.close()
            db = AndroidContextSqlite(context, dbName)
            repo = repository()
            assertEquals(ImportStage.READY, repo.listJobs().single { it.first.id == job.id }.first.stage)
            val after = repo.retrieve("original-after-$sample", query, knowledgeBaseIds = listOf(job.knowledgeBaseId))
            assertEquals(before.hits.map { it.chunkId }.toSet(), after.hits.map { it.chunkId }.toSet())
            assertEquals(callsAtReady, visionCalls)
            assertEquals(chunks, db.query("SELECT COUNT(*) AS n FROM chunks").single().long("n"))
            val row = buildJsonObject {
                put("sample", name); put("sha256", sha256Hex(bytes)); put("bytes", bytes.size)
                put("sampleNumber", sample); put("documentId", job.documentId); put("kbId", job.knowledgeBaseId); put("query", query)
                put("hitIds", JsonArray(after.hits.map { JsonPrimitive(it.chunkId) }))
                put("pages", parsed.pages.size); put("nativeChars", parsed.text.length)
                put("Parse", "PASS_REAL_PARSER"); put("Render", "PASS_ANDROID_PDF_RENDERER")
                put("Vision", "LOCAL_STUB_ACCURACY_UNVERIFIED"); put("Embedding", "LOCAL_HASHING_TEST_SPACE")
                put("Index", "PASS_SQLITE_AND_NATIVE_USEARCH"); put("Publish", "PASS_TEST_DATABASE")
                put("Retrieve", "PASS_IDS_AND_SOURCE_BYTES"); put("chunks", chunks)
                put("visionCalls", visionCalls); put("renders", rendered); put("resumeAdditionalCalls", visionCalls-callsAtReady)
                put("elapsedMs", SystemClock.elapsedRealtime()-started)
            }.toString()
            report.appendText(row+"\n")
            Log.i("MAROriginalRegression", row)
            repo.closeVectorIndexes()
            db.close()
        }
        File(instrumentation.targetContext.cacheDir, "original-regression-current.txt").writeText(root.name)
        Log.i("MAROriginalRegression", "report=${report.absolutePath}")
    }

    private fun isolatedContext(base: Context, root: File) = object : ContextWrapper(base) {
        override fun getDatabasePath(name: String) = File(root, name)
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    /** Run in a second instrumentation process after adb force-stop. No source reimport or Vision adapter exists here. */
    private fun verifyProcessRestart(base: Context) {
        val name = File(base.cacheDir, "original-regression-current.txt").readText()
        require(name.matches(Regex("original-regression-[0-9a-f-]+")))
        val root = File(base.cacheDir, name)
        val context = isolatedContext(base, root)
        val rows = File(root, "stages.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(3, rows.size)
        rows.forEach { row ->
            val sample = row.getValue("sampleNumber").jsonPrimitive.content.toInt()
            val db = AndroidContextSqlite(context, "sample-$sample.db")
            val repo = KnowledgeRepository(db, CasBlobSink(File(context.filesDir, "cas-$sample")),
                vectorIndexFactory = UsearchVectorIndexFactory(), vectorIndexDirectory = File(root, "index-$sample"))
            try {
                val result = repo.retrieve("process-restart-$sample", row.getValue("query").jsonPrimitive.content,
                    knowledgeBaseIds = listOf(row.getValue("kbId").jsonPrimitive.content))
                assertEquals(row.getValue("hitIds").jsonArray.map { it.jsonPrimitive.content }.toSet(), result.hits.map { it.chunkId }.toSet())
                result.citations.forEach { assertFalse(repo.locateCitation(it).removed); assertNotNull(repo.evidenceBytes(it)) }
                assertEquals(1L, repo.vectorIndexSnapshotStats().loads)
                assertEquals(0L, repo.vectorIndexSnapshotStats().rebuilds)
                Log.i("MAROriginalRegression", "PROCESS_RESTART sample=$sample PASS snapshotLoads=1 rebuilds=0 visionCalls=0")
            } finally { repo.closeVectorIndexes(); db.close() }
        }
    }
}
