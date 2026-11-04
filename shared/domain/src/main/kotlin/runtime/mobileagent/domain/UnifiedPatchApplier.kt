// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Receives the exact bytes of one emitted line plus its terminator (`""` for an unterminated tail). */
internal fun interface UnifiedPatchLineSink {
    /** Returns false when the destination budget rejects the write. */
    fun write(line: String, terminator: String): Boolean
}

/**
 * Single line/state machine behind both patch engines.
 *
 * Callers feed every source line with its real terminator (streaming readers hand over the bytes
 * they just consumed, in-memory callers hand over the scan result), so hunk positioning, pure
 * insertions, `\ No newline at end of file` handling and range checks cannot drift between the
 * bounded and in-memory paths.
 */
internal class UnifiedPatchApplier(
    private val hunks: List<UnifiedTextPatch.Hunk>,
    private val sink: UnifiedPatchLineSink,
) {
    private var sourceCursor = 0
    private var hunkIndex = 0
    private var hunkLineIndex = 0
    private var eol: String? = null
    private var unterminatedEmitted = false

    /** Returns null on success, otherwise a [RootFileOperationReason] string. */
    fun onSourceLine(line: String, terminator: String): String? {
        if (line.contains('\u0000')) return RootFileOperationReason.PATCH_INVALID
        if (terminator.isNotEmpty()) eol = terminator
        advancePureInsertions()?.let { return it }
        while (hunkIndex < hunks.size) {
            val hunk = hunks[hunkIndex]
            val base = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
            if (base > sourceCursor) break
            if (base < sourceCursor && hunkLineIndex == 0) {
                return RootFileOperationReason.PATCH_CONTEXT_MISMATCH
            }
            emitPlusLines()?.let { return it }
            if (hunkLineIndex >= hunk.lines.size) {
                hunkIndex += 1
                hunkLineIndex = 0
                advancePureInsertions()?.let { return it }
                continue
            }
            val expected = hunk.lines[hunkLineIndex]
            // A CRLF source line may be echoed in the record with its trailing CR (difflib/GNU
            // style), so accept both the bare content and the content plus that echoed CR.
            val matches = expected.text == line ||
                (terminator == "\r\n" && expected.text.endsWith('\r') && expected.text.dropLast(1) == line)
            if (!matches) return RootFileOperationReason.PATCH_CONTEXT_MISMATCH
            if (expected.noNewlineAtEof && terminator.isNotEmpty()) {
                return RootFileOperationReason.PATCH_CONTEXT_MISMATCH
            }
            when (expected.kind) {
                // Context lines are re-emitted from the source, not from the echoed record.
                ' ' -> emit(line, terminator)?.let { return it }
                '-' -> Unit
                else -> return RootFileOperationReason.PATCH_INVALID
            }
            hunkLineIndex += 1
            sourceCursor += 1
            emitPlusLines()?.let { return it }
            return null
        }
        sourceCursor += 1
        return emit(line, terminator)
    }

    /** Returns null when every hunk was applied, otherwise a [RootFileOperationReason] string. */
    fun finish(): String? {
        advancePureInsertions()?.let { return it }
        while (hunkIndex < hunks.size) {
            if (hunkLineIndex < hunks[hunkIndex].lines.size) {
                return RootFileOperationReason.PATCH_CONTEXT_MISMATCH
            }
            hunkIndex += 1
            hunkLineIndex = 0
            advancePureInsertions()?.let { return it }
        }
        return null
    }

    private fun advancePureInsertions(): String? {
        while (hunkIndex < hunks.size) {
            val hunk = hunks[hunkIndex]
            if (hunk.oldCount != 0 || hunk.oldStart != sourceCursor) break
            emitPlusLines()?.let { return it }
            hunkIndex += 1
            hunkLineIndex = 0
        }
        return null
    }

    private fun emitPlusLines(): String? {
        val hunk = hunks[hunkIndex]
        while (hunkLineIndex < hunk.lines.size && hunk.lines[hunkLineIndex].kind == '+') {
            val added = hunk.lines[hunkLineIndex]
            val terminator = if (added.noNewlineAtEof) "" else (eol ?: "\n")
            // A difflib/GNU-style record already carries the target line's CR when that line is
            // CRLF-terminated; re-adding CRLF would double it. With an LF or unknown destination
            // terminator the kept CR plus LF still yields the CRLF bytes the record describes.
            val text = if (terminator == "\r\n" && added.text.endsWith('\r')) {
                added.text.dropLast(1)
            } else {
                added.text
            }
            emit(text, terminator)?.let { return it }
            hunkLineIndex += 1
        }
        return null
    }

    private fun emit(text: String, terminator: String): String? {
        if (unterminatedEmitted) return RootFileOperationReason.PATCH_INVALID
        if (!sink.write(text, terminator)) {
            return RootFileOperationReason.PATCH_RESULT_EXCEEDS_BUDGET
        }
        if (terminator.isEmpty()) unterminatedEmitted = true
        return null
    }
}
