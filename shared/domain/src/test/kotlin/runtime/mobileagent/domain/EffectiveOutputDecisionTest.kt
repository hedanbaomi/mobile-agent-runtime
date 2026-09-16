// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EffectiveOutputDecisionTest {
    @Test
    fun profileManualIsTheDefaultCap() {
        val decision = resolveEffectiveOutputCap(OutputLimitMode.MANUAL, 8192)
        assertEquals(8192, decision.value)
        assertEquals(OutputCapSource.PROFILE_MANUAL, decision.source)
    }

    @Test
    fun autoWithoutOverrideSendsNothing() {
        val decision = resolveEffectiveOutputCap(OutputLimitMode.AUTO, 0)
        assertEquals(null, decision.value)
        assertEquals(OutputCapSource.PROVIDER_DEFAULT, decision.source)
    }

    /** The explicit override replaces the profile default, high or low. */
    @Test
    fun advancedOverrideReplacesTheProfileValueInBothDirections() {
        val lower = resolveEffectiveOutputCap(OutputLimitMode.MANUAL, 8192, "{\"max_completion_tokens\":4096}")
        assertEquals(4096, lower.value)
        assertEquals(OutputCapSource.ADVANCED_OVERRIDE, lower.source)
        val higher = resolveEffectiveOutputCap(OutputLimitMode.MANUAL, 8192, "{\"max_tokens\":9000}")
        assertEquals(9000, higher.value)
        assertEquals("max_tokens", higher.key)
    }

    @Test
    fun agentOverridesWinOverModelParameters() {
        val decision = resolveEffectiveOutputCap(OutputLimitMode.MANUAL, 8192, "{\"max_tokens\":3000}", "{\"max_tokens\":5000}")
        assertEquals(5000, decision.value)
    }

    @Test
    fun autoWithAnAdvancedOverrideSendsThatValue() {
        val decision = resolveEffectiveOutputCap(OutputLimitMode.AUTO, 0, "{\"max_output_tokens\":777}")
        assertEquals(777, decision.value)
    }

    /** R1: a probe never reads the numeric column the mode declares ignored. */
    @Test
    fun probeBudgetIsIndependentOfTheModeSentinel() {
        assertEquals(64, probeOutputTokenLimit(OutputLimitMode.AUTO, 0, 64))
        assertEquals(64, probeOutputTokenLimit(OutputLimitMode.AUTO, 0, 128).coerceAtMost(64))
        assertEquals(64, probeOutputTokenLimit(OutputLimitMode.MANUAL, 8192, 64))
        assertEquals(32, probeOutputTokenLimit(OutputLimitMode.MANUAL, 32, 64))
    }

    /** R2: an unknown upstream window must never make the local input budget fail. */
    @Test
    fun unknownWindowWithALargeManualOutputStillYieldsAUsableBudget() {
        val policy = AgentContextPolicy()
        assertTrue(policy.inputLimit(null, 32768) > 0)
        assertTrue(policy.inputLimit(null, 16384) > 0)
        assertTrue(policy.inputLimit(null, null) > 0)
    }

    /** R4: only provider/endpoint/model select the upstream capability. */
    @Test
    fun localRevisionsDoNotChangeTheCapabilityTarget() {
        assertEquals(
            contextWindowTarget("provider", "https://api.example/v1", "model-x"),
            contextWindowTarget("provider", "https://api.example/v1/", "model-x"),
        )
        assertTrue(contextWindowTarget("provider", "https://a.example/v1", "model-x") != contextWindowTarget("provider", "https://b.example/v1", "model-x"))
    }
}