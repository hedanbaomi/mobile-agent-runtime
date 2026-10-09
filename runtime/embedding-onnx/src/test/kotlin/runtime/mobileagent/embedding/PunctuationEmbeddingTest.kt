// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PunctuationEmbeddingTest {
    private val json = """{"normalizer":{"type":"BertNormalizer","clean_text":true,"handle_chinese_chars":true,"lowercase":false,"strip_accents":null},"model":{"type":"WordPiece","unk_token":"[UNK]","continuing_subword_prefix":"##","vocab":{"[PAD]":0,"[UNK]":100,"[CLS]":101,"[SEP]":102,"text":1}}}"""

    @Test
    fun standaloneEllipsisParagraphUsesTheModelUnknownPieces() {
        val encoded = BertWordPieceTokenizer(json, 128).encode("……")
        assertArrayEquals(longArrayOf(101, 100, 100, 102), encoded.inputIds)
        assertArrayEquals(longArrayOf(1, 1, 1, 1), encoded.attentionMask)
    }

    @Test
    fun unicodePunctuationAndWhitespaceKeepEveryPiece() {
        val encoded = BertWordPieceTokenizer(json, 128).encode(" \t…\u200B；\n")
        assertArrayEquals(longArrayOf(101, 100, 100, 102), encoded.inputIds)
    }

    @Test
    fun knownTextAndUnknownPunctuationKeepTheirExistingEncoding() {
        val encoded = BertWordPieceTokenizer(json, 128).encode("text ……")
        assertArrayEquals(longArrayOf(101, 1, 100, 100, 102), encoded.inputIds)
    }

    @Test
    fun punctuationDoesNotConcealUnsupportedWordsNumbersOrSymbols() {
        val tokenizer = BertWordPieceTokenizer(json, 128)
        listOf("unknown", "…… unknown", "é……", "Ж……", "123……", "……🙂", "∑").forEach { text ->
            val failure = assertThrows(IllegalArgumentException::class.java) { tokenizer.encodeWindows(text) }
            assertTrue(failure.message.orEmpty().contains("LOCAL_EMBEDDING_UNSUPPORTED_TEXT"))
        }
    }

    @Test
    fun punctuationStillObeysInputAndWindowBounds() {
        val tokenizer = BertWordPieceTokenizer(json, 4)
        val windows = tokenizer.encodeWindows("…".repeat(256))
        assertTrue(windows.size == 128)
        assertTrue(windows.sumOf { it.inputIds.size - 2 } == 256)
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encodeWindows("…".repeat(257)) }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encodeWindows("…".repeat(65_537)) }
    }
}
