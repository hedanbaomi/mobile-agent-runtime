// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.feature.chat.ChatCitationUi
import runtime.mobileagent.feature.chat.ReplyCitationMarker
import runtime.mobileagent.feature.chat.presentReply

/**
 * Headless guards for reply citation normalization: only host-bound ids are recognized, one
 * number per immutable source, and code/footnotes/unknown ids survive untouched.
 */
class ReplyPresentationTest {

    private val longId = "9f1c2b7a-6d4e-4a91-8c3f-tool-QUJDREVGR0g"

    private fun citation(
        id: String,
        kb: String = "kb-1",
        doc: String = "doc-1",
        version: String = "v1",
        chunk: String = "chunk-1",
        title: String = "Document",
        verified: Boolean = true,
    ): ChatCitationUi = ChatCitationUi(
        id = id,
        title = title,
        source = doc,
        excerpt = "excerpt for $id",
        verified = verified,
        knowledgeBaseId = kb,
        documentVersionId = version,
        chunkId = chunk,
    )

    @Test
    fun chineseBracketsAroundAToolGeneratedIdBecomeACanonicalCitationToken() {
        val result = presentReply(listOf("结论见【$longId】。"), listOf(longId), listOf(citation(longId)))
        assertEquals(listOf("结论见[citation:$longId]。"), result.texts)
        assertEquals(mapOf(longId to ReplyCitationMarker(1, longId)), result.markers)
        assertEquals("结论见[1]。", result.copyText)
    }

    @Test
    fun everyRecognizedTokenShapeNormalizesToTheSameCanonicalForm() {
        val result = presentReply(
            listOf("[citation:a1] [a1] 【a1】 【citation:a1】 [见来源](citation:a1) [见来源](a1)"),
            listOf("a1"),
            listOf(citation("a1")),
        )
        val expected = List(5) { "[citation:a1]" }.joinToString(" ") + " [见来源](a1)"
        assertEquals(listOf(expected), result.texts)
        assertEquals(1, result.markers.size)
        assertEquals(1, result.markers.getValue("a1").number)
        assertEquals(List(5) { "[1]" }.joinToString(" ") + " [见来源](a1)", result.copyText)
    }

    @Test
    fun repeatedToolCallsForTheSameImmutableChunkShareOneNumberAndCanonicalId() {
        val first = "call-1-abc"
        val second = "call-2-def"
        val result = presentReply(
            listOf("先[citation:$first]后[citation:$second]再[citation:$first]"),
            listOf(first, second),
            listOf(citation(first), citation(second)),
        )
        assertEquals(mapOf(first to ReplyCitationMarker(1, first)), result.markers)
        assertEquals(listOf("先[citation:$first]后[citation:$first]再[citation:$first]"), result.texts)
        assertEquals("先[1]后[1]再[1]", result.copyText)
    }

    @Test
    fun siblingChunksVersionsAndKnowledgeBasesKeepTheirOwnNumbers() {
        val ids = listOf("c-chunk1", "c-chunk2", "c-v2", "c-otherkb")
        val result = presentReply(
            listOf(ids.joinToString(" ") { "[citation:$it]" }),
            ids,
            listOf(
                citation("c-chunk1", chunk = "chunk-1"),
                citation("c-chunk2", chunk = "chunk-2"),
                citation("c-v2", version = "v2", chunk = "chunk-1"),
                citation("c-otherkb", kb = "kb-2", chunk = "chunk-1"),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4), ids.map { result.markers.getValue(it).number })
        assertEquals(4, result.markers.size)
    }

