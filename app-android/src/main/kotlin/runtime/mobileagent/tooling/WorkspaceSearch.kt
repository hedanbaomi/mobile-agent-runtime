// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import runtime.mobileagent.skills.tooling.ToolError
import runtime.mobileagent.skills.tooling.ToolErrorCode
import runtime.mobileagent.skills.tooling.WorkspaceBackend
import runtime.mobileagent.skills.tooling.WorkspaceEntryType
import runtime.mobileagent.skills.tooling.WorkspaceListRequest
import runtime.mobileagent.skills.tooling.WorkspaceListingWarningCode
import runtime.mobileagent.skills.tooling.WorkspaceReadTextRequest
import runtime.mobileagent.skills.tooling.WorkspaceResult
import runtime.mobileagent.tooling.WorkspaceSearchMatching.WorkspaceSearchGlob
import runtime.mobileagent.tooling.WorkspaceSearchMatching.WorkspaceSearchGrepScanner

/**
 * Bounded, backend-neutral recursive workspace search.
 *
 * The engine only ever uses the shared [WorkspaceBackend] contract (paginated `list` and
 * offset-based `readText`), so the internal, SAF and privileged adapters all work without a
 * Node/ripgrep binary, a raw URI or a platform absolute path.  Every traversal is bounded by an
 * explicit depth, entry, file, byte, result and wall-clock budget, observes coroutine
 * cancellation, and reports what it did instead of pretending an exhausted budget was "no match".
 *
 * The pure glob matcher and line scanner live in `WorkspaceSearchMatching.kt`; the safe
 * schema/parsing/rendering of the two model tools lives in `WorkspaceSearchProtocol.kt`.
 *
 * Nothing in this file decides authorization on its own.  The caller passes an authority guard
 * which is re-evaluated before **every** backend call, so a revocation stops the walk before the
 * next provider I/O and before any result is disclosed.
 */
internal object WorkspaceSearch {
    const val DEFAULT_MAX_DEPTH = 8
    const val MAX_DEPTH_LIMIT = 16

    const val DEFAULT_MAX_SCANNED_ENTRIES = 2_000
    const val MAX_SCANNED_ENTRIES_LIMIT = 20_000

    const val DEFAULT_MAX_SCANNED_FILES = 500
    const val MAX_SCANNED_FILES_LIMIT = 5_000

    const val DEFAULT_MAX_SCANNED_BYTES = 512L * 1024L
    const val MAX_SCANNED_BYTES_LIMIT = 4L * 1024L * 1024L

    const val DEFAULT_MAX_RESULTS = 100
    const val MAX_RESULTS_LIMIT = 1_000

    const val DEFAULT_MAX_MILLIS = 5_000L
    const val MAX_MILLIS_LIMIT = 30_000L

    /** One backend read request never exceeds the backend's own bounded read contract. */
    const val READ_CHUNK_BYTES = 64L * 1024L

    /** Backend-owned page size for one `list` call; a directory is followed to its last page. */
    const val LIST_PAGE_ENTRIES = 256

    /** Defensive cap so a hostile/broken cursor that never advances cannot loop forever. */
    const val MAX_PAGES_PER_DIRECTORY = 64

    /**
     * A chunk can end inside a 1..4 byte UTF-8 sequence.  The reader retries a few shorter
     * prefixes so a split character is never reported as an unreadable file.
     */
    const val MAX_UTF8_SHRINK = 3
    const val MIN_DECODABLE_CHUNK_BYTES = 4L

    /** The rendered search result stays well below the executor's outer result budget. */
    const val RESULT_TARGET_BYTES = 512 * 1024

    // ---------------------------------------------------------------- budgets

    internal data class WorkspaceSearchLimits(
        val maxDepth: Int,
        val maxScannedEntries: Int,
        val maxScannedFiles: Int,
        val maxScannedBytes: Long,
        val maxResults: Int,
        val maxMillis: Long,
    ) {
        init {
            require(maxDepth in 0..MAX_DEPTH_LIMIT)
            require(maxScannedEntries in 1..MAX_SCANNED_ENTRIES_LIMIT)
            require(maxScannedFiles in 1..MAX_SCANNED_FILES_LIMIT)
            require(maxScannedBytes in 1..MAX_SCANNED_BYTES_LIMIT)
            require(maxResults in 1..MAX_RESULTS_LIMIT)
            require(maxMillis in 1..MAX_MILLIS_LIMIT)
        }
    }

