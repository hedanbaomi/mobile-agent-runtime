// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Boundary regression for the SAF folder import walk.
 *
 * These cases exist because a folder selection must never become a silent partial import: an
 * exhausted budget, a too deep tree or an unreadable subdirectory has to reject the whole selection,
 * and a wide directory must be read row by row instead of being materialized first.  The fake nodes
 * below implement the same streaming contract the production `DocumentsContract` adapter implements.
 */
class KnowledgeFolderWalkTest {
    @Test
    fun exactlyFiveHundredFilesAreAcceptedWithTheirFolderShape() {
        val folder = FakeDirectory("root", listOf(FakeDirectory("docs", (1..500).map { FakeFile("f$it") })))

        val result = KnowledgeFolderWalk.walk(folder)

        val files = (result as KnowledgeFolderWalkResult.Files).files
        assertEquals(500, files.size)
        assertEquals("docs/f1", files.first().relativePath)
        assertEquals("content://test/f500", files.last().key)
    }

    @Test
    fun fiveHundredAndOneFilesRejectTheWholeSelection() {
        val folder = FakeDirectory("docs", (1..501).map { FakeFile("f$it") })

        val result = KnowledgeFolderWalk.walk(folder)

        assertEquals(KnowledgeFolderWalkReason.FILE_LIMIT, (result as KnowledgeFolderWalkResult.Rejected).reason)
    }

    @Test
    fun aWideSingleDirectoryStopsAtTheReadBudgetInsteadOfListingEverything() {
        val wide = WideDirectory(total = 20_000)

        val result = KnowledgeFolderWalk.walk(wide)

        assertEquals(KnowledgeFolderWalkReason.NODE_LIMIT, (result as KnowledgeFolderWalkResult.Rejected).reason)
        assertEquals(KnowledgeFolderWalk.MAX_DIRECTORY_CHILDREN, wide.offered)
        assertTrue(wide.offered < 20_000, "the walk must not enumerate the whole directory first")
    }

    @Test
    fun theGlobalNodeBudgetAlsoFailsClosed() {
        // 2_500 directories with two empty children each: more visited entries than MAX_NODES while
        // the file budget never applies.
        val folder = FakeDirectory("root", (1..2_500).map { index ->
            FakeDirectory("d$index", listOf(FakeDirectory("e$index-a"), FakeDirectory("e$index-b")))
        })

        val result = KnowledgeFolderWalk.walk(folder)

        assertEquals(KnowledgeFolderWalkReason.NODE_LIMIT, (result as KnowledgeFolderWalkResult.Rejected).reason)
    }

    @Test
    fun aTreeDeeperThanTheDepthBudgetIsRejected() {
        var node: KnowledgeFolderNode = FakeDirectory("leaf", listOf(FakeFile("deep.txt")))
        repeat(KnowledgeFolderWalk.MAX_DEPTH + 1) { level -> node = FakeDirectory("d$level", listOf(node)) }

        val result = KnowledgeFolderWalk.walk(node)

        assertEquals(KnowledgeFolderWalkReason.DEPTH_LIMIT, (result as KnowledgeFolderWalkResult.Rejected).reason)
    }

    @Test
    fun aTreeAtTheDepthBudgetIsStillAccepted() {
        var node: KnowledgeFolderNode = FakeDirectory("leaf", listOf(FakeFile("deep.txt")))
        repeat(KnowledgeFolderWalk.MAX_DEPTH) { level -> node = FakeDirectory("d$level", listOf(node)) }

        val result = KnowledgeFolderWalk.walk(node)

        assertEquals(1, (result as KnowledgeFolderWalkResult.Files).files.size)
    }

    @Test
    fun anEmptyFolderIsRejectedInsteadOfImportingNothingSilently() {
        val result = KnowledgeFolderWalk.walk(FakeDirectory("empty"))

        assertEquals(KnowledgeFolderWalkReason.EMPTY, (result as KnowledgeFolderWalkResult.Rejected).reason)
    }