    @Test
    fun aBareChunkIdIsAdmittedOnlyWhenItUniquelyIdentifiesOneBoundSource() {
        val unique = presentReply(listOf("见 [chunk-1]"), listOf("c1"), listOf(citation("c1", chunk = "chunk-1")))
        assertEquals(listOf("见 [citation:c1]"), unique.texts)
        assertEquals(1, unique.markers.getValue("c1").number)

        val ambiguous = presentReply(
            listOf("见 [shared-chunk]"),
            listOf("c1", "c2"),
            listOf(
                citation("c1", doc = "doc-1", chunk = "shared-chunk"),
                citation("c2", doc = "doc-2", chunk = "shared-chunk"),
            ),
        )
        assertEquals(listOf("见 [shared-chunk]"), ambiguous.texts)
        assertTrue(ambiguous.markers.isEmpty())
        assertEquals("见 [shared-chunk]", ambiguous.copyText)

        val unknown = presentReply(listOf("见 [forged-id]"), listOf("c1"), listOf(citation("c1")))
        assertEquals(listOf("见 [forged-id]"), unknown.texts)
        assertTrue(unknown.markers.isEmpty())
    }

    @Test
    fun aBareChunkIdStaysAdmittedWhenEveryBindingPointsAtTheSameImmutableChunk() {
        val result = presentReply(
            listOf("[shared]"),
            listOf("c1", "c2"),
            listOf(citation("c1", chunk = "shared"), citation("c2", chunk = "shared")),
        )
        assertEquals(listOf("[citation:c1]"), result.texts)
        assertEquals(setOf("c1"), result.markers.keys)
    }

    @Test
    fun anExactBoundIdWinsOverTheSameStringBeingAnotherSourcesChunkId() {
        val result = presentReply(
            listOf("[citation:chunk-of-b]"),
            listOf("chunk-of-b", "b"),
            listOf(citation("chunk-of-b", doc = "doc-a", chunk = "chunk-a"), citation("b", chunk = "chunk-of-b")),
        )
        assertEquals(listOf("[citation:chunk-of-b]"), result.texts)
        assertEquals(setOf("chunk-of-b"), result.markers.keys)
    }

    @Test
    fun fencedAndInlineCodeKeepTheirTextAndNeverBecomeCitations() {
        val body = listOf(
            "示例：`[citation:a1]` 与 ``【a1】``。",
            "```kotlin",
            "val x = \"[citation:a1]\"",
            "```",
            "~~~",
            "[citation:a1]",
            "~~~",
        ).joinToString("\n")
        val result = presentReply(listOf(body), listOf("a1"), listOf(citation("a1")))
        assertEquals(listOf(body), result.texts)
        assertTrue(result.markers.isEmpty())
        assertEquals(body, result.copyText)
    }

    @Test
    fun aTrailingCitationTheBodyNeverReferencedIsKept() {
        val text = "结论尚未引用新来源。\n\n来源：\n[citation:a1]"
        val result = presentReply(listOf(text), listOf("a1"), listOf(citation("a1")))
        assertEquals(listOf(text), result.texts)
        assertEquals(1, result.markers.getValue("a1").number)
    }

    @Test
    fun proseThatOnlyLooksLikeASourceFooterIsKept() {
        val text = "本节来源：[citation:a1] 与解释文字。\n来源：本报告由团队整理"
        val result = presentReply(listOf(text), listOf("a1"), listOf(citation("a1")))
        assertEquals(listOf(text), result.texts)
        assertEquals(1, result.markers.size)
    }

    @Test
    fun aTrailingSourceListOfAlreadyReferencedIdsIsRemoved() {
        val body = "答案要点来自甲与乙。[citation:a1] 另外一处说明。[citation:a2]"
        val text = body + "\n\n来源\n[citation:a1]\n[citation:a2]\n"
        val result = presentReply(
            listOf(text),
            listOf("a1", "a2"),
            listOf(citation("a1", chunk = "chunk-1"), citation("a2", chunk = "chunk-2")),
        )
        assertEquals(listOf(body), result.texts)
        assertEquals(
            mapOf("a1" to ReplyCitationMarker(1, "a1"), "a2" to ReplyCitationMarker(2, "a2")),
            result.markers,
        )
        assertEquals("答案要点来自甲与乙。[1] 另外一处说明。[2]", result.copyText)
    }

