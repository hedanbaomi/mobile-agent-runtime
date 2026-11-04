// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * FD-confined recursive `file_glob` and `file_grep` operations for Root workspaces (`ROOT09`, `ROOT10`, `ROOT20`).
 *
 * Every directory step and file chunk read passes through [RootWorkspaceFileReadOperations], so
 * control-plane boundaries, symlink/hardlink/special-node rejections, and trusted mount roots are
 * enforced on every step.
 */
class RootWorkspaceSearchOperations(
    private val readOperations: RootWorkspaceFileReadOperations,
    private val workspaceRootRejection: RootConfinementRejection?,
    private val nanoTimeProvider: () -> Long = System::nanoTime,
    private val isActive: () -> Boolean = { true },
) {
    fun glob(args: RootTypedArguments.FileGlob): RootFileOperationResult<RootGlobSearchResult> {
        if (workspaceRootRejection != null) {
            return RootFileOperationResult.Rejected(
                workspaceRootRejection,
                RootFileOperationReason.WORKSPACE_ROOT_UNCONFINED,
            )
        }
        val matcher = RootGlobMatcher.parse(args.pattern)
            ?: return RootFileOperationResult.Rejected(
                RootConfinementRejection.LOCATOR_INVALID,
                "invalid_glob_pattern",
            )
        val prefix = if (args.relativePath.isEmpty()) "" else "${args.relativePath}/"
        val matches = mutableListOf<RootGlobMatchEntry>()
        val walk = traverse(
            workspaceId = args.workspaceId,
            root = args.relativePath,
            maxDepth = args.maxDepth,
            maxScannedEntries = args.maxScannedEntries,
            maxScannedFiles = args.maxScannedFiles,
            maxMillis = args.maxMillis,
        ) { entry ->
            val relToSearchRoot = if (prefix.isEmpty()) entry.relativePath else entry.relativePath.removePrefix(prefix)
            if (!matcher.matches(relToSearchRoot) && !matcher.matches(entry.relativePath)) {
                null
            } else if (matches.size >= args.maxResults) {
                "max_results"
            } else {
                matches += entry
                null
            }
        }
        walk.rootFailure?.let { return it }
        val res = RootGlobSearchResult(
            workspaceId = args.workspaceId,
            root = args.relativePath,
            pattern = args.pattern,
            matches = matches,
            truncated = walk.reasons.isNotEmpty(),
            truncationReasons = walk.reasons,
            counters = walk.counters,
        )
        val digest = RootMetadataSnapshots.sha256Hex(
            matches.joinToString("\u0000") { "${it.relativePath}:${it.entryKind.name}:${it.sizeBytes}" }
                .toByteArray(Charsets.UTF_8),
        )
        return RootFileOperationResult.Succeeded(res, digest)
    }

    fun grep(args: RootTypedArguments.FileGrep): RootFileOperationResult<RootGrepSearchResult> {
        if (workspaceRootRejection != null) {
            return RootFileOperationResult.Rejected(
                workspaceRootRejection,
                RootFileOperationReason.WORKSPACE_ROOT_UNCONFINED,
            )
        }
        if (!RootPathGuards.isValidGrepQuery(args.query)) {
            return RootFileOperationResult.Rejected(
                RootConfinementRejection.LOCATOR_INVALID,
                "invalid_grep_query",
            )
        }
        val startedNanos = nanoTimeProvider()
        val deadlineNanos = startedNanos + args.maxMillis * 1_000_000L
        val hits = mutableListOf<RootGrepHitEntry>()
        val walk = traverse(
            workspaceId = args.workspaceId,
            root = args.relativePath,
            maxDepth = args.maxDepth,
            maxScannedEntries = args.maxScannedEntries,
            maxScannedFiles = args.maxScannedFiles,
            maxMillis = args.maxMillis,
            startedNanos = startedNanos,
        ) { entry ->
            if (entry.entryKind != RootFileEntryKind.REGULAR_FILE) {
                null
            } else {
                scanFileForGrep(args, entry, deadlineNanos, hits)
            }
        }
        walk.rootFailure?.let { return it }
        val res = RootGrepSearchResult(
            workspaceId = args.workspaceId,
            root = args.relativePath,
            query = args.query,
            caseSensitive = args.caseSensitive,
            matches = hits,
            truncated = walk.reasons.isNotEmpty(),
            truncationReasons = walk.reasons,
            counters = walk.counters,
        )
        val digest = RootMetadataSnapshots.sha256Hex(
            hits.joinToString("\u0000") { "${it.relativePath}:${it.lineNumber}:${it.column}:${it.line}" }
                .toByteArray(Charsets.UTF_8),
        )
        return RootFileOperationResult.Succeeded(res, digest)
    }

    private data class WalkState(
        val counters: RootSearchCounters,
        val reasons: List<String>,
        val rootFailure: RootFileOperationResult<Nothing>?,
    )

    private fun traverse(
        workspaceId: String,
        root: String,
        maxDepth: Int,
        maxScannedEntries: Int,
        maxScannedFiles: Int,
        maxMillis: Long,
        startedNanos: Long = nanoTimeProvider(),
        visitor: RootSearchCounters.(RootGlobMatchEntry) -> String?,
    ): WalkState {
        val counters = RootSearchCounters()
        val degraded = linkedSetOf<String>()
        var stopReason: String? = null
        val deadlineNanos = startedNanos + maxMillis * 1_000_000L
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(root to 0)

        walkLoop@ while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeLast()
            counters.directories += 1
            val descend = mutableListOf<Pair<String, Int>>()
            var cursor: String? = null
            var pages = 0
            do {
                if (isCancelled()) return cancelledWalk(counters)
                if (nanoTimeProvider() >= deadlineNanos) {
                    stopReason = "deadline"
                    break
                }
                if (pages >= MAX_PAGES_PER_DIRECTORY) {
                    degraded += "listing_truncated"
                    break
                }
                val rem = maxScannedEntries - counters.entries
                if (rem <= 0) {
                    stopReason = "max_scanned_entries"
                    break
                }
                pages += 1
                val listed = readOperations.list(
                    RootTypedArguments.FileList(
                        workspaceId = workspaceId,
                        relativePath = dir,
                        maxEntries = minOf(LIST_PAGE_ENTRIES, rem),
                        cursor = cursor,
                    ),
                )
                if (isCancelled()) return cancelledWalk(counters)
                val page = when (listed) {
                    is RootFileOperationResult.Succeeded -> listed.value
                    else -> {
                        if (dir == root) {
                            @Suppress("UNCHECKED_CAST")
                            return WalkState(counters, emptyList(), listed as RootFileOperationResult<Nothing>)
                        }
                        counters.unreadableEntries += 1
                        break
                    }
                }
                counters.skippedEntries += page.skippedSpecialEntries
                if (page.truncated && page.nextCursor == null) degraded += "listing_truncated"
                val nextLevel = depth + 1
                for (item in page.entries) {
                    if (isCancelled()) return cancelledWalk(counters)
                    if (counters.entries >= maxScannedEntries) {
                        stopReason = "max_scanned_entries"
                        break
                    }
                    counters.entries += 1
                    if (item.entryKind == RootFileEntryKind.REGULAR_FILE) {
                        if (counters.files >= maxScannedFiles) {
                            stopReason = "max_scanned_files"
                            break
                        }
                        counters.files += 1
                    } else if (nextLevel <= maxDepth) {
                        descend += item.relativePath to nextLevel
                    } else {
                        degraded += "max_depth"
                    }
                    val matchEntry = RootGlobMatchEntry(item.relativePath, item.entryKind, item.sizeBytes, nextLevel)
                    val visitStop = counters.visitor(matchEntry)
                    if (visitStop == STOP_CANCELLED) return cancelledWalk(counters)
                    if (visitStop != null) {
                        stopReason = visitStop
                        break
                    }
                }
                if (stopReason != null) break
                cursor = page.nextCursor
            } while (cursor != null)
            if (stopReason != null) break@walkLoop
            for (i in descend.indices.reversed()) pending.addLast(descend[i])
        }
        val reasons = linkedSetOf<String>()
        reasons += degraded
        if (counters.unreadableEntries > 0) reasons += "unreadable_entries"
        if (stopReason != null) reasons += stopReason
        return WalkState(counters, reasons.toList(), null)
    }

    private fun isCancelled(): Boolean = Thread.currentThread().isInterrupted || !isActive()

    private fun cancelledWalk(counters: RootSearchCounters): WalkState = WalkState(
        counters = counters,
        reasons = emptyList(),
        rootFailure = RootFileOperationResult.Rejected(
            RootConfinementRejection.STAGED_HANDLE_INVALID,
            "operation_cancelled",
        ),
    )

    private fun RootSearchCounters.scanFileForGrep(
        args: RootTypedArguments.FileGrep,
        entry: RootGlobMatchEntry,
        deadlineNanos: Long,
        hits: MutableList<RootGrepHitEntry>,
    ): String? {
        if (hits.size >= args.maxResults) return "max_results"
        val scanner = RootGrepLineScanner(
            query = args.query,
            caseSensitive = args.caseSensitive,
            relativePath = entry.relativePath,
            limit = (args.maxResults - hits.size + 1).coerceAtLeast(1),
        )
        val fileHits = mutableListOf<RootGrepHitEntry>()
        var fileBytes = 0L
        var expectedVersion: String? = null
        var offset = 0L
        var anyRead = false
        while (true) {
            if (isCancelled()) return STOP_CANCELLED
            if (nanoTimeProvider() >= deadlineNanos) return "deadline"
            val remBytes = args.maxScannedBytes - (bytes + fileBytes)
            if (remBytes <= 0L) {
                bytes += fileBytes
                commitFileHits(hits, fileHits, args.maxResults)
                return "max_scanned_bytes"
            }
            val ask = minOf(READ_CHUNK_BYTES.toLong(), remBytes).toInt()
            val window = readDecodableUtf8Chunk(args.workspaceId, entry.relativePath, offset, ask)
            if (isCancelled()) return STOP_CANCELLED
            if (window == null || (expectedVersion != null && window.version != expectedVersion)) {
                unreadableEntries += 1
                return null
            }
            expectedVersion = window.version
            if (window.consumedBytes <= 0 && window.truncated) {
                bytes += fileBytes
                commitFileHits(hits, fileHits, args.maxResults)
                return "max_scanned_bytes"
            }
            anyRead = true
            fileBytes += window.consumedBytes
            offset += window.consumedBytes
            fileHits += scanner.feed(window.text)
            if (hits.size + fileHits.size >= args.maxResults) {
                bytes += fileBytes
                commitFileHits(hits, fileHits, args.maxResults)
                return "max_results"
            }
            if (!window.truncated || window.consumedBytes <= 0) break
        }
        if (anyRead) {
            fileHits += scanner.finish()
        }
        bytes += fileBytes
        commitFileHits(hits, fileHits, args.maxResults)
        return if (scanner.limitReached || hits.size >= args.maxResults) "max_results" else null
    }

    private fun commitFileHits(
        hits: MutableList<RootGrepHitEntry>,
        fileHits: List<RootGrepHitEntry>,
        maxResults: Int,
    ) {
        val remaining = (maxResults - hits.size).coerceAtLeast(0)
        if (remaining > 0) hits += fileHits.take(remaining)
    }

    private data class DecodedWindow(
        val text: String,
        val consumedBytes: Int,
        val truncated: Boolean,
        val version: String,
    )

    private fun readDecodableUtf8Chunk(
        workspaceId: String,
        relativePath: String,
        offsetBytes: Long,
        maxBytes: Int,
    ): DecodedWindow? {
        val readRes = readOperations.read(
            RootTypedArguments.FileReadText(
                workspaceId = workspaceId,
                relativePath = relativePath,
                offsetBytes = offsetBytes,
                maxBytes = maxBytes,
            ),
            // Search returns matches, never a conditional-write token. Bound its I/O to the
            // requested windows instead of hashing the entire file twice for every 64 KiB.
            // Kernel identity/metadata changes still invalidate the scan's observed version.
            includeContentVersion = false,
        )
        val raw = (readRes as? RootFileOperationResult.Succeeded)?.value ?: return null
        if (raw.content.isEmpty()) return DecodedWindow("", 0, raw.truncated, raw.version)
        val input = ByteBuffer.wrap(raw.content)
        val output = CharBuffer.allocate(raw.content.size)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = decoder.decode(input, output, !raw.truncated)
        if (decoded.isError || decoded.isOverflow) return null
        val consumed = input.position()
        if (raw.truncated && consumed == 0) return DecodedWindow("", 0, true, raw.version)
        return DecodedWindow(
            output.flip().toString(),
            consumed,
            raw.truncated || consumed < raw.content.size,
            raw.version,
        )
    }

    companion object {
        private const val LIST_PAGE_ENTRIES = 256
        private const val MAX_PAGES_PER_DIRECTORY = 64
        private const val READ_CHUNK_BYTES = 64 * 1024
        private const val STOP_CANCELLED = "__cancelled__"
    }
}
