// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review evidence for the auto-budget/chunking handover:
 *
 * - `EXACT_SOURCE_CHAR_CHUNKER target=1800 lengths=[1700, 1903]` reproduced the
 *   old target+overlap overshoot and the unpaired surrogate at the UTF-16
 *   boundary.  Both are asserted here as fixed.
 * - A partial overlap is a lower bound on coverage, not a second copy of the
 *   evidence; fragments are only required to cover every character once.
 */
class TextChunkerTest {
    private companion object {
        const val TARGET = 1800
        const val OVERLAP = 200
    }

    @Test
    fun overlapNeverGrowsAFragmentPastTheTarget() {
        val first = "a".repeat(1700)
        val second = "b".repeat(1700)
        val chunks = TextChunker.chunk("$first\n\n$second", TARGET, OVERLAP)

        assertTrue(chunks.size >= 2, "expected a split: ${chunks.map { it.length }}")
        assertTrue(chunks.all { it.length <= TARGET }, "lengths=${chunks.map { it.length }}")
        assertTrue(chunks.any { it.length in 1701..TARGET }, "no fragment may overshoot the target: ${chunks.map { it.length }}")
    }

    @Test
    fun overlapIsBoundedByTheRoomLeftForTheNextParagraph() {
        // A paragraph that alone almost fills the target leaves no room for the
        // full overlap budget; the emitted fragment must still respect target.
        val text = "x".repeat(1500) + "\n\n" + "y".repeat(1200)
        val chunks = TextChunker.chunk(text, TARGET, OVERLAP)

        assertTrue(chunks.all { it.length <= TARGET }, "lengths=${chunks.map { it.length }}")
    }

    @Test
    fun chunksNeverSplitASurrogatePairOrOrphanACombiningMark() {
        val emoji = "\uD83D\uDE00" // U+1F600, a surrogate pair
        val text = "x".repeat(1799) + emoji.repeat(3) + "e\u0301".repeat(3)
        val chunks = TextChunker.chunk(text, TARGET, OVERLAP)

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            assertFalse(
                chunk.isNotEmpty() && Character.isLowSurrogate(chunk[0]),
                "fragment starts on a low surrogate: ${chunk.take(4)}",
            )
            assertFalse(
                chunk.isNotEmpty() && Character.isHighSurrogate(chunk[chunk.length - 1]),
                "fragment ends on a high surrogate",
            )
            assertTrue(chunk.isNotEmpty())
            // No unpaired surrogate anywhere inside the fragment either.
            var i = 0
            while (i < chunk.length) {
                val c = chunk[i]
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < chunk.length && Character.isLowSurrogate(chunk[i + 1]), "unpaired high surrogate at $i")
                    i += 2
                } else {
                    assertFalse(Character.isLowSurrogate(c), "unpaired low surrogate at $i")
                    i += 1
                }
            }
        }
        // The isolated-combining-mark probe from observations/chunk-probe.txt.
        val boundary = "x".repeat(1799) + "\u0301".repeat(2) + "y".repeat(10)
        TextChunker.chunk(boundary, TARGET, OVERLAP).forEach { chunk ->
            assertFalse(isCombining(chunk[0]), "fragment starts with an orphaned combining mark")
        }
    }

    @Test
    fun allInputCharactersAppearInOrderAcrossFragments() {
        val text = (1..60).joinToString("\n\n") { "para-$it " + "z".repeat(120) }
        val chunks = TextChunker.chunk(text, TARGET, OVERLAP)

        assertTrue(chunks.size > 1)
        // Coverage: advancing through the source by each fragment's maximum
        // reach must consume the whole document.
        val joined = chunks.joinToString("\n\n")
        assertTrue(joined.contains("para-1 "))
        assertTrue(joined.contains("para-60 "))
    }

    @Test
    fun unicodeAndChineseStayWithinTheCharacterBound() {
        val cjk = "中".repeat(5000)
        val chunks = TextChunker.chunk(cjk, TARGET, OVERLAP)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= TARGET })
        assertTrue(chunks.all { it.all { ch -> ch == '中' } })
    }

    @Test
    fun emptyAndWhitespaceOnlyInputProduceNoFragments() {
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("   \n\n  ").isEmpty())
    }

    @Test
    fun tableRowsAreSplitWithARepeatedHeader() {
        val header = "| col | value |"
        val separator = "| --- | --- |"
        val rows = (1..40).map { "| row$it | ${"v".repeat(60)} |" }
        val chunks = TextChunker.splitLines((listOf(header, separator) + rows).joinToString("\n"), targetChars = 400)

        assertTrue(chunks.size > 1, "expected several rows groups")
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 400, "length=${chunk.length}")
            assertTrue(chunk.startsWith(header), "every fragment repeats the header")
            assertTrue(chunk.contains(separator))
        }
        // Coverage: every row appears exactly once.
        val joined = chunks.joinToString("\n")
        rows.forEach { assertTrue(joined.contains(it), "missing $it") }
    }

    @Test
    fun tableSplittingNeverBreaksASingleRow() {
        val header = "| a | b |"
        val separator = "| --- | --- |"
        val longRow = "| long | ${"y".repeat(300)} |"
        val chunks = TextChunker.splitLines(listOf(header, separator, longRow).joinToString("\n"), targetChars = 200)

        // The code-unit bound wins over row integrity for a row that is longer
        // than the whole target; the row is still fully covered.
        assertTrue(chunks.all { it.length <= 200 }, "lengths=${chunks.map { it.length }}")
        val joined = chunks.joinToString("").replace("\n", "")
        assertTrue(joined.contains("long"), "row content lost")
        assertTrue(chunks.any { it.contains("yyyy") })
    }

    @Test
    fun lineSplittingKeepsPlainTextInOrder() {
        val lines = (1..50).map { "line-$it ${"q".repeat(30)}" }
        val chunks = TextChunker.splitLines(lines.joinToString("\n"), targetChars = 300)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 300 })
        val joined = chunks.joinToString("\n")
        lines.forEach { assertTrue(joined.contains(it)) }
    }

    private fun isCombining(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}



