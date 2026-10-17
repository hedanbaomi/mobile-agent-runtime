// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.ImportStage
import runtime.mobileagent.knowledge.MemoryBlobSink

/** Real SQLite append and repair paths; these checks count work, not a wall-clock deadline. */
class KnowledgeGenerationEfficiencyTest {
    private class Statements(private val delegate: SqlConnection) : SqlConnection by delegate {
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, List<Any?>>>()
        var failLexicalInsert = false
        override fun query(sql: String, args: List<Any?>): List<SqlRow> {
            reads += sql
            return delegate.query(sql, args)
        }
        override fun execute(sql: String, args: List<Any?>) {
            writes += sql to args.toList()
            if (failLexicalInsert && sql.startsWith("INSERT INTO chunks_fts(rowid")) error("fixture lexical write failure")
            delegate.execute(sql, args)
        }
        fun clear() { reads.clear(); writes.clear() }
    }

    @Test fun appendingADocumentDoesNotRewriteHealthyLexicalRowsOfOlderDocuments() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val statements = Statements(db)
            val repo = KnowledgeRepository(statements, MemoryBlobSink(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("append work")
            repeat(8) { index ->
                val text = if (index % 2 == 0) "  铁路运输记录${index}包含独有的退款信息。  "
                    else "Document $index contains a unique railway record."
                assertEquals(ImportStage.READY, repo.importBytes("source-$index.txt", "text/plain",
                    text.toByteArray(), false, kb).stage)
            }
            val previous = db.query("SELECT rowid AS rid FROM chunks").map { it.long("rid") }.toSet()
            statements.clear()
            assertEquals(ImportStage.READY, repo.importBytes("next.txt", "text/plain",
                "The next document contains a refund record.".toByteArray(), false, kb).stage)
            val rewritten = statements.writes.count { (sql, args) ->
                (sql.startsWith("DELETE FROM chunks_fts") || sql.startsWith("INSERT INTO chunks_fts")) &&
                    args.firstOrNull() in previous
            }
            println("GENERATION_APPEND previous_chunks=${previous.size} rewritten_lexical_rows=$rewritten")
            assertEquals(0, rewritten, "An append must reuse lexical rows that are already correct")
            assertEquals(4, directMatches(db, "\"铁路\""), "CJK bigram postings must remain directly searchable")
        }
    }

    @Test fun rebuildingRepairsMissingAndStaleLexicalRowsAndRejectsInvalidVectors() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val repo = KnowledgeRepository(db, MemoryBlobSink(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("repair work")
            repeat(3) { index ->
                repo.importBytes("source-$index.txt", "text/plain", "Evidence document $index describes railway records.".toByteArray(), false, kb)
            }
            val rows = db.query("SELECT rowid AS rid,id FROM chunks ORDER BY rowid")
            db.execute("DELETE FROM chunks_fts WHERE rowid=?", listOf(rows[0].long("rid")))
            db.execute("DELETE FROM chunks_fts WHERE rowid=?", listOf(rows[1].long("rid")))
            db.execute("INSERT INTO chunks_fts(rowid,text) VALUES(?,?)", listOf(rows[1].long("rid"), "wrong replacement"))
            repo.rebuildIndex(kb)
            assertEquals(3, db.query("SELECT rowid FROM chunks_fts").size)
            assertEquals(3, directMatches(db, "railway"), "LIKE fallback must not conceal a broken FTS repair")
            assertEquals(0, directMatches(db, "replacement"), "Old index-only terms must be removed")
            assertEquals(3, repo.search("railway", topK = 20, knowledgeBaseIds = listOf(kb)).size)
            db.execute("UPDATE embeddings SET vector_blob=? WHERE chunk_id=?", listOf(byteArrayOf(1, 2), rows[2].string("id")))
            assertThrows(Exception::class.java) { repo.rebuildIndex(kb) }
        }
    }

