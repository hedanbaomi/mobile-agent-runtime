// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceBackendType
import runtime.mobileagent.skills.tooling.WorkspaceCreateDirectoryRequest
import runtime.mobileagent.skills.tooling.WorkspaceDescriptor
import runtime.mobileagent.skills.tooling.WorkspaceEntry
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListing
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.skills.tooling.WorkspaceText
import runtime.mobileagent.skills.tooling.WorkspaceWriteTextRequest
import runtime.mobileagent.workspace.InternalWorkspaceBackend
import runtime.mobileagent.workspace.SharedWorkspaceBackendAdapter

/**
 * Pure local tests for the bounded recursive search engine.
 *
 * Everything runs against an in-memory fixture or the real internal implementation; no device,
 * provider or network is involved.  The cases cover the parts of task A that are easiest to get
 * subtly wrong: glob semantics, path-shape rejection, pagination, the depth/entry/file/byte/result
 * /time budgets, honest truncation, per-entry degradation versus terminal failure, non-UTF-8 and
 * multi-byte handling, matches that straddle a read-chunk boundary, the UTF-8 retry gate, and
 * cooperative cancellation.
 */
class WorkspaceSearchTest {

    // ------------------------------------------------------------------ glob

    @Test
    fun globSupportsStarQuestionAndGlobstar() {
        val anyKotlin = globOf("*.kt")
        assertTrue(anyKotlin.matches("Main.kt"), "a separator-free pattern matches the basename")
        assertTrue(anyKotlin.matches("src/deep/Main.kt"), "and it matches that basename at any depth")
        assertFalse(anyKotlin.matches("src/Main.java"))
        assertFalse(anyKotlin.matches("src/kt"))

        val anchored = globOf("src/*.kt")
        assertTrue(anchored.matches("src/Main.kt"))
        assertFalse(anchored.matches("src/deep/Main.kt"), "* never crosses a separator")
        assertFalse(anchored.matches("other/Main.kt"))

        val deep = globOf("**/*.kt")
        assertTrue(deep.matches("Main.kt"), "** matches zero path segments")
        assertTrue(deep.matches("a/b/c/Main.kt"))
        assertFalse(deep.matches("a/b/c/Main.java"))

        val prefix = globOf("src/**")
        assertTrue(prefix.matches("src/a/b.txt"))
        assertFalse(prefix.matches("other/a.txt"))

        val single = globOf("file?.txt")
        assertTrue(single.matches("dir/file1.txt"))
        assertFalse(single.matches("dir/file12.txt"), "? is exactly one character")
        assertFalse(single.matches("dir/file/.txt"), "? never crosses a separator")

        val middle = globOf("a/**/z.txt")
        assertTrue(middle.matches("a/z.txt"))
        assertTrue(middle.matches("a/b/c/z.txt"))
        assertFalse(middle.matches("a/b/z.txt.bak"))
    }

