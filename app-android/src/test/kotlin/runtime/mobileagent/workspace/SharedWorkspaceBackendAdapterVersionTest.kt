// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest

/**
 * Internal → Shared version contract (9f5257 finding A).
 *
 * The Internal backend emits tagged tokens (`c1:` / `m1:` / `d1:`).  The
 * shared adapter must project them to the numeric contract instead of
 * parsing the prefix as hex.  Both sides of an expected-version comparison
 * go through the same projection, so a write/stat round-trip still binds.
 */
class SharedWorkspaceBackendAdapterVersionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun taggedTokensProjectTheSameAsTheirHexBody() {
        val digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val expected = WorkspaceVersionProjection.toPublic(digest)
        for (prefix in listOf("c1:", "m1:", "d1:")) {
            assertEquals(expected, WorkspaceVersionProjection.toPublic(prefix + digest), prefix)
        }
    }

    @Test
    fun invalidTokensFailClosedInsteadOfMasqueradingAsSuccess() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceVersionProjection.toPublic("c1:not-a-digest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceVersionProjection.toPublic("missing")
        }
    }

    @Test
    fun internalWriteStatListAndDirectoryRoundTripThroughSharedAdapter(): Unit = runBlocking {
        val root = tempDir.resolve("ws-c1").also { Files.createDirectories(it) }
        val internal = InternalWorkspaceBackend(root, workspaceId = "internal")
        val backend = SharedWorkspaceBackendAdapter(internal)
        val written = success(
            backend.writeText(
                WorkspaceWriteTextRequest("internal", "note.txt", "hello-internal", replace = false),
            ),
        )
        val writtenVersion = checkNotNull(written.version) { "shared write must return a projected version" }
        val opaque = opaqueVersion(internal, "note.txt")
        assertTrue(opaque.startsWith("c1:"), "small-file token must be content-tagged, got $opaque")
        assertEquals(WorkspaceVersionProjection.toPublic(opaque), writtenVersion)

        val stated = success(backend.stat(WorkspaceStatRequest("internal", "note.txt")))
        assertEquals(writtenVersion, stated.version)
        val listing = success(backend.list(WorkspaceListRequest("internal")))
        val listed = listing.entries.single { it.relativePath == "note.txt" }
        assertEquals(writtenVersion, listed.version)
        val read = success(backend.readText(WorkspaceReadTextRequest("internal", "note.txt")))
        assertEquals("hello-internal", read.text)
        assertEquals(writtenVersion, read.version)

        if (writtenVersion >= 0L) {
            val replaced = success(
                backend.writeText(
                    WorkspaceWriteTextRequest(
                        "internal",
                        "note.txt",
                        "hello-again",
                        replace = true,
                        expectedVersion = writtenVersion,
                    ),
                ),
            )
            assertTrue(replaced.version != writtenVersion)
        }

        val directory = success(
            backend.createDirectory(WorkspaceCreateDirectoryRequest("internal", "docs")),
        )
        val directoryVersion = checkNotNull(directory.version) { "shared mkdir must return a projected version" }
        val dirOpaque = opaqueVersion(internal, "docs")
        assertTrue(dirOpaque.startsWith("d1:"), "directory token must be tagged, got $dirOpaque")
        assertEquals(WorkspaceVersionProjection.toPublic(dirOpaque), directoryVersion)
        val dirStat = success(backend.stat(WorkspaceStatRequest("internal", "docs")))
        assertEquals(directoryVersion, dirStat.version)
    }

    @Test
    fun largeFileMetadataTokenStillProjectsThroughSharedAdapter(): Unit = runBlocking {
        val root = tempDir.resolve("ws-m1").also { Files.createDirectories(it) }
        val limits = InternalWorkspaceLimits(maxFileBytes = 256, quotaBytes = 1024, maxReadBytes = 32)
        val internal = InternalWorkspaceBackend(root, limits, workspaceId = "internal")
        val backend = SharedWorkspaceBackendAdapter(internal)
        val written = success(
            backend.writeText(WorkspaceWriteTextRequest("internal", "big.txt", "m".repeat(80), replace = false)),
        )
        val writtenVersion = checkNotNull(written.version) { "shared write must return a projected version" }
        val opaque = opaqueVersion(internal, "big.txt")
        assertTrue(opaque.startsWith("m1:"), "large-file token must stay metadata-tagged, got $opaque")
        assertEquals(WorkspaceVersionProjection.toPublic(opaque), writtenVersion)
        val stated = success(backend.stat(WorkspaceStatRequest("internal", "big.txt")))
        assertEquals(writtenVersion, stated.version)
    }

    private fun opaqueVersion(backend: InternalWorkspaceBackend, path: String): String =
        when (val result = backend.stat(path)) {
            is InternalWorkspaceResult.Success -> result.value.version
            is InternalWorkspaceResult.Failure -> fail("internal stat failed: ${result.error.code}")
        }

    private fun <T> success(result: WorkspaceResult<T>): T = when (result) {
        is WorkspaceResult.Success -> result.value
        is WorkspaceResult.Failure -> fail("expected success but got ${result.error.code}")
    }
}
