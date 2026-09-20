// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import runtime.mobileagent.knowledge.sha256Hex
import runtime.mobileagent.knowledge.MemoryBlobSink
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.TextEmbedder

class LocalEmbeddingUpgradeTest {
    private val oldSpace = "onnx:retired-test-space"
    private fun old() = object : TextEmbedder {
        override val spaceId = oldSpace
        override val dimension = 8
        override fun embed(text: String) = HashingTextEmbedder(8).embed(text)
    }
    @Test fun upgradePreservesChunksAndCitationAndDoesNotReembedOnReopen() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            val before = KnowledgeRepository(db,blobs,old())
            val job = before.importBytes("source.txt","text/plain","猫科动物的实验记录。The experiment uses a domestic cat.".toByteArray(),false)
            val hit = before.search("猫科动物").first()
            var calls=0
            val replacement = object : TextEmbedder {
                override val spaceId="onnx:replacement-test-space"
                override val dimension=16
                override fun embed(text:String)=HashingTextEmbedder(16).embed(text).also { calls++ }
            }
            val upgraded = KnowledgeRepository(db,blobs,replacement,legacyLocalEmbeddingSpaces=setOf(oldSpace))
            val next=upgraded.search("猫科动物").first()
            assertEquals(hit.chunkId,next.chunkId)
            assertEquals(hit.sourceSpan,next.sourceSpan)
            assertEquals(replacement.spaceId,upgraded.embeddingSpaceId(job.knowledgeBaseId))
            val count=calls
            KnowledgeRepository(db,blobs,replacement,legacyLocalEmbeddingSpaces=setOf(oldSpace)).upgradeLocalEmbeddingSpace(job.knowledgeBaseId)
            assertEquals(count,calls)
            assertEquals(1L,db.query("SELECT COUNT(*) AS n FROM chunks").single().long("n"))
        }
    }
    @Test fun interruptedUpgradeKeepsOldBindingAndReusesCommittedBatches() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs=MemoryBlobSink()
            val before=KnowledgeRepository(db,blobs,old())
            val job=before.importBytes("source.txt","text/plain","seed".toByteArray(),false)
            val version=db.query("SELECT active_version_id FROM documents WHERE id=?",listOf(job.documentId)).single().string("active_version_id")
            for(i in 1..128) {
                val text="fixture $i"
                db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,text_utf16_length) VALUES(?,?,?,?,?,?)",listOf("fixture-%04d".format(i),version,i,text,sha256Hex(text.toByteArray()),text.length))
            }
            val oldPin=before.generationPins(listOf(job.knowledgeBaseId))
            var calls=0;var fail=true
            val replacement=object:TextEmbedder {
                override val spaceId="onnx:replacement-test-space"
                override val dimension=16
                override fun embed(text:String):FloatArray {
                    calls++;if(fail && calls==129) error("synthetic local interruption")
                    return HashingTextEmbedder(16).embed(text)
                }
            }
            val first=KnowledgeRepository(db,blobs,replacement,legacyLocalEmbeddingSpaces=setOf(oldSpace))
            assertThrows(IllegalStateException::class.java) { first.upgradeLocalEmbeddingSpace(job.knowledgeBaseId) }
            assertEquals(oldSpace,first.embeddingSpaceId(job.knowledgeBaseId))
            assertEquals(oldPin,first.generationPins(listOf(job.knowledgeBaseId)))
            assertEquals(128L,db.query("SELECT COUNT(*) AS n FROM embeddings WHERE space_id=?",listOf(replacement.spaceId)).single().long("n"))
            fail=false
            val reopened=KnowledgeRepository(db,blobs,replacement,legacyLocalEmbeddingSpaces=setOf(oldSpace))
            reopened.upgradeLocalEmbeddingSpace(job.knowledgeBaseId)
            assertEquals(130,calls)
            assertEquals(replacement.spaceId,reopened.embeddingSpaceId(job.knowledgeBaseId))
            assertEquals(129L,db.query("SELECT COUNT(*) AS n FROM chunks").single().long("n"))
        }
    }
    @Test fun unknownAndApiBindingsAreNeverSilentlyRebound() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo=KnowledgeRepository(db,MemoryBlobSink(),legacyLocalEmbeddingSpaces=setOf(oldSpace))
            val kb=repo.createKnowledgeBase("remote",embeddingSpaceId="api:unknown")
            assertNull(repo.upgradeLocalEmbeddingSpace(kb))
            assertEquals("api:unknown",repo.embeddingSpaceId(kb))
        }
    }
    @Test fun retrievalPreservesUpgradeCancellation() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val blobs = MemoryBlobSink()
            KnowledgeRepository(db, blobs, old()).importBytes("source.txt", "text/plain", "seed".toByteArray(), false)
            val replacement = object : TextEmbedder {
                override val spaceId = "onnx:replacement-test-space"
                override val dimension = 16
                override fun embed(text: String): FloatArray = throw java.util.concurrent.CancellationException("cancelled")
            }
            val repo = KnowledgeRepository(db, blobs, replacement, legacyLocalEmbeddingSpaces = setOf(oldSpace))
            assertThrows(java.util.concurrent.CancellationException::class.java) { repo.search("seed") }
        }
    }
}