    @Test
    fun globRejectsTraversalAndPlatformShapedPatterns() {
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse(""))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("../secret/*"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("a/../../b"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("/etc/passwd"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("C:/windows/*"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("a\\b"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("a//b"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("a/./b"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("a/"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("a\u0000b"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("a\nb"))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("x".repeat(300)))
        assertNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse((1..20).joinToString("/") { "s$it" }))
        // A rejected pattern is never silently downgraded to "match everything".
        assertNotNull(WorkspaceSearchMatching.WorkspaceSearchGlob.parse("**"))
    }

    @Test
    fun globMatchingIsNotExponentialForPathologicalPatterns() {
        // A long run of stars against a long non-matching value must finish quickly: this is a
        // linear scan plus a small dynamic program, never a backtracking explosion.
        val glob = globOf("a" + "*".repeat(40) + "b")
        val started = System.nanoTime()
        assertFalse(glob.matches("a" + "c".repeat(4_000)))
        assertTrue(glob.matches("a" + "c".repeat(4_000) + "b"))
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    // ------------------------------------------------------- literal matching

    @Test
    fun literalIndexOfTreatsRegexMetacharactersAsText() {
        assertEquals(0, WorkspaceSearchMatching.indexOf("a.c", "a.c", 0, caseSensitive = true))
        assertEquals(-1, WorkspaceSearchMatching.indexOf("abc", "a.c", 0, caseSensitive = true))
        assertEquals(2, WorkspaceSearchMatching.indexOf("xxTODOyy", "TODO", 0, caseSensitive = true))
        assertEquals(-1, WorkspaceSearchMatching.indexOf("tOdO", "TODO", 0, caseSensitive = true))
        assertEquals(0, WorkspaceSearchMatching.indexOf("tOdO", "TODO", 0, caseSensitive = false))
        assertEquals(1, WorkspaceSearchMatching.indexOf("aÄb", "ä", 0, caseSensitive = false))
        assertEquals(-1, WorkspaceSearchMatching.indexOf("ab", "abc", 0, caseSensitive = false))
    }

    @Test
    fun queryValidationRejectsEmptyControlCharactersAndOverlongQueries() {
        assertNotNull(WorkspaceSearchMatching.validateQuery("needle"))
        assertNull(WorkspaceSearchMatching.validateQuery(""))
        assertNull(WorkspaceSearchMatching.validateQuery("a\u0000b"))
        assertNull(WorkspaceSearchMatching.validateQuery("a\nb"))
        assertNull(WorkspaceSearchMatching.validateQuery("x".repeat(300)))
        // A regex-looking query is accepted as *literal text*, which is exactly the contract:
        // no pattern engine ever runs on model input.
        assertEquals(".*", WorkspaceSearchMatching.validateQuery(".*"))
    }

    // -------------------------------------------------------------- traversal

    @Test
    fun globWalkFollowsPaginationToTheLastPage() = runBlocking {
        val backend = FakeSearchBackend(
            files = (1..5).associate { "f$it.txt" to "body $it\n" } + mapOf("sub/inner.txt" to "inner\n"),
            pageSize = 2,
        )
        val outcome = glob(backend, "", "*")
        assertEquals(
            listOf("f1.txt", "f2.txt", "f3.txt", "f4.txt", "f5.txt", "sub", "sub/inner.txt"),
            outcome.matches.map { it.relativePath }.sorted(),
        )
        assertTrue(backend.listCalls >= 3, "a small page size must be followed to the final page")
        assertFalse(outcome.truncated, "full pagination is not truncation: ${outcome.reasons}")
    }

    @Test
    fun depthBudgetPrunesDescentAndIsReportedAsTruncation() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("top.txt" to "a\n", "a/mid.txt" to "b\n", "a/b/deep.txt" to "c\n"),
        )
        val shallow = glob(backend, "", "**/*.txt", limits(maxDepth = 1))
        assertEquals(listOf("a/mid.txt", "top.txt"), shallow.matches.map { it.relativePath }.sorted())
        assertTrue(shallow.truncated, "a pruned subtree means the result is incomplete")
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.MAX_DEPTH in shallow.reasons)
        assertTrue(
            shallow.matches.none { it.relativePath == "a/b/deep.txt" },
            "no entry below the depth budget may be returned",
        )

        val deep = glob(backend, "", "**/*.txt", limits(maxDepth = 8))
        assertEquals(
            listOf("a/b/deep.txt", "a/mid.txt", "top.txt"),
            deep.matches.map { it.relativePath }.sorted(),
        )
        assertFalse(deep.truncated, "a fully covered tree is not truncated: ${deep.reasons}")
    }

    @Test
    fun entryBudgetStopsTheWalkAndReportsItsExactReason() = runBlocking {
        val backend = FakeSearchBackend(files = (1..5).associate { "f$it.txt" to "x\n" })
        val outcome = glob(backend, "", "*", limits(maxScannedEntries = 3))
        assertTrue(outcome.truncated)
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.MAX_SCANNED_ENTRIES in outcome.reasons)
        assertEquals(3, outcome.counters.entries, "the scan stops exactly at its budget")
        assertEquals(3, outcome.matches.size)
    }

    @Test
    fun fileBudgetStopsTheWalkAndReportsItsExactReason() = runBlocking {
        val backend = FakeSearchBackend(files = (1..5).associate { "f$it.txt" to "x\n" })
        val outcome = glob(backend, "", "*", limits(maxScannedFiles = 2))
        assertTrue(outcome.truncated)
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.MAX_SCANNED_FILES in outcome.reasons)
        assertEquals(2, outcome.counters.files)
    }

    @Test
    fun grepHonoursTheByteBudgetInsteadOfClaimingNoMatch() = runBlocking {
        val backend = FakeSearchBackend(files = mapOf("note.txt" to "needle\nneedle\nneedle\n"))
        val outcome = grep(backend, "", "needle", limits(maxScannedBytes = 8, maxResults = 10))
        assertEquals(1, outcome.matches.size)
        assertTrue(outcome.truncated)
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.MAX_SCANNED_BYTES in outcome.reasons)
        assertEquals(8L, outcome.counters.bytes)
    }