    @Test
    fun aHeadinglessTrailingListOfTwoAlreadyReferencedIdsIsRemoved() {
        val body = "正文提到甲[citation:a1]和乙[citation:a2]。"
        val result = presentReply(
            listOf(body + "\n\n[citation:a1]\n[citation:a2]"),
            listOf("a1", "a2"),
            listOf(citation("a1", chunk = "chunk-1"), citation("a2", chunk = "chunk-2")),
        )
        assertEquals(listOf(body), result.texts)
    }

    @Test
    fun aSingleTrailingCitationLineAfterProseIsNotTreatedAsASourceList() {
        val text = "结论见下[citation:a1]。\n\n[citation:a1]"
        val result = presentReply(listOf(text), listOf("a1"), listOf(citation("a1")))
        assertEquals(listOf(text), result.texts)
    }

    @Test
    fun aSourceListThatCarriesAnUnknownIdIsKeptWhole() {
        val text = "正文[citation:a1]。\n\n来源\n[citation:a1]\n[citation:ghost]"
        val result = presentReply(listOf(text), listOf("a1"), listOf(citation("a1")))
        assertEquals(listOf(text), result.texts)
    }

    @Test
    fun aSourceListGluedToACodeBlockIsKept() {
        val text = "正文[citation:a1]。\n\n来源\n[citation:a1]\n```\nraw\n```"
        val result = presentReply(listOf(text), listOf("a1"), listOf(citation("a1")))
        assertEquals(listOf(text), result.texts)
    }

    @Test
    fun numbersFollowFirstAppearanceAcrossAssistantFragments() {
        val result = presentReply(
            listOf("第一段引用乙[citation:b1]。", "第二段引用甲[citation:a1]与乙[citation:b1]。"),
            listOf("a1", "b1"),
            listOf(citation("a1", chunk = "chunk-1"), citation("b1", chunk = "chunk-2")),
        )
        assertEquals(2, result.markers.getValue("a1").number)
        assertEquals(1, result.markers.getValue("b1").number)
        assertEquals(
            listOf("第一段引用乙[citation:b1]。", "第二段引用甲[citation:a1]与乙[citation:b1]。"),
            result.texts,
        )
        assertEquals("第一段引用乙[1]。\n\n第二段引用甲[2]与乙[1]。", result.copyText)
    }

    @Test
    fun aFragmentThatOnlyRepeatsAlreadyReferencedIdsIsDroppedWithoutChangingTheFragmentCount() {
        val result = presentReply(
            listOf("正文[citation:a1]。", "来源\n[citation:a1]"),
            listOf("a1"),
            listOf(citation("a1")),
        )
        assertEquals(listOf("正文[citation:a1]。", ""), result.texts)
        assertEquals("正文[1]。", result.copyText)
    }

    @Test
    fun aSourceTheHostNeverBoundIsNotRecognized() {
        val result = presentReply(
            listOf("见[citation:ghost]与【ghost】与[ghost]。"),
            listOf("a1"),
            listOf(citation("a1"), citation("ghost")),
        )
        assertEquals(listOf("见[citation:ghost]与【ghost】与[ghost]。"), result.texts)
        assertTrue(result.markers.isEmpty())
    }

    @Test
    fun bindingsWithoutAFullIdentityDedupeByExactIdOnly() {
        val blank = citation("x1", kb = "", version = "", chunk = "", title = "同样的标题")
        val blank2 = citation("x2", kb = "", version = "", chunk = "", title = "同样的标题")
        val result = presentReply(
            listOf("[citation:x1] [citation:x2] [citation:x1]"),
            listOf("x1", "x2"),
            listOf(blank, blank2),
        )
        assertEquals(setOf(1, 2), setOf(result.markers.getValue("x1").number, result.markers.getValue("x2").number))
        assertEquals(listOf("[citation:x1] [citation:x2] [citation:x1]"), result.texts)
    }

