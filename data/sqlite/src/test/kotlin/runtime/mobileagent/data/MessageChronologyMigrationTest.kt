// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.MessageRole

class MessageChronologyMigrationTest {
    @Test fun v32UpgradeIsBoundedPreservesOriginalsAndRepeatedOpenDoesNoBackfillWrites() {
        JdbcSqlConnection().use { db ->
            seed(db)
            downgrade(db)
            val base = java.time.Instant.parse("2026-10-13T00:00:00.100Z")
            (260 downTo 0).forEach { i ->
                db.execute("INSERT INTO messages(id,conversation_id,role,text,status,created_at) VALUES(?,?,?,?,?,?)",
                    listOf("m$i", "c", "USER", "body $i", "COMPLETE", base.plusNanos(i.toLong()).toString()))
            }
            val original = originals(db)
            val tracked = TrackingConnection(db)
            Migrations.apply(tracked)
            assertEquals(33L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(original, originals(db))
            assertEquals(listOf(1, 128, 128, 5, 0), tracked.batches)
            assertEquals(261, tracked.metadataWrites)
            assertEquals((0..260).map { "m$it" }, ConversationRepository(db).messages("c").map { it.id })
            tracked.metadataWrites = 0
            tracked.batches.clear()
            Migrations.apply(tracked)
            assertEquals(0, tracked.metadataWrites)
            assertEquals(listOf(0), tracked.batches)
            assertEquals(original, originals(db))
        }
    }

    @Test fun failedUpgradeRollsBackMetadataSchemaAndVersionWithoutChangingMessages() {
        JdbcSqlConnection().use { db ->
            seed(db)
            ConversationRepository(db).append("c", MessageRole.USER, "preserved", messageId = "m")
            downgrade(db)
            val original = originals(db)
            val failing = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    db.execute(sql, args)
                    if (sql.startsWith("UPDATE messages SET chronology_key=")) error("synthetic migration interruption")
                }
            }
            assertThrows(IllegalStateException::class.java) { Migrations.apply(failing) }
            assertEquals(32L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertFalse(db.query("PRAGMA table_info(messages)").any { it.string("name") == "chronology_key" })
            assertEquals(original, originals(db))
            Migrations.apply(db)
            assertEquals("preserved", ConversationRepository(db).messages("c").single().text)
        }
    }

    @Test fun legacyReadRepairDrainsAllBatchesAndEqualInstantCursorUsesBothIndexedTerms() {
        JdbcSqlConnection().use { db ->
            seed(db)
            repeat(300) { i ->
                db.execute("INSERT INTO messages(id,conversation_id,role,text,status,created_at) VALUES(?,?,?,?,?,?)",
                    listOf("m$i", "c", "USER", "body $i", "COMPLETE", "2026-10-13T00:00:00Z"))
            }
            val tracked = TrackingConnection(db)
            val repo = ConversationRepository(tracked)
            assertEquals((250..299).map { "m$it" }, repo.messagePage("c").messages.map { it.id })
            assertEquals(listOf(1, 128, 128, 44, 0), tracked.batches)
            assertEquals((200..249).map { "m$it" }, repo.messagePage("c", "m250").messages.map { it.id })
            val sql = "SELECT * FROM messages WHERE conversation_id=? AND (chronology_key,chronology_ordinal)<(?,?) ORDER BY chronology_key DESC,chronology_ordinal DESC LIMIT ?"
            val key = MessageChronology.key("2026-10-13T00:00:00Z")
            val args = listOf("c", key, 201, 51)
            val plan = db.query("EXPLAIN QUERY PLAN $sql", args).map { it.string("detail") }
            assertTrue(plan.any { it.contains("messages_chronology") && it.contains("chronology_ordinal") }, plan.toString())
            assertFalse(plan.any { it.contains("TEMP B-TREE") }, plan.toString())
            val seek = db.query("EXPLAIN $sql", args).filter { it.string("opcode") in listOf("SeekLT", "SeekLE") }
            assertTrue(seek.any { it.string("p4") == "3" }, seek.toString())
            for (scope in listOf("", " AND conversation_id=?")) {
                val repairPlan = db.query("EXPLAIN QUERY PLAN SELECT id,created_at FROM messages INDEXED BY messages_missing_chronology WHERE (chronology_key='' OR chronology_ordinal IS NULL)$scope ORDER BY conversation_id,id LIMIT 128",
                    if (scope.isEmpty()) emptyList() else listOf("c")).map { it.string("detail") }
                assertTrue(repairPlan.any { it.contains("messages_missing_chronology") }, repairPlan.toString())
                assertFalse(repairPlan.any { it.contains("TEMP B-TREE") }, repairPlan.toString())
            }
            val beforeTransactions = tracked.transactions
            assertEquals(300, repo.messages("c").size)
            assertEquals(beforeTransactions, tracked.transactions, "completed metadata must not acquire a writer transaction")
            assertEquals(0, tracked.historyDepths.last(), "full history decoding must stay on the reader path")
            assertTrue(db.query("SELECT id FROM messages WHERE chronology_key='' OR chronology_ordinal IS NULL").isEmpty())
            assertTrue(db.query("SELECT id FROM messages WHERE chronology_ordinal<>rowid").isEmpty())
        }
    }

    private fun seed(db: SqlConnection) {
        Migrations.apply(db)
        db.execute("INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            listOf("s", 1, "agent", "prompt", "model", 1, "[]", "[]", "2026-10-13T00:00:00Z"))
        ConversationRepository(db).create("s", "Fixture", "c")
    }

    private fun downgrade(db: SqlConnection) {
        db.execute("DROP INDEX messages_chronology")
        db.execute("DROP INDEX messages_missing_chronology")
        db.execute("ALTER TABLE messages DROP COLUMN chronology_key")
        db.execute("ALTER TABLE messages DROP COLUMN chronology_ordinal")
        db.execute("UPDATE schema_version SET version=32")
    }

    private fun originals(db: SqlConnection) = db.query("SELECT rowid,id,conversation_id,parent_message_id,role,text,status,created_at,parts_json,metadata_json FROM messages ORDER BY rowid")

    private class TrackingConnection(private val delegate: SqlConnection) : SqlConnection by delegate {
        val batches = mutableListOf<Int>()
        var metadataWrites = 0
        var transactions = 0
        private var depth = 0
        val historyDepths = mutableListOf<Int>()
        override fun query(sql: String, args: List<Any?>): List<SqlRow> = delegate.query(sql, args).also {
            if (sql.startsWith("SELECT id,created_at FROM messages")) batches += it.size
            if (sql.startsWith("SELECT * FROM messages WHERE conversation_id=")) historyDepths += depth
        }
        override fun <T> transaction(block: () -> T): T {
            transactions++
            depth++
            return try { delegate.transaction(block) } finally { depth-- }
        }
        override fun execute(sql: String, args: List<Any?>) {
            if (sql.startsWith("UPDATE messages SET chronology_key=")) metadataWrites++
            delegate.execute(sql, args)
        }
    }
}