    @Test fun generationMembershipWritesAreBatchedWhileEveryChunkRemainsRetrievable() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val statements = Statements(db)
            val repo = KnowledgeRepository(statements, MemoryBlobSink(), HashingTextEmbedder(), automaticStorageMaintenance = false)
            val kb = repo.createKnowledgeBase("bounded writes")
            val text = (1..200).joinToString("\n\n") { index ->
                "# Record $index\n" + "This railway record includes local facts about delivery and refund processing. ".repeat(12)
            }
            val imported = repo.importBytes("many.md", "text/markdown", text.toByteArray(), false, kb)
            assertEquals(ImportStage.READY, imported.stage)
            val count = db.query("SELECT COUNT(*) AS n FROM chunks WHERE document_version_id=(SELECT active_version_id FROM documents WHERE id=?)",
                listOf(imported.documentId)).single().long("n")
            assertTrue(count > 128, "Fixture must cross the bounded write boundary")
            statements.clear()
            repo.rebuildIndex(kb)
            val writes = statements.writes.count { it.first.startsWith("INSERT OR IGNORE INTO generation_members") }
            println("GENERATION_REBUILD chunks=$count membership_statements=$writes")
            assertTrue(writes <= (count + 127) / 128, "Generation members must use bounded statement batches")
            val generation = repo.generationPins(listOf(kb))[kb]!!.generationId!!
            val members = db.query("SELECT COUNT(*) AS n FROM generation_members WHERE generation_id=?", listOf(generation)).single().long("n")
            assertEquals(count, members)
        }
    }

    @Test fun sharedLexicalRepairPreservesOtherKnowledgeBasesAndRollsBackOnFailure() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val statements = Statements(db)
            val repo = KnowledgeRepository(statements, MemoryBlobSink(), automaticStorageMaintenance = false)
            val first = repo.createKnowledgeBase("first")
            val second = repo.createKnowledgeBase("second")
            repo.importBytes("first.txt", "text/plain", "铁路运输是第一份文档。".toByteArray(), false, first)
            repo.importBytes("second.txt", "text/plain", "The second knowledge base contains a garden record.".toByteArray(), false, second)
            val secondPin = repo.generationPins(listOf(second))[second]!!.generationId
            repo.rebuildIndex(first)
            assertEquals(1, directMatches(db, "\"铁路\""))
            assertEquals(1, directMatches(db, "garden"))
            assertEquals(secondPin, repo.generationPins(listOf(second))[second]!!.generationId)
            val firstPin = repo.generationPins(listOf(first))[first]!!.generationId
            statements.failLexicalInsert = true
            assertThrows(Exception::class.java) { repo.rebuildIndex(first) }
            assertEquals(firstPin, repo.generationPins(listOf(first))[first]!!.generationId)
            assertEquals(secondPin, repo.generationPins(listOf(second))[second]!!.generationId)
            assertEquals(1, directMatches(db, "\"铁路\""), "Failed repair must restore the prior FTS state")
            assertEquals(1, directMatches(db, "garden"))
        }
    }

    @Test fun startupRepairDoesNotResetTheSharedLexicalIndexForAnUnrelatedDirtyGeneration() {
        JdbcSqlConnection().use { db ->
            Migrations.apply(db)
            val statements = Statements(db)
            val repo = KnowledgeRepository(statements, MemoryBlobSink(), automaticStorageMaintenance = false)
            val first = repo.createKnowledgeBase("dirty generation")
            val second = repo.createKnowledgeBase("unrelated generation")
            repo.importBytes("first.txt", "text/plain", "铁路运输记录。".toByteArray(), false, first)
            repo.importBytes("second.txt", "text/plain", "A garden document.".toByteArray(), false, second)
            val otherPin = repo.generationPins(listOf(second))[second]!!.generationId
            db.execute("UPDATE knowledge_bases SET active_generation_id=NULL WHERE id=?", listOf(first))
            statements.clear()
            repo.repairIndexes(onlyChanged = true)
            assertFalse(statements.writes.any { it.first.contains("delete-all") }, "Startup must not enter explicit full lexical repair")
            assertEquals(otherPin, repo.generationPins(listOf(second))[second]!!.generationId)
            assertEquals(1, directMatches(db, "\"铁路\""))
            assertEquals(1, directMatches(db, "garden"))
        }
    }

    private fun directMatches(db: SqlConnection, query: String): Int = db.query(
        "SELECT COUNT(*) AS n FROM chunks_fts WHERE chunks_fts MATCH ?", listOf(query),
    ).single().long("n").toInt()
}