    /**
     * Why a search stopped covering its subtree.  Every value is a stable, path-free wire string:
     * the model is told the result is incomplete, never which unauthorized entry caused it.
     */
    internal enum class WorkspaceSearchReason(val wireName: String) {
        MAX_RESULTS("max_results"),
        MAX_SCANNED_ENTRIES("max_scanned_entries"),
        MAX_SCANNED_FILES("max_scanned_files"),
        MAX_SCANNED_BYTES("max_scanned_bytes"),
        MAX_DEPTH("max_depth"),
        DEADLINE("deadline"),
        AUTHORITY_REVALIDATION_FAILED("authority_revalidation_failed"),
        UNREADABLE_ENTRIES("unreadable_entries"),
        LISTING_TRUNCATED("listing_truncated"),
        RESULT_BYTES("result_bytes"),
    }

    internal class WorkspaceSearchCounters {
        var directories: Int = 0
        var entries: Int = 0
        var files: Int = 0
        var bytes: Long = 0L
        var skippedEntries: Int = 0
        var unreadableEntries: Int = 0
        val warnings: MutableMap<WorkspaceListingWarningCode, Int> = linkedMapOf()
    }

    internal data class WorkspaceSearchEntry(
        val relativePath: String,
        val type: WorkspaceEntryType,
        val depth: Int,
        val sizeBytes: Long,
    )

    internal data class WorkspaceSearchGrepHit(
        val relativePath: String,
        val lineNumber: Long,
        val column: Int,
        val line: String,
        val lineTruncated: Boolean,
    )

    internal sealed interface WorkspaceSearchOutcome {
        val root: String
        val counters: WorkspaceSearchCounters
        val truncated: Boolean
        val reasons: List<WorkspaceSearchReason>
        val authorityFailed: Boolean
        /** A typed hard failure (for example a root listing denial); no partial result is disclosed. */
        val failure: ToolError?
    }

    internal data class WorkspaceSearchGlobOutcome(
        override val root: String,
        val pattern: String,
        val matches: List<WorkspaceSearchEntry>,
        override val counters: WorkspaceSearchCounters,
        override val truncated: Boolean,
        override val reasons: List<WorkspaceSearchReason>,
        override val authorityFailed: Boolean,
        override val failure: ToolError?,
    ) : WorkspaceSearchOutcome

    internal data class WorkspaceSearchGrepOutcome(
        override val root: String,
        val query: String,
        val caseSensitive: Boolean,
        val matches: List<WorkspaceSearchGrepHit>,
        override val counters: WorkspaceSearchCounters,
        override val truncated: Boolean,
        override val reasons: List<WorkspaceSearchReason>,
        override val authorityFailed: Boolean,
        override val failure: ToolError?,
    ) : WorkspaceSearchOutcome

    // ---------------------------------------------------------------- entry points

    suspend fun glob(
        backend: WorkspaceBackend,
        workspaceId: String,
        root: String,
        patternText: String,
        pattern: WorkspaceSearchGlob,
        limits: WorkspaceSearchLimits,
        clock: ToolingClock,
        authorityGuard: suspend () -> Boolean,
    ): WorkspaceSearchGlobOutcome {
        val counters = WorkspaceSearchCounters()
        val gate = WorkspaceSearchGate(limits, clock, authorityGuard)
        val matches = mutableListOf<WorkspaceSearchEntry>()
        val traversal = traverse(backend, workspaceId, root, limits, counters, gate) { entry ->
            if (!pattern.matches(entry.relativePath)) {
                null
            } else if (matches.size >= limits.maxResults) {
                WorkspaceSearchStop.Budget(WorkspaceSearchReason.MAX_RESULTS)
            } else {
                matches += entry
                null
            }
        }
        return WorkspaceSearchGlobOutcome(
            root = root,
            pattern = patternText,
            // A search that lost its authorization discloses nothing, not even the
            // matches it collected while it was still allowed to read.
            matches = if (traversal.authorityFailed || traversal.failure != null) emptyList() else matches,
            counters = traversal.counters,
            truncated = traversal.truncated,
            reasons = traversal.reasons,
            authorityFailed = traversal.authorityFailed,
            failure = traversal.failure,
        )
    }

