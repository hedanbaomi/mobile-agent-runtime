// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.data

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.RunStatus

class ReleasedDatabaseUpgradeTest {
    @Test fun interruptedUpgradeRollsBackVersionAndPreservesReleasedData() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/release-fixtures/v1.4.0.db")).use { it.readBytes() }
        val path = Files.createTempFile("released-failed-upgrade", ".db")
        try {
            Files.write(path, bytes)
            JdbcSqlConnection("jdbc:sqlite:$path").use { db ->
                var writes = 0
                val broken = object : SqlConnection by db {
                    override fun execute(sql: String, args: List<Any?>) {
                        if (++writes == 30) throw java.io.IOException("synthetic disk write failure")
                        db.execute(sql, args)
                    }
                }
                assertThrows(java.io.IOException::class.java) { Migrations.apply(broken) }
                assertEquals(39L, db.query("SELECT version FROM schema_version").single().long("version"))
                assertEquals("保留旧版消息 / preserve old message", db.query("SELECT text FROM messages WHERE id='fixture-message'").single().string("text"))
                Migrations.apply(db)
                assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
                assertEquals(RunStatus.UNKNOWN_OUTCOME, RunRepository(db).get("fixture-run")?.state)
            }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun physicalReleasedSchemaUpgradesReopensAndKeepsUnknownGateAndContent() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/release-fixtures/v1.4.0.db")).use { it.readBytes() }
        val expectedHash = checkNotNull(javaClass.getResourceAsStream("/release-fixtures/v1.4.0.sha256")).bufferedReader().use { it.readText().trim() }
        assertEquals(expectedHash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val path = Files.createTempFile("released-upgrade", ".db")
        try {
            Files.write(path, bytes)
            JdbcSqlConnection("jdbc:sqlite:$path").use { db ->
                assertEquals(39L, db.query("SELECT version FROM schema_version").single().long("version"))
                assertTrue(db.query("SELECT name FROM sqlite_master WHERE name='root_dispatch_journal'").isEmpty())
                Migrations.apply(db)
                assertEquals(Migrations.VERSION.toLong(), db.query("SELECT version FROM schema_version").single().long("version"))
            }
            JdbcSqlConnection("jdbc:sqlite:$path").use { db ->
                Migrations.apply(db)
                assertEquals("保留旧版消息 / preserve old message", ConversationRepository(db).message("fixture-message")?.text)
                assertEquals("fixture-snapshot", ConversationRepository(db).get("fixture-conversation")?.snapshotId)
                val run = RunRepository(db).get("fixture-run")!!
                assertEquals(RunStatus.UNKNOWN_OUTCOME, run.state)
                assertEquals(37, run.inputTokens)
                assertNull(run.retryAcknowledgedAt)
                assertThrows(Exception::class.java) { RunRepository(db).save(run.copy(state = RunStatus.COMPLETED)) }
                assertTrue(db.query("SELECT text FROM chunks").any { it.string("text").contains("旧版知识证据") })
                assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
            }
        } finally { Files.deleteIfExists(path) }
    }
}
