// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import java.text.Normalizer

data class RootSearchCounters(
    var directories: Int = 0,
    var entries: Int = 0,
    var files: Int = 0,
    var bytes: Long = 0L,
    var skippedEntries: Int = 0,
    var unreadableEntries: Int = 0,
)

data class RootGlobMatchEntry(
    val relativePath: String,
    val entryKind: RootFileEntryKind,
    val sizeBytes: Long,
    val depth: Int,
)

data class RootGrepHitEntry(
    val relativePath: String,
    val lineNumber: Long,
    val column: Int,
    val line: String,
    val lineTruncated: Boolean,
)

data class RootGlobSearchResult(
    val workspaceId: String,
    val root: String,
    val pattern: String,
    val matches: List<RootGlobMatchEntry>,
    val truncated: Boolean,
    val truncationReasons: List<String>,
    val counters: RootSearchCounters,
)

data class RootGrepSearchResult(
    val workspaceId: String,
    val root: String,
    val query: String,
    val caseSensitive: Boolean,
    val matches: List<RootGrepHitEntry>,
    val truncated: Boolean,
    val truncationReasons: List<String>,
    val counters: RootSearchCounters,
)

internal class RootGlobMatcher private constructor(
    private val parts: List<Part>,
    private val basenameOnly: Boolean,
) {
    private data class Part(val globstar: Boolean, val text: String)

    fun matches(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        val segments = relativePath.split('/')
        val candidate = if (basenameOnly) listOf(segments.last()) else segments
        val rows = parts.size
        val cols = candidate.size
        val dp = Array(rows + 1) { BooleanArray(cols + 1) }
        dp[0][0] = true
        for (r in 1..rows) {
            if (parts[r - 1].globstar) dp[r][0] = dp[r - 1][0]
        }
        for (r in 1..rows) {
            val part = parts[r - 1]
            for (c in 1..cols) {
                dp[r][c] = if (part.globstar) {
                    dp[r - 1][c] || dp[r][c - 1]
                } else {
                    dp[r - 1][c - 1] && matchSegment(part.text, candidate[c - 1])
                }
            }
        }
        return dp[rows][cols]
    }

    companion object {
        fun parse(pattern: String): RootGlobMatcher? {
            if (!RootPathGuards.isValidGlobPattern(pattern)) return null
            val normalized = Normalizer.normalize(pattern, Normalizer.Form.NFC)
            val raw = normalized.split('/')
            val parts = raw.map { Part(it == "**", it) }
            return RootGlobMatcher(parts, basenameOnly = !normalized.contains('/'))
        }

        private fun matchSegment(pattern: String, value: String): Boolean {
            var p = 0
            var v = 0
            var star = -1
            var starV = 0
            while (v < value.length) {
                if (p < pattern.length && (pattern[p] == '?' || pattern[p] == value[v])) {
                    p += 1
                    v += 1
                } else if (p < pattern.length && pattern[p] == '*') {
                    star = p
                    starV = v
                    p += 1
                } else if (star >= 0) {
                    p = star + 1
                    starV += 1
                    v = starV
                } else {
                    return false
                }
            }
            while (p < pattern.length && pattern[p] == '*') p += 1
            return p == pattern.length
        }
    }
}

internal class RootGrepLineScanner(
    private val query: String,
    private val caseSensitive: Boolean,
    private val relativePath: String,
    private val limit: Int,
) {
    private val hits = mutableListOf<RootGrepHitEntry>()
    private val line = StringBuilder()
    private var lineNumber = 1L
    private var trimmedChars = 0L
    private var matchRecorded = false
    var limitReached: Boolean = false
        private set

    fun feed(chunk: String): List<RootGrepHitEntry> {
        var idx = 0
        while (idx < chunk.length && !limitReached) {
            val nl = chunk.indexOf('\n', idx)
            if (nl < 0) {
                append(chunk, idx, chunk.length)
                idx = chunk.length
            } else {
                append(chunk, idx, nl)
                completeLine()
                idx = nl + 1
            }
        }
        return drain()
    }

    fun finish(): List<RootGrepHitEntry> {
        if (!limitReached && (line.isNotEmpty() || trimmedChars > 0L)) {
            completeLine()
        }
        return drain()
    }

    private fun append(chunk: String, from: Int, to: Int) {
        if (from >= to) return
        line.append(chunk, from, to)
        while (line.length > MAX_LINE_BUFFER_CHARS) {
            recordMatchIfPresent(truncatedWindow = true)
            val before = line.length
            val keep = minOf((query.length - 1).coerceAtLeast(0), before - 1).coerceAtLeast(0)
            line.delete(0, before - keep)
            trimmedChars += (before - keep).toLong()
        }
    }

    private fun completeLine() {
        recordMatchIfPresent(truncatedWindow = trimmedChars > 0L)
        line.setLength(0)
        lineNumber += 1
        trimmedChars = 0L
        matchRecorded = false
    }

    private fun recordMatchIfPresent(truncatedWindow: Boolean) {
        if (matchRecorded || limitReached) return
        val raw = line.toString()
        val text = if (raw.endsWith("\r")) raw.substring(0, raw.length - 1) else raw
        val at = findMatch(text)
        if (at < 0) return
        matchRecorded = true
        val preview = if (text.length > MAX_LINE_PREVIEW_CHARS) text.substring(0, MAX_LINE_PREVIEW_CHARS) else text
        hits += RootGrepHitEntry(
            relativePath = relativePath,
            lineNumber = lineNumber,
            column = trimmedChars.toInt() + at + 1,
            line = preview,
            lineTruncated = truncatedWindow || text.length > MAX_LINE_PREVIEW_CHARS,
        )
        if (hits.size >= limit) limitReached = true
    }

    private fun findMatch(text: String): Int {
        if (caseSensitive) return text.indexOf(query)
        val last = text.length - query.length
        var i = 0
        while (i <= last) {
            if (text.regionMatches(i, query, 0, query.length, ignoreCase = true)) return i
            i += 1
        }
        return -1
    }

    private fun drain(): List<RootGrepHitEntry> {
        if (hits.isEmpty()) return emptyList()
        val out = hits.toList()
        hits.clear()
        return out
    }

    companion object {
        private const val MAX_LINE_BUFFER_CHARS = 4_096
        private const val MAX_LINE_PREVIEW_CHARS = 512
    }
}
