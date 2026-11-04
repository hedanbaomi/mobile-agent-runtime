// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.integration.RootDirectoryBrowseUiEntry
import runtime.mobileagent.integration.RootDirectoryBrowseUiPage
import runtime.mobileagent.integration.RootWorkspacePermissionsUi
import runtime.mobileagent.integration.RootWorkspaceUiPort

/**
 * The foreground Root browser must keep the service cursor and append whole pages. A former fixed
 * total would silently hide every directory after the cap and make "load more" disappear.
 */
class RootWorkspaceBrowsePagingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() = scope.cancel()

    @Test
    fun appendsPagesPastTheFormerCapAndPreservesCursor() {
        val firstPage = (1..150).map { directory("dir$it") }
        val secondPage = (151..260).map { directory("dir$it") }
        val port = PagingPort(
            listOf(
                RootDirectoryBrowseUiPage("/data", firstPage, nextCursor = "dir150"),
                RootDirectoryBrowseUiPage("/data", secondPage, nextCursor = null),
            ),
        )
        val dialogs = RootWorkspaceDialogState(scope, port) { 0L }

        dialogs.openPath(Authority.ROOT_DIRECT, "/data", "browse failed")
        awaitUntil("the first page arrives") { dialogs.browse.entries.size == 150 }
        assertEquals("dir150", dialogs.browse.nextCursor)
        assertEquals(true, dialogs.browse.hasMore)

        dialogs.browseMore(Authority.ROOT_DIRECT, "browse failed")
        awaitUntil("the second page is appended") { dialogs.browse.entries.size == 260 }
        assertNull(dialogs.browse.nextCursor)
        assertEquals(false, dialogs.browse.hasMore)
        assertEquals("dir1", dialogs.browse.entries.first().name)
        assertEquals("dir260", dialogs.browse.entries.last().name)
    }

    @Test
    fun repeatedPagesDoNotDuplicateRows() {
        val page = RootDirectoryBrowseUiPage(
            "/data",
            listOf(directory("a"), directory("b")),
            nextCursor = "b",
        )
        val port = PagingPort(listOf(page, page))
        val dialogs = RootWorkspaceDialogState(scope, port) { 0L }

        dialogs.openPath(Authority.ROOT_DIRECT, "/data", "browse failed")
        awaitUntil("the first page arrives") { dialogs.browse.entries.size == 2 }
        dialogs.browseMore(Authority.ROOT_DIRECT, "browse failed")
        awaitUntil("the overlapping page settles") { !dialogs.browse.loading }
        assertEquals(2, dialogs.browse.entries.size)
    }

    private fun directory(name: String) = RootDirectoryBrowseUiEntry(
        name = name,
        path = "/data/$name",
        directory = true,
    )

    private fun awaitUntil(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(5_000L) {
            while (!condition()) delay(5)
        }
    }

    private class PagingPort(private val pages: List<RootDirectoryBrowseUiPage>) : RootWorkspaceUiPort {
        private val next = AtomicInteger()

        override suspend fun browseRootDirectory(
            authority: Authority,
            path: String,
            cursor: String?,
        ): RootDirectoryBrowseUiPage? = pages.getOrNull(next.getAndIncrement())

        override fun rootWorkspacePermissions(workspaceId: String, agentId: String): RootWorkspacePermissionsUi? = null

        override suspend fun updateRootWorkspacePermissions(
            authority: Authority,
            workspaceId: String,
            agentId: String,
            writable: Boolean,
            metadata: Boolean,
            shell: Boolean,
        ): Boolean = false
    }
}
