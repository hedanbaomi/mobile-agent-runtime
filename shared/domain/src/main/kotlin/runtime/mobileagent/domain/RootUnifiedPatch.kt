// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

/** Strict single-file unified diff applier: exact context, no fuzz, no partial application. */
object RootUnifiedPatch {
    internal data class HunkLine(val kind: Char, val text: String)
    internal data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newCount: Int,
        val lines: List<HunkLine>,
    )

    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")

    internal fun parsePatchHunks(patchBytes: ByteArray): List<Hunk>? {
        val patchText = runCatching { patchBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return null
        val hunks = parseHunks(patchText.split('\n').map { it.removeSuffix("\r") }) ?: return null
        return hunks.takeIf { it.isNotEmpty() }
    }

    fun apply(current: ByteArray, patchBytes: ByteArray): RootPatchOutcome {
        val currentText = runCatching { current.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        val patchText = runCatching { patchBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
            ?: return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        val hunks = parseHunks(patchText.split('\n').map { it.removeSuffix("\r") })
            ?: return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        if (hunks.isEmpty()) return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        val currentLines = if (currentText.isEmpty()) emptyList() else currentText.removeSuffix("\n").split("\n")
        val output = mutableListOf<String>()
        var cursor = 0
        for (hunk in hunks) {
            val base = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
            if (base < cursor || base > currentLines.size) {
                return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_CONTEXT_MISMATCH)
            }
            while (cursor < base) output += currentLines[cursor++]
            for (line in hunk.lines) {
                when (line.kind) {
                    ' ' -> {
                        if (!matchesAt(currentLines, cursor, line.text)) {
                            return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_CONTEXT_MISMATCH)
                        }
                        output += line.text
                        cursor += 1
                    }
                    '-' -> {
                        if (!matchesAt(currentLines, cursor, line.text)) {
                            return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_CONTEXT_MISMATCH)
                        }
                        cursor += 1
                    }
                    '+' -> output += line.text
                }
            }
        }
        while (cursor < currentLines.size) output += currentLines[cursor++]
        val trailingNewline = currentText.endsWith("\n")
        val rebuilt = output.joinToString("\n") + if (trailingNewline && output.isNotEmpty()) "\n" else ""
        if (rebuilt.toByteArray(Charsets.UTF_8).size > RootContentVersionPolicy.MAX_CONTENT_BYTES) {
            return RootPatchOutcome.Rejected(RootFileOperationReason.PATCH_INVALID)
        }
        return RootPatchOutcome.Applied(rebuilt.toByteArray(Charsets.UTF_8))
    }

    private fun matchesAt(lines: List<String>, index: Int, expected: String): Boolean =
        index in lines.indices && lines[index] == expected

    private fun parseHunks(lines: List<String>): List<Hunk>? {
        val hunks = mutableListOf<Hunk>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            index += 1
            if (line.isEmpty() || line.startsWith("--- ") || line.startsWith("+++ ") ||
                line.startsWith("diff --git ") || line.startsWith("index ")
            ) {
                continue
            }
            val header = hunkHeader.matchEntire(line) ?: return null
            val oldStart = header.groupValues[1].toIntOrNull() ?: return null
            val oldCount = header.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return null
            val newCount = header.groupValues[4].ifEmpty { "1" }.toIntOrNull() ?: return null
            val body = mutableListOf<HunkLine>()
            var oldSeen = 0
            var newSeen = 0
            while (index < lines.size) {
                val raw = lines[index]
                if (raw.isEmpty()) break
                val kind = raw.firstOrNull() ?: return null
                if (kind == '@' || kind == 'd') break
                if (kind == '\\') {
                    index += 1
                    continue
                }
                if (kind != ' ' && kind != '-' && kind != '+') return null
                body += HunkLine(kind, raw.substring(1))
                if (kind != '+') oldSeen += 1
                if (kind != '-') newSeen += 1
                index += 1
            }
            if (body.isEmpty() || oldSeen != oldCount || newSeen != newCount) return null
            hunks += Hunk(oldStart, oldCount, newCount, body)
        }
        return hunks
    }
}
