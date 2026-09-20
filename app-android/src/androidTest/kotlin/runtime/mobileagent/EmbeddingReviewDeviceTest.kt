// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.embedding.AndroidModelPackLoader
import runtime.mobileagent.embedding.OnnxTextEmbedder
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.storage.AndroidContextSqlite
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

@RunWith(AndroidJUnit4::class)
class EmbeddingReviewDeviceTest {
    @Test(timeout=240_000) fun multilingualTailRetrievalSurvivesReopenWithRealPackAndJni() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir,"embedding-review-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name:String)=File(root,name)
        }
        val pageSize=Os.sysconf(OsConstants._SC_PAGESIZE)
        Log.i("EmbeddingReview", "page_size=$pageSize space=${AndroidModelPackLoader.DEFAULT_SPACE_ID}")
        OnnxTextEmbedder(AndroidModelPackLoader(context).load()).use { embedder ->
            val prefix="This section describes general experimental procedures and record keeping. ".repeat(20)
            val docs=listOf(prefix+"The experiment uses a domestic cat.",prefix+"The experiment uses a domestic dog.",prefix+"The experiment uses a railway train.")
            val vectors=docs.map(embedder::embed)
            assertTrue(sim(vectors[0],vectors[1])<0.99999)
            for ((query,target) in listOf("Which experiment uses a feline animal?" to 0,"Which experiment involves a railway vehicle?" to 2)) {
                val q=embedder.embed(query);val scores=vectors.map { sim(q,it) }
                assertEquals("$query: $scores",target,scores.indices.maxBy { scores[it] })
            }
            val concepts=listOf("缓存淘汰策略","贷款逾期罚息","玫瑰绽放芬芳").map(embedder::embed)
            assertTrue(sim(concepts[0],concepts[1])<0.99)
            assertTrue(sim(concepts[1],concepts[2])<0.99)
            val db=AndroidContextSqlite(context,"review.db");Migrations.apply(db)
            val blobs=CasBlobSink(File(context.filesDir,"cas"))
            fun repo()=KnowledgeRepository(db,blobs,embedder,vectorIndexFactory=UsearchVectorIndexFactory(),vectorIndexDirectory=File(context.cacheDir,"ann"))
            val first=repo()
            val text="这份文档介绍如何申请退款。顾客在购买后的七天内可以申请退回付款。"
            val refund=first.importBytes("退款.txt","text/plain",text.toByteArray(),false)
            assertEquals(ImportStage.READY,refund.stage)
            assertEquals(ImportStage.READY,first.importBytes("花园.txt","text/plain","玫瑰需要充足的阳光和定期浇水才能健康开花。".toByteArray(),false).stage)
            val query="买了东西不满意，如何把钱拿回来？"
            val before=first.retrieve("before",query,2)
            assertEquals(refund.documentId,before.hits.first().documentId)
            assertEquals(text,before.hits.first().text)
            assertTrue(before.citations.isNotEmpty())
            first.closeVectorIndexes()
            val reopened=repo();val after=reopened.retrieve("after",query,2)
            assertEquals(before.hits.map { it.chunkId },after.hits.map { it.chunkId })
            assertEquals(before.citations.map { it.sourceSpan },after.citations.map { it.sourceSpan })
            assertEquals(1L,reopened.vectorIndexSnapshotStats().loads)
            reopened.closeVectorIndexes()
            Log.i("EmbeddingReview","PASS real_model real_sqlite real_jni normalized_tail_and_chinese_retrieval reopen page_size=$pageSize")
        }
    }

    private fun sim(a:FloatArray,b:FloatArray)=a.indices.sumOf { a[it].toDouble()*b[it] }
}
