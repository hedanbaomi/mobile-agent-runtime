// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.HashingTextEmbedder

/**
 * v34 rewrites chunks_fts from an external-content index into a contentless-delete index.
 *
 * Every fixture is the migrated schema rewound to v33 on the derived index and the version only,
 * carrying deliberately wrong and missing postings plus two knowledge bases with live documents,
 * vectors and a legacy generation pin. The upgrade is therefore judged against real rows: the
 * index must be rebuilt from chunks.text without rewriting any of them.
 */
class LexicalIndexMigrationTest {
    @Test
    fun populatedV33UpgradeRebuildsPostingsFromChunkTextInBoundedBatchesAndPreservesEveryRow() {
        JdbcSqlConnection().use { db ->
            v33Fixture(db)
            // The fixture is genuinely damaged: ten postings exist and all of them carry a token
            // that occurs in no source row, while every other chunk is not indexed at all.
            assertEquals(10L, matchCount(db, STALE))
            assertEquals(0L, matchCount(db, ENGLISH))
            val preserved = preserved(db)

            val tracked = TrackingConnection(db)
            Migrations.apply(tracked)

            assertTrue(Migrations.VERSION >= 34)
            assertEquals(Migrations.VERSION.toLong(), version(db))
            val definition = ftsSql(db)
            assertTrue(definition.contains("contentless_delete=1"), definition)
            assertTrue(definition.contains("content=''"), definition)
            assertFalse(definition.contains("content='chunks'"), definition)
            // Keyset paging reads 128 + 128 + 44 rows, then one empty page ends the loop.
            assertEquals(listOf(128, 128, 44, 0), tracked.batches)
            // Exactly one replacement of the derived index, and one posting write per batch.
            assertEquals(2, tracked.indexReplacements)
            assertEquals(3, tracked.postingWrites)
            // Both scripts are searchable from the real chunks.text, and the stale token is gone.
            assertEquals(CHUNKS.toLong(), matchCount(db, ENGLISH))
            assertEquals(CHUNKS.toLong(), matchCount(db, CHINESE))
            assertEquals(0L, matchCount(db, STALE))
            // Matched postings still join to the exact chunks the text came from.
            assertEquals(sparseChunkIds(db), matchChunkIds(db, SPARSE))
            assertEquals(preserved, preserved(db))
        }
    }

    @Test
    fun reopeningACurrentDatabaseRebuildsNothingAndWritesNoPostings() {
        JdbcSqlConnection().use { db ->
            v33Fixture(db)
            Migrations.apply(db)
            val preserved = preserved(db)
            val definition = ftsSql(db)

            val tracked = TrackingConnection(db)
            Migrations.apply(tracked)

            assertEquals(Migrations.VERSION.toLong(), version(db))
            assertEquals(emptyList<String>(), tracked.lexicalWrites)
            assertEquals(emptyList<Int>(), tracked.batches)
            assertEquals(definition, ftsSql(db))
            assertEquals(preserved, preserved(db))
            assertEquals(CHUNKS.toLong(), matchCount(db, ENGLISH))
        }
    }

    @Test
    fun failureBetweenPostingBatchesKeepsV33AndTheOldIndexAndRetrySucceeds() {
        JdbcSqlConnection().use { db ->
            v33Fixture(db)
            val preserved = preserved(db)
            val before = snapshot(db)
            val failing = object : SqlConnection by db {
                private var batches = 0
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql.startsWith("INSERT INTO chunks_fts(rowid,text) VALUES")) {
                        batches++
                        // The first 128 postings are already in the new index when this fires.
                        if (batches == 2) throw IllegalStateException("fixture failure between posting batches")
                    }
                    db.execute(sql, args)
                }
            }

            val failure = assertThrows(IllegalStateException::class.java) { Migrations.apply(failing) }

            assertEquals("fixture failure between posting batches", failure.message)
            // The whole upgrade rolled back: old version, old index definition, old postings, all rows.
            assertEquals(before, snapshot(db))
            assertTrue(ftsSql(db).contains("content='chunks'"), ftsSql(db))
            assertEquals(10L, matchCount(db, STALE))
            assertEquals(0L, matchCount(db, ENGLISH))

