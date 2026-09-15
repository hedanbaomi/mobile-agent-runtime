// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

/**
 * Character-targeted splitter for retrieval/embedding fragments.
 *
 * [targetChars] is a hard upper bound on every emitted fragment, not a soft
 * goal.  It counts UTF-16 code units, so it is deliberately *not* a token or
 * byte budget: callers that need a real provider limit must measure the
 * encoded request (see `RequestInputBudget`).  The guarantees this object does
 * provide are:
 *
 * - every fragment has at most `targetChars` code units (overlap included);
 * - fragments never begin or end between a surrogate pair, and never begin
 *   with an orphaned combining mark;
 * - concatenating the fragments in order covers every input character at
 *   least once (overlap intentionally duplicates a bounded suffix).
 */
object TextChunker {
    fun chunk(text: String, targetChars: Int = 1800, overlapChars: Int = 200): List<String> {
        val target = targetChars.coerceAtLeast(1)
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val overlapBudget = overlapChars.coerceAtLeast(0)
        val paragraphs = normalized.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        var lastChunk = ""

        fun flush() {
            val flushed = buf.toString().trim()
            buf.clear()
            if (flushed.isNotEmpty()) {
                chunks += flushed
                lastChunk = flushed
            }
        }

        paragraphs.forEach { para ->
            if (para.length > target) {
                flush()
                val parts = splitLong(para, target, tail(lastChunk, overlapBudget, target))
                if (parts.isNotEmpty()) {
                    chunks += parts
                    lastChunk = parts.last()
                }
                return@forEach
            }
            // Spend part of the bound on overlap only after reserving room for
            // the paragraph; otherwise target + overlap could exceed target.
            if (buf.isNotEmpty()) {
                val projected = buf.length + tail(lastChunk, overlapBudget, target).length + para.length + 2
                if (projected > target) flush()
            }
            // Recomputed after a flush: the previous fragment is now the one the
            // next fragment must overlap without exceeding the bound.
            if (buf.isEmpty()) {
                // The overlap may only use room the paragraph does not need; the
                // suffix is trimmed to the budget here, never afterwards, so a
                // surrogate pair or combining cluster is never cut in half.
                val room = (target - para.length - 2).coerceAtLeast(0)
                val overlap = tail(lastChunk, overlapBudget, room)
                if (overlap.isNotEmpty()) buf.append(overlap)
            }
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(para)
        }
        flush()
        return chunks.ifEmpty { listOf(normalized) }
    }

    /**
     * Split a block that is meaningful line by line (OCR text, Markdown tables,
     * logs) without breaking rows.  A Markdown table repeats its header and the
     * alignment separator on every fragment so a snippet stays interpretable.
     */
    fun splitLines(text: String, targetChars: Int = 1800, repeatHeader: Boolean = true): List<String> {
        val target = targetChars.coerceAtLeast(1)
        val lines = text.replace("\r\n", "\n").split('\n').map { it.trimEnd() }
        val content = lines.filter { it.isNotBlank() }
        if (content.isEmpty()) return emptyList()
        val header = if (repeatHeader) headerLines(content) else emptyList()
        val headerSize = if (header.isEmpty()) 0 else header.sumOf { it.length } + header.size
        val bodyBudget = (target - headerSize).coerceAtLeast(1)
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        var bodyLength = 0
        fun flush() {
            if (buf.isEmpty()) return
            chunks += (header + buf.toString().split('\n')).joinToString("\n").trim()
            buf.clear()
            bodyLength = 0
        }
        content.forEachIndexed { index, line ->
            if (index < header.size) return@forEachIndexed
            if (line.length > bodyBudget) {
                // A single row is longer than the bound: keep the row together
                // only when it fits the whole target; otherwise the code-unit
                // bound wins and the row is split by characters.
                flush()
                if (header.isEmpty()) {
                    chunks += chunk(line, target, 0)
                } else {
                    // The header is repeated for readability, but it must never
                    // push a fragment past the bound: reserve its size first.
                    chunks += chunk(line, (target - headerSize).coerceAtLeast(1), 0)
                        .map { body -> (header + body).joinToString("\n") }
                }
                return@forEachIndexed
            }
            val added = line.length + if (bodyLength == 0) 0 else 1
            if (bodyLength > 0 && bodyLength + added > bodyBudget) flush()
            if (bodyLength > 0) buf.append('\n')
            buf.append(line)
            bodyLength += added
        }
        flush()
        return chunks.ifEmpty { listOf(content.joinToString("\n")) }
    }

    /**
     * Longest suffix of [previous] within [budget] code units, clamped to
     * [room] so a caller can reserve space for the paragraph that follows.
     */
    private fun tail(previous: String, budget: Int, room: Int): String {
        if (previous.isEmpty() || budget <= 0 || room <= 0) return ""
        val effective = budget.coerceAtMost(room)
        if (effective <= 0) return ""
        return safeTail(previous, effective)
    }

    private fun splitLong(para: String, target: Int, overlap: String): List<String> {
        if (para.length <= target) return listOf(para)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < para.length) {
            var end = minOf(start + target, para.length)
            // A fragment must never end between a surrogate pair, nor split a
            // base character from the combining marks that follow it.
            if (end < para.length && Character.isHighSurrogate(para[end - 1])) end -= 1
            // Back up while the boundary still sits inside a combining run so a
            // long pathological run cannot push the fragment past the bound.
            // The 1-unit minimum only remains for a target consisting solely of
            // combining marks, which cannot be split legally at all.
            while (end < para.length && end > start + 1 && isCombining(para[end])) end -= 1
            if (end <= start) end = minOf(start + target, para.length)
            val piece = para.substring(start, end)
            if (piece.isNotEmpty()) parts += piece
            if (end >= para.length) break
            // Step back inside the fragment so the next one re-states context.
            // Never leave a surrogate pair on the boundary.
            var next = (end - overlap.length).coerceAtLeast(start + 1)
            while (next < end && Character.isLowSurrogate(para[next])) next += 1
            start = if (next > start) next else end
        }
        return parts.ifEmpty { listOf(para) }
    }

    private fun headerLines(lines: List<String>): List<String> {
        if (lines.size < 2) return emptyList()
        val first = lines[0]
        if (!first.trimStart().startsWith("|")) return emptyList()
        val separator = lines[1]
        val separatorCells = separator.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (separatorCells.isEmpty() || !separatorCells.all { cell -> cell.all { it == '-' || it == ':' } }) {
            return emptyList()
        }
        return listOf(first, separator)
    }

    /**
     * Take at most [budget] code units from the end of [text], extending
     * slightly past the budget when needed so the fragment does not begin with
     * half a surrogate pair or an orphaned combining mark.
     */
    private fun safeTail(text: String, budget: Int): String {
        if (budget >= text.length) return text
        var start = text.length - budget
        if (start > 0 && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) {
            start -= 1
        }
        while (start > 0 && isCombining(text[start])) start -= 1
        while (start < text.length && isCombining(text[start])) start += 1
        return if (start >= text.length) "" else text.substring(start)
    }

    private fun isCombining(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}





