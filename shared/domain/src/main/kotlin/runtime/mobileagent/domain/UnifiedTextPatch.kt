// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/**
 * Engine-neutral unified-diff semantics shared by the in-memory (internal workspace and wired ADB)
 * and streaming (Root) file engines.
 *
 * Contract:
 *
 * - Lines are matched on content only. A terminator is `\n` or `\r\n`; a `\r` only counts as part
 *   of a terminator when it directly precedes `\n`, so an interior `\r` is data and file content is
 *   never trimmed or normalised behind the caller's back.
 * - Patch records are separated by `\n`. The patch file's own line-ending style is decided from its
 *   structural records (file/hunk headers and the no-newline marker), never from
 *   hunk body content: a CRLF patch has exactly one record-terminator `\r` removed per terminated
 *   record, while an LF patch keeps every trailing `\r` as source content (for example a lone CR at
 *   EOF, or a content CR on a terminated line). Structural records that disagree about the style
 *   are ambiguous and are rejected instead of guessed; an unterminated final record is never
 *   stripped either.
 * - Source text is text: a NUL character anywhere in the source lines (including lines no hunk
 *   touches) rejects the patch in both engines.
 * - Emitted source lines keep their own terminator, so a CRLF file stays CRLF and a mixed file is
 *   not silently rewritten. Added lines use the most recently observed source terminator (LF for an
 *   empty source, which has no terminator to inherit).
 * - Generators such as Python `difflib.unified_diff` and GNU diff over CRLF lines keep each line's
 *   `\r` inside the hunk body record (` a\r`, `-b\r`, `+c\r`) while headers stay LF. That echoed CR
 *   is matched against the source terminator: when the matched source line is CRLF-terminated a
 *   single trailing CR on the record may be the terminator echo (context lines are re-emitted from
 *   the source content), and an added line's trailing CR is dropped before the CRLF terminator so
 *   the result never doubles it. An unterminated source line keeps its lone CR as content.
 * - Line numbers are 1-based exactly as in the unified format. `oldStart == 0` means "before the
 *   first line" and is legal only for a pure insertion. An empty file has zero lines, so its only
 *   insertion anchor is `@@ -0,0 +1,N @@`; on an `n`-line file `oldStart == n` appends after the
 *   last line and `oldStart > n` is rejected. Hunk headers must also agree with the accumulated
 *   `newCount - oldCount` delta, so a wrong `newStart` is rejected instead of guessed.
 * - `\ No newline at end of file` is honoured on both sides: it marks the preceding hunk line as
 *   its file's last line. A marker on an old-side line requires the matched source line to be
 *   unterminated; a marker on a new-side line suppresses the final terminator. An unterminated
 *   emitted line may not be followed by more output, matching the standard convention.
 */
object UnifiedTextPatch {
    data class HunkLine(val kind: Char, val text: String, val noNewlineAtEof: Boolean = false)
    data class Hunk(val oldStart: Int, val oldCount: Int, val newCount: Int, val lines: List<HunkLine>)

    private const val NO_NEWLINE_MARKER = "\\ No newline at end of file"
    private val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")

