// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Local JVM tests for the usage accounting shared by both workspace backends.
 *
 * Regression covered here (P1): usage accounting must never apply the per-file
 * limit ([InternalWorkspaceLimits.maxFileBytes]) to pre-existing entries.  A
 * directory that already contains an oversized file is common (photo/document
 * folders) and must stay writeable: the oversized neighbour is counted toward
 * the quota, not rejected with FILE_TOO_LARGE.  The quota, entry, and depth
 * ceilings keep firing exactly as before.
 */
class WorkspaceUsageAccountingTest {
    private companion object {
        const val MEBIBYTE = 1024L * 1024L
        const val EIGHT_MEBIBYTES = 8L * MEBIBYTE
    }

    /** A 1 MiB per-file limit with a 16 MiB quota: the exact shape of the false positive. */
    private val limits = InternalWorkspaceLimits(
        maxFileBytes = MEBIBYTE,
        quotaBytes = 16L * MEBIBYTE,
        maxReadBytes = 256L * 1024L,
        maxPathDepth = 4,
        maxEntries = 8,
    )

    private fun failureCode(block: () -> Unit): InternalWorkspaceErrorCode? = try {
        block()
        null
    } catch (failure: InternalWorkspaceFailure) {
        failure.error.code
    }

    /** Accounts one directory level whose direct children are files of [fileSizes]. */
    private fun accountDirectory(fileSizes: List<Long>): WorkspaceUsage {
        var usage = WorkspaceUsage()
        WorkspaceUsageAccounting.enterDirectory(limits, 0)
        fileSizes.forEach { size ->
            usage = WorkspaceUsageAccounting.countEntry(usage, limits)
            usage = WorkspaceUsageAccounting.countFile(usage, size, limits)
        }
        return usage
    }

    @Test
    fun preExistingFileAbovePerFileLimitIsCountedNotRejected() {
        var usage: WorkspaceUsage? = null
        val failure = failureCode { usage = accountDirectory(listOf(EIGHT_MEBIBYTES)) }
        assertNull(failure, "accounting must not fail a scan of a pre-existing 8 MiB file, got $failure")
        val counted = requireNotNull(usage)
        assertEquals(EIGHT_MEBIBYTES, counted.bytes, "the oversized file's bytes must count toward the quota")
        assertEquals(1, counted.files)
        assertEquals(1, counted.entries)
    }

    @Test
    fun preExistingLargeFileBytesStillTripTheQuota() {
        // 8 MiB + 8 MiB fills a 16 MiB quota exactly and must be accepted ...
        assertNull(failureCode { accountDirectory(listOf(EIGHT_MEBIBYTES, EIGHT_MEBIBYTES)) })
        // ... but one more byte must still fail closed with QUOTA_EXCEEDED.
        val failure = failureCode { accountDirectory(listOf(EIGHT_MEBIBYTES, EIGHT_MEBIBYTES, 1L)) }
        assertEquals(InternalWorkspaceErrorCode.QUOTA_EXCEEDED, failure)
    }

    @Test
    fun entryCeilingStillTripsOnEnumeratedChildren() {
        val ceiling = limits.maxEntries
        assertNull(failureCode { accountDirectory(List(ceiling) { 1L }) })
        val failure = failureCode { accountDirectory(List(ceiling + 1) { 1L }) }
        assertEquals(InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED, failure)
    }

    @Test
    fun depthCeilingStillTripsOnNestedDirectories() {
        assertNull(failureCode { WorkspaceUsageAccounting.enterDirectory(limits, limits.maxPathDepth) })
        val failure = failureCode { WorkspaceUsageAccounting.enterDirectory(limits, limits.maxPathDepth + 1) }
        assertEquals(InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED, failure)
    }

    @Test
    fun accountingRemainsPureAcrossIndependentScans() {
        val first = accountDirectory(listOf(EIGHT_MEBIBYTES))
        val second = accountDirectory(listOf(3L))
        assertEquals(WorkspaceUsage(files = 1, bytes = EIGHT_MEBIBYTES, entries = 1), first)
        assertEquals(WorkspaceUsage(files = 1, bytes = 3L, entries = 1), second)
        // A failed fold leaves no residue behind for the next operation.
        assertEquals(
            InternalWorkspaceErrorCode.ENTRY_LIMIT_EXCEEDED,
            failureCode { accountDirectory(List(limits.maxEntries + 1) { 1L }) },
        )
        assertEquals(WorkspaceUsage(files = 1, bytes = 3L, entries = 1), accountDirectory(listOf(3L)))
    }
}