    suspend fun grep(
        backend: WorkspaceBackend,
        workspaceId: String,
        root: String,
        query: String,
        caseSensitive: Boolean,
        limits: WorkspaceSearchLimits,
        clock: ToolingClock,
        authorityGuard: suspend () -> Boolean,
    ): WorkspaceSearchGrepOutcome {
        val counters = WorkspaceSearchCounters()
        val gate = WorkspaceSearchGate(limits, clock, authorityGuard)
        val matches = mutableListOf<WorkspaceSearchGrepHit>()
        val traversal = traverse(backend, workspaceId, root, limits, counters, gate) { entry ->
            if (entry.type != WorkspaceEntryType.FILE) {
                null
            } else {
                scanFile(backend, workspaceId, entry, query, caseSensitive, limits, counters, gate, matches)
            }
        }
        return WorkspaceSearchGrepOutcome(
            root = root,
            query = query,
            caseSensitive = caseSensitive,
            // A search that lost its authorization discloses nothing, not even the
            // hits it collected while it was still allowed to read.
            matches = if (traversal.authorityFailed || traversal.failure != null) emptyList() else matches,
            counters = traversal.counters,
            truncated = traversal.truncated,
            reasons = traversal.reasons,
            authorityFailed = traversal.authorityFailed,
            failure = traversal.failure,
        )
    }

    // ---------------------------------------------------------------- traversal

    internal data class WorkspaceSearchTraversal(
        val counters: WorkspaceSearchCounters,
        val reasons: List<WorkspaceSearchReason>,
        val authorityFailed: Boolean,
        val failure: ToolError?,
    ) {
        val truncated: Boolean get() = reasons.isNotEmpty()
    }

    /**
     * Walk the authorized subtree rooted at [root] with explicit depth/entry/file budgets and a
     * pre-I/O gate.  Returns only path-free counters and bounded reason codes.
     *
     * The [visitor] may stop the walk with a bounded budget reason, or terminate it with a typed
     * failure when it can no longer prove it is allowed to continue.  A failure never yields a
     * partial result.
     */
    private suspend fun traverse(
        backend: WorkspaceBackend,
        workspaceId: String,
        root: String,
        limits: WorkspaceSearchLimits,
        counters: WorkspaceSearchCounters,
        gate: WorkspaceSearchGate,
        visitor: suspend (WorkspaceSearchEntry) -> WorkspaceSearchStop?,
    ): WorkspaceSearchTraversal {
        // Pruning/degradation reasons that do not stop the walk but do prove incompleteness.
        val degradedReasons = linkedSetOf<WorkspaceSearchReason>()
        var stopReason: WorkspaceSearchReason? = null
        var failure: ToolError? = null
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(root to 0)

        walk@ while (pending.isNotEmpty()) {
            val (directory, directoryDepth) = pending.removeLast()
            counters.directories += 1
            val descend = mutableListOf<Pair<String, Int>>()
            var cursor: String? = null
            var pages = 0
            do {
                val gateReason = gate.before()
                if (gateReason != null) {
                    stopReason = gateReason
                    break
                }
                if (pages >= MAX_PAGES_PER_DIRECTORY) {
                    degradedReasons += WorkspaceSearchReason.LISTING_TRUNCATED
                    break
                }
                val remaining = limits.maxScannedEntries - counters.entries
                if (remaining <= 0) {
                    stopReason = WorkspaceSearchReason.MAX_SCANNED_ENTRIES
                    break
                }
                pages += 1
                val listed = backend.list(
                    WorkspaceListRequest(
                        workspaceId = workspaceId,
                        relativePath = directory.ifEmpty { null },
                        maxEntries = minOf(LIST_PAGE_ENTRIES, remaining),
                        cursor = cursor,
                    ),
                )
                val listing = when (listed) {
                    is WorkspaceResult.Success -> listed.value
                    is WorkspaceResult.Failure -> {
                        // A denial/authority/ambiguous failure anywhere in the walk is never a
                        // partial success; only a recoverable per-directory fault degrades.
                        if (directory == root || listed.error.code.isSearchTerminal()) {
                            failure = listed.error
                        } else {
                            counters.unreadableEntries += 1
                        }
                        break
                    }
                }
                counters.skippedEntries += listing.skippedEntries
                for (warning in listing.warnings) {
                    counters.warnings[warning.code] = (counters.warnings[warning.code] ?: 0) + warning.count
                }
                if (listing.truncated && listing.nextCursor == null) {
                    // The adapter claims more children but issued no continuation: incomplete.
                    degradedReasons += WorkspaceSearchReason.LISTING_TRUNCATED
                }
                val level = directoryDepth + 1
                for (entry in listing.entries) {
                    if (counters.entries >= limits.maxScannedEntries) {
                        stopReason = WorkspaceSearchReason.MAX_SCANNED_ENTRIES
                        break
                    }
                    counters.entries += 1
                    val path = entry.relativePath
                    if (entry.type == WorkspaceEntryType.FILE) {
                        if (counters.files >= limits.maxScannedFiles) {
                            stopReason = WorkspaceSearchReason.MAX_SCANNED_FILES
                            break
                        }
                        counters.files += 1
                    } else {
                        val depthOk = level <= limits.maxDepth
                        val pathOk = workspaceDepth(path) <= WorkspacePathPolicy.MAX_DEPTH
                        if (depthOk && pathOk) {
                            descend += path to level
                        } else {
                            degradedReasons += WorkspaceSearchReason.MAX_DEPTH
                        }
                    }
                    when (val visit = visitor(WorkspaceSearchEntry(path, entry.type, level, entry.sizeBytes))) {
                        null -> Unit
                        is WorkspaceSearchStop.Budget -> {
                            stopReason = visit.reason
                            break
                        }
                        is WorkspaceSearchStop.Failure -> {
                            failure = visit.error
                            break
                        }
                    }
                }
                if (stopReason != null || failure != null) break
                cursor = listing.nextCursor
            } while (cursor != null)
            if (stopReason != null || failure != null) break@walk
            // Push in reverse so the lowest sibling directory is visited first (stable order).
            for (index in descend.indices.reversed()) pending.addLast(descend[index])
        }

        val reasons = linkedSetOf<WorkspaceSearchReason>()
        reasons += degradedReasons
        if (counters.unreadableEntries > 0) reasons += WorkspaceSearchReason.UNREADABLE_ENTRIES
        if (stopReason != null) reasons += stopReason
        return WorkspaceSearchTraversal(
            counters = counters,
            reasons = reasons.toList(),
            authorityFailed = gate.authorityFailed,
            failure = failure,
        )
    }

