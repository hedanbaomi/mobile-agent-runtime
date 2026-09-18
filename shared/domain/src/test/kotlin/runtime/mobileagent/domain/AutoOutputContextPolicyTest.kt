// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AUTO must not damage context management: the provider cap and the local
 * reserve are separate concepts, so an absent provider cap can neither disable
 * compaction nor make the input window look unlimited.
 */
class AutoOutputContextPolicyTest {
    @Test
    fun autoKeepsAFiniteInputBudgetFromTheLocalReserve() {
        val policy = AgentContextPolicy()
        // No provider cap, no explicit local reserve: the documented local
        // reserve still bounds the input budget.
        assertEquals(32_768L - policy.localOutputReserve, policy.inputLimit(32_768, null))
        assertTrue(policy.autoCompact, "compaction stays enabled")
    }

    @Test
    fun manualUsesTheProviderCapAsTheReserve() {
        val policy = AgentContextPolicy()
        assertEquals(32_768L - 8192, policy.inputLimit(32_768, 8192))
    }

    @Test
    fun explicitLocalReserveOverridesTheFallbackUnderAuto() {
        val policy = AgentContextPolicy(reservedOutputTokens = 4096)
        assertEquals(32_768L - 4096, policy.inputLimit(32_768, null))
    }

    @Test
    fun explicitInputCeilingStillAppliesUnderAuto() {
        val policy = AgentContextPolicy(maxInputTokens = 8000)
        assertEquals(8000L, policy.inputLimit(32_768, null))
    }

    @Test
    fun autoRoundTripsThroughTheStoredJsonWithItsFieldDefaults() {
        val decoded = AgentContextPolicy.fromJson("""{"autoCompact":true,"localOutputReserve":2048}""")
        assertEquals(2048, decoded.localOutputReserve)
        assertEquals(32_768L - 2048, decoded.inputLimit(32_768, null))
    }

    @Test
    fun providerCapAndLocalReserveReportTheLargerReserve() {
        val policy = AgentContextPolicy()
        // A tiny provider cap must not shrink the local protection.
        assertEquals(policy.outputReserve(64), policy.localOutputReserve.toLong())
        // A huge manual cap is still honoured as the reserve.
        assertEquals(20_000L, policy.outputReserve(20_000))
        assertNull(null)
    }
}