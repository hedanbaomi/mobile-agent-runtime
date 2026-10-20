// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.*
import runtime.mobileagent.serialization.worldbook.WorldBookBatchPlanner
import runtime.mobileagent.serialization.worldbook.WorldBookCodec
import runtime.mobileagent.serialization.worldbook.WorldBookProbe

class WorldBookStorageCompactionTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private fun document() = WorldBookCodec.decode(
        """{"name":"规则书","entries":{"0":{"uid":0,"key":["火球术"],"content":"${"中文规则内容".repeat(20_000)}","extra":{"x":1}}}}""",
    ).document

    @Test fun repeatedTogglesPreserveHistoryWithoutCopyingBodies() = database { db ->
        val books = WorldBookRepository(db)
        val first = books.save(document(), "book")
        val bytes = books.storageUsage().storedBytes
        repeat(100) { index ->
            val stored = books.setEnabled("book", index % 2 == 1, index + 1)
            assertEquals(index + 2, stored.summary.revision)
            assertEquals((index + 1).toLong(), stored.revocationEpoch)
        }
        assertEquals(bytes, books.storageUsage().storedBytes)
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM world_book_revisions WHERE document_revision IS NULL").single().long("n"))
        assertEquals(100L, db.query("SELECT COUNT(*) AS n FROM world_book_revisions WHERE document_revision=1").single().long("n"))
        assertEquals(first.document, books.get("book", 50)!!.document)
        assertEquals(first.document, WorldBookRepository(db).get("book")!!.document)
        assertThrows(IllegalStateException::class.java) { books.save(first.document, "book", 1) }
        assertThrows(IllegalStateException::class.java) { books.setEnabled("book", false, 1) }
    }

    @Test fun noOpToggleDoesNotConsumeEpochOrRevisionAndFullQuotaCanStillDisable() = database { db ->
        val books = WorldBookRepository(db)
        val first = books.save(document(), "book")
        assertEquals(first, books.setEnabled("book", true, 1))
        db.execute("UPDATE world_book_revisions SET byte_length=?", listOf(books.storageUsage().maxStoredBytes))
        val disabled = books.setEnabled("book", false, 1)
        assertFalse(disabled.summary.enabled)
        assertEquals(2, disabled.summary.revision)
        assertEquals(books.storageUsage().maxStoredBytes, books.storageUsage().storedBytes)
    }

    @Test fun editorEnableOnlySaveReusesBodyAndLaterTextEditStartsANewBody() = database { db ->
        val books = WorldBookRepository(db)
        val first = books.save(document(), "book")
        val bytes = books.storageUsage().storedBytes
        books.save(first.document, "book", 1, false)
        assertEquals(bytes, books.storageUsage().storedBytes)
        val edited = first.document.copy(description = "修订")
        books.save(edited, "book", 2, false)
        books.setEnabled("book", true, 3)
        assertEquals(3L, db.query("SELECT document_revision FROM world_book_revisions WHERE revision=4").single().long("document_revision"))
        assertEquals(first.document, books.get("book", 2)!!.document)
        assertEquals(edited, books.get("book", 4)!!.document)
    }

    @Test fun legacyMigrationCompactsAndDeduplicatesWithoutChangingHistoricalIds() = database { db ->
        val original = document()
        val books = WorldBookRepository(db)
        books.save(original, "book")
        seedLegacy(db, original, 1)
        seedLegacy(db, original, 2)
        db.execute("UPDATE world_books SET revision=2,revocation_epoch=9 WHERE id='book'")
        db.execute("INSERT INTO app_prefs(key,value) VALUES('unrelated','preserved')")
        val oldBytes = books.storageUsage().storedBytes
        db.execute("ALTER TABLE world_book_revisions DROP COLUMN document_revision")
        db.execute("UPDATE schema_version SET version=36")
        Migrations.apply(db)
        val migrated = books.get("book")!!
        assertEquals(original, migrated.document)
        assertEquals(original, books.get("book", 1)!!.document)
        assertEquals(2, migrated.summary.revision)
        assertEquals(9L, migrated.revocationEpoch)
        assertTrue(books.storageUsage().storedBytes < oldBytes / 2)
        assertEquals(1L, db.query("SELECT document_revision FROM world_book_revisions WHERE revision=2").single().long("document_revision"))
        assertEquals("preserved", db.query("SELECT value FROM app_prefs WHERE key='unrelated'").single().string("value"))
        val probe = WorldBookBatchPlanner.probe(WorldBookCodec.encodeNative(original), "book") as WorldBookProbe.Book
        assertEquals(probe.nativeHash, books.heads().single().contentHash)
        val bytes = books.storageUsage().storedBytes
        Migrations.apply(db)
        assertEquals(bytes, books.storageUsage().storedBytes)
    }

    @Test fun corruptLegacyHistoryRollsBackColumnAndPreviouslyCompactedRows() = database { db ->
        val original = document()
        WorldBookRepository(db).save(original, "book")
        val legacy = seedLegacy(db, original, 1)
        seedLegacy(db, original, 2)
        db.execute("UPDATE world_book_revisions SET content_hash='bad' WHERE revision=2")
        db.execute("ALTER TABLE world_book_revisions DROP COLUMN document_revision")
        db.execute("UPDATE schema_version SET version=36")
        assertThrows(IllegalStateException::class.java) { Migrations.apply(db) }
        assertEquals(36L, db.query("SELECT version FROM schema_version").single().long("version"))
        assertEquals(legacy, db.query("SELECT document_json FROM world_book_revisions WHERE revision=1").single().string("document_json"))
        assertFalse(db.query("PRAGMA table_info(world_book_revisions)").any { it.string("name") == "document_revision" })
    }

    @Test fun brokenOrChainedReferencesAreRejected() = database { db ->
        val books = WorldBookRepository(db)
        books.save(document(), "book")
        books.setEnabled("book", false, 1)
        books.setEnabled("book", true, 2)
        db.execute("UPDATE world_book_revisions SET document_revision=2 WHERE revision=3")
        assertThrows(IllegalStateException::class.java) { books.get("book") }
        db.execute("UPDATE world_book_revisions SET document_revision=1,content_hash='wrong' WHERE revision=3")
        assertThrows(IllegalStateException::class.java) { books.get("book") }
    }

    @Test fun unchangedReplacementCannotSilentlySkipADeletedOrStaleTarget() = database { db ->
        val books = WorldBookRepository(db)
        val doc = document()
        books.save(doc, "book")
        assertNull(books.replaceIfChanged(doc, "book", 1, true))
        books.setEnabled("book", false, 1)
        assertThrows(IllegalStateException::class.java) { books.replaceIfChanged(doc, "book", 1, true) }
        assertNull(books.replaceIfChanged(doc, "book", 2, false))
        books.delete("book", 2)
        assertThrows(IllegalStateException::class.java) { books.replaceIfChanged(doc, "book", 2, false) }
    }

    @Test fun maximumEntryCountCanBeSavedAndReadAfterNativeConversion() = database { db ->
        val entries = (0 until 5_000).joinToString(",") { """{"id":$it,"keys":["检定"],"content":"条目$it"}""" }
        val document = WorldBookCodec.decode("""{"name":"大书","entries":[$entries]}""").document
        val books = WorldBookRepository(db)
        books.save(document, "large")
        assertEquals(document, books.get("large")!!.document)
        assertEquals(5_000, books.get("large")!!.summary.entryCount)
    }

    private fun seedLegacy(db: SqlConnection, document: WorldBookDocument, revision: Int): String {
        val raw = buildJsonObject {
            put("spec", "mobileagent.worldbook"); put("version", 1); put("format", document.format.name)
            put("name", document.name); put("description", document.description)
            put("settings", json.encodeToJsonElement(document.settings)); put("originalJson", document.originalJson)
            put("entries", JsonArray(document.entries.map { json.encodeToJsonElement(it) }))
        }.toString()
        db.execute("INSERT OR REPLACE INTO world_book_revisions(book_id,revision,document_json,content_hash,byte_length,created_at) VALUES(?,?,?,?,?,?)",
            listOf("book", revision, raw, WorldBookRevisionStorage.hash(raw), raw.toByteArray().size, "legacy"))
        return raw
    }

    private fun database(block: (JdbcSqlConnection) -> Unit) =
        JdbcSqlConnection().use { db -> Migrations.apply(db); block(db) }
}