    private fun workspaceDepth(path: String): Int =
        if (path.isEmpty()) 0 else path.count { it == '/' } + 1

    /**
     * Failures that must terminate the whole search and be reported as a typed failure rather than
     * as a partial result with a counter: a lost or denied authorization, an unavailable
     * authority/provider, or an externally ambiguous outcome.  Everything else (a non-UTF-8 chunk,
     * an entry that vanished mid-walk, a transient per-file I/O error) is a recoverable per-entry
     * degradation and is surfaced through `unreadable_entries` plus an explicit truncation reason.
     */
    private fun ToolErrorCode.isSearchTerminal(): Boolean = when (this) {
        ToolErrorCode.CAPABILITY_DENIED,
        ToolErrorCode.PERMISSION_DENIED,
        ToolErrorCode.AUTHORITY_NOT_GRANTED,
        ToolErrorCode.AUTHORITY_PROVIDER_NOT_SELECTED,
        ToolErrorCode.AUTHORITY_TEMPORARILY_UNAVAILABLE,
        ToolErrorCode.PATH_OUT_OF_SCOPE,
        ToolErrorCode.SYMLINK_FORBIDDEN,
        ToolErrorCode.ROOT_OPERATION_FORBIDDEN,
        ToolErrorCode.WORKSPACE_NOT_FOUND,
        ToolErrorCode.WORKSPACE_READ_ONLY,
        ToolErrorCode.DANGEROUS_MODE_DISABLED,
        ToolErrorCode.SNAPSHOT_STALE,
        ToolErrorCode.AUDIT_FUSE_OPEN,
        ToolErrorCode.AUDIT_UNAVAILABLE,
        ToolErrorCode.UNKNOWN_OUTCOME,
        ToolErrorCode.TIMEOUT,
        ToolErrorCode.SHELL_TIMED_OUT,
        ToolErrorCode.SHIZUKU_PERMISSION_DENIED,
        ToolErrorCode.SHIZUKU_SERVICE_UNAVAILABLE,
        ToolErrorCode.BRIDGE_NOT_PAIRED,
        ToolErrorCode.BRIDGE_DISCONNECTED,
        ToolErrorCode.BRIDGE_PROTOCOL_MISMATCH,
        ToolErrorCode.ADB_DEVICE_UNAUTHORIZED,
        ToolErrorCode.ADB_DEVICE_OFFLINE,
        ToolErrorCode.ADB_DEVICE_DISCONNECTED,
        ToolErrorCode.ADB_APP_NOT_INSTALLED,
            -> true
        else -> false
    }

