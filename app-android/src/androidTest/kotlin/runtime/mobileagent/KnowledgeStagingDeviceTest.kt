// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.knowledge.ImportBatchKind
import runtime.mobileagent.knowledge.ImportBatchState

/** Production coordinator -> WorkManager -> real local ONNX/search, scoped to new fixture KBs. */
@RunWith(AndroidJUnit4::class)
class KnowledgeStagingDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext as MobileAgentApp

    @Test
    fun zipPublishesAllChildrenThroughProductionCoordinatorAndWorker() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val token = UUID.randomUUID().toString().replace("-", "")
            val kb = app.container.knowledge.createKnowledgeBase("review-test-zip-" + token)
            assertEquals(AndroidModelPackLoader.DEFAULT_SPACE_ID, app.container.knowledge.embeddingSpaceId(kb))
            val archive = File.createTempFile("review-test-", ".zip", app.cacheDir)
            try {
                ZipOutputStream(archive.outputStream()).use { zip ->
                    listOf("alpha" to ("reviewalpha" + token), "beta" to ("reviewbeta" + token)).forEach { (name, marker) ->
                        zip.putNextEntry(ZipEntry(name + ".txt"))
                        zip.write((marker + " local document content").toByteArray())
                        zip.closeEntry()
                    }
                }
                val result = KnowledgeImportCoordinator.forApplication(app).start(
                    listOf(KnowledgeImportInput("fixture.zip", archive.toURI().toString(),
                        { archive.inputStream() }, { "application/zip" })),
                    ImportBatchKind.ZIP, "review-test-zip", kb, visionTarget = null)
                val operation = (result as? KnowledgeImportStart.Started)?.operation
                    ?: error("Production staging was not started: " + result)
                val staged = withTimeout(60_000) { operation.completion.await() }
                assertEquals(KnowledgeImportTerminal.COMPLETED, staged.terminal)
                awaitPublished(requireNotNull(staged.batchId), kb, listOf("reviewalpha" + token, "reviewbeta" + token))
            } finally {
                archive.delete()
            }
        }
    }

    @Test
    fun identicalBasenamesFromDifferentSourcesRemainTwoPublishedMembers() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val token = UUID.randomUUID().toString().replace("-", "")
            val kb = app.container.knowledge.createKnowledgeBase("review-test-files-" + token)
            assertEquals(AndroidModelPackLoader.DEFAULT_SPACE_ID, app.container.knowledge.embeddingSpaceId(kb))
            val files = listOf(
                File.createTempFile("review-test-one-", ".txt", app.cacheDir),
                File.createTempFile("review-test-two-", ".txt", app.cacheDir))
            val markers = listOf("reviewfirst" + token, "reviewsecond" + token)
            try {
                files.zip(markers).forEach { (file, marker) -> file.writeText(marker + " local content") }
                val result = KnowledgeImportCoordinator.forApplication(app).start(
                    files.map { file -> KnowledgeImportInput("same.txt", file.toURI().toString(),
                        { file.inputStream() }, { "text/plain" }) },
                    ImportBatchKind.FILES, "review-test-files", kb, visionTarget = null)
                val operation = (result as? KnowledgeImportStart.Started)?.operation
                    ?: error("Production staging was not started: " + result)
                val staged = withTimeout(60_000) { operation.completion.await() }
                assertEquals(KnowledgeImportTerminal.COMPLETED, staged.terminal)
                awaitPublished(requireNotNull(staged.batchId), kb, markers)
            } finally {
                files.forEach { it.delete() }
            }
        }
    }

    @Test
    fun resumedZipUsesPrivateSnapshotWhenExternalArchiveChanges() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use {
            val token = UUID.randomUUID().toString().replace("-", "")
            val kb = app.container.knowledge.createKnowledgeBase("review-test-zip-resume-" + token)
            val archive = File.createTempFile("review-test-mutable-", ".zip", app.cacheDir)
            val originalMarkers = listOf("originalfirst" + token, "originalsecond" + token)
            fun writeArchive(markers: List<String>) {
                ZipOutputStream(archive.outputStream()).use { zip ->
                    markers.forEachIndexed { index, marker ->
                        zip.putNextEntry(ZipEntry("entry" + index + ".txt"))
                        zip.write((marker + " local snapshot content").toByteArray())
                        zip.closeEntry()
                    }
                }
            }
            writeArchive(originalMarkers)
            val copiedFirst = CountDownLatch(1)
            val stagingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val adapter = AndroidKnowledgeImportPorts(app)
            val ports = object : KnowledgeImportPorts by adapter {
                override fun stageInput(batchId: String, input: KnowledgeImportInput,
                    kind: ImportBatchKind, knowledgeBaseId: String,
                    checkpoint: () -> Unit): List<String> =
                    adapter.stageInput(batchId, input, kind, knowledgeBaseId) {
                        checkpoint()
                        if (app.container.knowledge.batchProgress(batchId).copied == 1) {
                            copiedFirst.countDown()
                            // Deterministic barrier only; production adapter owns all staging.
                            CountDownLatch(1).await()
                        }
                    }
            }
            try {
                val coordinator = KnowledgeImportCoordinator(stagingScope, ports)
                val started = coordinator.start(listOf(KnowledgeImportInput("mutable.zip",
                    archive.toURI().toString(), { archive.inputStream() }, { "application/zip" })),
                    ImportBatchKind.ZIP, "review-test-zip-resume", kb, visionTarget = null)
                    as KnowledgeImportStart.Started
                assertTrue("First child must reach a durable checkpoint", copiedFirst.await(30, TimeUnit.SECONDS))
                val batch = requireNotNull(started.operation.progress.value.batchId)
                assertTrue(coordinator.pauseBatch(batch))
                assertEquals(KnowledgeImportTerminal.PAUSED,
                    withTimeout(10_000) { started.operation.completion.await() }.terminal)
                assertEquals(1, app.container.knowledge.batchProgress(batch).copied)
                val snapshot = File(app.filesDir, "import-staging/batch-" + batch + ".zip")
                assertTrue("Complete private ZIP survives pause", snapshot.isFile)
                writeArchive(listOf("changedfirst" + token, "changedsecond" + token))
                stagingScope.cancel()

                val resumed = KnowledgeImportCoordinator.forApplication(app).resumeStaging(batch)
                    as KnowledgeImportStart.Started
                assertEquals(KnowledgeImportTerminal.COMPLETED,
                    withTimeout(60_000) { resumed.operation.completion.await() }.terminal)
                awaitPublished(batch, kb, originalMarkers)
                assertTrue("Snapshot is removed only after membership completes", !snapshot.exists())
                assertTrue(app.container.knowledge.search("changedsecond" + token,
                    knowledgeBaseIds = listOf(kb)).none { ("changedsecond" + token) in it.text })
            } finally {
                stagingScope.cancel()
                archive.delete()
            }
        }
    }

    private suspend fun awaitPublished(batchId: String, kb: String, markers: List<String>) {
        val repo = app.container.knowledge
        withTimeout(120_000) {
            while (true) {
                val batch = requireNotNull(repo.findBatch(batchId))
                check(batch.state !in setOf(ImportBatchState.FAILED, ImportBatchState.BLOCKED,
                    ImportBatchState.CANCELLED, ImportBatchState.WAITING)) {
                    "Local production import failed; verify ONNX model assets: state=${batch.state}, error=${batch.error}"
                }
                if (batch.state == ImportBatchState.COMPLETED) break
                delay(200)
            }
        }
        val batch = requireNotNull(repo.findBatch(batchId))
        assertEquals(2, batch.totalItems)
        assertEquals(2, batch.published)
        markers.forEach { marker ->
            assertTrue("Published member must be searchable in the fixture KB",
                repo.search(marker, knowledgeBaseIds = listOf(kb)).any { marker in it.text })
        }
    }
}
