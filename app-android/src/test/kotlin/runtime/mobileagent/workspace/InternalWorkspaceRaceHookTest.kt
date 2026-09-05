// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Race-window fault-injection tests for the file commit path (b07 follow-up
 * findings C2–C4).
 *
 * The backend's test-only hooks run an *external* writer (raw `java.nio`,
 * never the backend itself) inside the TOCTOU window — after the final
 * pre-commit re-check, before the commit.  Ordinary "create the target, then
 * call" tests only exercise the pre-check path; these prove the commit
 * primitive itself cannot overwrite.
 */
class InternalWorkspaceRaceHookTest {
    @TempDir
    lateinit var tempDir: Path

    private fun fixture(): Pair<InternalWorkspaceBackend, Path> {
        val root = tempDir.resolve("ws-${System.nanoTime()}")
        return InternalWorkspaceBackend(root) to root
    }

    private fun code(result: InternalWorkspaceResult<*>): InternalWorkspaceErrorCode =
        when (result) {
            is InternalWorkspaceResult.Success -> fail("expected failure but got success")
            is InternalWorkspaceResult.Failure -> result.error.code
        }

    private fun text(path: Path): String = String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    @Test
    fun createOnlyRaceInsideCommitWindowNeverOverwrites() {
        val (backend, root) = fixture()
        backend.afterPreflightBeforeCommit = {
            Files.write(root.resolve("claim.txt"), "external-owner-data".toByteArray())
        }
        try {
            assertEquals(
                InternalWorkspaceErrorCode.ENTRY_EXISTS,
                code(backend.write("claim.txt", "candidate".toByteArray(), InternalWorkspaceVersions.MISSING, false)),
            )
        } finally {
            backend.afterPreflightBeforeCommit = null
        }
        assertEquals("external-owner-data", text(root.resolve("claim.txt")))
    }

    @Test
    fun noReplaceMoveRaceInsideCommitWindowLosesNothing() {
        val (backend, root) = fixture()
        check(backend.write("src.txt", "AAAA".toByteArray(), InternalWorkspaceVersions.MISSING, false)
            is InternalWorkspaceResult.Success)
        // An external writer lands inside the former commit window (same
        // length, so a size check could not catch it either).
        backend.afterPreflightBeforeCommit = {
            Files.write(root.resolve("dst.txt"), "BBBB".toByteArray())
        }
        try {
            // Scheme A: no-replace move is UNSUPPORTED for every node kind —
            // never success, never a delete of the new content.
            assertEquals(
                InternalWorkspaceErrorCode.UNSUPPORTED,
                code(backend.move("src.txt", "dst.txt", null, false)),
            )
        } finally {
            backend.afterPreflightBeforeCommit = null
        }
        assertEquals("AAAA", text(root.resolve("src.txt")))
        assertEquals("BBBB", text(root.resolve("dst.txt")))
    }

