// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

object TextChunker {
    fun chunk(text: String, targetChars: Int = 1800, overlapChars: Int = 200): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
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
            if (buf.length + para.length + 2 > targetChars && buf.isNotEmpty()) {
                val overflow = buf.toString()
                flush()
                if (overlapChars > 0 && overflow.length > overlapChars) {
                    buf.append(overflow.takeLast(overlapChars)).append('\n')
                }
            }
            if (buf.isNotEmpty()) buf.append("\n\n")
            buf.append(para)
        }
        flush()
        return chunks.ifEmpty { listOf(normalized) }
    }
}
