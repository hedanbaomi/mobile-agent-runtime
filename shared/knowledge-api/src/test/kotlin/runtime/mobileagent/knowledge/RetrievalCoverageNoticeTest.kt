// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Locks the shared retrieval-coverage notice wording produced by
 * [formatRetrievalCoverageNotice] (used verbatim by both
 * [RetrievalCoverage.notice] and KnowledgeRepository's retrieval warning):
 *
 * - zero missing/unavailable knowledge bases emit NO notice at all;
 * - otherwise the notice states N/M with N = number NOT participating and
 *   M = total requested, so it cannot be misread as "0 knowledge bases
 *   participated", and keeps the per-source reason tail;
 * - the text carries KB ids and reason codes only — never query text.
 */
class RetrievalCoverageNoticeTest {
    @Test
    fun completeCoverageEmitsNoNoticeAtAll() {
        val coverage = RetrievalCoverage(
            requested = listOf("kb-a", "kb-b"),
            searched = listOf("kb-a", "kb-b"),
        )
        assertNull(formatRetrievalCoverageNotice(coverage))
        assertNull(coverage.notice())
    }

    @Test
    fun unavailableFreeCoverageStaysSilentEvenWhenNothingWasSearched() {
        // e.g. the blank-query result: nothing searched and nothing
        // unavailable, so there is no missing source to disclose.
        val coverage = RetrievalCoverage(requested = listOf("kb-a"), searched = emptyList())
        assertNull(formatRetrievalCoverageNotice(coverage))
        assertNull(coverage.notice())
    }

    @Test
    fun singleMissingBaseStatesOneOutOfTotalRequested() {
        val coverage = RetrievalCoverage(
            requested = listOf("kb-a", "kb-b"),
            searched = listOf("kb-a"),
            unavailable = listOf(UnavailableSource("kb-b", RetrievalUnavailableReason.RETRIEVAL_DISABLED)),
        )
        val expected = "本次检索有 1/2 个知识库未参与（原因：kb-b: RETRIEVAL_DISABLED）。不要声称已检索这些未参与的来源。"
        assertEquals(expected, formatRetrievalCoverageNotice(coverage))
        assertEquals(expected, coverage.notice())
    }

    @Test
    fun partialCoverageStatesMissingCountOverTotalWithReasonTail() {
        val coverage = RetrievalCoverage(
            requested = listOf("kb-a", "kb-b", "kb-c"),
            searched = listOf("kb-a"),
            unavailable = listOf(
                UnavailableSource("kb-b", RetrievalUnavailableReason.GENERATION_NOT_READY),
                UnavailableSource("kb-c", RetrievalUnavailableReason.KB_NOT_FOUND),
            ),
        )
        val expected = "本次检索有 2/3 个知识库未参与（原因：kb-b: GENERATION_NOT_READY；kb-c: KB_NOT_FOUND）。不要声称已检索这些未参与的来源。"
        assertEquals(expected, formatRetrievalCoverageNotice(coverage))
        assertEquals(expected, coverage.notice())
    }
}
