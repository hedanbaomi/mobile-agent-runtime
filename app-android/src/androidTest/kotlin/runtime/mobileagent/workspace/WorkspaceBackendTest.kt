// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import android.content.Context
import android.net.Uri
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode
import runtime.mobileagent.skills.tooling.WorkspaceResult

@RunWith(AndroidJUnit4::class)
class WorkspaceBackendTest {
    @Test
    fun safMutationDocumentUriIsReboundToPersistedTreeAndRejectsForeignHandles() {
        val tree = Uri.parse("content://fixture.provider/tree/root")
        val ordinaryDocument = Uri.parse("content://fixture.provider/document/root%2Fchild.txt")

        val rebound = rebindSafMutationDocumentUri(tree, ordinaryDocument)
        assertNotNull(rebound)
        assertEquals("root", android.provider.DocumentsContract.getTreeDocumentId(rebound))
        assertEquals("root/child.txt", android.provider.DocumentsContract.getDocumentId(rebound))

        assertEquals(
            rebound,
            rebindSafMutationDocumentUri(
                tree,
                Uri.parse("content://fixture.provider/tree/root/document/root%2Fchild.txt"),
            ),
        )
        assertEquals(null, rebindSafMutationDocumentUri(tree, Uri.parse("content://other.provider/document/root%2Fchild.txt")))
        assertEquals(null, rebindSafMutationDocumentUri(tree, Uri.parse("content://fixture.provider/document/root%2Fchild.txt?token=secret")))
        assertEquals(null, rebindSafMutationDocumentUri(tree, Uri.parse("content://fixture.provider/not-a-document")))
    }

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
            // No-replace move is UNSUPPORTED (scheme A): the replace path is
            // the only move this backend proves.
            assertSuccess(backend.move("copy.txt", "moved.txt", replaceExisting = true))
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
    fun internalBackendListingSkipsExternalSymlinkAndKeepsNormalEntries() {
        withInternal { backend, root ->
            Files.createDirectories(root)
            Files.write(root.resolve("normal.txt"), byteArrayOf(1, 2, 3))
            val outside = Files.createTempFile("mar-list-outside", ".txt")
            try {
                Files.createSymbolicLink(root.resolve("outside-link.txt"), outside)

                val listing = backend.list("")
                assertSuccess(listing)
                val value = (listing as InternalWorkspaceResult.Success).value
                val entries = value.entries
                assertTrue(entries.any { it.path == "normal.txt" })
                assertFalse(entries.any { it.path == "outside-link.txt" })
                assertTrue(entries.all { !it.path.contains(outside.toString()) })
                assertEquals(1, value.skippedEntries)
                assertEquals(
                    listOf(WorkspaceListingWarningCode.SYMLINK_SKIPPED),
                    value.warnings.map { it.code },
                )
                assertEquals(1, value.warnings.single().count)
            } finally {
                Files.deleteIfExists(root.resolve("outside-link.txt"))
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun internalBackendListingSkipsUnsupportedFifoAndKeepsNormalEntries() {
        withInternal { backend, root ->
            Files.createDirectories(root)
            Files.write(root.resolve("normal-with-fifo.txt"), byteArrayOf(1, 2, 3))
            val fifo = root.resolve("unsupported.fifo")
            val created = runCatching {
                Os.mkfifo(fifo.toString(), 0x1A4)
                true
            }.getOrDefault(false)
            assumeTrue("The test filesystem does not support FIFO entries", created)

            val listing = backend.list("")
            assertSuccess(listing)
            val value = (listing as InternalWorkspaceResult.Success).value
            assertEquals(setOf("normal-with-fifo.txt"), value.entries.map { it.path }.toSet())
            assertEquals(1, value.skippedEntries)
            assertEquals(
                listOf(WorkspaceListingWarningCode.UNSUPPORTED_ENTRY_SKIPPED),
                value.warnings.map { it.code },
            )
            assertEquals(1, value.warnings.single().count)
        }
    }

    @Test
    fun internalBackendPaginatesWithoutDuplicatesAndBindsCursorToDirectory() {
        val limits = InternalWorkspaceLimits(
            maxFileBytes = 64,
            quotaBytes = 4 * 1024,
            maxEntries = 32,
            maxDirectoryEntries = 3,
            maxReadBytes = 64,
        )
        withInternal(limits) { backend, _ ->
            val names = (0 until 8).map { "entry-${it.toString().padStart(2, '0')}.txt" }
            names.forEach { name ->
                assertSuccess(backend.write(name, byteArrayOf(1), expectedVersion = InternalWorkspaceVersions.MISSING))
            }

            val observed = mutableListOf<String>()
            var cursor: String? = null
            do {
                val page = backend.list("", maxEntries = 2, cursor = cursor)
                assertSuccess(page)
                val value = (page as InternalWorkspaceResult.Success).value
                observed += value.entries.map { it.path }
                cursor = value.nextCursor
            } while (cursor != null)

            assertEquals(names, observed)
            assertEquals(names.size, observed.toSet().size)

            // A random token cannot address backend state.
            assertCode(backend.list("", maxEntries = 2, cursor = "forged-cursor"), InternalWorkspaceErrorCode.INVALID_CURSOR)

            // A valid token is invalidated by a directory change rather than
            // silently returning an overlapping or skipped page.
            val first = (backend.list("", maxEntries = 2) as InternalWorkspaceResult.Success).value
            assertNotNull(first.nextCursor)
            assertSuccess(backend.write("new.txt", byteArrayOf(2), expectedVersion = InternalWorkspaceVersions.MISSING))
            assertCode(
                backend.list("", maxEntries = 2, cursor = first.nextCursor),
                InternalWorkspaceErrorCode.INVALID_CURSOR,
            )
        }
    }

    @Test
    fun internalBackendStatsAndReadsLargeFileInBoundedChunks() {
        val limits = InternalWorkspaceLimits(
            maxFileBytes = 2 * 1024 * 1024,
            quotaBytes = 2 * 1024 * 1024,
            maxEntries = 8,
            maxDirectoryEntries = 8,
            maxReadBytes = 4096,
        )
        withInternal(limits) { backend, root ->
            assertSuccess(backend.createDirectory("seed"))
            val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
            Files.write(root.resolve("large.bin"), bytes)
            val hugeBytes = 2 * 1024 * 1024 + 1
            Files.write(root.resolve("huge.bin"), ByteArray(hugeBytes))

            val listing = backend.list("")
            assertSuccess(listing)
            val listedHuge = (listing as InternalWorkspaceResult.Success).value.entries.single { it.path == "huge.bin" }
            assertEquals(hugeBytes.toLong(), listedHuge.sizeBytes)

            assertCode(
                backend.read("huge.bin", maxBytes = 4096),
                InternalWorkspaceErrorCode.FILE_TOO_LARGE,
            )

            val stat = backend.stat("large.bin")
            assertSuccess(stat)
            assertEquals(bytes.size.toLong(), (stat as InternalWorkspaceResult.Success).value.sizeBytes)

            val first = backend.read("large.bin", maxBytes = 4096, offsetBytes = 0)
            assertSuccess(first)
            val firstChunk = (first as InternalWorkspaceResult.Success).value
            assertEquals(0L, firstChunk.offsetBytes)
            assertEquals(bytes.size.toLong(), firstChunk.totalBytes)
            assertFalse(firstChunk.eof)
            assertTrue(firstChunk.bytes.contentEquals(bytes.copyOfRange(0, 4096)))

            val second = backend.read("large.bin", maxBytes = 4096, offsetBytes = firstChunk.offsetBytes + firstChunk.bytes.size)
            assertSuccess(second)
            val secondChunk = (second as InternalWorkspaceResult.Success).value
            assertEquals(4096L, secondChunk.offsetBytes)
            assertTrue(secondChunk.bytes.contentEquals(bytes.copyOfRange(4096, 8192)))

            val tailOffset = bytes.size.toLong() - 5L
            val tail = backend.read("large.bin", maxBytes = 4096, offsetBytes = tailOffset)
            assertSuccess(tail)
            val tailChunk = (tail as InternalWorkspaceResult.Success).value
            assertTrue(tailChunk.eof)
            assertEquals(5, tailChunk.bytes.size)
            assertTrue(tailChunk.bytes.contentEquals(bytes.copyOfRange(tailOffset.toInt(), bytes.size)))
            assertCode(backend.read("large.bin", maxBytes = 4096, offsetBytes = bytes.size.toLong() + 1), InternalWorkspaceErrorCode.OFFSET_OUT_OF_RANGE)
        }
    }

    @Test
    fun safReadChunkStopsAtLimitAndKeepsOffsetEofAndUtf8FailClosed() {
        val maxBytes = 256 * 1024
        val bytes = ByteArray(maxBytes + 17) { (it % 251).toByte() }
        val input = ByteArrayInputStream(bytes)
        val first = readSafChunk(input, offset = 0L, maximum = maxBytes, declaredSize = bytes.size.toLong())

        assertEquals(maxBytes, first.bytes.size)
        assertTrue(first.bytes.contentEquals(bytes.copyOfRange(0, maxBytes)))
        assertEquals(bytes.size.toLong(), first.totalBytes)
        assertFalse(first.eof)
        // Regression guard: a bounded read must leave the unread tail in the provider stream.
        assertEquals(bytes.size - maxBytes, input.available())

        val second = readSafChunk(
            ByteArrayInputStream(bytes),
            offset = maxBytes.toLong(),
            maximum = maxBytes,
            declaredSize = bytes.size.toLong(),
        )
        assertTrue(second.bytes.contentEquals(bytes.copyOfRange(maxBytes, bytes.size)))
        assertEquals(17, second.bytes.size)
        assertEquals(bytes.size.toLong(), second.totalBytes)
        assertTrue(second.eof)

        // Keep the existing strict UTF-8 boundary policy: a chunk that ends inside a code point
        // is not silently repaired or reassembled by this low-level reader.
        val utf8Bytes = ByteArray(maxBytes - 1) { 'a'.code.toByte() } +
            "中".toByteArray(StandardCharsets.UTF_8)
        val utf8Chunk = readSafChunk(
            ByteArrayInputStream(utf8Bytes),
            offset = 0L,
            maximum = maxBytes,
            declaredSize = utf8Bytes.size.toLong(),
        )
        assertTrue(InternalWorkspaceVersions.decode(utf8Chunk.bytes) is InternalWorkspaceResult.Failure)
    }

    @Test
    fun safUnknownSizeProbeIsBoundedAndRejectsFilesAboveTheWorkspaceLimit() {
        val maximum = 16L
        val exact = ByteArrayInputStream(ByteArray(maximum.toInt()))
        assertEquals(maximum, probeSafFileSize(exact, maximum))
        assertEquals(0, exact.available())

        val oversizedBytes = ByteArray(maximum.toInt() + 9)
        val oversized = ByteArrayInputStream(oversizedBytes)
        val failure = runCatching { probeSafFileSize(oversized, maximum) }.exceptionOrNull()
        assertTrue(failure is InternalWorkspaceFailure)
        assertEquals(
            InternalWorkspaceErrorCode.FILE_TOO_LARGE,
            (failure as InternalWorkspaceFailure).error.code,
        )
        // The guard reads at most limit + 1 and leaves the remainder untouched.
        assertEquals(oversizedBytes.size - maximum.toInt() - 1, oversized.available())
    }

    @Test
    fun internalBackendApplyPatchIsConditionalAndUsesAtomicReplacement() {
        withInternal { backend, root ->
            val initial = backend.write(
                "note.txt",
                "one\ntwo\nthree\n".toByteArray(),
                expectedVersion = InternalWorkspaceVersions.MISSING,
            )
            assertSuccess(initial)
            val version = (initial as InternalWorkspaceResult.Success).value.version
            val patch = "@@ -2,1 +2,1 @@\n-two\n+TWO\n"
            val applied = backend.applyPatch(
                relativePath = "note.txt",
                patch = patch,
                expectedVersion = version,
                format = InternalWorkspacePatchFormat.UNIFIED_DIFF,
            )
            assertSuccess(applied)
            assertEquals(InternalWorkspaceResult.Success("one\nTWO\nthree\n"), backend.readText("note.txt"))
            assertNoTemporaryArtifacts(root)

            val stale = backend.applyPatch("note.txt", patch, version, InternalWorkspacePatchFormat.UNIFIED_DIFF)
            assertCode(stale, InternalWorkspaceErrorCode.CONFLICT)
            assertEquals(InternalWorkspaceResult.Success("one\nTWO\nthree\n"), backend.readText("note.txt"))

            val invalid = backend.applyPatch(
                "note.txt",
                "@@ -1,1 +1,1 @@\n-not-this-line\n+replacement\n",
                (applied as InternalWorkspaceResult.Success).value.version,
                InternalWorkspacePatchFormat.UNIFIED_DIFF,
            )
            assertCode(invalid, InternalWorkspaceErrorCode.INVALID_PATCH)
        }
    }

    @Test
    fun safBackendDoesNotPretendToSupportAtomicPatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = SafWorkspaceBackend(context, Uri.parse("content://fixture.provider/tree/opaque"))
        val result = backend.applyPatch("note.txt", "replacement", "version", InternalWorkspacePatchFormat.REPLACE)
        assertCode(result, InternalWorkspaceErrorCode.UNSUPPORTED)
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
    fun safGrantLossBecomesBackendNeutralPermissionDenied() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = SafWorkspaceBackend(context, Uri.parse("content://fixture.provider/tree/no-grant"))
        val adapter = SharedWorkspaceBackendAdapter(backend)

        val result = adapter.list(WorkspaceListRequest(adapter.descriptor.id, null, 16, null))

        assertTrue(result is WorkspaceResult.Failure)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, (result as WorkspaceResult.Failure).error.code)
    }

    @Test
    fun safCapabilitiesExposeCreateOnlyTextWritesButNotUnsupportedMutations() {
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

        val createCapable = SafWorkspaceCapabilityPolicy.derive(
            readGranted = true,
            writeGranted = true,
            rootFlags = android.provider.DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
            children = emptyList(),
        )
        assertTrue(createCapable.writable)
        assertTrue(createCapable.operationCapabilities.contains(InternalWorkspaceCapabilities.WRITE_TEXT))
        assertTrue(createCapable.operationCapabilities.contains(InternalWorkspaceCapabilities.CREATE_DIRECTORY))
        assertFalse(createCapable.operationCapabilities.contains(InternalWorkspaceCapabilities.DELETE))
        assertFalse(createCapable.operationCapabilities.contains(InternalWorkspaceCapabilities.MOVE))
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
