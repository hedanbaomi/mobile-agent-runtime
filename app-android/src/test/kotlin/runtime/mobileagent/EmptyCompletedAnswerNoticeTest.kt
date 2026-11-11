// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R3 QA P3: a run that COMPLETED with no visible answer text must explain itself instead of
 * reading as a silent non-reply next to a bare retrieval-coverage notice.
 *
 * The copy itself is a resource, so this JVM test only pins the contract: the caller-supplied
 * notice is used verbatim for a blank answer, and it never appears when an answer exists. The
 * bilingual wording is asserted by `HostChatTextDeviceTest` against the real resources.
 */
class EmptyCompletedAnswerNoticeTest {
    @Test
    fun blankAnswerProducesAnActionableNotice() {
        val notice = "SYNTHETIC_NOTICE for the blank-answer contract"
        listOf("", "   ", "\n\n", "\t").forEach { blank ->
            val actual = requireNotNull(emptyCompletedAnswerNotice(blank, notice)) { "blank answer: '$blank'" }
            assertTrue(actual == notice, actual)
        }
    }

    @Test
    fun anyVisibleAnswerSuppressesTheNotice() {
        val notice = "SYNTHETIC_NOTICE"
        assertNull(emptyCompletedAnswerNotice("R3-OK-2026-09-24", notice))
        assertNull(emptyCompletedAnswerNotice("  ok  ", notice))
    }
}