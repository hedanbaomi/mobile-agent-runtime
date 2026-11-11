// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.*
import runtime.mobileagent.domain.RunStatus
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.knowledge.FileBlobSink
import runtime.mobileagent.storage.BundledSqliteConnection

/**
 * Explicit host-driven tests, never a one-process simulation of process death.
 * The host waits for a durable checkpoint, kills this app, proves the PID gone,
 * then launches verifyAfterHostKill with the same fixture ID and stage.
 * All data is synthetic and stays under a unique private fixture directory.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryFaultMatrixDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val stages = setOf("MIGRATION_BEFORE", "MIGRATION_DURING", "MIGRATION_AFTER",
        "RESTORE_BEFORE", "RESTORE_DURING", "RESTORE_AFTER", "IN_FLIGHT_RECOVERY")

    @Test fun prepareAndWaitForHostKill() {
        assumeTrue("Requires the explicit host kill orchestrator", arguments.containsKey("faultFixtureId"))
        val stage = stage()
        val root = root()
        check(!root.exists() && root.mkdir()) { "A new fixture ID is required" }
        val path = File(root, "fixture.db")
        if (stage.startsWith("MIGRATION_")) {
            path.writeBytes(asset("v1.4.0.db"))
            BundledSqliteConnection(path.absolutePath).use { db ->
                if (stage == "MIGRATION_BEFORE") checkpointAndWait(root, stage, db)
                val wrapped = checkpointConnection(db, root, stage, migration = true)
                Migrations.apply(wrapped)
                check(stage != "MIGRATION_DURING") { "Required in-transaction migration checkpoint was not reached" }
                assertReleasedDatabase(db)
                checkpointAndWait(root, stage, db)
            }
        } else {
            BundledSqliteConnection(path.absolutePath).use { db ->
                Migrations.apply(db)
                if (stage == "RESTORE_BEFORE") checkpointAndWait(root, stage, db)
                val wrapped = checkpointConnection(db, root, stage, migration = false)
                restore(wrapped, root)
                check(stage != "RESTORE_DURING") { "Required in-transaction restore checkpoint was not reached" }
                assertArchive(db, root)
                if (stage == "IN_FLIGHT_RECOVERY") {
                    val runs = RunRepository(db)
                    runs.create(requireNotNull(runs.get("archive-run")).copy(
                        runId = "fault-running", state = RunStatus.MODEL_STREAMING, inputTokens = 13))
                    db.execute("INSERT INTO tool_invocations(invocation_id,run_id,call_id,name,arguments_json,permission_decision,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                        listOf("fault-invocation", "fault-running", "fault-call", "synthetic_tool", "{}", "APPROVED", "RUNNING",
                            "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z"))
                }
                checkpointAndWait(root, stage, db)
            }
        }
    }

    @Test fun verifyAfterHostKill() {
        assumeTrue("Requires the explicit host kill orchestrator", arguments.containsKey("faultFixtureId"))
        val stage = stage()
        val root = root()
        check(root.isDirectory && File(root, "fixture.db").isFile)
        val checkpoint = File(root, "checkpoint").readLines()
        assertEquals(stage, checkpoint[0])
        assertNotEquals("Verification must run in a fresh process", checkpoint[1].toInt(), Process.myPid())
        assertEquals(boundary(stage), checkpoint[2])
        val transactionDepth = checkpoint[4].toInt()
        assertEquals(stage.endsWith("_DURING"), transactionDepth > 0)
        BundledSqliteConnection(File(root, "fixture.db").absolutePath).use { db ->
            assertEquals("ok", db.query("PRAGMA quick_check").single().string("quick_check"))
            if (stage.startsWith("MIGRATION_")) {
                val expected = if (stage == "MIGRATION_AFTER") Migrations.VERSION.toLong() else 39L
                assertEquals("Interrupted migration must roll back its schema version", expected,
                    db.query("SELECT version FROM schema_version").single().long("version"))
                if (stage != "MIGRATION_AFTER") {
                    assertTrue("New receipts DDL must not survive interrupted migration",
                        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='root_dispatch_receipts'").isEmpty())
                }
                Migrations.apply(db)
                assertReleasedDatabase(db)
            } else {
                Migrations.apply(db)
                if (stage == "RESTORE_BEFORE" || stage == "RESTORE_DURING") {
                    assertNull("No partial Agent may publish", AgentRepository(db).get("archive-agent"))
                    assertNull(ConversationRepository(db).get("archive-conversation"))
                    assertNull(RunRepository(db).get("archive-run"))
                    assertTrue(db.query("SELECT id FROM messages").isEmpty())
                    restore(db, root)
                }
                if (stage == "IN_FLIGHT_RECOVERY") assertInterruptedRunRecovery(db)
                assertArchive(db, root)
            }
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
        }
        // Keep the DB, WAL/CAS and checkpoint for host evidence; never clear app data.
    }

    /** Real SQLite allocation failure; max_page_count constrains only this test DB. */
    @Test fun boundedSqliteFullPreservesCommittedArchiveAndRollsBackTheWrite() {
        val id = UUID.randomUUID().toString()
        val root = fixtureRoot(id)
        check(root.mkdir())
        val path = File(root, "fixture.db")
        BundledSqliteConnection(path.absolutePath).use { db ->
            Migrations.apply(db)
            restore(db, root)
            db.execute("CREATE TABLE fault_pressure(id INTEGER PRIMARY KEY, payload BLOB NOT NULL)")
            db.transaction {
                val pages = db.query("PRAGMA page_count").single().long("page_count")
                val actual = db.query("PRAGMA max_page_count=${pages + 8}").single().long("max_page_count")
                assertEquals(pages + 8, actual)
            }
            val failure = runCatching {
                db.transaction {
                    db.execute("UPDATE messages SET text='must roll back' WHERE id='archive-message'")
                    // One bounded allocation exceeds the DB page cap, without filling a volume.
                    db.execute("INSERT INTO fault_pressure(payload) VALUES(zeroblob(8388608))")
                }
            }.exceptionOrNull()
            assertNotNull("The real SQLite driver must reject allocation", failure)
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            assertTrue("Expected SQLite SQLITE_FULL, received: $messages",
                messages.contains("SQLITE_FULL", true) || messages.contains("database or disk is full", true))
        }
        // SQLITE_FULL can auto-abort SQLite's transaction; reopen even if rollback poisoned the old handle.
        BundledSqliteConnection(path.absolutePath).use { db ->
            Migrations.apply(db)
            assertTrue(db.query("SELECT id FROM fault_pressure").isEmpty())
            assertArchive(db, root)
            db.transaction { db.query("PRAGMA max_page_count=1073741823") }
            db.execute("INSERT INTO fault_pressure(payload) VALUES(zeroblob(8192))")
            assertEquals(1, db.query("SELECT id FROM fault_pressure").size)
            assertEquals("ok", db.query("PRAGMA quick_check").single().string("quick_check"))
        }
        assertTrue("Fault injection must remain bounded", root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 32L * 1024 * 1024)
    }

    private fun checkpointConnection(db: BundledSqliteConnection, root: File, stage: String, migration: Boolean): SqlConnection {
        var messageWrites = 0
        var transactionDepth = 0
        return object : SqlConnection by db {
            override fun <T> transaction(block: () -> T): T = db.transaction {
                transactionDepth++
                try { block() } finally { transactionDepth-- }
            }

            override fun execute(sql: String, args: List<Any?>) {
                db.execute(sql, args)
                if (sql.startsWith("INSERT INTO messages")) messageWrites++
                if (migration && stage == "MIGRATION_DURING" && sql.contains("root_dispatch_receipts") &&
                    db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='root_dispatch_receipts'").isNotEmpty()) {
                    assertEquals(39L, db.query("SELECT version FROM schema_version").single().long("version"))
                    check(transactionDepth > 0) { "Migration checkpoint must be inside the delegated SQL transaction" }
                    checkpointAndWait(root, stage, db, transactionDepth)
                }
                if (!migration && stage == "RESTORE_DURING" && messageWrites == 2) {
                    check(transactionDepth > 0) { "Restore checkpoint must be inside the delegated SQL transaction" }
                    assertEquals(2, db.query("SELECT id FROM messages").size)
                    checkpointAndWait(root, stage, db, transactionDepth)
                }
            }
        }
    }

    private fun checkpointAndWait(root: File, stage: String, db: SqlConnection, transactionDepth: Int = 0): Nothing {
        check(stage.endsWith("_DURING") == (transactionDepth > 0)) { "Checkpoint boundary does not match actual transaction depth" }
        val version = db.query("SELECT version FROM schema_version").single().long("version")
        val readyRows = when {
            stage.startsWith("MIGRATION_") -> db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='root_dispatch_receipts'").size
            stage == "IN_FLIGHT_RECOVERY" -> db.query("SELECT run_id FROM runs WHERE run_id='fault-running' AND state='MODEL_STREAMING'").size
            else -> db.query("SELECT id FROM messages").size
        }
        val expectedRows = when (stage) {
            "MIGRATION_BEFORE", "RESTORE_BEFORE" -> 0
            "RESTORE_DURING", "RESTORE_AFTER" -> 2
            else -> 1
        }
        assertEquals("Required row/table readiness was not observed", expectedRows, readyRows)
        if (stage == "MIGRATION_BEFORE" || stage == "MIGRATION_DURING") assertEquals(39L, version)
        else assertEquals(Migrations.VERSION.toLong(), version)
        val boundary = boundary(stage)
        val text = "$stage\n${Process.myPid()}\n$boundary\n$version\n$transactionDepth\n$readyRows\n"
        FileOutputStream(File(root, "checkpoint")).use { output ->
            output.write(text.toByteArray()); output.fd.sync()
        }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "MAR_FAULT_CHECKPOINT stage=$stage boundary=$boundary schemaVersion=$version transactionDepth=$transactionDepth readyRows=$readyRows pid=${Process.myPid()} fixture=${root.name}\n")
        })
        // Host force-stop must happen while handles/transactions remain open.
        Thread.sleep(120_000)
        error("Host did not kill the checkpoint process within 120 seconds")
    }

    private fun boundary(stage: String): String = when (stage) {
        "MIGRATION_BEFORE" -> "before_migration"
        "MIGRATION_DURING" -> "receipts_created_uncommitted"
        "MIGRATION_AFTER" -> "migration_committed"
        "RESTORE_BEFORE" -> "before_restore"
        "RESTORE_DURING" -> "two_messages_uncommitted"
        "RESTORE_AFTER" -> "restore_committed"
        "IN_FLIGHT_RECOVERY" -> "in_flight_committed"
        else -> error("Unsupported checkpoint stage")
    }

    private fun assertInterruptedRunRecovery(db: BundledSqliteConnection) {
        val runs = RunRepository(db)
        assertEquals(RunStatus.MODEL_STREAMING, runs.get("fault-running")?.state)
        assertEquals(listOf("fault-running"), runs.recoverInterruptedRuns())
        val recovered = requireNotNull(runs.get("fault-running"))
        assertEquals(RunStatus.UNKNOWN_OUTCOME, recovered.state)
        assertEquals(13, recovered.inputTokens)
        assertNull(recovered.retryAcknowledgedAt)
        assertEquals("UNKNOWN_OUTCOME", runs.invocations("fault-running").single().state)
        assertTrue(runs.recoverInterruptedRuns().isEmpty())
        assertEquals(1, runs.invocations("fault-running").size)
        assertNotNull(runCatching { runs.save(recovered.copy(state = RunStatus.COMPLETED)) }.exceptionOrNull())
    }

    private fun assertReleasedDatabase(db: BundledSqliteConnection) {
        assertEquals("保留旧版消息 / preserve old message", ConversationRepository(db).message("fixture-message")?.text)
        assertEquals("fixture-snapshot", ConversationRepository(db).get("fixture-conversation")?.snapshotId)
        val run = requireNotNull(RunRepository(db).get("fixture-run"))
        assertEquals(RunStatus.UNKNOWN_OUTCOME, run.state)
        assertEquals(37, run.inputTokens)
        assertNull(run.retryAcknowledgedAt)
        assertNotNull(runCatching { RunRepository(db).save(run.copy(state = RunStatus.COMPLETED)) }.exceptionOrNull())
        assertTrue(db.query("SELECT text FROM chunks").any { it.string("text").contains("旧版知识证据") })
        assertTrue(CapabilityGrantRepository(db).forAgent("fixture-agent", includeRevoked = true).isEmpty())
    }

    private fun assertArchive(db: BundledSqliteConnection, root: File) {
        val conversations = ConversationRepository(db)
        assertEquals("保留旧版归档消息", conversations.message("archive-message")?.text)
        assertEquals("archive-snapshot", conversations.get("archive-conversation")?.snapshotId)
        val runs = RunRepository(db)
        val run = requireNotNull(runs.get("archive-run"))
        assertEquals(RunStatus.UNKNOWN_OUTCOME, run.state)
        assertEquals(37, run.inputTokens)
        assertNull(run.retryAcknowledgedAt)
        assertEquals("UNKNOWN_OUTCOME", runs.invocations("archive-run").single().state)
        assertNotNull(runCatching { runs.save(run.copy(state = RunStatus.COMPLETED)) }.exceptionOrNull())
        assertTrue(CapabilityGrantRepository(db).forAgent("archive-agent", includeRevoked = true).isEmpty())
        assertTrue(requireNotNull(ProfileRepository(db).getProvider("archive-provider")).secretRef.isNullOrBlank())
        val citations = Json.parseToJsonElement(requireNotNull(conversations.message("archive-answer")).metadataJson)
            .jsonObject.getValue("citations").jsonArray
        assertTrue(citations.isNotEmpty())
        val knowledge = KnowledgeRepository(db, FileBlobSink(File(root, "cas")))
        try {
            citations.forEach { value ->
                val fields = value.jsonObject
                fun text(key: String) = fields[key]?.jsonPrimitive?.contentOrNull
                val citation = Citation(citationId = requireNotNull(text("citationId")), runId = "archive-run",
                    knowledgeBaseId = requireNotNull(text("knowledgeBaseId")), documentId = requireNotNull(text("documentId")),
                    chunkId = requireNotNull(text("chunkId")), documentVersionId = requireNotNull(text("documentVersionId")),
                    page = fields["page"]?.jsonPrimitive?.intOrNull, assetId = null, sourceSpan = text("sourceSpan"))
                assertFalse(knowledge.locateCitation(citation).removed)
                assertArrayEquals("旧版归档证据 archive source evidence".toByteArray(), knowledge.evidenceBytes(citation)?.second)
            }
        } finally { knowledge.closeVectorIndexes() }
    }

    private fun restore(db: SqlConnection, root: File) {
        assertEquals("archive-agent", TransferRepository(db, blobSink = FileBlobSink(File(root, "cas")))
            .importArchive(asset("v1.4.0.zip")).agentId)
    }

    private fun asset(name: String): ByteArray {
        val expected = when (name) {
            "v1.4.0.db" -> "ac227be35cb55ec11cd892a826a1207d9cdc03815f766ebb295beba2f3780749"
            "v1.4.0.zip" -> "3100689b32f8972cc9e3d778e407f5d81229f3bc8669d29a1976894014d957d2"
            else -> error("Unknown frozen fixture")
        }
        val bytes = instrumentation.context.assets.open("fault-recovery/$name").use { it.readBytes() }
        assertEquals(expected, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return bytes
    }

    private fun stage(): String = requireNotNull(arguments.getString("faultStage")).also { require(it in stages) }
    private fun root(): File = fixtureRoot(requireNotNull(arguments.getString("faultFixtureId")))
    private fun fixtureRoot(id: String): File {
        require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id))
        val parent = instrumentation.targetContext.filesDir.canonicalFile
        return File(parent, "fault-recovery-$id").canonicalFile.also { check(it.parentFile == parent) }
    }
}
