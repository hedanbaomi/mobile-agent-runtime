// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Automatic context window: unknown stays unknown, a recorded value only
 * applies to the exact frozen target, and local protection keeps the input
 * budget finite so compaction never silently stops.
 */
class AutoContextWindowTest {
    private val target = contextWindowTargetKey("provider", 2, "https://api.example/v1", "model-x")

    private fun model(
        mode: ContextLimitMode,
        limit: Int = 32_768,
        value: Int? = null,
        source: ContextLimitSource = ContextLimitSource.UNKNOWN,
        recordedFor: String? = null,
    ) = ModelProfile(
        id = "m", providerId = "provider", role = ModelRole.CHAT, modelId = "model-x",
        capabilities = setOf("stream"), contextLimit = limit, outputLimit = 4096, revision = 1,
        contextLimitMode = mode, contextWindowValue = value, contextWindowSource = source,
        contextWindowTarget = recordedFor,
    )

    @Test
    fun autoWithoutACapabilityIsUnknownNotUnlimited() {
        val unknown = model(ContextLimitMode.AUTO)
        assertNull(unknown.resolvedContextWindow(target))
        assertFalse(unknown.contextWindowIsStale(target))
    }

    @Test
    fun autoUsesARecordedValueForTheSameTarget() {
        val known = model(ContextLimitMode.AUTO, value = 131_072, source = ContextLimitSource.PROVIDER_METADATA, recordedFor = target)
        assertEquals(131_072, known.resolvedContextWindow(target))
        assertFalse(known.contextWindowIsStale(target))
    }

    @Test
    fun autoDegradesToUnknownWhenTheTargetChanged() {
        val stale = model(ContextLimitMode.AUTO, value = 131_072, source = ContextLimitSource.USER_DECLARED, recordedFor = contextWindowTargetKey("provider", 1, "https://api.example/v1", "model-x"))
        assertNull(stale.resolvedContextWindow(target))
        assertTrue(stale.contextWindowIsStale(target))
    }

    @Test
    fun manualIsAUserOverrideAndIgnoresRecordedCapability() {
        val manual = model(ContextLimitMode.MANUAL, limit = 65_536, value = 8_192, source = ContextLimitSource.PROVIDER_METADATA, recordedFor = target)
        assertEquals(65_536, manual.resolvedContextWindow(target))
        assertFalse(manual.contextWindowIsStale(target))
    }

    @Test
    fun unknownWindowStillYieldsAFiniteInputBudgetAndKeepsCompaction() {
        val policy = AgentContextPolicy()
        val budget = policy.inputLimit(null, null)
        assertEquals((policy.localUnknownWindow - policy.localOutputReserve).toLong(), budget)
        assertTrue(policy.autoCompact)
    }

    @Test
    fun knownWindowAndAutoOutputCombine() {
        val policy = AgentContextPolicy()
        // AUTO output (null cap) with a known window.
        assertEquals(131_072L - policy.localOutputReserve, policy.inputLimit(131_072, null))
        // AUTO window (null) with a MANUAL output cap.
        assertEquals(policy.localUnknownWindow - 8192L, policy.inputLimit(null, 8192))
    }

    @Test
    fun targetKeyIsStableForTrailingSlashes() {
        assertEquals(target, contextWindowTargetKey("provider", 2, "https://api.example/v1/", "model-x"))
    }

    @Test
    fun stateNamesWhatTheRuntimeWillActuallyRelyOn() {
        // MANUAL is the user's own number regardless of a recorded capability.
        assertEquals(ContextWindowState.Mode.MANUAL, model(ContextLimitMode.MANUAL, limit = 65_536).resolvedContextWindowState(target).mode)
        assertEquals(65_536, model(ContextLimitMode.MANUAL, limit = 65_536).resolvedContextWindowState(target).value)

        // AUTO without any recorded window stays unknown - never a fabricated number.
        assertEquals(ContextWindowState.Mode.UNKNOWN, model(ContextLimitMode.AUTO).resolvedContextWindowState(target).mode)

        val effective = model(
            ContextLimitMode.AUTO, value = 131_072,
            source = ContextLimitSource.PROVIDER_METADATA, recordedFor = target,
        ).resolvedContextWindowState(target)
        assertEquals(ContextWindowState.Mode.AUTO_EFFECTIVE, effective.mode)
        assertEquals(131_072, effective.value)

        // A target change is reported as stale, and the stale value is not "effective".
        val stale = model(
            ContextLimitMode.AUTO, value = 131_072,
            source = ContextLimitSource.USER_DECLARED, recordedFor = contextWindowTargetKey("provider", 1, "https://api.example/v1", "model-x"),
        ).resolvedContextWindowState(target)
        assertEquals(ContextWindowState.Mode.STALE, stale.mode)
        assertNull(stale.value)
        assertEquals(131_072, stale.recordedValue)
    }

    /**
     * The UI and the runtime must judge the same recorded row the same way: a
     * canonical target written by the editor and a legacy revision target read
     * from an older row both resolve to the same effective window.
     */
    @Test
    fun canonicalAndLegacyTargetsAgreeForTheSameRow() {
        val canonical = contextWindowTarget("provider", "https://api.example/v1", "model-x")
        val legacy = contextWindowTargetKey("provider", 2, "https://api.example/v1", "model-x")
        assertTrue(contextWindowTargetMatches(legacy, canonical))
        assertEquals(
            model(ContextLimitMode.AUTO, value = 131_072, source = ContextLimitSource.USER_DECLARED, recordedFor = legacy)
                .resolvedContextWindowState(canonical),
            model(ContextLimitMode.AUTO, value = 131_072, source = ContextLimitSource.USER_DECLARED, recordedFor = canonical)
                .resolvedContextWindowState(canonical),
        )
    }
}
