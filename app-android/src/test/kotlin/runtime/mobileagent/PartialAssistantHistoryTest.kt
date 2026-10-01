// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PartialAssistantHistoryTest {
    @Test fun interruptedAnswerRemainsContextWithoutExecutingAnything() {
        assertTrue(canIncludePartialAssistant("assistant", "UNKNOWN_OUTCOME", "段落0062", false))
        assertTrue(canIncludePartialAssistant("assistant", "STREAMING", "段落0062", false))
        assertFalse(canIncludePartialAssistant("assistant", "UNKNOWN_OUTCOME", "", false))
        assertFalse(canIncludePartialAssistant("assistant", "UNKNOWN_OUTCOME", "partial", true))
        assertFalse(canIncludePartialAssistant("tool", "UNKNOWN_OUTCOME", "partial", false))
    }
}
