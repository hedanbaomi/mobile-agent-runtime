// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.embedding

import org.junit.Assert.*
import org.junit.Test

class TokenizerCoverageTest {
    private val json = """{"normalizer":{"type":"BertNormalizer","clean_text":true,"handle_chinese_chars":true,"lowercase":false,"strip_accents":null},"model":{"type":"WordPiece","unk_token":"[UNK]","continuing_subword_prefix":"##","vocab":{"[PAD]":0,"[UNK]":100,"[CLS]":101,"[SEP]":102,"common":1,"cat":2,"dog":3,"中":4,"文":5,"É":6,"é":7,"𠀀":8}}}"""

    @Test fun everyPieceIncludingTailIsCoveredExactlyOnce() {
        val tokenizer = BertWordPieceTokenizer(json, 128)
        val windows = tokenizer.encodeWindows("common ".repeat(300) + "cat")
        assertEquals(listOf(128,128,51), windows.map { it.inputIds.size })
        val bodies = windows.flatMap { it.inputIds.drop(1).dropLast(1) }
        assertEquals(List(300) { 1L } + 2L, bodies)
        assertTrue(windows.all { w -> w.attentionMask.all { it == 1L } })
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("common ".repeat(127)) }
    }

    @Test fun casedNormalizerAndSupplementaryChineseMatchTokenizerContract() {
        val ids = BertWordPieceTokenizer(json,128).encode("É é 中文𠀀").inputIds
        assertArrayEquals(longArrayOf(101,6,7,4,5,8,102), ids)
    }

    @Test fun trailingFactHasItsOwnContextInsteadOfBoilerplate() {
        val windows = BertWordPieceTokenizer(json,128).encodeWindows("common. ".repeat(20)+"cat")
        assertEquals(21,windows.size)
        assertArrayEquals(longArrayOf(101,2,102),windows.last().inputIds)
        assertEquals(20,windows.dropLast(1).count { it.inputIds.contains(1L) })
    }

    @Test fun denseShortLinesPackWithoutLosingPieces() {
        val windows = BertWordPieceTokenizer(json,128).encodeWindows("common\n".repeat(130) + "cat")
        assertEquals(2, windows.size)
        assertEquals(List(130) { 1L } + 2L, windows.flatMap { it.inputIds.drop(1).dropLast(1) })
    }

    @Test fun unsupportedAndOversizeInputFailInsteadOfCollapsingOrTruncating() {
        val tokenizer = BertWordPieceTokenizer(json, 4)
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encodeWindows("unknown") }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encodeWindows("common ".repeat(257)) }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encodeWindows("x".repeat(65_537)) }
    }
}
