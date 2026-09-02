/*
 * SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package runtime.mobileagent.skills.tooling

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope

class WorkspaceBrowserTest {
    @Test
    fun browseUsesOpaqueSessionBoundHandlesAndAttachPrefixesPaths() = runBlocking {
        val provider = TypedAuthorityWorkspaceProvider(Authority.SHIZUKU, FakeBackend())
        val root = assertSuccess(provider.root())
        val books = root.entries.single { it.name == "books" }
        val handle = books.handle ?: error("directory entry did not provide a handle")
        assertEquals("WorkspaceDirectoryHandle", handle.toString())
        assertFalse(handle.toString().contains("books"))

        val nested = assertSuccess(provider.directoryBrowser.browse(WorkspaceBrowseRequest(handle)))
        assertEquals("book.md", nested.entries.single().name)
        assertTrue(nested.parent != null)

        val attachment = assertSuccess(
            provider.attachDirectory(WorkspaceAttachRequest("agent-books", "Books", handle)),
        )
        assertEquals(WorkspaceScope.SELECTED_DIRECTORY, attachment.descriptor.scope)
        assertEquals("", attachment.descriptor.rootReference)
        val text = assertSuccess(attachment.backend.readText("agent-books", "book.md"))
        assertEquals("hello", text.text)
        assertEquals("book.md", text.relativePath)
    }

    @Test
    fun handlesCannotCrossProviderAndClosedProviderFailsClosed() = runBlocking {
        val first = TypedAuthorityWorkspaceProvider(Authority.WIRED_ADB, FakeBackend())
        val second = TypedAuthorityWorkspaceProvider(Authority.WIRED_ADB, FakeBackend())
        val handle = assertSuccess(first.root()).entries.single { it.name == "books" }.handle
            ?: error("directory entry did not provide a handle")
        val foreign = second.browse(WorkspaceBrowseRequest(handle))
        assertEquals(ToolErrorCode.INVALID_REQUEST, assertFailure(foreign).code)

        first.close()
        assertEquals(
            ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
            assertFailure(first.root()).code,
        )
    }

    @Test
    fun fullDeviceRequiresPersistentGrantAndExplicitConfirmation() = runBlocking {
        val store = FakeGrantStore()
        val provider = TypedAuthorityWorkspaceProvider(
            authority = Authority.SHIZUKU,
            rootBackend = FakeBackend(),
            fullDeviceBackend = FakeBackend("device-base"),
            fullDeviceGrantStore = store,
        )
        assertTrue(provider.supportsFullDeviceFiles)
        val request = FullDeviceFilesRequest("full-device", "Full device files", 1, confirmedByUser = false)
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, assertFailure(provider.openFullDeviceFiles(request)).code)

        val confirmed = request.copy(confirmedByUser = true)
        assertEquals(ToolErrorCode.AUTHORITY_NOT_GRANTED, assertFailure(provider.openFullDeviceFiles(confirmed)).code)
        store.grant = FullDeviceFilesGrant("full-device", revision = 1, confirmedAtEpochMs = 1)
        val attachment = assertSuccess(provider.openFullDeviceFiles(confirmed))
        assertEquals(WorkspaceScope.FULL_DEVICE_FILES, attachment.descriptor.scope)
        assertEquals("full-device", attachment.descriptor.id)

        assertEquals(ToolErrorCode.CONFLICT, assertFailure(provider.openFullDeviceFiles(confirmed.copy(grantRevision = 2))).code)
        assertEquals(WorkspaceResult.Success(Unit), provider.revokeFullDeviceFiles("full-device", 1))
        assertTrue(store.revoked)
    }

    private class FakeGrantStore : FullDeviceFilesGrantStore {
        var grant: FullDeviceFilesGrant? = null
        var revoked = false

        override fun load(workspaceId: String): FullDeviceFilesGrant? = grant?.takeIf { it.workspaceId == workspaceId && !revoked }

        override fun save(grant: FullDeviceFilesGrant): WorkspaceResult<Unit> {
            this.grant = grant
            revoked = false
            return WorkspaceResult.Success(Unit)
        }

        override fun revoke(workspaceId: String, expectedRevision: Long): WorkspaceResult<Unit> {
            val current = grant?.takeIf { it.workspaceId == workspaceId }
                ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.AUTHORITY_NOT_GRANTED))
            if (current.revision != expectedRevision) return WorkspaceResult.Failure(ToolError(ToolErrorCode.CONFLICT))
            revoked = true
            return WorkspaceResult.Success(Unit)
        }
    }

    private class FakeBackend(
        id: String = "root-backend",
    ) : WorkspaceBackend {
        override val descriptor = WorkspaceDescriptor(
            id = id,
            displayName = "Fake",
            backendType = WorkspaceBackendType.PRIVILEGED,
            rootReference = "internal-test-only",
            readable = true,
            writable = true,
        )

        override val capabilities = setOf(CapabilityId(CapabilityId.FILE_LIST), CapabilityId(CapabilityId.FILE_READ_TEXT))

        override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
            val path = request.relativePath.orEmpty()
            val entries = when (path) {
                "" -> listOf(
                    WorkspaceEntry("books", WorkspaceEntryType.DIRECTORY, 0),
                    WorkspaceEntry("notes.txt", WorkspaceEntryType.FILE, 5),
                )
                "books" -> listOf(WorkspaceEntry("books/book.md", WorkspaceEntryType.FILE, 5))
                else -> emptyList()
            }
            return WorkspaceResult.Success(WorkspaceListing(path.ifEmpty { "." }, entries))
        }

        override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> =
            WorkspaceResult.Success(WorkspaceText(request.relativePath, "hello"))
    }

    private fun <T> assertSuccess(result: WorkspaceResult<T>): T = when (result) {
        is WorkspaceResult.Success -> result.value
        is WorkspaceResult.Failure -> error("expected success, got ${result.error.code}")
    }

    private fun assertFailure(result: WorkspaceResult<*>): ToolError = when (result) {
        is WorkspaceResult.Failure -> result.error
        is WorkspaceResult.Success -> error("expected failure")
    }
}
