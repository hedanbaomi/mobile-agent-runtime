// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.workspace

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Fault-injection and data-integrity tests for the file commit path.
 *
 * - create-only (`replaceExisting=false`) commits must fail with ENTRY_EXISTS
 *   when the target appears before the commit — including racers outside this
 *   process (editors, git, adb, shell) — and must never overwrite it.
 * - `expectedVersion` compare-and-swap is content-bound for files inside the
 *   read envelope: a same-size rewrite with a restored mtime still conflicts.
 * - symlink swaps, parent replacement, and type changes fail closed.
 *
 * These are local JVM tests: the Internal backend and the shared commit
 * primitive are pure `java.nio` with no Android-framework dependency.
 */
class InternalWorkspaceDataIntegrityTest {
    @TempDir
    lateinit var tempDir: Path

    private data class Fixture(val backend: InternalWorkspaceBackend, val root: Path)

    private fun fixture(limits: InternalWorkspaceLimits = InternalWorkspaceLimits()): Fixture {
        val root = tempDir.resolve("ws-${System.nanoTime()}")
        return Fixture(InternalWorkspaceBackend(root, limits), root)
    }

    private fun success(result: InternalWorkspaceResult<InternalWorkspaceWrite>): InternalWorkspaceWrite =
        when (result) {
            is InternalWorkspaceResult.Success -> result.value
            is InternalWorkspaceResult.Failure -> fail("expected success but got ${result.error.code}")
        }

    private fun code(result: InternalWorkspaceResult<*>): InternalWorkspaceErrorCode =
        when (result) {
            is InternalWorkspaceResult.Success -> fail("expected failure but got success")
            is InternalWorkspaceResult.Failure -> result.error.code
        }

    private fun readBackendText(backend: InternalWorkspaceBackend, path: String): String =
        when (val result = backend.readText(path)) {
            is InternalWorkspaceResult.Success -> result.value
            is InternalWorkspaceResult.Failure -> fail("expected readable file but got ${result.error.code}")
        }

    @Test
    fun createOnlySecondWriteFailsWithoutOverwrite() {
        val (backend) = fixture()
        val first = success(backend.write("note.txt", "first".toByteArray(), InternalWorkspaceVersions.MISSING, false))
        assertTrue(first.created)
        assertEquals(InternalWorkspaceErrorCode.ENTRY_EXISTS, code(backend.write("note.txt", "second".toByteArray(), null, false)))
        assertEquals("first", readBackendText(backend, "note.txt"))
    }