    @Test
    fun resultBudgetDistinguishesAnExactFitFromMoreMatches() = runBlocking {
        val exact = grep(
            FakeSearchBackend(files = mapOf("a.txt" to "needle\n", "b.txt" to "needle\n", "c.txt" to "needle\n")),
            "", "needle", limits(maxResults = 3),
        )
        assertEquals(3, exact.matches.size)
        assertFalse(exact.truncated, "exactly filling the budget is not truncation: ${exact.reasons}")

        val overflow = grep(
            FakeSearchBackend(
                files = mapOf("a.txt" to "needle\n", "b.txt" to "needle\n", "c.txt" to "needle\n", "d.txt" to "needle\n"),
            ),
            "", "needle", limits(maxResults = 3),
        )
        assertEquals(3, overflow.matches.size)
        assertTrue(overflow.truncated, "a fourth match must be reported as truncation, never hidden")
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.MAX_RESULTS in overflow.reasons)
    }

    @Test
    fun grepReportsOneHitPerLineWithCorrectLineAndColumn() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("a.txt" to "alpha\nbeta NEEDLE gamma\nNEEDLE again\nneedle twice\n"),
        )
        val insensitive = grep(backend, "", "NEEDLE")
        assertEquals(listOf(2L, 3L, 4L), insensitive.matches.map { it.lineNumber })
        assertEquals(6, insensitive.matches.first().column)
        assertEquals("beta NEEDLE gamma", insensitive.matches.first().line)
        assertFalse(insensitive.matches.first().lineTruncated)

        val sensitive = grep(backend, "", "NEEDLE", caseSensitive = true)
        assertEquals(listOf(2L, 3L), sensitive.matches.map { it.lineNumber })
    }

    @Test
    fun deadlineStopsBeforeAnyBackendIo() = runBlocking {
        val backend = FakeSearchBackend(files = mapOf("a.txt" to "needle\n"))
        var ticks = 0
        val outcome = grep(
            backend, "", "needle",
            limits(maxMillis = 10),
            // The gate captures its start from the first reading and sees the budget gone next.
            clock = ToolingClock { if (ticks++ == 0) 0L else 10_000L },
        )
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.DEADLINE in outcome.reasons)
        assertTrue(outcome.truncated)
        assertEquals(0, backend.listCalls, "an expired deadline must not touch the provider")
        assertEquals(0, backend.readAttempts)
    }

    @Test
    fun authorityGuardFailureStopsBeforeAnyBackendIo() = runBlocking {
        val backend = FakeSearchBackend(files = mapOf("a.txt" to "needle\n"))
        var guardCalls = 0
        val outcome = grep(backend, "", "needle", authorityGuard = { guardCalls += 1; false })
        assertTrue(outcome.authorityFailed)
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.AUTHORITY_REVALIDATION_FAILED in outcome.reasons)
        assertEquals(0, backend.listCalls)
        assertTrue(guardCalls >= 1)
    }

    /**
     * A revocation must be observed before the next provider call, so the gate runs on every I/O
     * rather than once at the start or at a coarse interval.
     */
    @Test
    fun everyBackendIoRevalidatesAuthorityAndNotOnlyTheFirst() = runBlocking {
        val backend = FakeSearchBackend(files = mapOf("a.txt" to "needle\n", "b.txt" to "needle\n"))
        var guardCalls = 0
        val outcome = grep(backend, "", "needle", authorityGuard = { guardCalls += 1; true })
        assertEquals(3, guardCalls, "one list plus two reads must each consult the gate")
        assertEquals(2, outcome.matches.size)
        assertFalse(outcome.truncated)
    }

    @Test
    fun aRevocationInTheMiddleOfTheWalkStopsItImmediately() = runBlocking {
        val backend = FakeSearchBackend(files = mapOf("a.txt" to "needle\n", "b.txt" to "needle\n"))
        var guardCalls = 0
        val outcome = grep(backend, "", "needle", authorityGuard = { guardCalls += 1; guardCalls <= 2 })
        assertTrue(outcome.authorityFailed, "the lost authorization must terminate the search")
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.AUTHORITY_REVALIDATION_FAILED in outcome.reasons)
        assertEquals(1, backend.listCalls)
        assertEquals(1, backend.readAttempts, "no further file may be read after the revocation")
        assertTrue(outcome.matches.isEmpty(), "an unauthorized search must not disclose a partial result")
    }

    @Test
    fun cancellationIsNeverSwallowedByTheEngine() {
        val failure = runCatching {
            runBlocking {
                glob(CancellingSearchBackend(), "", "*")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException, "expected cancellation, got $failure")
    }

    @Test
    fun anAlreadyCancelledRunNeverTouchesTheProvider() {
        val backend = FakeSearchBackend(files = mapOf("a.txt" to "needle\n"))
        val cancelled = Job().apply { cancel() }
        val failure = runCatching {
            runBlocking(cancelled) {
                grep(backend, "", "needle")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException, "expected cancellation, got $failure")
        assertEquals(0, backend.listCalls)
    }

    // ------------------------------------------- degradation versus termination

    @Test
    fun unreadableFileIsCountedAndNeverReportedAsNoMatch() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("good.txt" to "needle here\n", "blob.bin" to "binary"),
            binary = setOf("blob.bin"),
        )
        val outcome = grep(backend, "", "needle")
        assertEquals(listOf("good.txt"), outcome.matches.map { it.relativePath })
        assertTrue(outcome.truncated)
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.UNREADABLE_ENTRIES in outcome.reasons)
        assertEquals(1, outcome.counters.unreadableEntries)
        assertNull(outcome.failure, "a non-UTF-8 file is a recoverable per-entry degradation")
    }

    @Test
    fun aFailingDirectoryDoesNotHideTheRestOfTheTree() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("keep.txt" to "needle\n", "gone/inner.txt" to "needle\n"),
            unlistableDirectories = setOf("gone"),
        )
        val outcome = grep(backend, "", "needle")
        assertEquals(listOf("keep.txt"), outcome.matches.map { it.relativePath })
        assertEquals(1, outcome.counters.unreadableEntries)
        assertTrue(outcome.truncated)
        assertNull(outcome.failure)
    }

    @Test
    fun aDeniedRootListingFailsTheWholeSearchInsteadOfReturningAPartialResult() = runBlocking {
        val backend = FakeSearchBackend(files = mapOf("a.txt" to "needle\n"))
        backend.denyListing = true
        val outcome = grep(backend, "", "needle")
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, outcome.failure?.code)
        assertTrue(outcome.matches.isEmpty())
    }

    @Test
    fun aDeniedReadTerminatesTheSearchWithoutAPartialResult() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("blocked.txt" to "needle\n", "ok.txt" to "needle\n"),
            terminalFailures = mapOf("blocked.txt" to ToolErrorCode.PERMISSION_DENIED),
        )
        val outcome = grep(backend, "", "needle")
        assertEquals(ToolErrorCode.PERMISSION_DENIED, outcome.failure?.code)
        assertEquals(0, outcome.counters.unreadableEntries, "a denial is not a per-file skip")
        assertTrue(outcome.matches.isEmpty(), "a terminated search discloses nothing")
        assertEquals(1, backend.readAttempts, "a denial is terminal and is not retried")
    }

    @Test
    fun anAmbiguousReadResultTerminatesTheSearch() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("a.txt" to "needle\n"),
            terminalFailures = mapOf("a.txt" to ToolErrorCode.UNKNOWN_OUTCOME),
        )
        val outcome = grep(backend, "", "needle")
        assertEquals(ToolErrorCode.UNKNOWN_OUTCOME, outcome.failure?.code)
        assertTrue(outcome.matches.isEmpty())
    }

    @Test
    fun aDeniedSubdirectoryListingTerminatesInsteadOfDegrading() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("keep.txt" to "needle\n", "blocked/inner.txt" to "needle\n"),
            terminalListFailures = setOf("blocked"),
        )
        val outcome = grep(backend, "", "needle")
        assertEquals(ToolErrorCode.CAPABILITY_DENIED, outcome.failure?.code)
        assertEquals(0, outcome.counters.unreadableEntries)
        assertTrue(outcome.matches.isEmpty(), "a terminal subdirectory denial discloses nothing")
    }

    // ------------------------------------------------------------ UTF-8 retries

    @Test
    fun aSplitCharacterRetrySucceedsAndKeepsTheMatch() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("note.txt" to "needle\n"),
            splitFailureSizes = setOf(WorkspaceSearch.READ_CHUNK_BYTES),
        )
        val outcome = grep(backend, "", "needle")
        assertEquals(1, outcome.matches.size, "the retry must still find the match")
        assertEquals(2, backend.readAttempts, "the first attempt failed, the shorter retry decoded")
        assertEquals(0, outcome.counters.unreadableEntries, "a split character is not a bad file")
        assertFalse(outcome.truncated, "a recovered read is not truncation: ${outcome.reasons}")
        assertNull(outcome.failure)
    }

    /**
     * The retry inside one logical read is still backend I/O, so it must pass the same
     * cancellation/deadline/authority gate before touching the provider again.
     */
    @Test
    fun theUtf8RetryPathConsultsTheAuthorityGate() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("note.txt" to "needle\n"),
            splitFailureSizes = setOf(WorkspaceSearch.READ_CHUNK_BYTES),
        )
        var guardCalls = 0
        val outcome = grep(
            backend, "", "needle",
            // 1st call: the directory listing. 2nd: the first read attempt. 3rd: the retry.
            authorityGuard = { guardCalls += 1; guardCalls < 3 },
        )
        assertTrue(outcome.authorityFailed)
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.AUTHORITY_REVALIDATION_FAILED in outcome.reasons)
        assertEquals(1, backend.readAttempts, "a revoked retry must not reach the provider again")
        assertTrue(outcome.matches.isEmpty())
    }

    @Test
    fun theUtf8RetryPathHonoursTheDeadline() = runBlocking {
        val backend = FakeSearchBackend(
            files = mapOf("note.txt" to "needle\n"),
            splitFailureSizes = setOf(WorkspaceSearch.READ_CHUNK_BYTES),
        )
        var ticks = 0
        val outcome = grep(
            backend, "", "needle",
            limits(maxMillis = 10),
            // Start at t=0, then every later reading is already past the budget.
            clock = ToolingClock { if (ticks++ == 0) 0L else 10_000L },
        )
        assertTrue(WorkspaceSearch.WorkspaceSearchReason.DEADLINE in outcome.reasons)
        assertEquals(0, backend.listCalls, "the deadline is observed before the first provider call")
    }

    // -------------------------------------------- real backend chunk boundaries

    @Test
    fun grepReconstructsALineThatStraddlesTwoReadChunks() = runBlocking {
        val adapter = internalAdapter()
        val prefix = "x".repeat(WorkspaceSearch.READ_CHUNK_BYTES.toInt() - 2)
        assertTrue(writeFile(adapter, "wide.txt", prefix + "NEEDLE" + "\n"))
        val outcome = realGrep(adapter, "NEEDLE")
        assertEquals(1, outcome.matches.size, "a match across the chunk boundary must still be found")
        assertEquals(1L, outcome.matches.single().lineNumber)
        assertEquals(WorkspaceSearch.READ_CHUNK_BYTES.toInt() - 1, outcome.matches.single().column)
        assertEquals(0, outcome.counters.unreadableEntries)
    }

    @Test
    fun grepRecoversFromAMultiByteCharacterSplitAcrossChunks() = runBlocking {
        val adapter = internalAdapter()
        // 'é' is two UTF-8 bytes and starts at the last byte of the first chunk, so a strict
        // decode of that chunk fails exactly like the real adapter's chunk decode.
        val prefix = "x".repeat(WorkspaceSearch.READ_CHUNK_BYTES.toInt() - 1)
        assertTrue(writeFile(adapter, "unicode.txt", prefix + "éNEEDLE\n"))
        val outcome = realGrep(adapter, "NEEDLE")
        assertEquals(1, outcome.matches.size, "a split multi-byte character must not hide a match")
        assertEquals(WorkspaceSearch.READ_CHUNK_BYTES.toInt() + 1, outcome.matches.single().column)
        assertEquals(0, outcome.counters.unreadableEntries, "a split character is not an unreadable file")
        assertFalse(outcome.truncated, "an in-budget read is not truncated: ${outcome.reasons}")
    }

    @Test
    fun grepFindsUnicodeTextInsideTheRealBackend() = runBlocking {
        val adapter = internalAdapter()
        assertTrue(writeFile(adapter, "notes.md", "第一行\n包含 检索词 的说明\n第三行\n"))
        val outcome = realGrep(adapter, "检索词")
        assertEquals(1, outcome.matches.size)
        assertEquals(2L, outcome.matches.single().lineNumber)
        assertEquals(4, outcome.matches.single().column)
        assertEquals("包含 检索词 的说明", outcome.matches.single().line)
    }

    @Test
    fun globOverTheRealBackendNeverLeaksASiblingSubtree() = runBlocking {
        val adapter = internalAdapter()
        assertTrue(writeFile(adapter, "src/Main.kt", "fun main() {}\n"))
        assertTrue(writeFile(adapter, "src/Util.kt", "fun util() {}\n"))
        assertTrue(writeFile(adapter, "docs/readme.md", "text\n"))
        val outcome = WorkspaceSearch.glob(
            backend = adapter,
            workspaceId = WORKSPACE_ID,
            root = "src",
            patternText = "**/*.kt",
            pattern = globOf("**/*.kt"),
            limits = limits(),
            clock = ToolingClock { 0L },
            authorityGuard = { true },
        )
        assertEquals(listOf("src/Main.kt", "src/Util.kt"), outcome.matches.map { it.relativePath }.sorted())
        assertTrue(
            outcome.matches.all { it.relativePath.startsWith("src/") },
            "a bounded root must never disclose a sibling subtree",
        )
    }

    // ------------------------------------------------------------- fixtures

    @TempDir
    lateinit var tempDir: Path

    private var adapterCounter = 0

    private suspend fun glob(
        backend: WorkspaceBackend,
        root: String,
        pattern: String,
        searchLimits: WorkspaceSearch.WorkspaceSearchLimits = limits(),
        clock: ToolingClock = ToolingClock { 0L },
        authorityGuard: suspend () -> Boolean = { true },
    ): WorkspaceSearch.WorkspaceSearchGlobOutcome = WorkspaceSearch.glob(
        backend = backend,
        workspaceId = WORKSPACE_ID,
        root = root,
        patternText = pattern,
        pattern = globOf(pattern),
        limits = searchLimits,
        clock = clock,
        authorityGuard = authorityGuard,
    )

    private suspend fun grep(
        backend: WorkspaceBackend,
        root: String,
        query: String,
        searchLimits: WorkspaceSearch.WorkspaceSearchLimits = limits(),
        caseSensitive: Boolean = false,
        clock: ToolingClock = ToolingClock { 0L },
        authorityGuard: suspend () -> Boolean = { true },
    ): WorkspaceSearch.WorkspaceSearchGrepOutcome = WorkspaceSearch.grep(
        backend = backend,
        workspaceId = WORKSPACE_ID,
        root = root,
        query = query,
        caseSensitive = caseSensitive,
        limits = searchLimits,
        clock = clock,
        authorityGuard = authorityGuard,
    )

    private suspend fun realGrep(
        adapter: SharedWorkspaceBackendAdapter,
        query: String,
    ): WorkspaceSearch.WorkspaceSearchGrepOutcome = grep(
        backend = adapter,
        root = "",
        query = query,
        searchLimits = limits(maxScannedBytes = 1_024L * 1_024L),
    )

    private fun internalAdapter(): SharedWorkspaceBackendAdapter {
        adapterCounter += 1
        val root = tempDir.resolve("search-$adapterCounter")
        return SharedWorkspaceBackendAdapter(InternalWorkspaceBackend(root, workspaceId = WORKSPACE_ID))
    }

    private suspend fun writeFile(adapter: SharedWorkspaceBackendAdapter, path: String, text: String): Boolean {
        val directory = path.substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            val created = adapter.createDirectory(
                WorkspaceCreateDirectoryRequest(workspaceId = WORKSPACE_ID, relativePath = directory),
            )
            if (created is WorkspaceResult.Failure) return false
        }
        val written = adapter.writeText(
            WorkspaceWriteTextRequest(workspaceId = WORKSPACE_ID, relativePath = path, text = text, replace = true),
        )
        return written is WorkspaceResult.Success
    }

    private fun globOf(pattern: String): WorkspaceSearchMatching.WorkspaceSearchGlob =
        WorkspaceSearchMatching.WorkspaceSearchGlob.parse(pattern) ?: fail("pattern should be valid: $pattern")

    private fun limits(
        maxDepth: Int = 8,
        maxScannedEntries: Int = 2_000,
        maxScannedFiles: Int = 500,
        maxScannedBytes: Long = 512L * 1024L,
        maxResults: Int = 100,
        maxMillis: Long = 5_000L,
    ): WorkspaceSearch.WorkspaceSearchLimits = WorkspaceSearch.WorkspaceSearchLimits(
        maxDepth = maxDepth,
        maxScannedEntries = maxScannedEntries,
        maxScannedFiles = maxScannedFiles,
        maxScannedBytes = maxScannedBytes,
        maxResults = maxResults,
        maxMillis = maxMillis,
    )

    private companion object {
        const val WORKSPACE_ID = "ws-search"
    }
}