    /**
     * Cancellation, wall-clock and authority revalidation in front of **every** backend I/O.
     *
     * This runs before each `list` call and before each `readText` attempt, including each UTF-8
     * shrink retry inside a single logical read.  There is deliberately no re-check interval: a
     * revoked grant, an expired deadline or a cancelled run must be observed before the next
     * provider call, never only at the end of the walk.  The caller supplies the authority check
     * (the executor re-resolves the exact subtree), so this class stays provider- and grant-free.
     */
    internal class WorkspaceSearchGate(
        private val limits: WorkspaceSearchLimits,
        private val clock: ToolingClock,
        private val authorityGuard: suspend () -> Boolean,
    ) {
        private val startedAt = clock.nowMillis()
        var authorityFailed: Boolean = false
            private set

        suspend fun before(): WorkspaceSearchReason? {
            currentCoroutineContext().ensureActive()
            if (clock.nowMillis() - startedAt >= limits.maxMillis) return WorkspaceSearchReason.DEADLINE
            if (!authorityGuard()) {
                authorityFailed = true
                return WorkspaceSearchReason.AUTHORITY_REVALIDATION_FAILED
            }
            return null
        }
    }

    // ---------------------------------------------------------------- chunked read

    private sealed interface WorkspaceSearchChunk {
        data class Data(val text: String, val consumedBytes: Long, val eof: Boolean) : WorkspaceSearchChunk
        /** The remaining byte budget is too small to hold a UTF-8 sequence; stop this file cleanly. */
        data object End : WorkspaceSearchChunk
        /** The pre-I/O gate stopped this attempt (cancelled, expired, or no longer authorized). */
        data class Stop(val reason: WorkspaceSearchReason) : WorkspaceSearchChunk
        data class Failure(val error: ToolError) : WorkspaceSearchChunk
    }

    /**
     * Outcome of one visited entry.
     *
     * A [Failure] aborts the whole search and is reported as a typed failure with no partial
     * result; only a [Budget] stop may return the counted matches collected so far.
     */
    internal sealed interface WorkspaceSearchStop {
        data class Budget(val reason: WorkspaceSearchReason) : WorkspaceSearchStop
        data class Failure(val error: ToolError) : WorkspaceSearchStop
    }

    /**
     * Read one bounded chunk through the shared contract.
     *
     * The adapter reports a chunk that ends inside a multi-byte UTF-8 sequence as
     * [ToolErrorCode.INVALID_REQUEST].  A few shorter prefixes are retried first, so a legitimate
     * character straddling the chunk boundary is not misreported as an unreadable file.  Because
     * callers always advance by the number of bytes that actually decoded, the next chunk starts
     * on a character boundary.
     *
     * Every attempt — the first one and each retry — passes the pre-I/O gate, so a revocation,
     * cancellation or expired deadline is observed before the provider is touched again even in
     * the middle of one logical read.
     */
    private suspend fun readChunk(
        backend: WorkspaceBackend,
        workspaceId: String,
        relativePath: String,
        offset: Long,
        maxBytes: Long,
        gate: WorkspaceSearchGate,
    ): WorkspaceSearchChunk {
        var lastError: ToolError? = null
        for (shrink in 0..MAX_UTF8_SHRINK) {
            val attempt = maxBytes - shrink
            if (attempt < 1L) break
            val gateReason = gate.before()
            if (gateReason != null) return WorkspaceSearchChunk.Stop(gateReason)
            val request = WorkspaceReadTextRequest(
                workspaceId = workspaceId,
                relativePath = relativePath,
                maxBytes = attempt,
                offsetBytes = offset,
            )
            when (val result = backend.readText(request)) {
                is WorkspaceResult.Success -> {
                    val value = result.value
                    // A chunk that reports no bytes and no end cannot advance the offset;
                    // that is an incomplete read of this file, never a silent success.
                    if (value.byteSize <= 0L && !value.eof) {
                        return WorkspaceSearchChunk.Failure(ToolError(ToolErrorCode.IO_ERROR))
                    }
                    return WorkspaceSearchChunk.Data(value.text, value.byteSize, value.eof)
                }
                is WorkspaceResult.Failure -> {
                    if (result.error.code != ToolErrorCode.INVALID_REQUEST) {
                        return WorkspaceSearchChunk.Failure(result.error)
                    }
                    lastError = result.error
                }
            }
        }
        return if (maxBytes <= MIN_DECODABLE_CHUNK_BYTES) {
            WorkspaceSearchChunk.End
        } else {
            WorkspaceSearchChunk.Failure(lastError ?: ToolError(ToolErrorCode.IO_ERROR))
        }
    }