    @Test
    fun cancellationNeverReturnsAPartialFileList() {
        val folder = FakeDirectory("docs", (1..1_000).map { FakeFile("f$it") })
        val probes = AtomicInteger()
        val cancelled = { probes.incrementAndGet() > 10 }

        val result = KnowledgeFolderWalk.walk(folder, cancelled)

        assertTrue(result is KnowledgeFolderWalkResult.Cancelled, "a cancelled walk must not yield files")
    }

    @Test
    fun anUnreadableSubdirectoryNeverYieldsPartialFiles() {
        // The readable file comes first, so a walk that kept collecting would return Files here.
        val root = FakeDirectory(
            "docs",
            listOf(
                FakeFile("readable.txt"),
                UnreadableDirectory("locked"),
                FakeFile("also-readable.txt"),
            ),
        )

        val result = KnowledgeFolderWalk.walk(root)

        assertEquals(KnowledgeFolderWalkReason.UNREADABLE, (result as KnowledgeFolderWalkResult.Rejected).reason)
    }

    @Test
    fun anUnreadableRootIsRejected() {
        val result = KnowledgeFolderWalk.walk(UnreadableDirectory("docs"))

        assertEquals(KnowledgeFolderWalkReason.UNREADABLE, (result as KnowledgeFolderWalkResult.Rejected).reason)
    }

    @Test
    fun namelessEntriesRejectTheWholeSelection() {
        val root = FakeDirectory("docs", listOf(FakeFile(null), FakeFile("kept.txt"), FakeFile("  ")))

        val result = KnowledgeFolderWalk.walk(root)

        assertEquals(KnowledgeFolderWalkReason.UNREADABLE, (result as KnowledgeFolderWalkResult.Rejected).reason)
    }

    private abstract class FakeNode(
        override val name: String?,
        override val isDirectory: Boolean,
        override val key: String,
    ) : KnowledgeFolderNode

    private class FakeFile(name: String?) :
        FakeNode(name, isDirectory = false, key = "content://test/${name.orEmpty()}") {
        override fun forEachChild(
            maximum: Int,
            cancelled: () -> Boolean,
            visit: (KnowledgeFolderNode) -> Boolean,
        ): KnowledgeFolderChildRead = KnowledgeFolderChildRead.COMPLETE
    }

    private class FakeDirectory(
        name: String,
        private val children: List<KnowledgeFolderNode> = emptyList(),
    ) : FakeNode(name, isDirectory = true, key = "content://test/$name") {
        override fun forEachChild(
            maximum: Int,
            cancelled: () -> Boolean,
            visit: (KnowledgeFolderNode) -> Boolean,
        ): KnowledgeFolderChildRead {
            var read = 0
            for (child in children) {
                if (cancelled()) return KnowledgeFolderChildRead.STOPPED
                if (read >= maximum) return KnowledgeFolderChildRead.STOPPED
                read += 1
                if (!visit(child)) return KnowledgeFolderChildRead.STOPPED
            }
            return KnowledgeFolderChildRead.COMPLETE
        }
    }

    private class UnreadableDirectory(name: String) :
        FakeNode(name, isDirectory = true, key = "content://test/$name") {
        override fun forEachChild(
            maximum: Int,
            cancelled: () -> Boolean,
            visit: (KnowledgeFolderNode) -> Boolean,
        ): KnowledgeFolderChildRead = KnowledgeFolderChildRead.UNREADABLE
    }

    /** Streams [total] entries lazily and enforces the budget exactly like the SAF adapter does. */
    private class WideDirectory(private val total: Int) :
        FakeNode("wide", isDirectory = true, key = "content://test/wide") {
        var offered = 0
            private set

        override fun forEachChild(
            maximum: Int,
            cancelled: () -> Boolean,
            visit: (KnowledgeFolderNode) -> Boolean,
        ): KnowledgeFolderChildRead {
            var read = 0
            while (true) {
                if (cancelled()) return KnowledgeFolderChildRead.STOPPED
                if (read >= maximum) return KnowledgeFolderChildRead.STOPPED
                if (read >= total) return KnowledgeFolderChildRead.COMPLETE
                read += 1
                offered = read
                if (!visit(FakeDirectory("d$read"))) return KnowledgeFolderChildRead.STOPPED
            }
        }
    }
}
