// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.VectorIndexFactory
import runtime.mobileagent.knowledge.VectorIndexPort
import runtime.mobileagent.knowledge.VisionBackend
import runtime.mobileagent.knowledge.VisionBinding
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Opt-in only: storage/waiting load is NOT 450 MiB of completed Vision or text inference. */
@RunWith(AndroidJUnit4::class)
class KnowledgeLoadDeviceTest {
    @Test
    fun storageWaitingCorpusAndRealLocalTextSubset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("K06 large fixture requires -e knowledgeLoad true", args.getString("knowledgeLoad") == "true")
        val phase = args.getString("knowledgeLoadPhase") ?: "full"
        require(phase in setOf("full", "checkpoint", "resume"))
        val minuteArgument = args.getString("knowledgeLoadMaxMinutes")
        val minutes = if (minuteArgument == null) 90L else requireNotNull(minuteArgument.toLongOrNull()) { "knowledgeLoadMaxMinutes must be an integer" }
        require(minutes in 10..360)
        val target = instrumentation.targetContext
        val cache = target.cacheDir.canonicalFile
        val fixtureName = if (phase == "resume") requireNotNull(args.getString("knowledgeLoadFixture")) else "knowledge-load-${UUID.randomUUID()}"
        require(fixtureName.matches(Regex("knowledge-load-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        val root = File(cache, fixtureName).canonicalFile
        check(root.parentFile == cache)
        if (phase == "resume") check(root.isDirectory) else check(root.mkdirs())
        val context = LoadContext(target, root)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + minutes * 60_000
        val runTag = "${System.currentTimeMillis()}-$phase"
        val events = File(root, "events-$runTag.jsonl")
        val resultFile = File(root, "result-$runTag.json")
        val nativeCreates = AtomicInteger()
        val remoteVisionCalls = AtomicInteger()
        var status = "INCOMPLETE"
        var failure = ""
        var manifestSourceBytes = 0L
        var ready = 0
        var waiting = 0
        var copying = 0
        val monitor = LoadMonitor(context)
        val batteryStart = deviceState(context)
        fun checkDeadline() {
            check(SystemClock.elapsedRealtime() < deadline) { "Load deadline reached; incomplete workload is not K06 PASS" }
        }
        fun event(value: JsonObject) {
            events.appendText(value.toString() + "\n")
            monitor.sample()
        }
        Log.i(TAG, "START phase=$phase fixture=$fixtureName root=$root opt_in=true")
        try {
            if (phase != "resume") {
                // Input sources + CAS + verified model pack coexist. Never deliberately fill a device.
                check(StatFs(root.path).availableBytes >= 1_536L * MIB) { "Need at least 1.5 GiB free in the dedicated test cache volume" }
            }
            val entries = if (phase == "resume") KnowledgeLoadDeviceFixtures.read(root) else {
                KnowledgeLoadDeviceFixtures.generate(root, { entry ->
                    event(buildJsonObject { put("phase", "generated"); put("file", entry.json()); put("elapsedMs", SystemClock.elapsedRealtime() - started) })
                }, ::checkDeadline)
            }
            manifestSourceBytes = entries.sumOf { it.bytes }
            val stateFile = File(root, "checkpoints.json")
            val db = AndroidContextSqlite(context, "load.db")
            Migrations.apply(db)
            event(buildJsonObject {
                put("phase", "sqlite_ready"); put("schemaVersion", Migrations.VERSION)
                put("sqliteVersion", db.query("SELECT sqlite_version() AS version").single().string("version"))
            })
            // The loader and ONNX session are real even when only creating COPY checkpoints.
            // No fabricated text embedder is injected into the production repository.
            checkDeadline()
            val pack = AndroidModelPackLoader(context).load()
            assertEquals(AndroidModelPackLoader.DEFAULT_MODEL_SHA256, pack.modelFile.inputStream().use(KnowledgeLoadDeviceFixtures::sha256))
            event(buildJsonObject {
                put("phase", "model_loaded"); put("modelSha256", pack.manifest.sha256)
                put("tokenizerSha256", pack.manifest.tokenizerSha256); put("dimension", pack.manifest.dimension)
            })
            OnnxTextEmbedder(pack).use { embedder ->
                val blobs = CasBlobSink(File(context.filesDir, "cas"))
                val factory = object : VectorIndexFactory {
                    override fun create(spaceId: String, dimension: Int, capacity: Int): VectorIndexPort {
                        val index = UsearchVectorIndexFactory().create(spaceId, dimension, capacity)
                        nativeCreates.incrementAndGet()
                        return index
                    }
                }
                val visionGuard = VisionBackend {
                    remoteVisionCalls.incrementAndGet()
                    error("No Vision authorization: the test must never invoke this guard")
                }
                fun repository(connection: SqlConnection) = KnowledgeRepository(connection, blobs, embedder,
                    vision = visionGuard,
                    visionBinding = { VisionBinding("fixture-only", "not-a-real-provider", "https://fixture.invalid/v1", 1) },
                    vectorIndexFactory = factory)
                val initial = repository(db)
                val state = if (phase == "resume") Checkpoints.read(stateFile) else Checkpoints(
                    textKbs = List(4) { initial.createKnowledgeBase("K06 synthetic text group $it") },
                    visualKb = initial.createKnowledgeBase("K06 storage and Vision waiting only"),
                    records = mutableListOf(),
                )
                check(state.textKbs.size == 4)
                if (phase != "resume") {
                    for (entry in entries) {
                        checkDeadline()
                        val stepStarted = SystemClock.elapsedRealtime()
                        val bytes = sourceBytes(root, entry)
                        val kbId = if (entry.kind == "text") state.textKbs[entry.caseIndex / 5] else state.visualKb
                        val job = initial.importBytes(entry.name, entry.mime, bytes, false, kbId, pauseAt = ImportStage.COPYING)
                        assertEquals(ImportStage.COPYING, job.stage)
                        state.records += Record(entry.name, kbId, job.id, job.documentId)
                        state.write(stateFile)
                        copying++
                        event(buildJsonObject {
                            put("phase", "checkpoint"); put("name", entry.name); put("sourceSha256", entry.sha256)
                            put("bytes", entry.bytes); put("stage", job.stage.name); put("jobId", job.id)
                            put("elapsedMs", SystemClock.elapsedRealtime() - stepStarted)
                        })
                    }
                }
                assertEquals(KnowledgeLoadDeviceFixtures.FILE_COUNT, state.records.size)
                assertEquals(entries.map { it.name }.toSet(), state.records.map { it.name }.toSet())
                assertEquals(KnowledgeLoadDeviceFixtures.FILE_COUNT.toLong(), db.query("SELECT COUNT(*) AS n FROM documents").single().long("n"))
                if (phase == "checkpoint") {
                    assertEquals(KnowledgeLoadDeviceFixtures.FILE_COUNT.toLong(), db.query("SELECT COUNT(*) AS n FROM import_jobs WHERE stage='COPYING'").single().long("n"))
                    status = "CHECKPOINT_COMPONENT_ASSERTIONS_PASSED_NOT_K06_PASS"
                } else {
                    // A fresh connection and repository prove durable checkpoints, not just object reuse.
                    val resumedDb = AndroidContextSqlite(context, "load.db")
                    Migrations.apply(resumedDb)
                    val resumed = repository(resumedDb)
                    for (entry in entries) {
                        checkDeadline()
                        val record = state.records.single { it.name == entry.name }
                        val stepStarted = SystemClock.elapsedRealtime()
                        // On a manual resume, verify sources still match the published manifest.
                        if (phase == "resume") sourceBytes(root, entry)
                        val current = resumedDb.query("SELECT stage FROM import_jobs WHERE id=? AND document_id=?", listOf(record.jobId, record.documentId)).single().string("stage")
                        val stage = if (current == ImportStage.READY.name || current == ImportStage.WAITING_FOR_VISION_MODEL.name) ImportStage.valueOf(current)
                            else resumed.resumeImport(record.jobId, visionConfigured = false).stage
                        val expected = if (entry.kind == "text") ImportStage.READY else ImportStage.WAITING_FOR_VISION_MODEL
                        assertEquals("${entry.name} returned unexpected stage", expected, stage)
                        if (stage == ImportStage.READY) ready++ else waiting++
                        assertEquals(1L, resumedDb.query("SELECT COUNT(*) AS n FROM documents WHERE id=? AND kb_id=? AND deleted_at IS NULL", listOf(record.documentId, record.kbId)).single().long("n"))
                        assertEquals(1L, resumed.blobRefCount(entry.sha256))
                        if (entry.kind == "visual") assertTrue(resumedDb.query("SELECT active_version_id FROM documents WHERE id=?", listOf(record.documentId)).single().string("active_version_id").isBlank())
                        event(buildJsonObject {
                            put("phase", "resume"); put("name", entry.name); put("stage", stage.name)
                            put("sourceSha256", entry.sha256); put("bytes", entry.bytes)
                            put("elapsedMs", SystemClock.elapsedRealtime() - stepStarted)
                        })
                    }
                    assertEquals(20, ready); assertEquals(300, waiting)
                    // Every named warehouse must have a real in-scope retrieval hit and valid citation.
                    for (entry in entries.filter { it.kind == "text" }) {
                        checkDeadline()
                        val record = state.records.single { it.name == entry.name }
                        val result = resumed.retrieve("k06-${entry.caseIndex}", entry.keyword, 8, listOf(record.kbId))
                        assertTrue("Missing proper name: ${entry.keyword}", result.hits.any { it.documentId == record.documentId && entry.keyword in it.text })
                        assertTrue(result.hits.all { it.knowledgeBaseId == record.kbId })
                        val citation = result.citations.first { it.documentId == record.documentId }
                        assertFalse(resumed.locateCitation(citation).removed)
                        event(buildJsonObject { put("phase", "proper_name_query"); put("name", entry.name); put("query", entry.keyword); put("hits", result.hits.size); put("citationId", citation.citationId) })
                    }
                    val first = entries.first { it.kind == "text" }
                    val firstRecord = state.records.single { it.name == first.name }
                    val beforeDocuments = resumedDb.query("SELECT COUNT(*) AS n FROM documents").single().long("n")
                    val repeated = resumed.importBytes(first.name, first.mime, sourceBytes(root, first), false, firstRecord.kbId)
                    assertEquals(firstRecord.documentId, repeated.documentId); assertEquals(ImportStage.READY, repeated.stage)
                    assertEquals(beforeDocuments, resumedDb.query("SELECT COUNT(*) AS n FROM documents").single().long("n"))
                    assertEquals(1L, resumed.blobRefCount(first.sha256))
                    val visual = entries.first { it.kind == "visual" }
                    val visualRecord = state.records.single { it.name == visual.name }
                    val duplicateVisual = resumed.importBytes(visual.name, visual.mime, sourceBytes(root, visual), false, state.visualKb)
                    assertEquals(visualRecord.documentId, duplicateVisual.documentId)
                    assertEquals(ImportStage.WAITING_FOR_VISION_MODEL, duplicateVisual.stage)
                    assertEquals(beforeDocuments, resumedDb.query("SELECT COUNT(*) AS n FROM documents").single().long("n"))
                    assertEquals(1L, resumed.blobRefCount(visual.sha256))
                    val otherKb = resumed.createKnowledgeBase("K06 shared visual remains isolated")
                    val otherImage = resumed.importBytes(visual.name, visual.mime, sourceBytes(root, visual), true, otherKb, visionConsent = false)
                    assertNotEquals(visualRecord.documentId, otherImage.documentId)
                    assertEquals(ImportStage.AWAITING_UPLOAD_CONSENT, otherImage.stage)
                    assertEquals(2L, resumed.blobRefCount(visual.sha256))
                    resumed.deleteKnowledgeBase(state.visualKb)
                    assertEquals(1L, resumed.blobRefCount(visual.sha256))
                    assertEquals(otherKb, resumed.documentKnowledgeBaseId(otherImage.documentId))
                    assertEquals(visual.sha256, requireNotNull(blobs.get(visual.sha256)).inputStream().use(KnowledgeLoadDeviceFixtures::sha256))
                    assertTrue(resumed.search(visual.keyword, 8, listOf(state.visualKb)).isEmpty())
                    assertTrue(resumed.search(first.keyword, 8, listOf(firstRecord.kbId)).any { it.documentId == firstRecord.documentId })
                    assertTrue(nativeCreates.get() > 0)
                    assertEquals(0, remoteVisionCalls.get())
                    checkDeadline()
                    event(buildJsonObject { put("phase", "assertions"); put("duplicateDocumentDelta", 0); put("sharedBlobSurvivedVisualKbDeletion", true); put("visionGuardCalls", remoteVisionCalls.get()); put("nativeIndexInstances", nativeCreates.get()) })
                    status = "STORAGE_WAITING_AND_LOCAL_TEXT_COMPONENT_ASSERTIONS_PASSED_NOT_K06_PASS"
                }
            }
        } catch (problem: Throwable) {
            failure = "${problem.javaClass.simpleName}: ${problem.message.orEmpty()}"
            throw problem
        } finally {
            monitor.close()
            val summary = buildJsonObject {
                put("datasetKind", KnowledgeLoadDeviceFixtures.KIND); put("status", status); put("failure", failure)
                put("fixture", fixtureName); put("phase", phase); put("elapsedMs", SystemClock.elapsedRealtime() - started)
                put("deadlineMinutes", minutes); put("deadlineEnforcement", "cooperative-between-files; not an interruptible native inference watchdog")
                put("manifestSourceBytes", manifestSourceBytes); put("expectedFileCount", 320)
                put("observedSourceFileCountAtEnd", File(root, "sources").listFiles()?.count { it.isFile } ?: 0)
                put("observedSourceBytesAtEnd", File(root, "sources").listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L)
                put("observedReadyTextBeforeDeletion", ready)
                put("observedWaitingImagesBeforeDeletion", waiting); put("copyingCheckpointsCreatedThisInvocation", copying)
                put("visualGuardCalls", remoteVisionCalls.get()); put("remoteApiClientsConstructed", 0)
                put("apiEmbeddingAdapterConfigured", false); put("nativeIndexInstances", nativeCreates.get())
                put("modelSha256", AndroidModelPackLoader.DEFAULT_MODEL_SHA256)
                put("tokenizerSha256", AndroidModelPackLoader.DEFAULT_TOKENIZER_SHA256)
                put("modelHashesAre", "locked expected values; see model_loaded event for completed verification")
                put("fixtureDiskBytesAtEnd", root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                put("measurement", monitor.json()); put("deviceStart", batteryStart); put("deviceEnd", deviceState(context))
                put("notTested", JsonArray(listOf("complete Vision pipeline", "natural compressed 450MiB corpus", "450MiB text inference", "all-stage process death", "disk full", "network timeout and cloud unknown outcome", "Android 12-16 foreground matrix", "MobileAgentApp startup").map(::JsonPrimitive)))
            }
            resultFile.writeText(summary.toString())
            Log.i(TAG, "END status=$status elapsed_ms=${SystemClock.elapsedRealtime() - started} fixture=$fixtureName result=${resultFile.name}")
        }
    }

    private fun sourceBytes(root: File, entry: KnowledgeLoadDeviceFixtures.Entry): ByteArray {
        val source = KnowledgeLoadDeviceFixtures.source(root, entry)
        assertEquals(entry.sha256, source.inputStream().use(KnowledgeLoadDeviceFixtures::sha256))
        return source.readBytes()
    }

    private data class Record(val name: String, val kbId: String, val jobId: String, val documentId: String) {
        fun json() = buildJsonObject { put("name", name); put("kbId", kbId); put("jobId", jobId); put("documentId", documentId) }
    }

    private data class Checkpoints(val textKbs: List<String>, val visualKb: String, val records: MutableList<Record>) {
        fun write(file: File) = KnowledgeLoadDeviceFixtures.atomicText(file, buildJsonObject {
            put("datasetKind", KnowledgeLoadDeviceFixtures.KIND); put("textKbs", JsonArray(textKbs.map(::JsonPrimitive)))
            put("visualKb", visualKb); put("records", JsonArray(records.map { it.json() }))
        }.toString())

        companion object {
            fun read(file: File): Checkpoints {
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                check(root.getValue("datasetKind").jsonPrimitive.content == KnowledgeLoadDeviceFixtures.KIND)
                return Checkpoints((root.getValue("textKbs") as JsonArray).map { it.jsonPrimitive.content }, root.getValue("visualKb").jsonPrimitive.content,
                    (root.getValue("records") as JsonArray).map { item ->
                        val row = item.jsonObject
                        Record(row.getValue("name").jsonPrimitive.content, row.getValue("kbId").jsonPrimitive.content,
                            row.getValue("jobId").jsonPrimitive.content, row.getValue("documentId").jsonPrimitive.content)
                    }.toMutableList())
            }
        }
    }

    private class LoadContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { check(mkdirs() || isDirectory) }
        override fun getCacheDir() = File(root, "cache").apply { check(mkdirs() || isDirectory) }
        override fun getNoBackupFilesDir() = File(root, "model-cache").apply { check(mkdirs() || isDirectory) }
        override fun getDatabasePath(name: String): File {
            check(name == "load.db")
            return File(File(root, "databases").apply { check(mkdirs() || isDirectory) }, name)
        }
    }

    private class LoadMonitor(private val context: Context) : AutoCloseable {
        private val javaPeak = AtomicLong()
        private val nativePeak = AtomicLong()
        private val pssPeak = AtomicLong()
        private val samples = AtomicLong()
        private val minimumFree = AtomicLong(Long.MAX_VALUE)
        private val worker = Executors.newSingleThreadScheduledExecutor()
        init { worker.scheduleAtFixedRate({ runCatching { sample() } }, 0, 1, TimeUnit.SECONDS) }
        @Synchronized fun sample() {
            val runtime = Runtime.getRuntime()
            javaPeak.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
            nativePeak.updateAndGet { maxOf(it, Debug.getNativeHeapAllocatedSize()) }
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            pssPeak.updateAndGet { maxOf(it, memory.totalPss.toLong() * 1024) }
            minimumFree.updateAndGet { minOf(it, StatFs(context.cacheDir.path).availableBytes) }
            samples.incrementAndGet()
        }
        override fun close() { worker.shutdownNow(); sample() }
        fun json() = buildJsonObject {
            put("sampleCount", samples.get()); put("nominalSamplePeriodMs", 1000)
            put("sampledPeakJavaHeapBytes", javaPeak.get()); put("sampledPeakNativeHeapBytes", nativePeak.get())
            put("sampledPeakProcessPssBytes", pssPeak.get()); put("minimumObservedVolumeFreeBytes", minimumFree.get())
            put("scope", "sampled process peaks, not continuous maxima; volume free space includes other processes")
        }
    }

    private fun deviceState(context: Context) = buildJsonObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("api", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE); put("abis", JsonArray(Build.SUPPORTED_ABIS.map(::JsonPrimitive)))
        put("targetSdk", context.applicationInfo.targetSdkVersion)
        put("batteryLevel", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1)
        put("batteryScale", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1)
        put("batteryTemperatureTenthsC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
        put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
        put("thermalStatus", if (Build.VERSION.SDK_INT >= 29) (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1)
        put("networkMeasurement", "no networking clients constructed; only injected Vision guard invocation count, not global packet capture")
    }

    private companion object {
        const val TAG = "KnowledgeLoadDevice"
        const val MIB = 1024L * 1024
    }
}