    @Test
    fun exclusiveCreateNeverOverwritesExternallyCreatedTarget() {
        val root = Files.createDirectories(tempDir.resolve("commit-${System.nanoTime()}"))
        val target = root.resolve("target.txt")
        Files.write(target, "external".toByteArray())

        // The exact indicted race: the target appeared after the caller's
        // pre-check.  The exclusive create fails and the target is untouched.
        // (A rename-without-replace cannot prove this: on Windows it silently
        // replaces, verified by probe — hence no rename on this path at all.)
        try {
            WorkspaceAtomicCommit.writeExclusive(target, "candidate".toByteArray())
            fail("exclusive create over an existing target must fail")
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Expected: the existing target is untouched.
        }
        assertEquals("external", String(Files.readAllBytes(target), StandardCharsets.UTF_8))

        // An absent target is created atomically with exactly our content.
        val fresh = root.resolve("fresh.txt")
        WorkspaceAtomicCommit.writeExclusive(fresh, "candidate".toByteArray())
        assertEquals("candidate", String(Files.readAllBytes(fresh), StandardCharsets.UTF_8))

        // An explicit replace still publishes atomically.
        val temporary = root.resolve("tmp.bin")
        Files.write(temporary, "replaced".toByteArray())
        WorkspaceAtomicCommit.publish(temporary, target, replaceExisting = true)
        assertEquals("replaced", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
    }

    @Test
    fun concurrentCreateOnlyWritesHaveSingleWinnerWithoutTornContent() {
        val (backend) = fixture()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val wins = AtomicInteger(0)
        val entryExists = AtomicInteger(0)
        val candidates = (0 until threads).map { "candidate-$it".padEnd(32, '-') }
        try {
            val futures = candidates.map { payload ->
                pool.submit {
                    start.await(10, TimeUnit.SECONDS)
                    when (val result = backend.write("race.txt", payload.toByteArray(), null, false)) {
                        is InternalWorkspaceResult.Success -> wins.incrementAndGet()
                        is InternalWorkspaceResult.Failure -> {
                            assertEquals(InternalWorkspaceErrorCode.ENTRY_EXISTS, result.error.code)
                            entryExists.incrementAndGet()
                        }
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, wins.get())
        assertEquals(threads - 1, entryExists.get())
        val stored = readBackendText(backend, "race.txt")
        assertTrue(stored in candidates, "stored content must be exactly one candidate, got: $stored")
    }

    @Test
    fun sameSizeRewriteWithRestoredMtimeStillConflicts() {
        val (backend, root) = fixture()
        val first = success(backend.write("note.txt", "aaaa".toByteArray(), InternalWorkspaceVersions.MISSING, false))
        assertTrue(first.version.startsWith("c1:"), "small-file token must be content-bound, got ${first.version}")

        // External same-size rewrite with the timestamp restored: a metadata
        // version would reproduce the old token and miss the conflict.
        val raw = root.resolve("note.txt")
        val mtime = Files.readAttributes(raw, BasicFileAttributes::class.java).lastModifiedTime()
        Files.write(raw, "bbbb".toByteArray())
        Files.setLastModifiedTime(raw, mtime)

        val restated = when (val stat = backend.stat("note.txt")) {
            is InternalWorkspaceResult.Success -> stat.value.version
            is InternalWorkspaceResult.Failure -> fail("stat failed: ${stat.error.code}")
        }
        assertTrue(restated.startsWith("c1:") && restated != first.version, "content change must invalidate the token")

        assertEquals(
            InternalWorkspaceErrorCode.CONFLICT,
            code(backend.write("note.txt", "cccc".toByteArray(), first.version, true)),
        )
        assertEquals("bbbb", readBackendText(backend, "note.txt"))
    }

    @Test
    fun largeFileVersionIsTaggedBestEffort() {
        val limits = InternalWorkspaceLimits(maxFileBytes = 64, quotaBytes = 1024, maxReadBytes = 8)
        val (backend) = fixture(limits)
        val big = success(backend.write("big.bin", ByteArray(32) { it.toByte() }, InternalWorkspaceVersions.MISSING, false))
        assertTrue(big.version.startsWith("m1:"), "large-file token must be tagged metadata/best-effort, got ${big.version}")
        val small = success(backend.write("small.bin", ByteArray(4) { it.toByte() }, InternalWorkspaceVersions.MISSING, false))
        assertTrue(small.version.startsWith("c1:"), "small-file token must be content-bound, got ${small.version}")
        val dir = when (val stat = backend.stat("")) {
            is InternalWorkspaceResult.Success -> stat.value.version
            is InternalWorkspaceResult.Failure -> fail("root stat failed: ${stat.error.code}")
        }
        assertTrue(dir.startsWith("d1:"), "directory token must be tagged, got $dir")
    }

    @Test
    fun symlinkTargetSwapFailsClosedWithoutFollowing() {
        val (backend, root) = fixture()
        success(backend.write("link.txt", "original".toByteArray(), InternalWorkspaceVersions.MISSING, false))
        val outside = tempDir.resolve("outside-${System.nanoTime()}.txt")
        Files.write(outside, "outside".toByteArray())
        val target = root.resolve("link.txt")
        try {
            Files.delete(target)
            Files.createSymbolicLink(target, outside)
        } catch (_: UnsupportedOperationException) {
            return // Platform cannot create symlinks; device tests cover this path.
        } catch (_: java.io.IOException) {
            return // Windows without symlink privilege; device tests cover this path.
        }
        val result = backend.write("link.txt", "evil".toByteArray(), null, true)
        assertTrue(result is InternalWorkspaceResult.Failure, "symlink swap must fail closed")
        assertTrue(Files.isSymbolicLink(target), "the link itself must be untouched")
        assertEquals("outside", String(Files.readAllBytes(outside), StandardCharsets.UTF_8))
    }

    @Test
    fun parentReplacementFailsClosed() {
        val (backend, root) = fixture()
        backend.createDirectory("sub", null)
        success(backend.write("sub/note.txt", "v1".toByteArray(), InternalWorkspaceVersions.MISSING, false))
        val sub = root.resolve("sub")
        val outsideDir = tempDir.resolve("outside-dir-${System.nanoTime()}")
        Files.createDirectories(outsideDir)
        try {
            deleteRecursively(sub)
            Files.createSymbolicLink(sub, outsideDir)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.io.IOException) {
            return
        }
        val result = backend.write("sub/note.txt", "v2".toByteArray(), null, true)
        assertTrue(result is InternalWorkspaceResult.Failure, "parent replacement must fail closed")
        assertTrue(Files.notExists(outsideDir.resolve("note.txt")), "no content may leak through the swapped parent")
    }

    @Test
    fun typeChangeFailsClosed() {
        val (backend, root) = fixture()
        val first = success(backend.write("node", "data".toByteArray(), InternalWorkspaceVersions.MISSING, false))
        // External type change: file replaced by a directory.  A replace write
        // over a non-regular target fails closed without merging or deleting.
        Files.delete(root.resolve("node"))
        Files.createDirectory(root.resolve("node"))
        assertEquals(
            InternalWorkspaceErrorCode.ENTRY_EXISTS,
            code(backend.write("node", "data2".toByteArray(), first.version, true)),
        )
        assertTrue(Files.isDirectory(root.resolve("node"), LinkOption.NOFOLLOW_LINKS))
        // A create-only write over a directory reports existence, never a merge.
        assertEquals(
            InternalWorkspaceErrorCode.ENTRY_EXISTS,
            code(backend.write("node", "data2".toByteArray(), null, false)),
        )
    }

    @Test
    fun moveCreateOnlyNeverOverwritesDestination() {
        val (backend) = fixture()
        success(backend.write("a.txt", "aaa".toByteArray(), InternalWorkspaceVersions.MISSING, false))
        success(backend.write("b.txt", "bbb".toByteArray(), InternalWorkspaceVersions.MISSING, false))
        assertEquals(
            InternalWorkspaceErrorCode.ENTRY_EXISTS,
            code(backend.move("a.txt", "b.txt", null, false)),
        )
        assertEquals("aaa", readBackendText(backend, "a.txt"))
        assertEquals("bbb", readBackendText(backend, "b.txt"))
    }

    @Test
    fun legacyMetadataTokenRemainsAcceptedForInflightCallers() {
        // New tokens are tagged; an outstanding pre-upgrade metadata token must
        // keep old behavior instead of spuriously conflicting.
        expectVersion("c1:new", "m1-old", "m1-old")
        try {
            expectVersion("c1:new", "stale-token", "m1-old")
            fail("a stale token must conflict")
        } catch (failure: InternalWorkspaceFailure) {
            assertEquals(InternalWorkspaceErrorCode.CONFLICT, failure.error.code)
        }
        // create-only sentinel semantics are unchanged.
        expectVersion(null, InternalWorkspaceVersions.MISSING)
        try {
            expectVersion("c1:x", InternalWorkspaceVersions.MISSING)
            fail("MISSING must conflict when the entry exists")
        } catch (failure: InternalWorkspaceFailure) {
            assertEquals(InternalWorkspaceErrorCode.CONFLICT, failure.error.code)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(path).use { stream -> stream.toList().forEach(::deleteRecursively) }
        }
        Files.deleteIfExists(path)
    }
}
