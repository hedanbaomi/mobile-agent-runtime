// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

object TextChunker {
    fun chunk(text: String, targetChars: Int = 1800, overlapChars: Int = 200): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val overlap = overlapChars.coerceAtLeast(0).coerceAtMost(targetChars - 1)
        val paragraphs = normalized.split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotBlank()) {
                chunks += buf.toString().trim()
                buf.clear()
            }
        }
        paragraphs.forEach { para ->
            if (para.length > targetChars) {
                flush()
                chunks += splitLong(para, targetChars, overlap)
            } else {
                if (buf.length + para.length + 2 > targetChars && buf.isNotEmpty()) {
                    val overflow = buf.toString()
                    flush()
                    if (overlap > 0 && overflow.length > overlap) {
                        buf.append(overflow.takeLast(overlap)).append('\n')
                    }
                }
                if (buf.isNotEmpty()) buf.append("\n\n")
                buf.append(para)
            }
        }
        flush()
        return chunks.ifEmpty { listOf(normalized) }
    }

    private fun splitLong(para: String, targetChars: Int, overlap: Int): List<String> {
        if (para.length <= targetChars) return listOf(para)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < para.length) {
            val end = minOf(start + targetChars, para.length)
            parts += para.substring(start, end)
            if (end >= para.length) break
            val next = end - overlap
            start = if (next <= start) end else next
        }
        return parts
    }
}