    @Test
    fun noReplaceMoveIsUnsupportedWithoutMutating() {
        val (backend, root) = fixture()
        check(backend.write("src.txt", "agent".toByteArray(), InternalWorkspaceVersions.MISSING, false)
            is InternalWorkspaceResult.Success)
        assertEquals(
            InternalWorkspaceErrorCode.UNSUPPORTED,
            code(backend.move("src.txt", "dst.txt", null, false)),
        )
        assertTrue(Files.exists(root.resolve("src.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("agent", text(root.resolve("src.txt")))
        assertFalse(Files.exists(root.resolve("dst.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun noReplaceDirectoryMoveIsUnsupportedRatherThanOverwriting() {
        val (backend, root) = fixture()
        check(backend.createDirectory("dir", null) is InternalWorkspaceResult.Success)
        check(backend.write("dir/note.txt", "x".toByteArray(), InternalWorkspaceVersions.MISSING, false)
            is InternalWorkspaceResult.Success)
        assertEquals(
            InternalWorkspaceErrorCode.UNSUPPORTED,
            code(backend.move("dir", "dir2", null, false)),
        )
        assertTrue(Files.isDirectory(root.resolve("dir"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(root.resolve("dir2"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun noReplaceDirectoryCopyIsUnsupported() {
        val (backend, root) = fixture()
        check(backend.createDirectory("dir", null) is InternalWorkspaceResult.Success)
        assertEquals(
            InternalWorkspaceErrorCode.UNSUPPORTED,
            code(backend.copy("dir", "dir2", null, false)),
        )
        assertFalse(Files.exists(root.resolve("dir2"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun replaceDetectsRewriteLandingBeforeFinalCheck() {
        val (backend, root) = fixture()
        val first = when (val created =
            backend.write("note.txt", "v1".toByteArray(), InternalWorkspaceVersions.MISSING, false)) {
            is InternalWorkspaceResult.Success -> created.value
            is InternalWorkspaceResult.Failure -> fail("setup failed: ${created.error.code}")
        }
        // The final pre-commit re-read catches any rewrite that landed before
        // it.  A rewrite landing *after* that re-read is the documented
        // best-effort boundary (no COMPARE_AND_REPLACE is advertised); this
        // test pins the detection side, the descriptor test pins the honesty.
        Files.write(root.resolve("note.txt"), "external-rewrite".toByteArray())
        assertEquals(
            InternalWorkspaceErrorCode.CONFLICT,
            code(backend.write("note.txt", "v2".toByteArray(), first.version, true)),
        )
        assertEquals("external-rewrite", text(root.resolve("note.txt")))
    }

    @Test
    fun copyRaceInsideCommitWindowNeverOverwrites() {
        val (backend, root) = fixture()
        check(backend.write("src.txt", "agent".toByteArray(), InternalWorkspaceVersions.MISSING, false)
            is InternalWorkspaceResult.Success)
        backend.afterPreflightBeforeCommit = {
            Files.write(root.resolve("dst.txt"), "external-owner-data".toByteArray())
        }
        try {
            assertEquals(
                InternalWorkspaceErrorCode.ENTRY_EXISTS,
                code(backend.copy("src.txt", "dst.txt", null, false)),
            )
        } finally {
            backend.afterPreflightBeforeCommit = null
        }
        assertEquals("agent", text(root.resolve("src.txt")))
        assertEquals("external-owner-data", text(root.resolve("dst.txt")))
    }

    @Test
    fun versionCheckHookRunsBetweenFinalCheckAndReplaceCommit() {
        val (backend) = fixture()
        val first = when (val created =
            backend.write("note.txt", "v1".toByteArray(), InternalWorkspaceVersions.MISSING, false)) {
            is InternalWorkspaceResult.Success -> created.value
            is InternalWorkspaceResult.Failure -> fail("setup failed: ${created.error.code}")
        }
        var hookRan = false
        backend.afterVersionCheckBeforeReplace = { hookRan = true }
        try {
            val replaced = backend.write("note.txt", "v2".toByteArray(), first.version, true)
            check(replaced is InternalWorkspaceResult.Success) {
                "replace failed: ${(replaced as InternalWorkspaceResult.Failure).error.code}"
            }
        } finally {
            backend.afterVersionCheckBeforeReplace = null
        }
        assertTrue(hookRan, "the post-check seam must run before the replace commit")
    }

    @Test
    fun publishNewNeverOverwritesAnAppearedTarget() {
        val root = Files.createDirectories(tempDir.resolve("publish-${System.nanoTime()}"))
        val temporary = root.resolve("tmp.bin")
        Files.write(temporary, "candidate".toByteArray())
        Files.write(root.resolve("target.bin"), "external-owner-data".toByteArray())
        try {
            WorkspaceAtomicCommit.publish(root.resolve("tmp.bin"), root.resolve("target.bin"), replaceExisting = false)
            fail("publish over an existing target must fail")
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Expected.
        }
        assertEquals("external-owner-data", text(root.resolve("target.bin")))
    }

    @Test
    fun publishNewInstallsCompleteContentAtomically() {
        val root = Files.createDirectories(tempDir.resolve("publish-ok-${System.nanoTime()}"))
        val temporary = root.resolve("tmp.bin")
        Files.write(temporary, "complete".toByteArray())
        WorkspaceAtomicCommit.publish(temporary, root.resolve("target.bin"), replaceExisting = false)
        assertEquals("complete", text(root.resolve("target.bin")))
        assertFalse(Files.exists(temporary, java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun descriptorDoesNotAdvertiseStrictCas() {
        val (backend) = fixture()
        val capabilities = backend.descriptor.mutationCapabilities
        assertTrue(WorkspaceMutationCapability.CREATE_IF_ABSENT in capabilities)
        assertTrue(WorkspaceMutationCapability.ATOMIC_PUBLISH in capabilities)
        assertTrue(WorkspaceMutationCapability.BEST_EFFORT_CONFLICT_DETECTION in capabilities)
        assertFalse(
            WorkspaceMutationCapability.COMPARE_AND_REPLACE in capabilities,
            "no backend here owns an atomic compare-and-swap primitive; " +
                "the read-compare-rewrite sequence is best-effort across processes",
        )
    }
}
