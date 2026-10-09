// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.OfficeParser
import runtime.mobileagent.knowledge.TextEmbedder
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Real packaged ONNX, SQLite and native index; no external model calls. */
@RunWith(AndroidJUnit4::class)
class OfficePunctuationEmbeddingDeviceTest {
    @Test(timeout = 240_000)
    fun standaloneEllipsisKeepsItsTextAndSectionThroughImportAndReopen() = withRepository { db, repo, reopen ->
        val paragraphs = listOf("这是退款办理的课程说明。", "……", "顾客购买后七天内可以申请退回付款。")
        val bytes = docx(paragraphs)
        val job = repo.importBytes("punctuation.docx", MIME, bytes, false)
        assertEquals(job.error, ImportStage.READY, job.stage)
        val rows = db.query("SELECT text, source_span, page FROM chunks WHERE document_version_id IN (SELECT id FROM document_versions WHERE document_id=?) ORDER BY ordinal", listOf(job.documentId))
        assertEquals(paragraphs, rows.map { it.string("text") })
        assertTrue(rows.all { it.longOrNull("page") == null })
        assertEquals("section-ordinal:2|source:parser-native|association:PAGE_CONTEXT", rows[1].string("source_span"))
        assertTrue(repo.retrieve("before", "如何把购买的钱退回来？", 3).hits.any { it.documentId == job.documentId && it.text.contains("退回付款") })
        val duplicate = repo.importBytes("punctuation.docx", MIME, bytes, false)
        assertEquals(ImportStage.READY, duplicate.stage)
        assertEquals(job.documentId, duplicate.documentId)
        repo.closeVectorIndexes()
        val after = reopen()
        assertTrue(after.retrieve("after", "如何把购买的钱退回来？", 3).hits.any { it.documentId == job.documentId && it.text.contains("退回付款") })
        after.closeVectorIndexes()
    }

    @Test(timeout = 600_000)
    fun originalCorpusOrSyntheticFourDocumentsReachReadyWithAllNativeText() = withRepository { db, repo, _ ->
        // An explicit local-only directory allows replaying private inputs without
        // putting them in source, test assets, logs or published APKs.
        val directory = InstrumentationRegistry.getArguments().getString("docx_corpus_dir")
        val inputs = if (directory != null) {
            val files = File(directory).listFiles()?.filter { it.isFile && it.extension == "docx" }?.sortedBy { it.name }
            require(files != null && files.size == 4)
            files.map { it.name to it.readBytes() }
        } else {
            listOf(
                listOf("课程说明与参考资料", "……", "顺序执行的基本方法"),
                listOf("……", "条件判断与循环的基本方法"),
                listOf("序列与字典的基本方法", "……", "集合的基本方法", "……"),
                listOf("函数的基本方法", "课后练习与参考资料"),
            ).mapIndexed { index, paragraphs -> "fixture-$index.docx" to docx(paragraphs) }
        }
        val failures = mutableListOf<String>()
        inputs.forEachIndexed { index, (name, bytes) ->
            val expected = OfficeParser.parse(name, bytes).pages.map { it.text }
            val job = repo.importBytes(name, MIME, bytes, false)
            Log.i("OfficePunctuation", "input=$index stage=${job.stage} error=${job.error}")
            if (job.stage != ImportStage.READY) {
                failures += "input=$index ${job.stage}: ${job.error}"
            } else {
                val actual = db.query("SELECT text FROM chunks WHERE document_version_id IN (SELECT id FROM document_versions WHERE document_id=?) ORDER BY ordinal", listOf(job.documentId)).map { it.string("text") }
                assertEquals("input=$index native paragraphs must be preserved", expected, actual)
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test(timeout = 240_000)
    fun failedLocalImportResumesFromCasWithoutCreatingAnotherDocument() = withRepository(initialLegacyRejection = true) { db, repo, reopen ->
        val paragraphs = listOf("课程介绍文档中的标点与段落。", "……", "所有原文都需要保留。")
        val failed = repo.importBytes("retry.docx", MIME, docx(paragraphs), false)
        assertEquals(ImportStage.FAILED, failed.stage)
        assertEquals("LOCAL_EMBEDDING_UNSUPPORTED_TEXT", failed.error)
        repo.closeVectorIndexes()
        val repaired = reopen()
        val resumed = repaired.resumeImport(failed.id, visionConfigured = false)
        assertEquals(resumed.error, ImportStage.READY, resumed.stage)
        assertEquals(failed.id, resumed.id)
        assertEquals(failed.documentId, resumed.documentId)
        assertEquals(1L, db.query("SELECT COUNT(*) AS count FROM documents").single().long("count"))
        val actual = db.query("SELECT text FROM chunks WHERE document_version_id IN (SELECT id FROM document_versions WHERE document_id=?) ORDER BY ordinal", listOf(resumed.documentId)).map { it.string("text") }
        assertEquals(paragraphs, actual)
        repaired.closeVectorIndexes()
    }

    private fun withRepository(initialLegacyRejection: Boolean = false, block: (AndroidContextSqlite, KnowledgeRepository, () -> KnowledgeRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "office-punctuation-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root, name)
        }
        val db = AndroidContextSqlite(context, "corpus.db")
        Migrations.apply(db)
        OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
            val blobs = CasBlobSink(File(context.filesDir, "cas"))
            val oldRejection = object : TextEmbedder {
                override val spaceId = embedder.spaceId
                override val dimension = embedder.dimension
                override fun embed(text: String): FloatArray {
                    if (text == "……") throw IllegalArgumentException("LOCAL_EMBEDDING_UNSUPPORTED_TEXT")
                    return embedder.embed(text)
                }
            }
            fun create(selected: TextEmbedder = embedder) = KnowledgeRepository(db, blobs, selected, vectorIndexFactory = UsearchVectorIndexFactory(), vectorIndexDirectory = File(context.cacheDir, "ann"))
            val repo = create(if (initialLegacyRejection) oldRejection else embedder)
            try { block(db, repo, { create() }) } finally { repo.closeVectorIndexes(); db.close() }
        }
    }

    private fun docx(paragraphs: List<String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val body = paragraphs.joinToString("") { "<w:p><w:r><w:t>$it</w:t></w:r></w:p>" }
            zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>".toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    companion object {
        private const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
