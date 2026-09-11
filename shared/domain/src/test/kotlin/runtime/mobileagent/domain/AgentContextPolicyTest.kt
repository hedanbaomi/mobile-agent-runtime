// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentContextPolicyTest {
    @Test fun defaultsEnableCompactionWithSeparateSegmentAndTotalLimits() {
        val policy = AgentContextPolicy.fromJson("{}")
        assertTrue(policy.autoCompact)
        assertEquals(8, policy.maxModelRoundsPerSegment)
        assertEquals(32, policy.maxModelRequestsPerRun)
        assertEquals(8, policy.maxCompactionsPerRun)
    }

    @Test fun configuredInputCannotExceedModelWindowAfterOutputReservation() {
        assertEquals(6000L, AgentContextPolicy(maxInputTokens = 9000).inputLimit(8000, 2000))
        assertEquals(3000L, AgentContextPolicy(maxInputTokens = 3000).inputLimit(8000, 2000))
        assertEquals(4000L, AgentContextPolicy(reservedOutputTokens = 4000).inputLimit(8000, 2000))
        assertThrows(IllegalArgumentException::class.java) { AgentContextPolicy().inputLimit(2000, 2000) }
    }

    @Test fun malformedKnownSettingsNeverSilentlyRelaxBudgets() {
        listOf("[]", "{\"autoCompact\":\"true\"}", "{\"maxInputTokens\":null}",
            "{\"maxInputTokens\":-1}", "{\"maxModelRequestsPerRun\":129}",
            "{\"softLimitPercent\":60,\"targetPercent\":60}", "{\"imageBudget\":0}",
            "{\"maxHistoryMessages\":1.5}").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java, { AgentContextPolicy.fromJson(raw) }, raw)
        }
        assertEquals(AgentContextPolicy(), AgentContextPolicy.fromJson("{\"future_setting\":true}"))
    }
}
