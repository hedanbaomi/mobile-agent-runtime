// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.data.Migrations
import runtime.mobileagent.data.SqlConnection
import runtime.mobileagent.data.SqlRow
import runtime.mobileagent.data.WorldBookRepository
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy
import runtime.mobileagent.domain.worldbook.WorldBookFileSkip

/**
 * Staging and confirming a batch against the real repository: files, folders and ZIP archives,
 * preview-to-confirm binding, partial failures and the "nothing written before confirm" rule.
 */
class WorldBookBatchImportTest {
    @TempDir lateinit var cache: File

    private fun book(name: String, content: String = "3d6") =
        """{"name":"$name","entries":{"0":{"uid":0,"key":["火球术"],"content":"$content"}}}""".toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    private class Sources(val files: Map<String, ByteArray?>, val names: Map<String, String> = emptyMap()) {
        val opened = ArrayList<String>()
        fun open(key: String): InputStream? {
            opened += key
            if (!files.containsKey(key)) throw IOException("gone")
            return files[key]?.let(::ByteArrayInputStream)
        }
    }

    private fun stager(sources: Sources, checkpoint: () -> Unit = {}) =
        WorldBookBatchStager(sources::open, { sources.names[it] ?: it }, cache, chinese = true, checkpoint = checkpoint, progress = {})

    @Test
    fun filesAndArchivesStageOnlyJsonAndReportEverythingElse() {
        val sources = Sources(mapOf(
            "PHB.json" to book("PHB"),
            "rules.zip" to zip("MM.json" to book("MM"), "DMG.json" to book("DMG"), "cover.jpg" to byteArrayOf(1)),
            "noext" to book("SpC"),
            "card.png" to byteArrayOf(1),
            "notes.txt" to "hello".toByteArray(),
            "pack.7z" to byteArrayOf(1),
            "missing.json" to null,
        ))
        val staging = stager(sources).stageFiles(sources.files.keys.toList())
        try {
            assertEquals(listOf("PHB.json", "rules.zip/MM.json", "rules.zip/DMG.json", "noext"), staging.files.map { it.sourcePath })
            assertEquals(listOf("PHB", "MM", "DMG", "noext"), staging.files.map { it.fallbackName })
            assertEquals(
                mapOf(
                    "rules.zip/cover.jpg" to WorldBookFileSkip.IMAGE,
                    "card.png" to WorldBookFileSkip.IMAGE,
                    "notes.txt" to WorldBookFileSkip.UNSUPPORTED_TYPE,
                    "pack.7z" to WorldBookFileSkip.UNSUPPORTED_ARCHIVE,
                    "missing.json" to WorldBookFileSkip.UNREADABLE,
                ),
                staging.skips.associate { it.sourcePath to it.reason },
            )
            assertFalse("card.png" in sources.opened, "image files are not even opened")
            assertTrue(staging.files.all { it.file.isFile && it.file.parentFile == staging.directory })
        } finally {
            staging.discard()
        }
        assertFalse(staging.directory.exists())
    }

    @Test
    fun aFolderIsWalkedWithItsRelativeShapeAndMetadataIsIgnored() {
        val root = Dir("worlds", listOf(
            Leaf("PHB.json"), Leaf(".DS_Store"), Leaf("readme.md"),
            Dir("coc", listOf(Leaf("神话典籍.json"), Leaf("modules.zip"))),
        ))
        val sources = Sources(mapOf(
            "worlds/PHB.json" to book("PHB"), "worlds/coc/神话典籍.json" to book("神话典籍"),
            "worlds/coc/modules.zip" to zip("调查员手册.json" to book("调查员手册")),
        ))
        val staging = stager(sources).stageFolder(node(root))
        try {
            assertEquals("worlds", staging.label)
            assertEquals(listOf("coc/modules.zip/调查员手册.json", "coc/神话典籍.json", "PHB.json"), staging.files.map { it.sourcePath })
            assertEquals(listOf("readme.md" to WorldBookFileSkip.UNSUPPORTED_TYPE), staging.skips.map { it.sourcePath to it.reason })
        } finally {
            staging.discard()
        }
    }

    @Test
    fun anOversizedSelectionFailsWithoutLeavingStagedFiles() {
        val members = (1..WorldBookBatchStager.MAX_CANDIDATES + 1).map { "b$it.json" to book("b$it") }
        val sources = Sources(mapOf("first.json" to book("first"), "many.zip" to zip(*members.toTypedArray())))
        val error = assertThrows(WorldBookBatchException::class.java) { stager(sources).stageFiles(listOf("first.json", "many.zip")) }
        assertTrue(error.message!!.contains("一次最多导入 ${WorldBookBatchStager.MAX_CANDIDATES} 个"))
        assertTrue(WorldBookBatchStager.stagingRoot(cache).listFiles().orEmpty().isEmpty())
        assertThrows(WorldBookBatchException::class.java) {
            stager(sources).stageFiles((1..WorldBookBatchStager.MAX_SELECTED + 1).map { "x$it" })
        }
    }

