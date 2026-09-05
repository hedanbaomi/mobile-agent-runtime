// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Output limits do not bound execution cost: a shallow list fingerprint
 * recurses into subtrees, and quota checks walk the tree.  These tests prove
 * the separate work budget (scanned nodes, metadata reads, wall time) caps
 * that cost per operation, while returned pages stay independently bounded.
 */
class WorkspaceWorkBudgetTest {
    @TempDir
    lateinit var tempDir: Path

    private fun root(): Path = tempDir.resolve("ws-${System.nanoTime()}")

    private fun code(result: InternalWorkspaceResult<*>): InternalWorkspaceErrorCode =
        when (result) {
            is InternalWorkspaceResult.Success -> fail("expected failure but got success")
            is InternalWorkspaceResult.Failure -> result.error.code
        }

    @Test
    fun runawayDirectoryScanTripsScannedBudget() {
        val root = root()
        val generous = InternalWorkspaceBackend(root)
        assertTrue(generous.createDirectory("sub") is InternalWorkspaceResult.Success)
        repeat(30) { i ->
            val written = generous.write("sub/f$i.txt", "payload-$i".toByteArray(), InternalWorkspaceVersions.MISSING, false)
            assertTrue(written is InternalWorkspaceResult.Success, "setup write $i failed: $written")
        }

        val tight = InternalWorkspaceBackend(
            root,
            InternalWorkspaceLimits(maxEntries = 10_000, maxDirectoryEntries = 10_000, maxScannedEntries = 10),
        )
        assertEquals(InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED, code(tight.list("sub", 100)))

        // The same tree lists fine under the default budget.
        val listed = generous.list("sub", 100)
        assertTrue(listed is InternalWorkspaceResult.Success, listed.toString())
        assertEquals(30, (listed as InternalWorkspaceResult.Success).value.entries.size)
    }

    @Test
    fun returnedPageStaysBoundedWhileScanning() {
        val backend = InternalWorkspaceBackend(root())
        repeat(20) { i ->
            assertTrue(
                backend.write("f$i.txt", "x".toByteArray(), InternalWorkspaceVersions.MISSING, false) is InternalWorkspaceResult.Success,
            )
        }
        val first = backend.list("", 5)
        assertTrue(first is InternalWorkspaceResult.Success, first.toString())
        val page = (first as InternalWorkspaceResult.Success).value
        assertEquals(5, page.entries.size)
        val cursor = page.nextCursor ?: fail("expected a continuation cursor for a truncated page")
        val second = backend.list("", 5, cursor)
        assertTrue(second is InternalWorkspaceResult.Success, second.toString())
        assertEquals(5, (second as InternalWorkspaceResult.Success).value.entries.size)
    }

    @Test
    fun metadataReadsCapAttributeHeavyListings() {
        val root = root()
        val generous = InternalWorkspaceBackend(root)
        repeat(10) { i ->
            assertTrue(
                generous.write("m$i.txt", "payload".toByteArray(), InternalWorkspaceVersions.MISSING, false) is InternalWorkspaceResult.Success,
            )
        }
        val tight = InternalWorkspaceBackend(
            root,
            InternalWorkspaceLimits(maxEntries = 10_000, maxDirectoryEntries = 10_000, maxMetadataReads = 5),
        )
        assertEquals(InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED, code(tight.list("", 100)))
        assertTrue(generous.list("", 100) is InternalWorkspaceResult.Success)
    }

    @Test
    fun budgetsResetPerOperation() {
        val backend = InternalWorkspaceBackend(
            root(),
            InternalWorkspaceLimits(maxMetadataReads = 100, maxScannedEntries = 100),
        )
        assertTrue(
            backend.write("note.txt", "hello".toByteArray(), InternalWorkspaceVersions.MISSING, false) is InternalWorkspaceResult.Success,
        )
        // Five sequential stats each fit the per-operation budget: budgets
        // bound one execution, they do not accumulate across calls.
        repeat(5) { i ->
            assertTrue(backend.stat("note.txt") is InternalWorkspaceResult.Success, "stat $i tripped a fresh budget")
        }
    }

    @Test
    fun wallClockBoundTripsRunawayOperations() {
        var now = 0L
        val budget = ScanBudget(InternalWorkspaceLimits(maxWallTimeMs = 100)) { now }
        budget.visitNodes(1)
        now = 101L
        try {
            budget.visitNodes(1)
            fail("expired wall clock must trip the budget")
        } catch (failure: InternalWorkspaceFailure) {
            assertEquals(InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED, failure.error.code)
        }
    }

    @Test
    fun descriptorExposesTheWorkBudget() {
        val limits = InternalWorkspaceLimits(maxScannedEntries = 111, maxMetadataReads = 222, maxWallTimeMs = 333)
        val backend = InternalWorkspaceBackend(root(), limits)
        assertEquals(111, backend.descriptor.maxScannedEntries)
        assertEquals(222, backend.descriptor.maxMetadataReads)
        assertEquals(333L, backend.descriptor.maxWallTimeMs)
    }
}
