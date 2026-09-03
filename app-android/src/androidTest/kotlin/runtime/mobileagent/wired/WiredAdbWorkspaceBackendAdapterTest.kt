// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.wired

import android.system.Os
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import runtime.mobileagent.bridge.BridgeProtocol
import runtime.mobileagent.bridge.BridgeResponseEnvelope
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceAttachRequest
import runtime.mobileagent.skills.tooling.WorkspaceBrowseRequest
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryEntry
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryHandle
import runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest

class WiredAdbWorkspaceBackendAdapterTest {
    @Test
    fun wiredBrowserUsesTypedRootAndBrowseAndAttachesOpaqueChild() = runBlocking {
        val root = Files.createTempDirectory("mar-wired-picker-")
        try {
            Files.createDirectories(root.resolve("project"))
            Files.write(root.resolve("project").resolve("README.md"), "ok".toByteArray(StandardCharsets.UTF_8))
            Files.write(root.resolve("notes.txt"), "notes".toByteArray(StandardCharsets.UTF_8))
            val authority = FakeAuthority(root)
            val provider = WiredAdbDeviceWorkspaceProvider(authority, fullDeviceGrantStore = null)

            val rootPage = (provider.directoryBrowser.root() as WorkspaceResult.Success).value
            assertEquals(1, rootPage.entries.size)
            assertEquals("storage", rootPage.entries.single().name)
            val storage = rootPage.entries.single().handle
            assertNotNull(storage)

            val browsed = (provider.directoryBrowser.browse(
                WorkspaceBrowseRequest(storage!!),
            ) as WorkspaceResult.Success).value
            assertEquals(setOf("project", "notes.txt"), browsed.entries.map(WorkspaceDirectoryEntry::name).toSet())
            val project = browsed.entries.single { it.name == "project" }.handle
            assertNotNull(project)

            val attached = provider.attachDirectory(
                WorkspaceAttachRequest("agent-project", "Project", project!!),
            )
            assertTrue(attached is WorkspaceResult.Success)
            assertEquals("/storage/emulated/0/project", authority.lastAttachedPath)
            assertEquals(Authority.WIRED_ADB, provider.authority)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun readChunkStopsBeforeUtf8TailAndNextOffsetConsumesNoBytesTwice() {
        val root = Files.createTempDirectory("mar-wired-utf8-")
        try {
            val bytes = "a😀b".toByteArray(StandardCharsets.UTF_8)
            Files.write(root.resolve("utf8.txt"), bytes)
            val engine = NioPrivilegedFileEngine(root)

            val first = engine.execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("utf8-first"),
                    operation = WiredAdbFileOperation.READ_TEXT,
                    relativePath = "utf8.txt",
                    maxBytes = 2,
                ),
            ) as WiredAdbFileEngineResult.Success
            assertEquals("a", first.result.text)
            assertEquals(1L, first.result.bytes)
            assertEquals(0L, first.result.offsetBytes)
            assertEquals(bytes.size.toLong(), first.result.totalBytes)
            assertTrue(!first.result.eof)

            val second = engine.execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("utf8-second"),
                    operation = WiredAdbFileOperation.READ_TEXT,
                    relativePath = "utf8.txt",
                    offsetBytes = first.result.offsetBytes + (first.result.bytes ?: 0L),
                    maxBytes = 5,
                ),
            ) as WiredAdbFileEngineResult.Success
            assertEquals("😀b", second.result.text)
            assertEquals(5L, second.result.bytes)
            assertEquals(1L, second.result.offsetBytes)
            assertEquals(bytes.size.toLong(), second.result.totalBytes)
            assertTrue(second.result.eof)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun malformedUtf8InsideChunkFailsClosed() {
        val root = Files.createTempDirectory("mar-wired-invalid-utf8-")
        try {
            Files.write(root.resolve("invalid.txt"), byteArrayOf('a'.code.toByte(), 0xc2.toByte(), 0x20))
            val result = NioPrivilegedFileEngine(root).execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("utf8-invalid"),
                    operation = WiredAdbFileOperation.READ_TEXT,
                    relativePath = "invalid.txt",
                    maxBytes = 3,
                ),
            ) as WiredAdbFileEngineResult.Failure
            assertEquals(NioPrivilegedFileEngine.ERR_INVALID_CONTENT, result.code)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun desktopFileErrorCodesRemainTypedAcrossTheSharedAdapter() {
        val cases = mapOf(
            "FILE_INVALID_PATH" to WiredAdbErrorCode.PATH_OUT_OF_SCOPE,
            "FILE_OUTSIDE_ROOT" to WiredAdbErrorCode.PATH_OUT_OF_SCOPE,
            "FILE_SYMLINK_FORBIDDEN" to WiredAdbErrorCode.SYMLINK_FORBIDDEN,
            "FILE_INVALID_CONTENT" to WiredAdbErrorCode.INVALID_CONTENT,
            "FILE_TARGET_EXISTS" to WiredAdbErrorCode.TARGET_EXISTS,
            "FILE_NON_EMPTY_DIRECTORY" to WiredAdbErrorCode.NON_EMPTY_DIRECTORY,
            "FILE_UNSUPPORTED_ENTRY" to WiredAdbErrorCode.UNSUPPORTED_ENTRY,
            "FILE_TOO_LARGE" to WiredAdbErrorCode.FILE_TOO_LARGE,
            "FILE_LIMIT" to WiredAdbErrorCode.QUOTA_EXCEEDED,
            "FILE_PERMISSION_DENIED" to WiredAdbErrorCode.PERMISSION_DENIED,
            "FILE_OPERATION_UNAVAILABLE" to WiredAdbErrorCode.OPERATION_UNAVAILABLE,
            "FILE_WRITE_UNVERIFIED" to WiredAdbErrorCode.WRITE_UNVERIFIED,
            "FILE_INVALID_CURSOR" to WiredAdbErrorCode.INVALID_CURSOR,
        )
        cases.forEach { (wireCode, expected) ->
            val response = BridgeResponseEnvelope(
                protocolVersion = BridgeProtocol.VERSION,
                requestId = "typed-error",
                success = false,
                errorCode = wireCode,
            )
            assertEquals(wireCode, expected, WiredAdbSharedAdapter.mapError(response))
        }
    }

