// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.data.Migrations
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BundledSqliteReliabilityTest {
    private fun file(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "bundled-reliability-${UUID.randomUUID()}.db")

    @Test fun additiveOldFileCreatesNewTablesAndPreservesOldChunkText() {
        val file = file()
        // A reduced old physical schema, without bootstrapping current tables:
        // exercises CREATE/ALTER with FK enforcement actually enabled on reopen.
        BundledSqliteConnection(file.absolutePath).use { db ->
            db.execute("CREATE TABLE schema_version(version INTEGER NOT NULL)")
            db.execute("INSERT INTO schema_version VALUES(15)")
            db.execute("CREATE TABLE knowledge_bases(id TEXT PRIMARY KEY,name TEXT NOT NULL,active_generation_id TEXT,embedding_space_id TEXT NOT NULL,created_at TEXT NOT NULL,deleted_at TEXT)")
            db.execute("CREATE TABLE documents(id TEXT PRIMARY KEY,kb_id TEXT NOT NULL,blob_hash TEXT NOT NULL,display_name TEXT NOT NULL,format TEXT NOT NULL,active_version_id TEXT,deleted_at TEXT,FOREIGN KEY(kb_id) REFERENCES knowledge_bases(id))")
            db.execute("CREATE TABLE document_versions(id TEXT PRIMARY KEY,document_id TEXT NOT NULL,parser_fingerprint TEXT NOT NULL,content_hash TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL)")
            db.execute("CREATE TABLE chunks(id TEXT PRIMARY KEY,document_version_id TEXT NOT NULL,ordinal INTEGER NOT NULL,text TEXT NOT NULL,content_hash TEXT NOT NULL,source_span TEXT NOT NULL,asset_ids TEXT NOT NULL,page INTEGER)")
            db.execute("INSERT INTO knowledge_bases VALUES('old-kb','original',NULL,'local-hash-v1-d32','synthetic',NULL)")
            db.execute("INSERT INTO documents VALUES('old-doc','old-kb',?,'original.txt','TXT',NULL,NULL)", listOf("a".repeat(64)))
            db.execute("INSERT INTO document_versions VALUES('old-version','old-doc','old-parser',?,'STAGING','synthetic')", listOf("b".repeat(64)))
            db.execute("INSERT INTO chunks VALUES('old-chunk','old-version',0,'original persisted text',?,'','',NULL)", listOf("c".repeat(64)))
        }
        BundledSqliteConnection(file.absolutePath).use { db ->
            Migrations.apply(db)
            Migrations.apply(db)
            assertEquals("original persisted text", db.query("SELECT text FROM chunks WHERE id='old-chunk'").single().string("text"))
            assertEquals(23L, db.query("SELECT text_utf16_length FROM chunks WHERE id='old-chunk'").single().long("text_utf16_length"))
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='blob_gc_pending'").isNotEmpty())
            assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='pipeline_plans'").isNotEmpty())
            assertTrue(Migrations.foreignKeyCompatibility(db).isEmpty())
        }
    }

    @Test fun readerSeesCommittedSnapshotWhileWriterAndNestedSavepointsAreLive() {
        val file = file()
        val db = BundledSqliteConnection(file.absolutePath)
        db.execute("CREATE TABLE values_test(id INTEGER PRIMARY KEY, value INTEGER)")
        db.execute("INSERT INTO values_test VALUES(1,10)")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                db.transaction {
                    db.execute("UPDATE values_test SET value=20")
                    assertEquals(20L, db.query("SELECT value FROM values_test").single().long("value"))
                    try { db.transaction { db.execute("UPDATE values_test SET value=30"); error("rollback nested") } }
                    catch (_: IllegalStateException) { }
                    assertEquals(20L, db.query("SELECT value FROM values_test").single().long("value"))
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val read = pool.submit<Long> { db.query("SELECT value FROM values_test").single().long("value") }
            assertEquals(10L, read.get(1, TimeUnit.SECONDS).toLong())
            release.countDown()
            writer.get(10, TimeUnit.SECONDS)
            assertEquals(20L, db.query("SELECT value FROM values_test").single().long("value"))
            db.close()
            db.close()
            try { db.query("SELECT 1"); fail("closed read") } catch (_: IllegalStateException) { }
            BundledSqliteConnection(file.absolutePath).use {
                assertEquals(20L, it.query("SELECT value FROM values_test").single().long("value"))
            }
        } finally { release.countDown(); pool.shutdownNow(); db.close() }
    }

    @Test fun oldSchemasRetainLegacyOrphansAndEnableNewForeignKeyEnforcement() {
        for (version in listOf(15, 28, 31)) {
            val file = file()
            BundledSqliteConnection(file.absolutePath).use { db ->
                Migrations.apply(db)
                db.execute("UPDATE schema_version SET version=?", listOf(version))
                db.execute("PRAGMA foreign_keys=OFF")
                db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format) VALUES('legacy','absent',?,'original','TXT')", listOf("a".repeat(64)))
            }
            BundledSqliteConnection(file.absolutePath).use { db ->
                Migrations.apply(db)
                Migrations.apply(db)
                assertEquals("original", db.query("SELECT display_name FROM documents WHERE id='legacy'").single().string("display_name"))
                assertTrue(Migrations.foreignKeyCompatibility(db).any { it.table == "documents" && it.parentTable == "knowledge_bases" })
                assertEquals(1L, db.query("PRAGMA foreign_keys").single().long("foreign_keys"))
                try {
                    db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format) VALUES('new','absent',?,'new','TXT')", listOf("b".repeat(64)))
                    fail("new FK orphan accepted")
                } catch (expected: android.database.SQLException) {
                    // sqlite-android maps its common exception to SQLException;
                    // verify this is the FK rejection, not an unrelated SQL failure.
                    assertTrue(expected.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
                }
                assertTrue(db.query("SELECT id FROM documents WHERE id='new'").isEmpty())
                assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            }
        }
    }

    @Test fun writerWaitAndCloseAreBoundedAndClosingInTransactionFails() {
        val db = BundledSqliteConnection(file().absolutePath, waitMillis = 100)
        db.execute("CREATE TABLE t(id INTEGER)")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val work = pool.submit {
                db.transaction {
                    try { db.close(); fail("close in transaction") } catch (_: IllegalStateException) { }
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            try { db.execute("INSERT INTO t VALUES(1)"); fail("writer wait") } catch (_: IllegalStateException) { }
            try { db.close(); fail("close wait") } catch (_: IllegalStateException) { }
            release.countDown()
            work.get(10, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow(); db.close() }
    }
}
