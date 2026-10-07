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
        assertTrue(policy.modelAwareCompaction)
        assertEquals(100, policy.maxToolCalls)
        assertEquals(1_800_000, policy.maxRuntimeMs)
        assertEquals(32, policy.maxModelRoundsPerSegment)
        assertEquals(128, policy.maxModelRequestsPerRun)
        assertEquals(16, policy.maxCompactionsPerRun)
        assertEquals(64, policy.imageBudget)
        assertEquals(policy.imageBudget, AgentContextPolicy().imageBudget)
    }

    @Test fun explicitImageBudgetsArePreservedAndBounded() {
        assertEquals(4, AgentContextPolicy.fromJson("""{"imageBudget":4}""").imageBudget)
        assertEquals(7, AgentContextPolicy.fromJson("""{"imageBudget":7}""").imageBudget)
        assertEquals(64, AgentContextPolicy.fromJson("""{"imageBudget":64}""").imageBudget)
        assertThrows(IllegalArgumentException::class.java) { AgentContextPolicy.fromJson("""{"imageBudget":65}""") }
    }

    @Test fun configuredInputCannotExceedModelWindowAfterOutputReservation() {
        assertEquals(6000L, AgentContextPolicy(maxInputTokens = 9000).inputLimit(8000, 2000))
        assertEquals(3000L, AgentContextPolicy(maxInputTokens = 3000).inputLimit(8000, 2000))
        assertEquals(4000L, AgentContextPolicy(reservedOutputTokens = 4000).inputLimit(8000, 2000))
        assertThrows(IllegalArgumentException::class.java) { AgentContextPolicy().inputLimit(2000, 2000) }
    }

    @Test fun legacyExplicitCapsStayExactAndNewLimitsAreValidated() {
        val legacy = AgentContextPolicy.fromJson("""{"maxModelRequestsPerRun":32,"maxCompactionsPerRun":8,"maxToolCalls":20,"maxRuntimeMs":180000}""")
        assertEquals(32, legacy.maxModelRequestsPerRun)
        assertEquals(8, legacy.maxCompactionsPerRun)
        assertEquals(20, legacy.maxToolCalls)
        assertEquals(180_000, legacy.maxRuntimeMs)
        assertTrue(legacy.modelAwareCompaction)
        assertEquals(998_976L, AgentContextPolicy().inputLimit(1_000_000, null))
        assertEquals(15_360L, AgentContextPolicy().inputLimit(null, null))
        listOf("""{"modelAwareCompaction":"true"}""", """{"maxToolCalls":1001}""",
            """{"maxRuntimeMs":86400001}""", """{"maxRuntimeMs":0}""").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { AgentContextPolicy.fromJson(raw) }
        }
    }

    @Test fun malformedKnownSettingsNeverSilentlyRelaxBudgets() {
        listOf("[]", "{\"autoCompact\":\"true\"}", "{\"maxInputTokens\":null}",
            "{\"maxInputTokens\":-1}", "{\"maxModelRequestsPerRun\":513}",
            "{\"softLimitPercent\":60,\"targetPercent\":60}", "{\"imageBudget\":0}",
            "{\"maxHistoryMessages\":1.5}").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java, { AgentContextPolicy.fromJson(raw) }, raw)
        }
        assertEquals(AgentContextPolicy(), AgentContextPolicy.fromJson("{\"future_setting\":true}"))
    }
}