    @Test
    fun wiredBackendPreservesSecurityAndOutcomeErrorsAtTheToolBoundary() = runBlocking {
        val root = Files.createTempDirectory("mar-wired-error-map-")
        try {
            val cases = mapOf(
                WiredAdbErrorCode.PATH_OUT_OF_SCOPE to ToolErrorCode.PATH_OUT_OF_SCOPE,
                WiredAdbErrorCode.SYMLINK_FORBIDDEN to ToolErrorCode.SYMLINK_FORBIDDEN,
                WiredAdbErrorCode.INVALID_CONTENT to ToolErrorCode.INVALID_REQUEST,
                WiredAdbErrorCode.TARGET_EXISTS to ToolErrorCode.CONFLICT,
                WiredAdbErrorCode.NON_EMPTY_DIRECTORY to ToolErrorCode.CONFLICT,
                WiredAdbErrorCode.UNSUPPORTED_ENTRY to ToolErrorCode.UNSUPPORTED_ENTRY,
                WiredAdbErrorCode.FILE_TOO_LARGE to ToolErrorCode.FILE_TOO_LARGE,
                WiredAdbErrorCode.QUOTA_EXCEEDED to ToolErrorCode.QUOTA_EXCEEDED,
                WiredAdbErrorCode.PERMISSION_DENIED to ToolErrorCode.PERMISSION_DENIED,
                WiredAdbErrorCode.OPERATION_UNAVAILABLE to ToolErrorCode.OPERATION_UNAVAILABLE,
                WiredAdbErrorCode.WRITE_UNVERIFIED to ToolErrorCode.UNKNOWN_OUTCOME,
            )
            cases.forEach { (wiredCode, expected) ->
                val backend = WiredAdbWorkspaceBackendAdapter(
                    authority = FakeAuthority(root, wiredCode),
                    workspaceId = "error-map",
                )
                val result = backend.stat(WorkspaceStatRequest("error-map", "item.txt"))
                assertTrue(result is WorkspaceResult.Failure)
                assertEquals(wiredCode.name, expected, (result as WorkspaceResult.Failure).error.code)
            }
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun readDirectoryIsUnsupportedAndDirectoryChangeInvalidatesCursor() {
        val root = Files.createTempDirectory("mar-wired-directory-read-")
        try {
            Files.createDirectories(root.resolve("directory"))
            Files.write(root.resolve("one.txt"), byteArrayOf(1))
            Files.write(root.resolve("two.txt"), byteArrayOf(2))
            val engine = NioPrivilegedFileEngine(root)

            val directoryRead = engine.execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("read-directory"),
                    operation = WiredAdbFileOperation.READ_TEXT,
                    relativePath = "directory",
                    maxBytes = 16,
                ),
            ) as WiredAdbFileEngineResult.Failure
            assertEquals(NioPrivilegedFileEngine.ERR_UNSUPPORTED_ENTRY, directoryRead.code)

            val first = engine.execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("cursor-first"),
                    operation = WiredAdbFileOperation.LIST,
                    relativePath = null,
                    maxEntries = 1,
                ),
            ) as WiredAdbFileEngineResult.Success
            assertNotNull(first.result.nextCursor)
            Files.write(root.resolve("three.txt"), byteArrayOf(3))
            val stale = engine.execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("cursor-stale"),
                    operation = WiredAdbFileOperation.LIST,
                    relativePath = null,
                    cursor = first.result.nextCursor,
                    maxEntries = 1,
                ),
            ) as WiredAdbFileEngineResult.Failure
            assertEquals(NioPrivilegedFileEngine.ERR_INVALID_CURSOR, stale.code)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun oversizedFileIsListedWithMetadataButReadReturnsTypedLimit() {
        val root = Files.createTempDirectory("mar-wired-large-")
        try {
            Files.newByteChannel(
                root.resolve("huge.bin"),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.position(WIRED_MAX_FILE_BYTES.toLong())
                channel.write(ByteBuffer.wrap(byteArrayOf(1)))
            }
            val engine = NioPrivilegedFileEngine(root)

            val listing = engine.execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("large-list"),
                    operation = WiredAdbFileOperation.LIST,
                    relativePath = null,
                ),
            ) as WiredAdbFileEngineResult.Success
            assertEquals(WIRED_MAX_FILE_BYTES.toLong() + 1L, listing.result.entries.single().bytes)

            val read = engine.execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("large-read"),
                    operation = WiredAdbFileOperation.READ_TEXT,
                    relativePath = "huge.bin",
                    maxBytes = 1,
                ),
            ) as WiredAdbFileEngineResult.Failure
            assertEquals(NioPrivilegedFileEngine.ERR_FILE_TOO_LARGE, read.code)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun listingSkipsExternalSymlinkAndKeepsNormalEntries() {
        val root = Files.createTempDirectory("mar-wired-symlink-")
        val outside = Files.createTempDirectory("mar-wired-outside-")
        try {
            Files.write(root.resolve("normal.txt"), "ok".toByteArray(StandardCharsets.UTF_8))
            Files.write(outside.resolve("secret.txt"), "secret".toByteArray(StandardCharsets.UTF_8))
            Files.createSymbolicLink(root.resolve("outside-link"), outside)

            val listing = NioPrivilegedFileEngine(root).execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("symlink-list"),
                    operation = WiredAdbFileOperation.LIST,
                    relativePath = null,
                ),
            ) as WiredAdbFileEngineResult.Success
            assertEquals(setOf("normal.txt"), listing.result.entries.map { it.relativePath }.toSet())
            assertTrue(listing.result.entries.none { it.relativePath.contains("secret") })
            assertEquals(1, listing.result.skippedEntries)
            assertEquals(
                listOf(WorkspaceListingWarningCode.SYMLINK_SKIPPED),
                listing.result.listingWarnings.map { it.code },
            )
            assertEquals(1, listing.result.listingWarnings.single().count)
        } finally {
            deleteTree(root)
            deleteTree(outside)
        }
    }

    @Test
    fun listingSkipsUnsupportedFifoAndKeepsNormalEntries() {
        val root = Files.createTempDirectory("mar-wired-fifo-")
        val normal = root.resolve("normal-with-fifo.txt")
        val fifo = root.resolve("unsupported.fifo")
        try {
            Files.write(normal, "normal".toByteArray(StandardCharsets.UTF_8))
            val created = runCatching {
                Os.mkfifo(fifo.toString(), 0x1A4)
                true
            }.getOrDefault(false)
            assumeTrue("The test filesystem does not support FIFO entries", created)

            val result = NioPrivilegedFileEngine(root).execute(
                WiredAdbFileRequest(
                    requestId = WiredAdbRequestId("fifo-list"),
                    operation = WiredAdbFileOperation.LIST,
                    relativePath = null,
                ),
            )
            assertTrue(result is WiredAdbFileEngineResult.Success)
            val listing = (result as WiredAdbFileEngineResult.Success).result
            assertEquals(setOf(normal.fileName.toString()), listing.entries.map { it.relativePath }.toSet())
            assertTrue(listing.entries.none { it.relativePath.contains(fifo.fileName.toString()) })
            assertEquals(1, listing.skippedEntries)
            assertEquals(
                listOf(WorkspaceListingWarningCode.UNSUPPORTED_ENTRY_SKIPPED),
                listing.listingWarnings.map { it.code },
            )
            assertEquals(1, listing.listingWarnings.single().count)
        } finally {
            deleteTree(root)
        }
    }

    private class FakeAuthority(
        private val root: Path,
        private val fileFailure: WiredAdbErrorCode = WiredAdbErrorCode.AUTHORITY_UNSUPPORTED,
    ) : WiredAdbAuthorityPort {
        private val owner = Any()
        private val remoteHandle = WiredAdbWorkspaceHandle(owner, "wired-picker-root", "11".repeat(32), 1L)
        private val locator = WiredAdbWorkspaceRecoveryLocator.fromEncoded("22".repeat(32))
        private val _status = MutableStateFlow(
            WiredAdbStatus(
                state = WiredAdbLifecycleState.READY,
                userIntent = WiredAdbUserIntent.ENABLED,
                platformGrant = WiredAdbPlatformGrant.GRANTED,
                availability = WiredAdbAvailability.READY,
                connection = WiredAdbConnectionState.CONNECTED,
                trusted = true,
            ),
        )
        var lastAttachedPath: String? = null
            private set

        override val status: StateFlow<WiredAdbStatus> = _status
        override val workspace: WiredAdbWorkspacePort = object : WiredAdbWorkspacePort {
            override suspend fun executeFile(request: WiredAdbFileRequest): WiredAdbResult<WiredAdbFileResult> =
                WiredAdbResult.Failure(fileFailure)

            override suspend fun attachDirectory(
                workspaceId: String,
                displayName: String,
                absolutePath: String,
                scope: WiredAdbWorkspaceScope,
                grantRevision: Long,
                confirmedByUser: Boolean,
            ): WiredAdbResult<WiredAdbWorkspaceAttachment> {
                lastAttachedPath = absolutePath
                val handle = WiredAdbWorkspaceHandle(owner, workspaceId, "33".repeat(32), 1L)
                return WiredAdbResult.Success(
                    WiredAdbWorkspaceAttachment(
                        workspaceId = workspaceId,
                        scope = scope,
                        handle = handle,
                        initialPage = WiredAdbWorkspacePage(handle, "", emptyList(), false),
                        recoveryLocator = locator,
                    ),
                )
            }

            override suspend fun browseDirectory(
                handle: WiredAdbWorkspaceHandle,
                relativePath: String?,
                maxEntries: Int,
                cursor: String?,
            ): WiredAdbResult<WiredAdbWorkspacePage> {
                val path = relativePath.orEmpty()
                val directory = path.split('/').filter(String::isNotEmpty).fold(root) { parent, child ->
                    parent.resolve(child)
                }
                val entries = Files.list(directory).use { stream ->
                    stream.sorted(Comparator.comparing<Path, String> { it.fileName.toString() })
                        .map { child ->
                            val childPath = if (path.isEmpty()) child.fileName.toString() else "$path/${child.fileName}"
                            WiredAdbFileEntry(
                                relativePath = childPath,
                                type = if (Files.isDirectory(child)) WiredAdbEntryType.DIRECTORY else WiredAdbEntryType.FILE,
                                bytes = if (Files.isRegularFile(child)) Files.size(child) else null,
                            )
                        }
                        .toList()
                }
                return WiredAdbResult.Success(
                    WiredAdbWorkspacePage(
                        handle = handle,
                        relativePath = path,
                        entries = entries.take(maxEntries),
                        truncated = entries.size > maxEntries,
                    ),
                )
            }
        }

        override val shell: WiredAdbShellPort = object : WiredAdbShellPort {
            override suspend fun executeShell(
                request: WiredAdbShellRequest,
            ): WiredAdbResult<WiredAdbShellResult> =
                WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)

            override suspend fun cancel(requestId: WiredAdbRequestId): WiredAdbResult<Unit> =
                WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)
        }

        override fun setUserIntent(enabled: Boolean) = Unit
        override fun requestPairingFromForeground(replaceExistingTrust: Boolean): WiredAdbResult<WiredAdbPairingPrompt> =
            WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)
        override suspend fun pair(): WiredAdbResult<WiredAdbTrustRecord> =
            WiredAdbResult.Failure(WiredAdbErrorCode.AUTHORITY_UNSUPPORTED)
        override suspend fun connect(): WiredAdbResult<Unit> = WiredAdbResult.Success(Unit)
        override fun disconnect() = Unit
        override suspend fun forget() = Unit

        override fun newFileRequest(
            operation: WiredAdbFileOperation,
            relativePath: String?,
            destinationRelativePath: String?,
            contentUtf8: ByteArray?,
            replaceExisting: Boolean,
            maxBytes: Int,
            cursor: String?,
            maxEntries: Int,
            offsetBytes: Long,
            patchUtf8: ByteArray?,
            expectedVersion: Long?,
            patchFormat: WiredAdbPatchFormat,
        ): WiredAdbFileRequest = WiredAdbFileRequest(
            WiredAdbRequestId("fake-file"), operation, relativePath, destinationRelativePath,
            contentUtf8, replaceExisting, maxBytes, cursor, maxEntries, offsetBytes,
            patchUtf8, expectedVersion, patchFormat,
        )

        override fun newShellRequest(
            command: String,
            cwd: String?,
            timeoutMs: Long,
            maxOutputBytes: Long,
        ): WiredAdbShellRequest = WiredAdbShellRequest(
            WiredAdbRequestId("fake-shell"), command, cwd, timeoutMs, maxOutputBytes,
        )

        override fun close() = Unit
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
