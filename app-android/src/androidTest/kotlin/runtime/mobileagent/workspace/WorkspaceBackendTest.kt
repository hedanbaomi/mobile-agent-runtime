// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.CapabilityId

@RunWith(AndroidJUnit4::class)
class WorkspaceBackendTest {
    @Test
    fun internalBackendRejectsPathFormsBeforeCreatingAnything() {
        withInternal { backend, root ->
            val bad = listOf("../outside", "/absolute", "C:/absolute", "a//b", "a\\b", "a\u0000b", "a\u0001b")
            bad.forEach { path ->
                val result = backend.write(path, byteArrayOf(1))
                assertCode(result, InternalWorkspaceErrorCode.INVALID_PATH)
            }
            assertFalse(Files.exists(root))
        }
    }

    @Test
    fun internalBackendUsesContentVersionForConflictsAndAtomicReplacement() {
        withInternal { backend, _ ->
            val first = backend.write("note.txt", "first".toByteArray(), expectedVersion = InternalWorkspaceVersions.MISSING)
            assertSuccess(first)
            val version = (first as InternalWorkspaceResult.Success).value.version
            val stale = backend.write("note.txt", "second".toByteArray(), expectedVersion = "stale-version")
            assertCode(stale, InternalWorkspaceErrorCode.CONFLICT)
            val replacement = backend.write("note.txt", "second".toByteArray(), expectedVersion = version)
            assertTrue(replacement is InternalWorkspaceResult.Success)
            val read = backend.readText("note.txt")
            assertEquals(InternalWorkspaceResult.Success("second"), read)
            val stat = backend.stat("note.txt")
            assertTrue(stat is InternalWorkspaceResult.Success)
            assertEquals((replacement as InternalWorkspaceResult.Success).value.version, (stat as InternalWorkspaceResult.Success).value.version)
        }
    }

    @Test
    fun internalDirectoryVersionTracksSameSizedDescendantChanges() {
        withInternal { backend, root ->
            assertSuccess(backend.createDirectory("tree"))
            assertSuccess(
                backend.write(
                    "tree/file.txt",
                    byteArrayOf(1, 2),
                    expectedVersion = InternalWorkspaceVersions.MISSING,
                ),
            )
            val beforeFileEdit = (backend.stat("tree") as InternalWorkspaceResult.Success).value.version

            // Change the bytes without changing the size.  A metadata-only directory token would
            // incorrectly remain stable here.
            Files.write(root.resolve("tree/file.txt"), byteArrayOf(3, 4))
            val afterFileEdit = (backend.stat("tree") as InternalWorkspaceResult.Success).value.version
            assertTrue(beforeFileEdit != afterFileEdit)

            assertSuccess(backend.createDirectory("tree/nested"))
            val beforeNestedEdit = (backend.stat("tree") as InternalWorkspaceResult.Success).value.version
            assertSuccess(
                backend.write(
                    "tree/nested/child.txt",
                    byteArrayOf(5, 6),
                    expectedVersion = InternalWorkspaceVersions.MISSING,
                ),
            )
            val afterNestedEdit = (backend.stat("tree") as InternalWorkspaceResult.Success).value.version
            assertTrue(beforeNestedEdit != afterNestedEdit)
        }
    }

    @Test
    fun internalBackendSupportsDirectoryTransferAndRestrictsDelete() {
        withInternal { backend, root ->
            assertSuccess(backend.createDirectory("a"))
            assertSuccess(backend.write("a/file.txt", byteArrayOf(1, 2, 3), expectedVersion = InternalWorkspaceVersions.MISSING))
            assertSuccess(backend.copy("a/file.txt", "copy.txt"))
            assertNoTemporaryArtifacts(root)
            assertSuccess(backend.move("copy.txt", "moved.txt"))
            assertCode(backend.delete("a"), InternalWorkspaceErrorCode.NON_EMPTY_DIRECTORY)
            assertCode(backend.delete(""), InternalWorkspaceErrorCode.ROOT_OPERATION_FORBIDDEN)
            assertSuccess(backend.delete("moved.txt"))
            assertSuccess(backend.delete("a/file.txt"))
            assertSuccess(backend.delete("a"))
            assertNoTemporaryArtifacts(root)
        }
    }

