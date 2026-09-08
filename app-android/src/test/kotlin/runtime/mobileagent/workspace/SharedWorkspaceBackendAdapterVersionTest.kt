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
import runtime.mobileagent.skills.tooling.WorkspaceApplyPatchRequest
import runtime.mobileagent.skills.tooling.WorkspaceDeleteRequest
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceMoveRequest
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceStatRequest
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.skills.tooling.ToolErrorCode

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
    fun everyTokenKindProjectsIntoTheExpectedVersionRange() {
        val boundaries = mapOf(
            "0000000000000000" to 0L,
            "7fffffffffffffff" to Long.MAX_VALUE,
            "8000000000000000" to 0L,
            "ffffffffffffffff" to Long.MAX_VALUE,
            "9f86d081884c7d65" to 0x1f86d081884c7d65L,
            "08d88b8ea34ed36f" to 637412792014394223L,
        )
        for (prefix in listOf("", "c1:", "m1:", "d1:")) {
            for ((head, expected) in boundaries) {
                val token = prefix + head + "0".repeat(48)
                val version = WorkspaceVersionProjection.toPublic(token)
                assertEquals(expected, version, token)
                assertEquals(version, WorkspaceWriteTextRequest("internal", "note.txt", "next", expectedVersion = version).expectedVersion)
                assertEquals(version, WorkspaceApplyPatchRequest("internal", "note.txt", "patch", version).expectedVersion)
                assertEquals(version, WorkspaceCreateDirectoryRequest("internal", "docs", version).expectedVersion)
                assertEquals(version, WorkspaceMoveRequest("internal", "note.txt", "other.txt", version).expectedVersion)
                assertEquals(version, WorkspaceDeleteRequest("internal", "note.txt", version).expectedVersion)
                assertEquals(version, WorkspaceVersionProjection.toPublic(prefix + head.uppercase() + "0".repeat(48)))
            }
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
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceVersionProjection.toPublic("c1:" + "0".repeat(63) + "z")
        }
    }

    @Test
    fun internalWriteStatListAndDirectoryRoundTripThroughSharedAdapter(): Unit = runBlocking {
        val root = tempDir.resolve("ws-c1").also { Files.createDirectories(it) }
        val internal = InternalWorkspaceBackend(root, workspaceId = "internal")
        val backend = SharedWorkspaceBackendAdapter(internal)
        val written = success(
            backend.writeText(
                WorkspaceWriteTextRequest("internal", "note.txt", "test", replace = false),
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
        assertEquals("test", read.text)
        assertEquals(writtenVersion, read.version)

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
        assertConflict(backend.writeText(WorkspaceWriteTextRequest("internal", "note.txt", "stale", expectedVersion = writtenVersion)))
        assertEquals("hello-again", Files.readAllBytes(root.resolve("note.txt")).toString(Charsets.UTF_8))

        val directory = success(
            backend.createDirectory(WorkspaceCreateDirectoryRequest("internal", "docs")),
        )
        val directoryVersion = checkNotNull(directory.version) { "shared mkdir must return a projected version" }
        val dirOpaque = opaqueVersion(internal, "docs")
        assertTrue(dirOpaque.startsWith("d1:"), "directory token must be tagged, got $dirOpaque")
        assertEquals(WorkspaceVersionProjection.toPublic(dirOpaque), directoryVersion)
        val dirStat = success(backend.stat(WorkspaceStatRequest("internal", "docs")))
        assertEquals(directoryVersion, dirStat.version)
        assertEquals(directoryVersion, success(backend.createDirectory(WorkspaceCreateDirectoryRequest("internal", "docs", directoryVersion))).version)
        success(backend.writeText(WorkspaceWriteTextRequest("internal", "docs/child.txt", "test", replace = false)))
        assertConflict(backend.createDirectory(WorkspaceCreateDirectoryRequest("internal", "docs", directoryVersion)))
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
        val replaced = success(backend.writeText(WorkspaceWriteTextRequest("internal", "big.txt", "n".repeat(81), expectedVersion = writtenVersion)))
        assertTrue(replaced.version != writtenVersion)
        assertConflict(backend.writeText(WorkspaceWriteTextRequest("internal", "big.txt", "stale", expectedVersion = writtenVersion)))
        assertEquals("n".repeat(81), Files.readAllBytes(root.resolve("big.txt")).toString(Charsets.UTF_8))
    }

    private fun assertConflict(result: WorkspaceResult<*>) {
        assertTrue(result is WorkspaceResult.Failure)
        assertEquals(ToolErrorCode.CONFLICT, (result as WorkspaceResult.Failure).error.code)
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