    fun parsePatchHunks(bytes: ByteArray): List<Hunk>? {
        val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        if (text.contains('\u0000')) return null
        val rawLines = text.split('\n')
        val crlfPatch = detectPatchLineEnding(rawLines) ?: return null
        val terminatedRecords = rawLines.size - 1
        val lines = if (crlfPatch) {
            rawLines.mapIndexed { index, raw ->
                if (index < terminatedRecords) raw.removeSuffix("\r") else raw
            }
        } else {
            rawLines
        }
        val hunks = mutableListOf<Hunk>()
        var delta = 0L
        var index = 0
        while (index < lines.size) {
            val line = lines[index++]
            if (line.isEmpty() && index == lines.size) break
            if (hunks.isEmpty() && (line.startsWith("--- ") || line.startsWith("+++ ") ||
                    line.startsWith("diff --git ") || line.startsWith("index "))) continue
            val match = header.matchEntire(line) ?: return null
            val oldStart = match.groupValues[1].toIntOrNull() ?: return null
            val oldCount = match.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return null
            val newStart = match.groupValues[3].toIntOrNull() ?: return null
            val newCount = match.groupValues[4].ifEmpty { "1" }.toIntOrNull() ?: return null
            if ((oldStart == 0 && oldCount != 0) || (newStart == 0 && newCount != 0)) return null
            val expectedNewStart = when {
                newCount == 0 -> oldStart.toLong() - 1 + delta
                oldCount == 0 -> oldStart.toLong() + 1 + delta
                else -> oldStart.toLong() + delta
            }
            if (newStart.toLong() != expectedNewStart) return null
            delta += newCount - oldCount
            val body = mutableListOf<HunkLine>()
            var oldSeen = 0
            var newSeen = 0
            while (index < lines.size && !lines[index].startsWith("@@ ")) {
                val raw = lines[index++]
                if (raw.isEmpty() && index == lines.size) break
                if (raw == NO_NEWLINE_MARKER) {
                    val last = body.lastOrNull() ?: return null
                    if (last.noNewlineAtEof) return null
                    body[body.lastIndex] = last.copy(noNewlineAtEof = true)
                    continue
                }
                val kind = raw.firstOrNull() ?: return null
                if (kind !in " +-" || (kind != '+' && oldSeen >= oldCount) ||
                    (kind != '-' && newSeen >= newCount)) return null
                body += HunkLine(kind, raw.substring(1))
                if (kind != '+') oldSeen++
                if (kind != '-') newSeen++
            }
            if (body.isEmpty() || oldSeen != oldCount || newSeen != newCount) return null
            hunks += Hunk(oldStart, oldCount, newCount, body)
        }
        return hunks.takeIf { it.isNotEmpty() }
    }

    /**
     * Patch line-ending style is a property of the patch file, not of the source lines it edits.
     * Only structural records are evidence: a hunk body ending in `\r` may be source content (for
     * example a lone CR at EOF), so it must never vote. `---`/`+++` lines only count before the
     * first hunk, because a `+++ ...` record inside a hunk is just an added line whose content
     * starts with `++ `. Structural records that disagree mean the patch was assembled from mixed
     * line endings; that is ambiguous, so the patch is rejected rather than having its body CRs
     * guessed away.
     */
    private fun detectPatchLineEnding(rawLines: List<String>): Boolean? {
        var crlf = false
        var lf = false
        var beforeFirstHunk = true
        for (index in 0 until rawLines.size - 1) {
            val raw = rawLines[index]
            val content = raw.removeSuffix("\r")
            val hunkHeader = content.startsWith("@@ ")
            if (hunkHeader) beforeFirstHunk = false
            val structural = content.isEmpty() || content == NO_NEWLINE_MARKER || hunkHeader ||
                content.startsWith("diff --git ") || content.startsWith("index ") ||
                (beforeFirstHunk && (content.startsWith("--- ") || content.startsWith("+++ ")))
            if (!structural) continue
            if (raw.endsWith("\r")) crlf = true else lf = true
            if (crlf && lf) return null
        }
        return crlf
    }

    /** Bounded callers own their output limit and commit; rejection never mutates the input. */
    fun apply(current: String, patch: String): String? {
        if (current.contains('\u0000')) return null
        val hunks = parsePatchHunks(patch.toByteArray(Charsets.UTF_8)) ?: return null
        val output = StringBuilder(current.length)
        val applier = UnifiedPatchApplier(
            hunks,
            UnifiedPatchLineSink { line, terminator ->
                output.append(line)
                if (terminator.isNotEmpty()) output.append(terminator)
                true
            },
        )
        var start = 0
        var index = 0
        while (index < current.length) {
            if (current[index] == '\n') {
                val crlf = index > start && current[index - 1] == '\r'
                val end = if (crlf) index - 1 else index
                val terminator = if (crlf) "\r\n" else "\n"
                applier.onSourceLine(current.substring(start, end), terminator)?.let { return null }
                start = index + 1
            }
            index += 1
        }
        if (start < current.length) {
            applier.onSourceLine(current.substring(start), "")?.let { return null }
        }
        applier.finish()?.let { return null }
        return output.toString()
    }
}