    @Test
    fun internalBackendCleansInterruptedTemporaryArtifactsBeforeExposure() {
        withInternal { backend, root ->
            Files.createDirectories(root)
            val staleWrite = root.resolve(".mar-workspace-write-00000000-0000-0000-0000-000000000000.tmp")
            val staleCopy = root.resolve(".mar-workspace-copy-00000000-0000-0000-0000-000000000001.tmp")
            Files.write(staleWrite, byteArrayOf(1, 2, 3))
            Files.createDirectories(staleCopy)
            Files.write(staleCopy.resolve("partial.txt"), byteArrayOf(4, 5))

            val listing = backend.list("")
            assertSuccess(listing)
            assertFalse(Files.exists(staleWrite))
            assertFalse(Files.exists(staleCopy))
            assertNoTemporaryArtifacts(root)
        }
    }

    @Test
    fun internalBackendRejectsSymlinksAndEnforcesQuotaAndDepth() {
        val limits = InternalWorkspaceLimits(
            maxFileBytes = 4,
            quotaBytes = 4,
            maxPathDepth = 2,
            maxEntries = 3,
            maxDirectoryEntries = 3,
            maxReadBytes = 4,
        )
        withInternal(limits) { backend, root ->
            assertSuccess(backend.write("inside", byteArrayOf(1, 2, 3, 4), expectedVersion = InternalWorkspaceVersions.MISSING))
            assertCode(backend.write("too-big", byteArrayOf(1), expectedVersion = InternalWorkspaceVersions.MISSING), InternalWorkspaceErrorCode.QUOTA_EXCEEDED)
            assertCode(backend.createDirectory("one/two/three"), InternalWorkspaceErrorCode.DEPTH_LIMIT_EXCEEDED)
            val outside = Files.createTempFile("mar-outside", ".txt")
            try {
                Files.createSymbolicLink(root.resolve("link"), outside)
                assertCode(backend.stat("link"), InternalWorkspaceErrorCode.SYMLINK_FORBIDDEN)
            } finally {
                Files.deleteIfExists(root.resolve("link"))
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun internalBackendAllowsStableExternalAncestorAliasButRejectsWorkspaceRootLink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = context.cacheDir.toPath().resolve("backend-alias-${System.nanoTime()}")
        val actualParent = fixture.resolve("actual")
        val aliasParent = fixture.resolve("alias")
        try {
            Files.createDirectories(actualParent)
            Files.createSymbolicLink(aliasParent, actualParent)

            val backend = InternalWorkspaceBackend(aliasParent.resolve("workspace"))
            assertSuccess(backend.createDirectory("inside"))
            assertSuccess(
                backend.write(
                    "inside/file.txt",
                    byteArrayOf(1, 2, 3),
                    expectedVersion = InternalWorkspaceVersions.MISSING,
                ),
            )

            val linkedTarget = fixture.resolve("linked-target")
            val linkedRoot = fixture.resolve("linked-root")
            Files.createDirectories(linkedTarget)
            Files.createSymbolicLink(linkedRoot, linkedTarget)
            assertCode(InternalWorkspaceBackend(linkedRoot).list(""), InternalWorkspaceErrorCode.SYMLINK_FORBIDDEN)
        } finally {
            deleteTreeIfExists(fixture)
        }
    }

    @Test
    fun safWithoutPersistedGrantFailsClosedWithoutLeakingUriOrPath() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://fixture.provider/tree/private-token/document/root")
        val backend = SafWorkspaceBackend(context, uri)
        assertFalse(backend.descriptor.supportsAtomicReplace)
        assertFalse(backend.descriptor.enabled)
        assertFalse(backend.descriptor.readable)
        assertFalse(backend.descriptor.writable)
        assertTrue(backend.descriptor.operationCapabilities.isEmpty())
        val result = backend.list("secret-file.txt")
        assertCode(result, InternalWorkspaceErrorCode.GRANT_LOST)
        val failure = result as InternalWorkspaceResult.Failure
        assertFalse(failure.error.userMessage.contains(uri.toString()))
        assertFalse(failure.error.userMessage.contains("secret-file.txt"))
    }

    @Test
    fun safReadOnlyGrantAndUnsupportedProviderFlagsDoNotAdvertiseWrites() {
        val readOnly = SafWorkspaceCapabilityPolicy.derive(
            readGranted = true,
            writeGranted = false,
            rootFlags = android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
            children = listOf(
                SafCapabilityChild(
                    InternalWorkspaceEntryType.FILE,
                    android.provider.DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                        android.provider.DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                        android.provider.DocumentsContract.Document.FLAG_SUPPORTS_MOVE,
                ),
            ),
        )
        assertTrue(readOnly.operationCapabilities.contains(InternalWorkspaceCapabilities.READ_TEXT))
        assertFalse(readOnly.writable)
        assertFalse(readOnly.operationCapabilities.contains(InternalWorkspaceCapabilities.WRITE_TEXT))
        assertFalse(readOnly.operationCapabilities.contains(InternalWorkspaceCapabilities.CREATE_DIRECTORY))
        assertFalse(readOnly.operationCapabilities.contains(InternalWorkspaceCapabilities.DELETE))
        assertFalse(readOnly.operationCapabilities.contains(InternalWorkspaceCapabilities.MOVE))

        val unsupported = SafWorkspaceCapabilityPolicy.derive(
            readGranted = true,
            writeGranted = true,
            rootFlags = 0,
            children = listOf(SafCapabilityChild(InternalWorkspaceEntryType.FILE, 0)),
        )
        assertTrue(unsupported.operationCapabilities.contains(InternalWorkspaceCapabilities.READ_TEXT))
        assertFalse(unsupported.writable)
        assertFalse(unsupported.operationCapabilities.contains(InternalWorkspaceCapabilities.WRITE_TEXT))
        assertFalse(unsupported.operationCapabilities.contains(InternalWorkspaceCapabilities.CREATE_DIRECTORY))
        assertFalse(unsupported.operationCapabilities.contains(InternalWorkspaceCapabilities.DELETE))
        assertFalse(unsupported.operationCapabilities.contains(InternalWorkspaceCapabilities.MOVE))
    }

    @Test
    fun adaptersExposeOnlyBackendOwnedCapabilities() {
        withInternal { backend, _ ->
            val adapter = SharedWorkspaceBackendAdapter(backend)
            assertTrue(adapter.capabilities.contains(InternalWorkspaceCapabilities.ENUMERATE))
            assertTrue(adapter.capabilities.contains(InternalWorkspaceCapabilities.WRITE_TEXT))
            assertTrue(adapter.capabilities.contains(InternalWorkspaceCapabilities.DELETE))
            // copy is an app-only extension and is not a shared model-facing operation.
            assertFalse(adapter.capabilities.contains(CapabilityId("file.copy")))
        }
    }

    private fun withInternal(
        limits: InternalWorkspaceLimits = InternalWorkspaceLimits(),
        action: (InternalWorkspaceBackend, Path) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.toPath().resolve("backend-${System.nanoTime()}")
        try {
            action(InternalWorkspaceBackend(root, limits), root)
        } finally {
            deleteTreeIfExists(root)
        }
    }

    private fun deleteTreeIfExists(root: Path) {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun assertCode(result: InternalWorkspaceResult<*>, expected: InternalWorkspaceErrorCode) {
        assertTrue("expected failure $expected but got $result", result is InternalWorkspaceResult.Failure)
        assertEquals(expected, (result as InternalWorkspaceResult.Failure).error.code)
        assertNotNull((result as InternalWorkspaceResult.Failure).error.userMessage)
    }

    private fun assertSuccess(result: InternalWorkspaceResult<*>) {
        assertTrue("expected success but got $result", result is InternalWorkspaceResult.Success)
    }

    private fun assertNoTemporaryArtifacts(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            assertTrue(
                stream.noneMatch { path ->
                    val name = path.fileName?.toString() ?: ""
                    name.matches(Regex("\\.mar-workspace-(?:write|copy)-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp"))
                },
            )
        }
    }
}