/**
 * Small deterministic in-memory tree.  It implements the shared contract — including cursor
 * pagination and strict UTF-8 chunk decoding — so the engine is exercised through exactly the same
 * seam as the internal/SAF adapters.
 */
private class FakeSearchBackend(
    private val files: Map<String, String>,
    private val pageSize: Int = 256,
    private val binary: Set<String> = emptySet(),
    private val unlistableDirectories: Set<String> = emptySet(),
    private val terminalListFailures: Set<String> = emptySet(),
    private val terminalFailures: Map<String, ToolErrorCode> = emptyMap(),
    private val splitFailureSizes: Set<Long> = emptySet(),
) : WorkspaceBackend {
    var listCalls = 0
    var readAttempts = 0
    var denyListing = false
    var beforeList: (() -> Unit)? = null

    override val descriptor: WorkspaceDescriptor = WorkspaceDescriptor(
        id = "ws-search",
        displayName = "Search fixture",
        backendType = WorkspaceBackendType.INTERNAL,
    )

    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.FILE_LIST),
        CapabilityId(CapabilityId.FILE_STAT),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
    )

    override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
        listCalls += 1
        beforeList?.invoke()
        if (denyListing) return WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
        val directory = request.relativePath ?: ""
        if (directory in terminalListFailures) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.CAPABILITY_DENIED))
        }
        if (directory in unlistableDirectories) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.ENTRY_NOT_FOUND))
        }
        if (directory in files) return WorkspaceResult.Failure(ToolError(ToolErrorCode.UNSUPPORTED_ENTRY))
        val children = childrenOf(directory)
        val start = request.cursor?.removePrefix(CURSOR_PREFIX)?.toIntOrNull() ?: 0
        if (start < 0 || start > children.size) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_CURSOR))
        }
        val end = minOf(start + minOf(request.maxEntries, pageSize), children.size)
        return WorkspaceResult.Success(
            WorkspaceListing(
                relativePath = if (directory.isEmpty()) "." else directory,
                entries = children.subList(start, end),
                truncated = end < children.size,
                nextCursor = if (end < children.size) "$CURSOR_PREFIX$end" else null,
            ),
        )
    }

    override suspend fun readText(request: WorkspaceReadTextRequest): WorkspaceResult<WorkspaceText> {
        readAttempts += 1
        terminalFailures[request.relativePath]?.let { return WorkspaceResult.Failure(ToolError(it)) }
        if (request.relativePath in binary) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        }
        if (request.maxBytes in splitFailureSizes) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        }
        val text = files[request.relativePath]
            ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.ENTRY_NOT_FOUND))
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (request.offsetBytes > bytes.size) {
            return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        }
        val end = minOf(request.offsetBytes + request.maxBytes, bytes.size.toLong()).toInt()
        val slice = bytes.copyOfRange(request.offsetBytes.toInt(), end)
        val decoded = strictDecode(slice)
            ?: return WorkspaceResult.Failure(ToolError(ToolErrorCode.INVALID_REQUEST))
        return WorkspaceResult.Success(
            WorkspaceText(
                relativePath = request.relativePath,
                text = decoded,
                byteSize = slice.size.toLong(),
                version = 1L,
                offsetBytes = request.offsetBytes,
                totalBytes = bytes.size.toLong(),
                eof = end.toLong() >= bytes.size.toLong(),
            ),
        )
    }

    private fun childrenOf(directory: String): List<WorkspaceEntry> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val names = sortedSetOf<String>()
        for (path in files.keys) {
            if (!path.startsWith(prefix)) continue
            val rest = path.substring(prefix.length)
            if (rest.isEmpty()) continue
            names += rest.substringBefore('/')
        }
        return names.map { name ->
            val childPath = prefix + name
            val isDirectory = files.keys.any { it.startsWith("$childPath/") }
            WorkspaceEntry(
                relativePath = childPath,
                type = if (isDirectory) WorkspaceEntryType.DIRECTORY else WorkspaceEntryType.FILE,
                sizeBytes = files[childPath]?.toByteArray(StandardCharsets.UTF_8)?.size?.toLong() ?: 0L,
                version = 1L,
            )
        }
    }

    private fun strictDecode(bytes: ByteArray): String? = try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private companion object {
        const val CURSOR_PREFIX = "c:"
    }
}

/** Always cancels instead of answering, to prove the engine never swallows it. */
private class CancellingSearchBackend : WorkspaceBackend {
    override val descriptor: WorkspaceDescriptor = WorkspaceDescriptor(
        id = "ws-search",
        displayName = "Cancelling fixture",
        backendType = WorkspaceBackendType.INTERNAL,
    )

    override val capabilities: Set<CapabilityId> = setOf(
        CapabilityId(CapabilityId.FILE_LIST),
        CapabilityId(CapabilityId.FILE_READ_TEXT),
    )

    override suspend fun list(request: WorkspaceListRequest): WorkspaceResult<WorkspaceListing> {
        throw CancellationException("user cancelled the run")
    }
}
