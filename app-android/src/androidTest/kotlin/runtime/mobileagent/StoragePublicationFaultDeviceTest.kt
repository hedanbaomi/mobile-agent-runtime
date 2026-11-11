// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.os.Bundle
import android.os.Process
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.data.KnowledgeRepository
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.knowledge.HashingTextEmbedder
import runtime.mobileagent.knowledge.sha256Hex
import runtime.mobileagent.storage.BundledSqliteConnection
import runtime.mobileagent.storage.CasBlobSink
import runtime.mobileagent.vector.UsearchVectorIndexFactory

/** Explicit synthetic host fixtures only. Never fills an app/emulator/host volume. */
@RunWith(AndroidJUnit4::class)
class StoragePublicationFaultDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val source = "Original committed storage evidence: sapphire otter 2718."
    private val kb = "storage-fault-kb"

    @Test fun boundedFilesystemEnospcPreservesCasAndMetadata() {
        assumeTrue("Requires an explicitly mounted bounded tmpfs fixture", arguments.containsKey("enospcFixturePath"))
        val root = root()
        check(root.isDirectory) { "Host must prepare the unique synthetic fixture parent" }
        val mount = File(requireNotNull(arguments.getString("enospcFixturePath"))).canonicalFile
        check(mount == File(root, "enospc-mount").canonicalFile && mount.isDirectory)
        check(Os.stat(mount.path).st_uid == Process.myUid()) { "Mounted fixture must be app-owned" }
        val capacity = StatFs(mount.path).totalBytes
        check(Os.statvfs(mount.path).f_blocks > 0 && capacity in (1024L * 1024)..(8L * 1024 * 1024))
        val mounts = File("/proc/self/mountinfo").readLines()
        check(mounts.any { line ->
            val fields = line.substringBefore(" - ").split(' ')
            fields.size >= 5 && fields[4] == mount.path && line.substringAfter(" - ").startsWith("tmpfs ")
        }) { "The app process must observe the exact dedicated tmpfs mount" }
        val blobs = CasBlobSink(File(mount, "cas"))
        val original = source.toByteArray()
        val saved = blobs.put(original, "text/plain")
        val candidate = ByteArray(256 * 1024) { ((it * 31 + 17) % 251).toByte() }
        val candidateHash = sha256Hex(candidate)
        // Pre-create only the candidate CAS bucket so failure is a payload write, not mkdir.
        check(File(mount, "cas/" + candidateHash.take(2)).let { it.isDirectory || it.mkdirs() })
        val filler = File(mount, "bounded-pressure.bin")
        var fillFailure: Throwable? = null
        var fillWritten = 0L
        try {
            FileOutputStream(filler).use { output ->
                val block = ByteArray(64 * 1024) { 0x5a }
                try {
                    while (fillWritten <= capacity) { output.write(block); fillWritten += block.size }
                    error("Bounded tmpfs did not exhaust at its advertised capacity")
                } catch (failure: java.io.IOException) { fillFailure = failure }
            }
            assertTrue("Pressure must encounter actual ENOSPC", isEnospc(requireNotNull(fillFailure)))
            assertTrue(StatFs(mount.path).availableBytes < candidate.size)
            BundledSqliteConnection(File(root, "enospc.db").path).use { db ->
                Migrations.apply(db)
                db.execute("INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,1)",
                    listOf(saved.sha256, saved.byteLength, saved.mediaType, saved.localRef))
                val failed = runCatching {
                    db.transaction {
                        val pending = blobs.put(candidate, "application/octet-stream")
                        db.execute("INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,1)",
                            listOf(pending.sha256, pending.byteLength, pending.mediaType, pending.localRef))
                    }
                }.exceptionOrNull()
                assertNotNull("CAS put must not falsely report successful publication", failed)
                assertTrue("CAS failure must be actual filesystem ENOSPC", isEnospc(requireNotNull(failed)))
                assertNull(blobs.get(candidateHash))
                assertEquals(1L, db.query("SELECT count(*) AS n FROM blobs").single().long("n"))
                assertArrayEquals(original, blobs.get(saved.sha256))
                assertTrue("Production CAS finally must clean its failed temporary file",
                    File(mount, "cas").walkTopDown().none { it.isFile && it.name.endsWith(".tmp") })
                val fillerBytesAtEnospc = filler.length()
                check(filler.delete())
                db.transaction {
                    val recovered = blobs.put(candidate, "application/octet-stream")
                    assertEquals(candidateHash, recovered.sha256)
                    db.execute("INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,1)",
                        listOf(recovered.sha256, recovered.byteLength, recovered.mediaType, recovered.localRef))
                }
                assertArrayEquals(original, blobs.get(saved.sha256))
                assertArrayEquals(candidate, blobs.get(candidateHash))
                assertEquals(2L, db.query("SELECT count(*) AS n FROM blobs").single().long("n"))
                assertEquals("ok", db.query("PRAGMA quick_check").single().string("quick_check"))
                assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
                writeJson(File(root, "enospc-evidence.json"), JSONObject().put("status", "PASS")
                    .put("filesystem", "tmpfs").put("capacityBytes", capacity).put("fillerBytes", fillerBytesAtEnospc)
                    .put("fullWriteObserved", true).put("casEnospcObserved", true)
                    .put("originalSha256", saved.sha256).put("recoveredSha256", candidateHash)
                    .put("metadataRowsAfterFailure", 1).put("metadataRowsAfterRecovery", 2)
                    .put("limitation", "CAS on bounded tmpfs; SQLite metadata on normal fixture filesystem; not a SQLite-volume ENOSPC test"))
            }
        } finally { if (filler.exists()) check(filler.delete()) }
    }

    @Test fun prepareIndexAndWaitForHostKill() {
        assumeTrue("Requires explicit host force-stop orchestration", arguments.containsKey("faultFixtureId"))
        val stage = stage()
        val root = root()
        check(!root.exists() && root.mkdir()) { "Use a fresh fixture ID for each boundary" }
        BundledSqliteConnection(File(root, "index.db").path).use { raw ->
            Migrations.apply(raw)
            val blobs = CasBlobSink(File(root, "cas"))
            var armed = false
            var depth = 0
            var candidate: String? = null
            var previous = ""
            val guarded = object : SqlConnection by raw {
                override fun execute(sql: String, args: List<Any?>) {
                    if (armed && sql == "UPDATE knowledge_bases SET active_generation_id = ? WHERE id = ?") {
                        check(candidate == null && args[1] == kb && depth > 0)
                        candidate = args[0].toString()
                        if (stage == "INDEX_BEFORE") checkpoint(root, stage, raw, depth, previous, requireNotNull(candidate))
                    }
                    raw.execute(sql, args)
                }
                override fun <T> transaction(block: () -> T): T {
                    val value = raw.transaction {
                        depth++
                        try { block() } finally { depth-- }
                    }
                    if (armed && stage == "INDEX_AFTER" && depth == 0 && candidate != null) {
                        checkpoint(root, stage, raw, depth, previous, requireNotNull(candidate))
                    }
                    return value
                }
            }
            val repo = repository(guarded, blobs, root)
            try {
                repo.createKnowledgeBase("Synthetic storage publication", kb)
                seed(raw, blobs)
                previous = repo.rebuildIndex(kb)
                assertUsable(repo, blobs, previous)
                check(File(root, "ann").listFiles().orEmpty().any { it.extension == "idx" })
                File(root, "previous-generation").writeText(previous)
                assertNoAuthorityOrReplay(raw)
                armed = true
                repo.rebuildIndex(kb)
                error("The requested exact publication checkpoint was not reached")
            } finally { repo.closeVectorIndexes() }
        }
    }

    @Test fun verifyIndexAfterHostKill() {
        assumeTrue("Requires explicit host force-stop orchestration", arguments.containsKey("faultFixtureId"))
        val root = root()
        val stage = stage()
        val marker = JSONObject(File(root, "checkpoint.json").readText())
        assertEquals(stage, marker.getString("stage"))
        assertNotEquals("Must verify in a fresh process", marker.getInt("pid"), Process.myPid())
        assertEquals(if (stage == "INDEX_BEFORE") 1 else 0, marker.getInt("transactionDepth"))
        assertEquals(Migrations.VERSION, marker.getInt("schemaVersion"))
        assertEquals(1, marker.getInt("candidateMembers"))
        val previous = File(root, "previous-generation").readText()
        assertEquals(previous, marker.getString("previous"))
        val candidate = marker.getString("candidate")
        val expected = if (stage == "INDEX_BEFORE") previous else candidate
        BundledSqliteConnection(File(root, "index.db").path).use { db ->
            assertEquals("ok", db.query("PRAGMA quick_check").single().string("quick_check"))
            assertEquals(expected, active(db))
            val row = db.query("SELECT state,vector_count FROM index_generations WHERE id=?", listOf(expected)).single()
            assertEquals("READY", row.string("state"))
            assertEquals(1L, row.long("vector_count"))
            assertEquals(if (stage == "INDEX_BEFORE") 1 else 2,
                db.query("SELECT id FROM index_generations").size)
            if (stage == "INDEX_BEFORE") assertTrue(db.query("SELECT id FROM index_generations WHERE id=?", listOf(candidate)).isEmpty())
            assertEquals(1L, db.query("SELECT count(*) AS n FROM generation_members WHERE generation_id=?", listOf(expected)).single().long("n"))
            assertTrue(db.query("PRAGMA foreign_key_check").isEmpty())
            assertNoAuthorityOrReplay(db)
            val blobs = CasBlobSink(File(root, "cas"))
            val repo = repository(db, blobs, root)
            val firstStats = try {
                assertUsable(repo, blobs, expected)
                repo.vectorIndexSnapshotStats().also { stats ->
                    if (stage == "INDEX_AFTER") assertEquals("Committed truth must rebuild the missing derived snapshot", 1L, stats.rebuilds)
                    else assertEquals("The old committed snapshot must still load", 1L, stats.loads)
                }
            } finally { repo.closeVectorIndexes() }
            val reopened = repository(db, blobs, root)
            try {
                assertUsable(reopened, blobs, expected)
                assertEquals("Recovered real JNI snapshot must reload", 1L, reopened.vectorIndexSnapshotStats().loads)
            } finally { reopened.closeVectorIndexes() }
            assertEquals("Index recovery must not mutate committed generation truth", expected, active(db))
            assertNoAuthorityOrReplay(db)
            writeJson(File(root, "index-evidence.json"), JSONObject().put("status", "PASS").put("stage", stage)
                .put("killedPid", marker.getInt("pid")).put("verificationPid", Process.myPid())
                .put("previous", previous).put("candidate", candidate).put("active", expected)
                .put("snapshotLoads", firstStats.loads).put("snapshotRebuilds", firstStats.rebuilds)
                .put("casAndCitationPreserved", true).put("authorityAndReplayRows", 0)
                .put("limitation", "Synthetic local hashing vectors with production SQLite/CAS/repository and USearch JNI; no provider, real user data, platform trust, OEM or physical device acceptance"))
        }
    }

    private fun checkpoint(root: File, stage: String, db: SqlConnection, depth: Int, previous: String, candidate: String): Nothing {
        check(previous != candidate && previous.isNotBlank())
        val before = stage == "INDEX_BEFORE"
        assertEquals(if (before) 1 else 0, depth)
        assertEquals(if (before) previous else candidate, active(db))
        val ready = db.query("SELECT state,vector_count FROM index_generations WHERE id=?", listOf(candidate)).single()
        assertEquals("READY", ready.string("state"))
        assertEquals(1L, ready.long("vector_count"))
        val members = db.query("SELECT count(*) AS n FROM generation_members WHERE generation_id=?", listOf(candidate)).single().long("n")
        assertEquals(1L, members)
        assertNoAuthorityOrReplay(db)
        val version = db.query("SELECT version FROM schema_version").single().long("version")
        assertEquals(Migrations.VERSION.toLong(), version)
        val boundary = if (before) "ready_before_pointer_write_uncommitted" else "pointer_transaction_committed"
        writeJson(File(root, "checkpoint.json"), JSONObject().put("stage", stage).put("pid", Process.myPid())
            .put("boundary", boundary).put("transactionDepth", depth).put("schemaVersion", version)
            .put("candidateMembers", members).put("previous", previous).put("candidate", candidate).put("active", active(db)))
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "MAR_STORAGE_CHECKPOINT stage=$stage boundary=$boundary schemaVersion=$version transactionDepth=$depth candidateMembers=$members pid=${Process.myPid()} previous=$previous candidate=$candidate fixture=${root.name}\n")
        })
        // A signalled checkpoint, not a timing guess: only host force-stop may end this wait.
        check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Host did not force-stop the exact checkpoint process" }
        error("Unreachable checkpoint release")
    }

    private fun repository(db: SqlConnection, blobs: CasBlobSink, root: File) = KnowledgeRepository(db, blobs,
        HashingTextEmbedder(), vectorIndexFactory = UsearchVectorIndexFactory(),
        vectorIndexDirectory = File(root, "ann"), automaticStorageMaintenance = false)

    private fun seed(db: SqlConnection, blobs: CasBlobSink) {
        val stored = blobs.put(source.toByteArray(), "text/plain")
        db.transaction {
            db.execute("INSERT INTO blobs(hash,byte_length,media_type,local_ref,ref_count) VALUES(?,?,?,?,1)",
                listOf(stored.sha256, stored.byteLength, stored.mediaType, stored.localRef))
            db.execute("INSERT INTO documents(id,kb_id,blob_hash,display_name,format,active_version_id,deleted_at) VALUES(?,?,?,?,?,NULL,NULL)",
                listOf("storage-doc", kb, stored.sha256, "synthetic.txt", "TEXT"))
            db.execute("INSERT INTO document_versions(id,document_id,parser_fingerprint,content_hash,status,created_at) VALUES(?,?,?,?,?,?)",
                listOf("storage-version", "storage-doc", "synthetic-storage-v1", stored.sha256, "READY", "2026-11-11T00:00:00Z"))
            db.execute("INSERT INTO chunks(id,document_version_id,ordinal,text,content_hash,source_span,asset_ids,page,text_utf16_length) VALUES(?,?,0,?,?,?,'',NULL,?)",
                listOf("storage-chunk", "storage-version", source, stored.sha256, "synthetic-source:0", source.length))
            db.execute("UPDATE documents SET active_version_id=? WHERE id=?", listOf("storage-version", "storage-doc"))
        }
    }

    private fun assertUsable(repo: KnowledgeRepository, blobs: CasBlobSink, generation: String) {
        assertArrayEquals(source.toByteArray(), blobs.get(sha256Hex(source.toByteArray())))
        val citation = Citation("0", "storage-prior-run", kb, "storage-doc", "storage-chunk",
            documentVersionId = "storage-version", sourceSpan = "synthetic-source:0")
        assertFalse(repo.locateCitation(citation).removed)
        assertArrayEquals(source.toByteArray(), requireNotNull(repo.evidenceBytes(citation)).second)
        val found = repo.retrieve("storage-restart-query", source, 1, listOf(kb))
        assertFalse(requireNotNull(found.coverage).partial)
        assertEquals(listOf("storage-chunk"), found.hits.map { it.chunkId })
        assertEquals(generation, found.usedGenerations.single().generationId)
        assertEquals(listOf("storage-version"), found.citations.map { it.documentVersionId })
    }

    private fun assertNoAuthorityOrReplay(db: SqlConnection) {
        for (table in listOf("capability_grants", "permission_grants", "full_device_files_grants", "snapshot_grant_bindings",
            "desktop_trust", "consent_tickets", "tool_invocations", "skill_invocations", "embedding_operations")) {
            assertEquals("Synthetic index recovery must not create authority or replay: $table", 0L,
                db.query("SELECT count(*) AS n FROM $table").single().long("n"))
        }
    }

    private fun active(db: SqlConnection) = db.query("SELECT active_generation_id FROM knowledge_bases WHERE id=?", listOf(kb))
        .single().string("active_generation_id")

    private fun root(): File {
        val id = requireNotNull(arguments.getString("faultFixtureId"))
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        val parent = instrumentation.targetContext.filesDir.canonicalFile
        return File(parent, "storage-publication-$id").canonicalFile.also { check(it.parentFile == parent) }
    }

    private fun stage(): String = requireNotNull(arguments.getString("faultStage")).also {
        require(it == "INDEX_BEFORE" || it == "INDEX_AFTER")
    }

    private fun isEnospc(failure: Throwable): Boolean = generateSequence(failure) { it.cause }.take(12).any {
        (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message.orEmpty().contains("ENOSPC") ||
            it.message.orEmpty().contains("No space left on device", ignoreCase = true)
    }

    private fun writeJson(file: File, value: JSONObject) {
        FileOutputStream(file).use { output -> output.write((value.toString(2) + "\n").toByteArray()); output.fd.sync() }
    }
}