    @Test
    fun anUnverifiedSourceKeepsItsOwnVersionInsteadOfRebindingToANewerOne() {
        val old = citation("old", version = "v1", chunk = "chunk-1", verified = false)
        val newer = citation("new", version = "v2", chunk = "chunk-1")
        val result = presentReply(listOf("[citation:old] 与 [citation:new]"), listOf("old", "new"), listOf(old, newer))
        assertEquals(2, result.markers.size)
        assertEquals(1, result.markers.getValue("old").number)
        assertEquals(2, result.markers.getValue("new").number)

        val bare = presentReply(listOf("[chunk-1]"), listOf("old", "new"), listOf(old, newer))
        assertEquals(listOf("[chunk-1]"), bare.texts)
    }

    @Test
    fun footnotesUnknownBracketsAndPlainLinksStayVerbatim() {
        val text = "见[1]、[附录A]、[see docs](https://example.com/a) 与 [citation:missing]。"
        val result = presentReply(listOf(text), listOf("a1"), listOf(citation("a1")))
        assertEquals(listOf(text), result.texts)
        assertTrue(result.markers.isEmpty())
    }

    @Test
    fun aMissingHostSourceCannotBecomeAnInteractiveCitation() {
        val result = presentReply(listOf("[citation:missing]"), listOf("missing"), emptyList())
        assertEquals(listOf("[citation:missing]"), result.texts)
        assertTrue(result.markers.isEmpty())
    }

    @Test
    fun ordinaryRelativeLinksNeverTurnIntoCitationLinks() {
        val result = presentReply(listOf("[label](a1)"), listOf("a1"), listOf(citation("a1")))
        assertEquals("[label](a1)", result.copyText)
        assertTrue(result.markers.isEmpty())
    }

    @Test
    fun aMarkdownSourceHeadingIsRemovedButAnUnknownEntryKeepsTheWholeList() {
        val source = citation("a1")
        val repeated = "Body [a1]\n\n## 来源\n- [a1]"
        assertEquals("Body [1]", presentReply(listOf(repeated), listOf("a1"), listOf(source)).copyText)
        val mixed = "Body [a1]\n\n## 来源\n- [missing]\n- [a1]\n- [a1]"
        assertEquals("Body [1]\n\n## 来源\n- [missing]\n- [1]\n- [1]",
            presentReply(listOf(mixed), listOf("a1"), listOf(source)).copyText)
    }

    @Test
    fun aSourceHeadingWithoutReferencesOrWithExplanationNeverDeletesProse() {
        val texts = listOf("Body [a1]\n\n来源：", "Body [a1]\n\n来源：说明\n[a1]",
            "Body [a1]\n\n来源\n补充说明\n[a1]")
        texts.forEach { text ->
            assertEquals(text.replace("[a1]", "[1]"), presentReply(listOf(text), listOf("a1"), listOf(citation("a1"))).copyText)
        }
    }

    @Test
    fun differentOriginalAssetsOfOneChunkNeverOpenTheWrongImage() {
        val a = citation("a1").copy(assetId = "image-one")
        val b = citation("a2").copy(assetId = "image-two")
        val result = presentReply(listOf("[a1] [a2] [${a.chunkId}]"), listOf("a1", "a2"), listOf(a, b))
        assertEquals("[1] [2] [${a.chunkId}]", result.copyText)
    }

    @Test
    fun anIncompleteCompetingSourceMakesABareChunkAmbiguous() {
        val a = citation("a1")
        val incomplete = citation("a2").copy(documentVersionId = "")
        val result = presentReply(listOf("[a1] [a2] [${a.chunkId}]"), listOf("a1", "a2"), listOf(a, incomplete))
        assertEquals("[1] [2] [${a.chunkId}]", result.copyText)
    }

    @Test
    fun aReplyWithoutCitationBindingsIsReturnedUnchanged() {
        val texts = listOf("没有引用。", "第二段。")
        val result = presentReply(texts, emptyList(), emptyList())
        assertEquals(texts, result.texts)
        assertTrue(result.markers.isEmpty())
        assertEquals("没有引用。\n\n第二段。", result.copyText)
    }
}