    /**
     * Scan one file for literal [query] occurrences, one hit per line.
     *
     * Only a genuinely recoverable per-file failure is degraded into the `unreadable_entries`
     * counter together with an explicit truncation reason.  A revoked/denied authorization, an
     * unavailable authority or an externally ambiguous (`UNKNOWN_OUTCOME`) read terminates the
     * whole search with that typed error and no partial result, so a search can never present the
     * remains of a walk that lost its authority as a successful "no match".
     */
    private suspend fun scanFile(
        backend: WorkspaceBackend,
        workspaceId: String,
        entry: WorkspaceSearchEntry,
        query: String,
        caseSensitive: Boolean,
        limits: WorkspaceSearchLimits,
        counters: WorkspaceSearchCounters,
        gate: WorkspaceSearchGate,
        hits: MutableList<WorkspaceSearchGrepHit>,
    ): WorkspaceSearchStop? {
        val scanner = WorkspaceSearchGrepScanner(
            query = query,
            caseSensitive = caseSensitive,
            limit = (limits.maxResults - hits.size + 1).coerceAtLeast(1),
        )
        var offset = 0L
        var eof = false
        var read = false
        var stop: WorkspaceSearchReason? = null
        var failure: ToolError? = null
        while (!eof) {
            val remaining = limits.maxScannedBytes - counters.bytes
            if (remaining <= 0L) {
                stop = WorkspaceSearchReason.MAX_SCANNED_BYTES
                break
            }
            val chunkBytes = minOf(READ_CHUNK_BYTES, remaining)
            when (val chunk = readChunk(backend, workspaceId, entry.relativePath, offset, chunkBytes, gate)) {
                is WorkspaceSearchChunk.Stop -> {
                    stop = chunk.reason
                    break
                }
                is WorkspaceSearchChunk.Failure -> {
                    if (chunk.error.code.isSearchTerminal()) {
                        failure = chunk.error
                    } else {
                        counters.unreadableEntries += 1
                    }
                    break
                }
                WorkspaceSearchChunk.End -> {
                    // Not enough byte budget left to decode a UTF-8 sequence: the
                    // file was not covered, so this is a budget stop, not a match absence.
                    stop = WorkspaceSearchReason.MAX_SCANNED_BYTES
                    break
                }
                is WorkspaceSearchChunk.Data -> {
                    read = true
                    counters.bytes += chunk.consumedBytes
                    offset += chunk.consumedBytes
                    eof = chunk.eof || chunk.consumedBytes <= 0L
                    for (hit in scanner.feed(chunk.text)) {
                        if (hits.size >= limits.maxResults) {
                            stop = WorkspaceSearchReason.MAX_RESULTS
                            break
                        }
                        hits += WorkspaceSearchGrepHit(
                            relativePath = entry.relativePath,
                            lineNumber = hit.lineNumber,
                            column = hit.column,
                            line = hit.line,
                            lineTruncated = hit.lineTruncated,
                        )
                    }
                    if (stop != null) break
                }
            }
        }
        failure?.let { return WorkspaceSearchStop.Failure(it) }
        if (stop == null && read) {
            for (hit in scanner.finish()) {
                if (hits.size >= limits.maxResults) {
                    stop = WorkspaceSearchReason.MAX_RESULTS
                    break
                }
                hits += WorkspaceSearchGrepHit(
                    relativePath = entry.relativePath,
                    lineNumber = hit.lineNumber,
                    column = hit.column,
                    line = hit.line,
                    lineTruncated = hit.lineTruncated,
                )
            }
        }
        if (stop == null && scanner.limitReached) {
            // The scanner proved at least one more match than this request may return.
            stop = WorkspaceSearchReason.MAX_RESULTS
        }
        return stop?.let { WorkspaceSearchStop.Budget(it) }
    }
}