            Migrations.apply(db)

            assertEquals(Migrations.VERSION.toLong(), version(db))
            assertFalse(ftsSql(db).contains("content='chunks'"), ftsSql(db))
            assertEquals(0L, matchCount(db, STALE))
            assertEquals(CHUNKS.toLong(), matchCount(db, ENGLISH))
            assertEquals(preserved, preserved(db))
        }
    }

    @Test
    fun interruptionAfterTheIndexIsReplacedRollsBackTheOldIndexAndRetrySucceeds() {
        JdbcSqlConnection().use { db ->
            v33Fixture(db)
            val preserved = preserved(db)
            val before = snapshot(db)
            val interrupting = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    db.execute(sql, args)
                    // The old index is already dropped and the new one created at this point.
                    if (sql.startsWith("CREATE VIRTUAL TABLE chunks_fts")) Thread.currentThread().interrupt()
                }
            }

            try {
                assertThrows(InterruptedException::class.java) { Migrations.apply(interrupting) }
            } finally {
                // Never leak the interrupt flag into the rest of the test JVM.
                Thread.interrupted()
            }

            assertEquals(before, snapshot(db))
            assertTrue(ftsSql(db).contains("content='chunks'"), ftsSql(db))

            Migrations.apply(db)

            assertEquals(Migrations.VERSION.toLong(), version(db))
            assertEquals(0L, matchCount(db, STALE))
            assertEquals(CHUNKS.toLong(), matchCount(db, ENGLISH))
            assertEquals(preserved, preserved(db))
        }
    }

    @Test
    fun aCurrentDatabaseThatLostItsIndexRecoversTheContentlessIndexWithoutRewritingChunks() {
        JdbcSqlConnection().use { db ->
            v33Fixture(db)
            Migrations.apply(db)
            assertEquals(Migrations.VERSION.toLong(), version(db))
            val preserved = preserved(db)

            // A restore or manual cleanup can drop the derived index while the schema version stays current.
            db.execute("DROP TABLE chunks_fts")
            assertEquals("", ftsSql(db))

            val tracked = TrackingConnection(db)
            Migrations.apply(tracked)

            assertEquals(Migrations.VERSION.toLong(), version(db))
            // The historical create-if-not-exists statement runs first on every open; the repair
            // replaces the derived index once and refills it in three posting batches.
            assertEquals(2, tracked.indexReplacements)
            assertEquals(3, tracked.postingWrites)
            assertTrue(ftsSql(db).contains("contentless_delete=1"), ftsSql(db))
            assertFalse(ftsSql(db).contains("content='chunks'"), ftsSql(db))
            assertEquals(listOf(128, 128, 44, 0), tracked.batches)
            assertEquals(CHUNKS.toLong(), matchCount(db, ENGLISH))
            assertEquals(CHUNKS.toLong(), matchCount(db, CHINESE))
            assertEquals(0L, matchCount(db, STALE))
            assertEquals(sparseChunkIds(db), matchChunkIds(db, SPARSE))
            assertEquals(preserved, preserved(db))
        }
    }

    /**
     * Builds the migrated schema, then rewinds only the derived index and the version. This is a
     * synthetic v33 database, not a captured user file. Two knowledge bases hold live documents,
     * chunks with source spans/assets/pages, vectors, generation members and a legacy READY pin.
     */
    private fun v33Fixture(db: JdbcSqlConnection) {
        // Repair after every apply skips a KB whose persisted space is not the local one; the
        // fixture uses a distinct space so this test isolates the FTS conversion from index repair.
        assertNotEquals(HashingTextEmbedder().spaceId, FIXTURE_SPACE)
        Migrations.apply(db)
        db.execute("DROP TABLE chunks_fts")
        db.execute("CREATE VIRTUAL TABLE chunks_fts USING fts5(text, content='chunks', content_rowid='rowid')")
        db.execute("UPDATE schema_version SET version = 33")

        listOf("a" to "kb-a", "b" to "kb-b").forEach { (suffix, kb) ->
            db.execute("INSERT INTO knowledge_bases(id,name,active_generation_id,embedding_space_id,created_at,deleted_at) VALUES(?,?,?,?,?,NULL)",
                listOf(kb, "Base $suffix", "gen-old-$suffix", FIXTURE_SPACE, CREATED))
            db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES(?,?,?,?,?,?,NULL)",
                listOf("doc-$suffix", kb, hex(suffix), "$suffix.txt", "TEXT", "version-$suffix"))
            db.execute("INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES(?,?,?,?,?,?)",
                listOf("version-$suffix", "doc-$suffix", PARSER, hex(suffix + suffix), "READY", CREATED))
            db.execute("INSERT INTO index_generations(id,kb_id,space_id,manifest_hash,state,vector_count,fts_version,created_at) VALUES(?,?,?,?,?,?,?,?)",
                listOf("gen-old-$suffix", kb, FIXTURE_SPACE, hex(suffix + "g"), "READY", if (suffix == "a") 140 else CHUNKS - 140, 33, CREATED))
        }
        for (i in 1..CHUNKS) {
            val suffix = if (i <= 140) "a" else "b"
            val text = chunkText(i)
            db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page,text_utf16_length) VALUES(?,?,?,?,?,?,?,?,?)",
                listOf("chunk-$i", "version-$suffix", i, text, "hash-$i", """{"ordinal":$i}""",
                    if (i % 25 == 0) "asset-$i" else "", i, text.length))
            db.execute("INSERT INTO embeddings(chunk_id,space_id,vector_blob,content_hash) VALUES(?,?,?,?)",
                listOf("chunk-$i", FIXTURE_SPACE, byteArrayOf(1, 2, (i % 251).toByte(), (i / 251).toByte()), "hash-$i"))
            db.execute("INSERT INTO generation_members(generation_id,chunk_id,space_id,document_version_id) VALUES(?,?,?,?)",
                listOf("gen-old-$suffix", "chunk-$i", FIXTURE_SPACE, "version-$suffix"))
        }
        db.execute("INSERT INTO permission_grants(grant_id,install_id,package_hash,capabilities,revision,revoked,scopes_json,lifetime,policy_version,created_at,expires_at,revoked_at) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL)",
            listOf("grant-1", "install-1", hex("p"), """["knowledge.read"]""", 1, 0, "{}", "PERSISTENT", 1, CREATED))
        db.execute("INSERT INTO consent_tickets(id,kind,job_id,kb_id,fingerprint,consumed,created_at) VALUES(?,?,?,?,?,?,?)",
            listOf("consent-1", "API_EMBEDDING", "job-1", "kb-a", "fingerprint-1", 0, CREATED))

        // Only the first ten chunks were ever indexed, and with text no source row contains.
        db.query("SELECT rowid AS rid FROM chunks ORDER BY rowid LIMIT 10").forEach {
            db.execute("INSERT INTO chunks_fts(rowid,text) VALUES(?,?)", listOf(it.long("rid"), "obsolete placeholder"))
        }
    }

    private class TrackingConnection(private val delegate: SqlConnection) : SqlConnection by delegate {
        val batches = mutableListOf<Int>()
        val lexicalWrites = mutableListOf<String>()

        val indexReplacements: Int get() = lexicalWrites.count {
            it.startsWith("DROP TABLE IF EXISTS chunks_fts") || it.startsWith("CREATE VIRTUAL TABLE chunks_fts")
        }
        val postingWrites: Int get() = lexicalWrites.count {
            it.startsWith("INSERT INTO chunks_fts") || it.startsWith("DELETE FROM chunks_fts")
        }

        override fun query(sql: String, args: List<Any?>): List<SqlRow> = delegate.query(sql, args).also {
            if (sql.startsWith("SELECT rowid AS rid, text FROM chunks")) batches += it.size
        }

        override fun execute(sql: String, args: List<Any?>) {
            // The historical "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts" statement runs on
            // every open and is a no-op once the table exists, so it is not a write.
            if (lexicalWrite(sql)) lexicalWrites += sql
            delegate.execute(sql, args)
        }

        private fun lexicalWrite(sql: String): Boolean {
            val statement = sql.trimStart()
            return statement.startsWith("INSERT INTO chunks_fts") ||
                statement.startsWith("DELETE FROM chunks_fts") ||
                statement.startsWith("DROP TABLE IF EXISTS chunks_fts") ||
                statement.startsWith("CREATE VIRTUAL TABLE chunks_fts")
        }
    }

    private fun chunkText(i: Int): String =
        "row $i widget 汉语索引 内容 $i" + if (i % 10 == 0) " $SPARSE" else ""

    private fun hex(seed: String): String = seed.repeat(64).take(64)

    private fun version(db: SqlConnection): Long =
        db.query("SELECT version FROM schema_version").single().long("version")

    private fun ftsSql(db: SqlConnection): String = db.query(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='chunks_fts'",
    ).singleOrNull()?.string("sql").orEmpty()

    private fun matchCount(db: SqlConnection, term: String): Long = db.query(
        "SELECT COUNT(*) AS n FROM chunks_fts WHERE chunks_fts MATCH ?", listOf(term),
    ).single().long("n")

    /** Production retrieval shape: a match is joined back to its source chunk by rowid. */
    private fun matchChunkIds(db: SqlConnection, term: String): List<String> = db.query(
        "SELECT c.id AS id FROM chunks_fts JOIN chunks c ON c.rowid = chunks_fts.rowid " +
            "WHERE chunks_fts MATCH ? ORDER BY c.rowid",
        listOf(term),
    ).map { it.string("id") }

    private fun sparseChunkIds(db: SqlConnection): List<String> = db.query(
        "SELECT id FROM chunks WHERE ordinal % 10 = 0 ORDER BY rowid",
    ).map { it.string("id") }

    /** Byte-identical row images; blobs are hex-encoded so ByteArray identity cannot fake equality. */
    private fun rows(db: SqlConnection, sql: String): List<List<Any?>> = db.query(sql).map { row ->
        row.columns.values.map { if (it is ByteArray) it.joinToString("") { byte -> "%02x".format(byte) } else it }
    }

    /** Every durable row the migration must read but never rewrite. */
    private fun preserved(db: SqlConnection): List<List<List<Any?>>> = listOf(
        rows(db, "SELECT * FROM chunks ORDER BY rowid"),
        rows(db, "SELECT * FROM documents ORDER BY id"),
        rows(db, "SELECT * FROM document_versions ORDER BY id"),
        rows(db, "SELECT chunk_id,space_id,vector_blob,content_hash FROM embeddings ORDER BY chunk_id"),
        rows(db, "SELECT * FROM index_generations ORDER BY id"),
        rows(db, "SELECT id,name,active_generation_id,embedding_space_id,created_at,deleted_at FROM knowledge_bases ORDER BY id"),
        rows(db, "SELECT * FROM generation_members ORDER BY generation_id,chunk_id"),
        rows(db, "SELECT * FROM permission_grants ORDER BY grant_id"),
        rows(db, "SELECT * FROM consent_tickets ORDER BY id"),
    )

    /** Durable rows plus the index's own definition and postings, for rollback comparisons. */
    private fun snapshot(db: SqlConnection): List<Any?> = listOf(
        version(db),
        ftsSql(db),
        matchCount(db, STALE),
        matchCount(db, ENGLISH),
        preserved(db),
    )

    private companion object {
        const val CHUNKS = 300
        const val FIXTURE_SPACE = "fixture-space-v1"
        const val CREATED = "2026-10-17T00:00:00Z"
        const val PARSER = "parser-v1"
        const val ENGLISH = "widget"
        const val CHINESE = "索引"
        const val SPARSE = "sparsetopic"
        const val STALE = "obsolete"
    }
}
