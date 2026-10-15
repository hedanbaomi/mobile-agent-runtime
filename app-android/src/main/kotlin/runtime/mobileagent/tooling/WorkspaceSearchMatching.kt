// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.tooling

import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * Pure, I/O-free pattern matching for the bounded workspace search.
 *
 * This file holds only the two state machines that never touch a backend, a grant, a clock or a
 * coroutine: the safe glob matcher and the streaming line scanner.  Everything that needs
 * permission or I/O state (the traversal, the pre-I/O gate, the bounded chunk reader) stays in
 * `WorkspaceSearch.kt`, so the branch-heavy matching logic and the authorization-sensitive
 * traversal can be reviewed separately.
 */
internal object WorkspaceSearchMatching {
    /** Pattern/query inputs are small; the bound keeps parsing and matching provably linear. */
    const val MAX_PATTERN_BYTES = 256
    const val MAX_QUERY_BYTES = 256

    /** A grep query can never contain a newline, so matches live inside one reconstructed line. */
    const val MAX_LINE_BUFFER_CHARS = 4_096
    const val MAX_LINE_PREVIEW_CHARS = 512

    /**
     * Safe glob over workspace-relative paths.
     *
     * - `*` matches any run of characters inside one path segment (never `/`).
     * - `?` matches exactly one character inside one path segment (never `/`).
     * - `**` as a whole segment matches zero or more whole path segments.
     * - A pattern without `/` is matched against the entry basename at any depth; a pattern with
     *   `/` is matched against the complete workspace-relative path.
     *
     * Matching is a bounded dynamic program plus a linear wildcard scan.  No regex is compiled, so
     * a caller-supplied pattern cannot cause catastrophic backtracking.
     */
    internal class WorkspaceSearchGlob private constructor(
        private val parts: List<WorkspaceSearchGlobPart>,
        private val basenameOnly: Boolean,
    ) {
        fun matches(relativePath: String): Boolean {
            if (relativePath.isEmpty()) return false
            val segments = relativePath.split('/')
            val candidate = if (basenameOnly) listOf(segments.last()) else segments
            return matchParts(parts, candidate)
        }

        private fun matchParts(pattern: List<WorkspaceSearchGlobPart>, value: List<String>): Boolean {
            val rows = pattern.size
            val columns = value.size
            val matched = Array(rows + 1) { BooleanArray(columns + 1) }
            matched[0][0] = true
            for (row in 1..rows) {
                if (pattern[row - 1].globstar) matched[row][0] = matched[row - 1][0]
            }
            for (row in 1..rows) {
                val part = pattern[row - 1]
                for (column in 1..columns) {
                    matched[row][column] = if (part.globstar) {
                        matched[row - 1][column] || matched[row][column - 1]
                    } else {
                        matched[row - 1][column - 1] && matchPart(part.text, value[column - 1])
                    }
                }
            }
            return matched[rows][columns]
        }

        companion object {
            /** Parse and validate a model-supplied pattern; null means fail-closed rejection. */
            fun parse(pattern: String): WorkspaceSearchGlob? {
                if (pattern.isEmpty()) return null
                val normalized = Normalizer.normalize(pattern, Normalizer.Form.NFC)
                if (normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_PATTERN_BYTES) return null
                if (normalized.any { it == '\u0000' || it.isISOControl() }) return null
                // A backslash, a colon or a leading slash would name a platform/URI shape rather
                // than a workspace-relative pattern.
                if (normalized.contains('\\') || normalized.contains(':')) return null
                if (normalized.startsWith('/') || normalized.endsWith('/') || normalized.contains("//")) return null
                val raw = normalized.split('/')
                if (raw.size > WorkspaceSearch.MAX_DEPTH_LIMIT) return null
                if (raw.any { it.isEmpty() || it == "." || it == ".." }) return null
                val parts = raw.map { segment -> WorkspaceSearchGlobPart(segment == "**", segment) }
                return WorkspaceSearchGlob(parts, basenameOnly = !normalized.contains('/'))
            }

            /**
             * Linear wildcard match for one path segment.  `*` is backtracked through a single
             * remembered position, which keeps the worst case quadratic instead of exponential.
             */
            fun matchPart(pattern: String, value: String): Boolean {
                var patternIndex = 0
                var valueIndex = 0
                var starIndex = -1
                var starValueIndex = 0
                while (valueIndex < value.length) {
                    if (patternIndex < pattern.length &&
                        (pattern[patternIndex] == '?' || pattern[patternIndex] == value[valueIndex])
                    ) {
                        patternIndex += 1
                        valueIndex += 1
                    } else if (patternIndex < pattern.length && pattern[patternIndex] == '*') {
                        starIndex = patternIndex
                        starValueIndex = valueIndex
                        patternIndex += 1
                    } else if (starIndex >= 0) {
                        patternIndex = starIndex + 1
                        starValueIndex += 1
                        valueIndex = starValueIndex
                    } else {
                        return false
                    }
                }
                while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex += 1
                return patternIndex == pattern.length
            }
        }
    }

    /** One glob segment: a literal pattern (possibly containing `*`/`?`) or a `**` wildcard. */
    internal data class WorkspaceSearchGlobPart(val globstar: Boolean, val text: String)

