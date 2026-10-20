// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.storage

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.WorldBookRepository
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.serialization.worldbook.WorldBookCodec

class WorldBookStorageDeviceTest {
    @Test fun oldNativeHistoryMigratesAndRepeatedTogglesRemainSmallAfterReopen() {
        val file = file()
        val document = WorldBookCodec.decode(
            """{"name":"旧规则书","entries":{"0":{"uid":0,"key":["检定"],"content":"${"保留中文规则".repeat(10_000)}","unknown":{"x":1}}}}""",
        ).document
        val raw = legacy(document)
        val oldHash = hash(raw)
        BundledSqliteConnection(file.absolutePath).use { db ->
            Migrations.apply(db)
            WorldBookRepository(db).save(document, "book")
            db.execute("UPDATE world_book_revisions SET document_json=?,content_hash=?,byte_length=?", listOf(raw, oldHash, raw.toByteArray().size))
            db.execute("INSERT INTO world_book_revisions(book_id,revision,document_json,content_hash,byte_length,created_at) VALUES('book',2,?,?,?,'old')",
                listOf(raw, oldHash, raw.toByteArray().size))
            db.execute("UPDATE world_books SET revision=2,revocation_epoch=7 WHERE id='book'")
            db.execute("ALTER TABLE world_book_revisions DROP COLUMN document_revision")
            db.execute("UPDATE schema_version SET version=36")
        }
        var bytes = 0L
        BundledSqliteConnection(file.absolutePath).use { db ->
            Migrations.apply(db)
            val books = WorldBookRepository(db)
            assertEquals(document, books.get("book", 1)!!.document)
            assertEquals(document, books.get("book", 2)!!.document)
            bytes = books.storageUsage().storedBytes
            assertTrue(bytes < raw.toByteArray().size)
            repeat(100) { books.setEnabled("book", it % 2 == 1, it + 2) }
            assertEquals(bytes, books.storageUsage().storedBytes)
            assertEquals(107L, books.get("book")!!.revocationEpoch)
            assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM world_book_revisions WHERE document_revision IS NULL").single().long("n"))
        }
        BundledSqliteConnection(file.absolutePath).use { db ->
            Migrations.apply(db)
            val books = WorldBookRepository(db)
            assertEquals(bytes, books.storageUsage().storedBytes)
            assertEquals(document, books.get("book")!!.document)
            assertEquals(102, books.get("book")!!.summary.revision)
            assertEquals(document, books.get("book", 51)!!.document)
            assertEquals(37L, db.query("SELECT version FROM schema_version").single().long("version"))
        }
    }

    @Test fun corruptedLegacyRevisionPreservesVersionAndRowsOnMigrationFailure() {
        BundledSqliteConnection(file().absolutePath).use { db ->
            Migrations.apply(db)
            val document = WorldBookDocument("保留", listOf(WorldBookEntry("0", listOf("关键词"), "正文")))
            WorldBookRepository(db).save(document, "book")
            val raw = legacy(document)
            db.execute("UPDATE world_book_revisions SET document_json=?,content_hash='corrupt',byte_length=?", listOf(raw, raw.toByteArray().size))
            db.execute("ALTER TABLE world_book_revisions DROP COLUMN document_revision")
            db.execute("UPDATE schema_version SET version=36")
            try {
                Migrations.apply(db)
                fail("Corrupt history must stop migration")
            } catch (_: IllegalStateException) { /* Expected: the transaction preserves old data. */ }
            assertEquals(36L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(raw, db.query("SELECT document_json FROM world_book_revisions").single().string("document_json"))
            assertFalse(db.query("PRAGMA table_info(world_book_revisions)").any { it.string("name") == "document_revision" })
            db.execute("UPDATE world_book_revisions SET content_hash=?", listOf(hash(raw)))
            Migrations.apply(db)
            assertEquals(document, WorldBookRepository(db).get("book")!!.document)
        }
    }

    private fun legacy(document: WorldBookDocument): String {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        return buildJsonObject {
            put("spec", "mobileagent.worldbook"); put("version", 1); put("format", document.format.name)
            put("name", document.name); put("description", document.description)
            put("settings", json.encodeToJsonElement(document.settings)); put("originalJson", document.originalJson)
            put("entries", JsonArray(document.entries.map { json.encodeToJsonElement(it) }))
        }.toString()
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "worldbook-storage-${UUID.randomUUID()}.db")
}