    @Test
    fun cancellationDuringStagingDiscardsTheBatch() {
        val sources = Sources(mapOf("a.json" to book("a"), "b.json" to book("b")))
        var calls = 0
        assertThrows(CancellationException::class.java) {
            stager(sources) { if (++calls > 3) throw CancellationException("stop") }.stageFiles(listOf("a.json", "b.json"))
        }
        assertTrue(WorldBookBatchStager.stagingRoot(cache).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun previewWritesNothingAndConfirmWritesEachSelectedBook() = database { db ->
        val books = WorldBookRepository(db)
        books.save(runtime.mobileagent.serialization.worldbook.WorldBookCodec.decode(String(book("PHB", "old")), "x").document, "phb")
        val sources = Sources(mapOf(
            "PHB.json" to book("PHB", "new"), "MM.json" to book("MM"), "MM copy.json" to book("MM"),
            "report.json" to """{"ok":true}""".toByteArray(),
        ))
        val session = WorldBookImportSession.prepare(stager(sources).stageFiles(sources.files.keys.toList()), books, true, {}, {})
        assertEquals(1, books.list().size, "preview must not write")
        assertEquals(1, session.plan.conflicts)
        session.replan(WorldBookConflictPolicy.REPLACE)
        assertEquals(
            listOf(WorldBookBatchAction.CREATE, WorldBookBatchAction.SKIP_DUPLICATE_IN_BATCH, WorldBookBatchAction.REPLACE, WorldBookBatchAction.NOT_WORLD_BOOK),
            session.plan.rows.map { it.action },
        )
        val outcome = session.write(books, true, { false }, {})
        assertEquals(2, outcome.stored.size)
        assertEquals(1, outcome.replaced)
        assertTrue(outcome.failed.isEmpty())
        assertEquals(listOf("MM", "PHB"), books.list().map { it.name })
        assertEquals("new", books.get("phb")!!.document.entries.single().content)
        assertTrue(outcome.summary(true).contains("新建 1 本，覆盖 1 本"))
        session.discard()
    }

    @Test
    fun aStagedFileChangedAfterPreviewFailsOnlyThatBook() = database { db ->
        val books = WorldBookRepository(db)
        val sources = Sources(mapOf("A.json" to book("A"), "B.json" to book("B")))
        val session = WorldBookImportSession.prepare(stager(sources).stageFiles(listOf("A.json", "B.json")), books, false, {}, {})
        session.staging.files.first { it.sourcePath == "A.json" }.file.writeBytes(book("A", "tampered"))
        val outcome = session.write(books, false, { false }, {})
        assertEquals(listOf("B"), books.list().map { it.name })
        assertEquals("A.json", outcome.failed.single().first.candidate.sourcePath)
        assertTrue(outcome.failures()!!.contains("changed after preview"))
        session.discard()
    }

    @Test
    fun stopIsHonouredBetweenBooks() = database { db ->
        val books = WorldBookRepository(db)
        val sources = Sources(mapOf("A.json" to book("A"), "B.json" to book("B"), "C.json" to book("C")))
        val session = WorldBookImportSession.prepare(stager(sources).stageFiles(sources.files.keys.toList()), books, true, {}, {})
        var checks = 0
        val outcome = session.write(books, true, { ++checks > 1 }, {})
        assertTrue(outcome.stopped)
        assertEquals(1, books.list().size)
        assertTrue(outcome.summary(true).contains("剩余 2 本未导入"))
        session.discard()
    }

    @Test
    fun clearingTheCallerDuringAWriteStillFinishesTheBookAndRemovesStaging() = database { db ->
        val books = WorldBookRepository(db)
        val sources = Sources(mapOf("A.json" to book("A"), "B.json" to book("B")))
        val session = WorldBookImportSession.prepare(stager(sources).stageFiles(sources.files.keys.toList()), books, true, {}, {})
        var stop = false
        var resumed = false
        runBlocking {
            lateinit var caller: kotlinx.coroutines.Job
            caller = launch(start = CoroutineStart.LAZY) {
                withContext(Dispatchers.IO + NonCancellable) {
                    session.writeAndDiscard(books, true, { stop }) {
                        // Same cancellation boundary as ViewModelStore.clear/onCleared.
                        stop = true
                        caller.cancel()
                    }
                }
                resumed = true
            }
            caller.start()
            caller.join()
        }
        assertFalse(resumed, "caller cancellation must exercise the resume boundary")
        assertEquals(listOf("A"), books.list().map { it.name })
        assertFalse(session.staging.directory.exists(), "cleanup cannot depend on the cancelled caller resuming")
    }

    @Test
    fun aSingleUnreadableFileIsAnErrorAndAnEmbeddedCardBookIsOptInInBatches() = database { db ->
        val books = WorldBookRepository(db)
        val bad = Sources(mapOf("bad.json" to """{"entries": }""".toByteArray()))
        assertThrows(WorldBookBatchException::class.java) {
            WorldBookImportSession.prepare(stager(bad).stageFiles(listOf("bad.json")), books, true, {}, {})
        }
        val gbk = Sources(mapOf("gbk.json" to "{\"name\":\"城主\",\"entries\":{}}".toByteArray(charset("GB18030"))))
        assertThrows(WorldBookBatchException::class.java) {
            WorldBookImportSession.prepare(stager(gbk).stageFiles(listOf("gbk.json")), books, true, {}, {})
        }
        assertTrue(WorldBookBatchStager.stagingRoot(cache).listFiles().orEmpty().isEmpty())
        val card = """{"spec":"chara_card_v2","data":{"name":"Hero","character_book":{"entries":[{"keys":["a"],"content":"b"}]}}}"""
        val mixed = Sources(mapOf("card.json" to card.toByteArray(), "PHB.json" to book("PHB")))
        val session = WorldBookImportSession.prepare(stager(mixed).stageFiles(mixed.files.keys.toList()), books, true, {}, {})
        assertEquals(listOf(false, true), session.plan.rows.map { it.candidate.selected })
        assertTrue(session.toggle("c0"))
        assertEquals(2, session.plan.writeCount)
        session.discard()
    }

    @Test
    fun aReplacementWithUnchangedContentWritesNoRevision() = database { db ->
        val books = WorldBookRepository(db)
        // Stored as "PHB"; the file has no name, so it probes as "phb" and only matches after renaming.
        val unnamed = """{"entries":{"0":{"uid":0,"key":["火球术"],"content":"3d6"}}}"""
        books.save(runtime.mobileagent.serialization.worldbook.WorldBookCodec.decode(unnamed, "PHB").document, "phb")
        val sources = Sources(mapOf("phb.json" to unnamed.toByteArray()))
        val session = WorldBookImportSession.prepare(stager(sources).stageFiles(listOf("phb.json")), books, true, {}, {})
        session.replan(WorldBookConflictPolicy.REPLACE)
        assertEquals(WorldBookBatchAction.SKIP_IDENTICAL, session.plan.rows.single().action)
        assertEquals(0L, session.plan.bytesToWrite)
        val outcome = session.write(books, true, { false }, {})
        assertTrue(outcome.stored.isEmpty())
        assertEquals(1, books.get("phb")!!.summary.revision, "no duplicate revision")
        session.discard()
    }

    @Test
    fun staleStagingIsSweptAndFreshStagingIsKept() {
        val root = WorldBookBatchStager.stagingRoot(cache).apply { mkdirs() }
        val old = File(root, "batch-old").apply { mkdirs(); setLastModified(1_000) }
        val fresh = File(root, "batch-new").apply { mkdirs() }
        WorldBookBatchStager.sweep(cache, now = System.currentTimeMillis())
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    /** Tree spec; [node] gives every file the full path as its key, as document URIs are unique per file. */
    private sealed interface Spec { val name: String }
    private class Leaf(override val name: String) : Spec
    private class Dir(override val name: String, val children: List<Spec>) : Spec

    private class FakeNode(private val spec: Spec, override val key: String) : KnowledgeFolderNode {
        override val name: String = spec.name
        override val isDirectory = spec is Dir
        override fun forEachChild(maximum: Int, cancelled: () -> Boolean, visit: (KnowledgeFolderNode) -> Boolean): KnowledgeFolderChildRead {
            (spec as Dir).children.forEach { child -> if (!visit(FakeNode(child, "$key/${child.name}"))) return KnowledgeFolderChildRead.STOPPED }
            return KnowledgeFolderChildRead.COMPLETE
        }
    }

    private fun node(root: Dir): KnowledgeFolderNode = FakeNode(root, root.name)

    private fun database(block: (SqlConnection) -> Unit) = Jdbc().use { Migrations.apply(it); block(it) }

    private class Jdbc : SqlConnection, AutoCloseable {
        private val connection = DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        }
        @Synchronized override fun execute(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { s -> args.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.execute() }
        }
        @Synchronized override fun query(sql: String, args: List<Any?>): List<SqlRow> = connection.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, value -> s.setObject(i + 1, value) }
            s.executeQuery().use { result -> buildList { while (result.next()) add(SqlRow((1..result.metaData.columnCount).associate {
                result.metaData.getColumnLabel(it) to result.getObject(it)
            })) } }
        }
        @Synchronized override fun <T> transaction(block: () -> T): T {
            if (!connection.autoCommit) return block()
            connection.autoCommit = false
            return try { block().also { connection.commit() } }
            catch (failure: Throwable) { connection.rollback(); throw failure }
            finally { connection.autoCommit = true }
        }
        override fun close() = connection.close()
    }
}