    /**
     * Literal (non-regex) index lookup.  Case-insensitive comparison is delegated to
     * [String.regionMatches], which keeps this a bounded character comparison rather than a regex
     * or a locale-dependent fold.
     */
    fun indexOf(text: String, query: String, from: Int, caseSensitive: Boolean): Int {
        if (query.isEmpty()) return -1
        if (caseSensitive) return text.indexOf(query, from)
        val last = text.length - query.length
        var index = if (from < 0) 0 else from
        while (index <= last) {
            if (text.regionMatches(index, query, 0, query.length, ignoreCase = true)) return index
            index += 1
        }
        return -1
    }

    /** Validate a literal grep query; null means fail-closed rejection. */
    fun validateQuery(query: String): String? {
        if (query.isEmpty()) return null
        if (query.toByteArray(StandardCharsets.UTF_8).size > MAX_QUERY_BYTES) return null
        // A control character (including a newline) would break the one-match-per-line model and
        // could smuggle a non-printable payload into the model schema output.
        if (query.any { it == '\u0000' || it.isISOControl() }) return null
        return query
    }

    /** One literal hit inside a reconstructed line. */
    internal data class WorkspaceSearchLineHit(
        val lineNumber: Long,
        val column: Int,
        val line: String,
        val lineTruncated: Boolean,
    )

    /**
     * Reconstructs lines across chunk boundaries.
     *
     * A query validated by [validateQuery] cannot contain a newline, so a match always lies inside
     * a single line.  Accumulating the line before matching therefore finds a match that straddles
     * two read chunks without any overlap bookkeeping.  A line longer than
     * [MAX_LINE_BUFFER_CHARS] is searched before the buffer is trimmed to the last
     * `query.length - 1` characters, which keeps finding matches (including one spanning the trim
     * point) while bounding memory.
     */
    internal class WorkspaceSearchGrepScanner(
        private val query: String,
        private val caseSensitive: Boolean,
        /**
         * Stop after this many hits.  The caller passes one more than it can still keep, so a
         * chunk full of newlines cannot accumulate an unbounded hit list and the caller can still
         * distinguish "exactly the budget" from "more matches exist".
         */
        private val limit: Int,
    ) {
        private val hits = mutableListOf<WorkspaceSearchLineHit>()
        private val line = StringBuilder()
        private var lineNumber = 1L
        private var trimmedChars = 0L
        private var matchRecorded = false
        private var stopped = false

        /** True when the scanner stopped early because the caller's result budget was reached. */
        val limitReached: Boolean get() = stopped

        fun feed(chunk: String): List<WorkspaceSearchLineHit> {
            var index = 0
            while (index < chunk.length && !stopped) {
                val newline = chunk.indexOf('\n', index)
                if (newline < 0) {
                    append(chunk, index, chunk.length)
                    index = chunk.length
                } else {
                    append(chunk, index, newline)
                    completeLine()
                    index = newline + 1
                }
            }
            return drain()
        }

        /** Flush the final unterminated line. */
        fun finish(): List<WorkspaceSearchLineHit> {
            if (!stopped && (line.isNotEmpty() || trimmedChars > 0L)) {
                completeLine()
            }
            return drain()
        }

        private fun append(chunk: String, from: Int, to: Int) {
            if (from >= to) return
            line.append(chunk, from, to)
            while (line.length > MAX_LINE_BUFFER_CHARS) trimLine()
        }

        /** Search the buffered window, then keep only the tail needed for a straddling match. */
        private fun trimLine() {
            recordMatchIfPresent(truncatedWindow = true)
            val before = line.length
            val carry = (query.length - 1).coerceAtLeast(0)
            // Always drop at least one character so the trimming loop provably terminates even for
            // a pathologically long query; the normal branch keeps exactly `query.length - 1`.
            val keep = minOf(carry, before - 1).coerceAtLeast(0)
            line.delete(0, before - keep)
            trimmedChars += (before - keep).toLong()
        }

        private fun completeLine() {
            recordMatchIfPresent(truncatedWindow = trimmedChars > 0L)
            line.setLength(0)
            lineNumber += 1
            trimmedChars = 0L
            matchRecorded = false
        }

        private fun recordMatchIfPresent(truncatedWindow: Boolean) {
            if (matchRecorded || stopped) return
            val raw = line.toString()
            val text = if (raw.endsWith("\r")) raw.substring(0, raw.length - 1) else raw
            val at = indexOf(text, query, 0, caseSensitive)
            if (at < 0) return
            matchRecorded = true
            val preview = if (text.length > MAX_LINE_PREVIEW_CHARS) text.substring(0, MAX_LINE_PREVIEW_CHARS) else text
            hits += WorkspaceSearchLineHit(
                lineNumber = lineNumber,
                // `trimmedChars` keeps the column correct relative to the real line start even
                // after the buffer was trimmed.
                column = trimmedChars.toInt() + at + 1,
                line = preview,
                lineTruncated = truncatedWindow || text.length > MAX_LINE_PREVIEW_CHARS,
            )
            if (hits.size >= limit) stopped = true
        }

        private fun drain(): List<WorkspaceSearchLineHit> {
            if (hits.isEmpty()) return emptyList()
            val drained = hits.toList()
            hits.clear()
            return drained
        }

        private fun indexOf(text: String, needle: String, from: Int, caseSensitive: Boolean): Int =
            WorkspaceSearchMatching.indexOf(text, needle, from, caseSensitive)
    }
}
