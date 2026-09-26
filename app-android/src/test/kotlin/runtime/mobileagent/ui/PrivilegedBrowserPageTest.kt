// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryEntry
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceEntryType

class PrivilegedBrowserPageTest {
    private class Handle : WorkspaceDirectoryHandle()

    @Test
    fun appendKeepsLaterDirectoriesReachableAndRemovesOverlappingEntries() {
        val handle = Handle()
        val first = WorkspaceDirectoryPage(
            current = handle,
            parent = null,
            entries = listOf(directory("d000"), directory("d255")),
            truncated = true,
            continuation = "page-2",
        )
        val second = WorkspaceDirectoryPage(
            current = handle,
            parent = null,
            entries = listOf(directory("d255"), directory("d256"), directory("d299")),
            truncated = false,
        )

        val combined = appendPrivilegedBrowserPage(first, second)

        assertEquals(listOf("d000", "d255", "d256", "d299"), combined.entries.map { it.name })
        assertEquals(null, combined.continuation)
        assertFalse(combined.truncated)
    }

    private fun directory(name: String) = WorkspaceDirectoryEntry(
        name = name,
        type = WorkspaceEntryType.DIRECTORY,
        handle = Handle(),
    )
}
