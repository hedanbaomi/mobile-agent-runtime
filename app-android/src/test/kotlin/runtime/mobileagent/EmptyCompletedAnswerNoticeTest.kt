// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R3 QA P3: a run that COMPLETED with no visible answer text must explain itself instead of
 * reading as a silent non-reply next to a bare retrieval-coverage notice.
 */
class EmptyCompletedAnswerNoticeTest {
    @Test
    fun blankAnswerProducesAnActionableNotice() {
        listOf("", "   ", "\n\n", "\t").forEach { blank ->
            val notice = requireNotNull(emptyCompletedAnswerNotice(blank)) { "blank answer: '$blank'" }
            assertTrue("没有给出可见答复" in notice, notice)
            assertTrue("检索" in notice, notice)
        }
    }

    @Test
    fun anyVisibleAnswerSuppressesTheNotice() {
        assertNull(emptyCompletedAnswerNotice("R3-OK-2026-09-24"))
        assertNull(emptyCompletedAnswerNotice("  ok  "))
    }
}
