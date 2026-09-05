// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Workflow benchmark A (coding task) on a test repository:
 * read files → locate bug → modify → detect conflict → show diff →
 * run verification → rollback/restore.
 *
 * Requirements: an external concurrent modification is never overwritten,
 * failures are typed and explainable, and every edit leaves a versioned
 * restore path.
 */
class CodingWorkflowBenchmarkTest {
    @TempDir
    lateinit var tempDir: Path

    private fun backend(root: Path = tempDir.resolve("repo-${System.nanoTime()}")) =
        InternalWorkspaceBackend(root)

    private fun successWrite(backend: InternalWorkspaceBackend, path: String, text: String, version: String?): String {
        val result = backend.write(path, text.toByteArray(), version, version != InternalWorkspaceVersions.MISSING)
        return when (result) {
            is InternalWorkspaceResult.Success -> result.value.version
            is InternalWorkspaceResult.Failure -> fail("write $path failed: ${result.error.code}")
        }
    }

    private fun read(backend: InternalWorkspaceBackend, path: String): Pair<String, String> {
        return when (val result = backend.readText(path)) {
            is InternalWorkspaceResult.Success -> result.value to versionOf(backend, path)
            is InternalWorkspaceResult.Failure -> fail("read $path failed: ${result.error.code}")
        }
    }

    private fun versionOf(backend: InternalWorkspaceBackend, path: String): String {
        return when (val stat = backend.stat(path)) {
            is InternalWorkspaceResult.Success -> stat.value.version
            is InternalWorkspaceResult.Failure -> fail("stat $path failed: ${stat.error.code}")
        }
    }

    @Test
    fun codingTaskEndToEndWithConflictAndRestore() {
        val backend = backend()
        backend.createDirectory("src", null)

        // Read files: the task starts from the committed tree.
        var version = successWrite(backend, "src/Main.kt", "fun answer(): Int = 41\n", InternalWorkspaceVersions.MISSING)
        successWrite(backend, "src/Util.kt", "fun shout(text: String): String = text.uppercase()\n", InternalWorkspaceVersions.MISSING)

        // Locate bug: read and find the wrong constant.
        val (main, observed) = read(backend, "src/Main.kt")
        assertTrue("41" in main)
        assertEquals(version, observed)

        // Modify with the observed version: conditional write succeeds.
        version = successWrite(backend, "src/Main.kt", "fun answer(): Int = 42\n", observed)

        // External concurrent modification, then a stale write: the conflict
        // is detected and nothing is overwritten.
        val (_, external) = read(backend, "src/Main.kt")
        successWrite(backend, "src/Main.kt", "fun answer(): Int = 43 // external\n", external)
        when (val stale = backend.write("src/Main.kt", "fun answer(): Int = 44 // stale\n".toByteArray(), version, true)) {
            is InternalWorkspaceResult.Success -> fail("stale write must conflict")
            is InternalWorkspaceResult.Failure -> assertEquals(InternalWorkspaceErrorCode.CONFLICT, stale.error.code)
        }
        val (afterConflict, _) = read(backend, "src/Main.kt")
        assertTrue("43 // external" in afterConflict)

        // Show diff: a unified patch against the current tree applies cleanly.
        val patch = "@@ -1,1 +1,1 @@\n-fun answer(): Int = 43 // external\n+fun answer(): Int = 42\n"
        val patched = backend.applyPatch("src/Main.kt", patch, versionOf(backend, "src/Main.kt"), InternalWorkspacePatchFormat.UNIFIED_DIFF)
        assertTrue(patched is InternalWorkspaceResult.Success, patched.toString())

        // Run verification: read back the exact expected tree.
        val (verified, verifiedVersion) = read(backend, "src/Main.kt")
        assertEquals("fun answer(): Int = 42\n", verified)

        // Rollback/restore: the pre-patch content is restored with a fresh
        // version, and the stale pre-patch token no longer applies.
        val restored = backend.write(
            "src/Main.kt",
            "fun answer(): Int = 41\n".toByteArray(),
            verifiedVersion,
            true,
        )
        assertTrue(restored is InternalWorkspaceResult.Success, restored.toString())
        when (val replay = backend.write("src/Main.kt", "fun answer(): Int = 0\n".toByteArray(), verifiedVersion, true)) {
            is InternalWorkspaceResult.Success -> fail("replayed version must conflict after restore")
            is InternalWorkspaceResult.Failure -> assertEquals(InternalWorkspaceErrorCode.CONFLICT, replay.error.code)
        }
        val (final, _) = read(backend, "src/Main.kt")
        assertEquals("fun answer(): Int = 41\n", final)
    }

    @Test
    fun failuresAreTypedAndExplainable() {
        val backend = backend()
        backend.createDirectory("src", null)
        when (val missing = backend.readText("src/Missing.kt")) {
            is InternalWorkspaceResult.Success -> fail("missing file must not read")
            is InternalWorkspaceResult.Failure -> {
                assertEquals(InternalWorkspaceErrorCode.ENTRY_NOT_FOUND, missing.error.code)
                assertTrue(missing.error.userMessage.isNotBlank())
            }
        }
        // Create-only over an existing file explains itself without damage.
        successWrite(backend, "note.txt", "v1", InternalWorkspaceVersions.MISSING)
        when (val clash = backend.write("note.txt", "v2".toByteArray(), InternalWorkspaceVersions.MISSING, false)) {
            is InternalWorkspaceResult.Success -> fail("create-only clash must fail")
            is InternalWorkspaceResult.Failure -> assertEquals(InternalWorkspaceErrorCode.ENTRY_EXISTS, clash.error.code)
        }
        val (content, _) = read(backend, "note.txt")
        assertEquals("v1", content)
    }
}
