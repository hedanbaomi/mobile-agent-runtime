// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.feature.agents.WorkspacePickerErrorCodeUi
import runtime.mobileagent.integration.WorkspaceAccessResult
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryEntry
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceResult

/** Paging contract for the foreground picker: append, reachability, stale tokens. */
@RunWith(AndroidJUnit4::class)
class WorkspacePickerLoadMoreTest {
    @Test
    fun loadMoreAppendsNextPageAndClearsContinuationAtEnd(): Unit = runBlocking {
        val port = ScriptedPickerPort(
            rootEntries = listOf(dir("storage")),
            pages = mapOf(
                "storage" to listOf(
                    page("storage", listOf(dir("dir-0"), dir("dir-1")), continuation = "c1"),
                    page("storage", listOf(dir("dir-2")), continuation = null),
                ),
            ),
        )
        val vm = WorkspacePickerViewModel(ApplicationProvider.getApplicationContext<Context>() as Application, port)
        withTimeout(10_000) { vm.state.first { it.locations.any { location -> location.label == "内部存储" } } }

        vm.openLocation("entry-0")
        withTimeout(10_000) { vm.state.first { it.entries.map { entry -> entry.name } == listOf("dir-0", "dir-1") } }
        assertTrue(vm.state.value.canLoadMore)
        assertFalse(vm.state.value.loadingMore)

        vm.loadMore()
        withTimeout(10_000) {
            vm.state.first { it.entries.map { entry -> entry.name } == listOf("dir-0", "dir-1", "dir-2") }
        }
        assertFalse(vm.state.value.canLoadMore)
        assertFalse(vm.state.value.loadingMore)
        assertEquals(null, vm.state.value.errorCode)
    }

    @Test
    fun staleContinuationKeepsShownEntriesWithTypedRefreshError(): Unit = runBlocking {
        val port = ScriptedPickerPort(
            rootEntries = listOf(dir("storage")),
            pages = mapOf(
                "storage" to listOf(
                    page("storage", listOf(dir("dir-0"), dir("dir-1")), continuation = "stale"),
                ),
            ),
            staleContinuations = setOf("stale"),
        )
        val vm = WorkspacePickerViewModel(ApplicationProvider.getApplicationContext<Context>() as Application, port)
        withTimeout(10_000) { vm.state.first { it.locations.any { location -> location.label == "内部存储" } } }

        vm.openLocation("entry-0")
        withTimeout(10_000) { vm.state.first { it.entries.map { entry -> entry.name } == listOf("dir-0", "dir-1") } }
        assertTrue(vm.state.value.canLoadMore)

        vm.loadMore()
        withTimeout(10_000) { vm.state.first { it.errorCode == WorkspacePickerErrorCodeUi.CONFLICT } }
        // Already shown entries are kept; the user refreshes instead of paging.
        assertEquals(listOf("dir-0", "dir-1"), vm.state.value.entries.map { it.name })
        assertFalse(vm.state.value.canLoadMore)
        assertFalse(vm.state.value.loadingMore)
    }

    private class FakeHandle(val name: String) : WorkspaceDirectoryHandle()

    private fun dir(name: String): WorkspaceDirectoryEntry = WorkspaceDirectoryEntry(
        name = name,
        type = WorkspaceEntryType.DIRECTORY,
        handle = FakeHandle(name),
    )

    private fun page(
        directory: String,
        entries: List<WorkspaceDirectoryEntry>,
        continuation: String?,
    ): WorkspaceDirectoryPage = WorkspaceDirectoryPage(
        current = FakeHandle(directory),
        parent = FakeHandle("parent-of-$directory"),
        entries = entries,
        truncated = continuation != null,
        continuation = continuation,
    )

    private class ScriptedPickerPort(
        private val rootEntries: List<WorkspaceDirectoryEntry>,
        private val pages: Map<String, List<WorkspaceDirectoryPage>>,
        private val staleContinuations: Set<String> = emptySet(),
    ) : WorkspacePickerPort {
        private val cursors = mutableMapOf<String, Int>()

        override fun authoritySnapshot(): WorkspacePickerAuthoritySnapshot = WorkspacePickerAuthoritySnapshot(
            selectedAuthority = Authority.SHIZUKU,
            status = WorkspacePickerAuthorityStatus.READY,
            ready = true,
        )

        override suspend fun browsePrivilegedRoot(
            authority: Authority,
            maxEntries: Int,
        ): WorkspaceResult<WorkspaceDirectoryPage> = WorkspaceResult.Success(
            WorkspaceDirectoryPage(
                current = FakeHandle("root"),
                parent = null,
                entries = rootEntries,
                truncated = false,
                continuation = null,
            ),
        )

        override suspend fun browsePrivileged(
            authority: Authority,
            request: WorkspaceBrowseRequest,
        ): WorkspaceResult<WorkspaceDirectoryPage> {
            val key = (request.handle as? FakeHandle)?.name ?: return unavailable()
            val script = pages[key] ?: return unavailable()
            val continuation = request.continuation
            if (continuation != null && continuation in staleContinuations) {
                return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_CURSOR))
            }
            val index = if (continuation == null) 0 else cursors[key] ?: return unavailable()
            val page = script.getOrNull(index) ?: return unavailable()
            cursors[key] = index + 1
            return WorkspaceResult.Success(page)
        }

        override suspend fun attachPrivilegedDirectory(
            authority: Authority,
            request: WorkspaceAttachRequest,
            target: WorkspacePickerTarget,
        ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
            runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNSUPPORTED,
        )

        override suspend fun attachSaf(
            uri: android.net.Uri,
            resultFlags: Int,
            target: WorkspacePickerTarget,
        ): WorkspaceAccessResult = WorkspaceAccessResult.Failure(
            runtime.mobileagent.integration.WorkspaceAccessErrorCode.UNSUPPORTED,
        )

        private fun <T> unavailable(): WorkspaceResult<T> = WorkspaceResult.Failure(
            ToolError(ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE),
        )
    }
}
