// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.MessageRole

class MessageChronologyCompatibilityTest {
    @Test fun offsetsNanosecondsAndExistingInstantRangeRemainStable() {
        assertEquals(MessageChronology.key("2026-10-13T00:00:00.100Z"), MessageChronology.key("2026-10-13T08:00:00.1+08:00"))
        assertEquals(MessageChronology.key("2026-10-13T00:00:00.100Z"), MessageChronology.key("2026-10-12T19:00:00.100-05:00"))
        assertTrue(MessageChronology.key("2026-10-13T08:00:00.100000001+08:00") > MessageChronology.key("2026-10-13T00:00:00.100Z"))
        for (raw in listOf(Instant.MIN.toString(), Instant.MAX.toString(), "2016-12-31T23:59:60Z"))
            assertNotEquals("1", MessageChronology.key(raw))
        for (raw in listOf("garbage", "2026-10-13T08:00+08:00", "2026-02-30T00:00:00+08:00", "2026-10-13T08:00:00"))
            assertEquals("1", MessageChronology.key(raw))
    }

    @Test fun migrationRepairsMultipleBatchesWithoutChangingSourcesOrOrdinalsAndDoesNotRepeat() {
        JdbcSqlConnection().use { db ->
            seed(db)
            val before = original(db)
            var writes = 0
            val batches = mutableListOf<Int>()
            val tracked = object : SqlConnection by db {
                override fun query(sql: String, args: List<Any?>): List<SqlRow> = db.query(sql, args).also {
                    if (sql.startsWith("SELECT rowid AS sequence,id,created_at FROM messages")) batches += it.size
                }
                override fun execute(sql: String, args: List<Any?>) {
                    if (sql == "UPDATE messages SET chronology_key=? WHERE id=? AND chronology_key='1'") writes++
                    db.execute(sql, args)
                }
            }
            Migrations.apply(tracked)
            assertEquals(listOf(128, 128, 5, 0), batches)
            assertEquals(130, writes)
            assertEquals(before, original(db))
            assertEquals(131, db.query("SELECT id FROM messages WHERE chronology_key='1'").size)
            val keys = db.query("SELECT chronology_key FROM messages WHERE id LIKE 'offset-%'").map { it.string("chronology_key") }.toSet()
            assertEquals(setOf(MessageChronology.key("2026-10-13T00:00:00.100Z")), keys)
            writes = 0; batches.clear()
            Migrations.apply(tracked)
            assertEquals(0, writes)
            assertTrue(batches.isEmpty())
        }
    }

    @Test fun repairFailureRollsBackVersionAndAllDerivedKeys() {
        JdbcSqlConnection().use { db ->
            seed(db)
            val before = original(db)
            val failing = object : SqlConnection by db {
                override fun execute(sql: String, args: List<Any?>) {
                    db.execute(sql, args)
                    if (sql == "UPDATE messages SET chronology_key=? WHERE id=? AND chronology_key='1'") error("Injected repair failure")
                }
            }
            assertThrows(IllegalStateException::class.java) { Migrations.apply(failing) }
            assertEquals(37L, db.query("SELECT version FROM schema_version").single().long("version"))
            assertEquals(261, db.query("SELECT id FROM messages WHERE chronology_key='1'").size)
            assertEquals(before, original(db))
            Migrations.apply(db)
            assertEquals(131, db.query("SELECT id FROM messages WHERE chronology_key='1'").size)
        }
    }

    private fun seed(db: SqlConnection) {
        Migrations.apply(db)
        db.execute("INSERT INTO agent_snapshots(id,schema_version,agent_id,prompt_revision_id,chat_model_id,provider_revision,knowledge_base_ids,skill_ids,created_at) VALUES('s',1,'a','p','m',1,'[]','[]','2026-10-13T00:00:00Z')")
        val conversations = ConversationRepository(db)
        conversations.create("s", "History", "c")
        // More than a full batch of invalid values must still advance the cursor.
        repeat(131) { conversations.append("c", MessageRole.USER, "invalid $it", messageId = "invalid-$it", createdAt = "legacy-invalid-$it") }
        repeat(130) { conversations.append("c", MessageRole.USER, "offset $it", messageId = "offset-$it", createdAt = "2026-10-13T08:00:00.1+08:00") }
        db.execute("UPDATE messages SET chronology_key='1'")
        db.execute("UPDATE schema_version SET version=37")
    }

    private fun original(db: SqlConnection) = db.query("SELECT rowid,id,created_at,text,parts_json,metadata_json,chronology_ordinal FROM messages ORDER BY rowid")
}
