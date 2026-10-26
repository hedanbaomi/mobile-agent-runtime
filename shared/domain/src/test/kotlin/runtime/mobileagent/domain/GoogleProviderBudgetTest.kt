// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GoogleProviderBudgetTest {
    @Test fun nativeCapsDriveTheSameReservationAndWireDecision() {
        for (params in listOf("""{"maxOutputTokens":8192}""", """{"generationConfig":{"maxOutputTokens":8192}}""")) {
            assertNull(validateOutputCapLayers(params))
            val cap = resolveEffectiveOutputCap(OutputLimitMode.AUTO, 0, params)
            assertEquals(8192, cap.value)
            assertEquals("maxOutputTokens", cap.key)
            assertEquals(OutputCapSource.ADVANCED_OVERRIDE, cap.source)
            assertTrue(hasAdvancedOutputLimitOverride(params))
            assertEquals(1024, pythonModelWireDecision(OutputLimitMode.AUTO, 0, params, "{}", 1024).outputTokenLimit)
        }
    }

    @Test fun malformedOrConflictingNativeCapsCannotBypassAdmission() {
        for (value in listOf("0", "-1", "1.5", "\"8192\"", "4294975488", "null", "{}")) {
            assertNotNull(validateOutputCapLayers("""{"generationConfig":{"maxOutputTokens":$value}}"""), value)
        }
        assertEquals(OutputCapValidationError.AMBIGUOUS_ALIASES,
            validateOutputCapLayers("""{"max_tokens":1,"generationConfig":{"maxOutputTokens":8192}}"""))
        assertEquals(512, resolveEffectiveOutputCap(OutputLimitMode.MANUAL, 128,
            """{"generationConfig":{"maxOutputTokens":8192}}""", """{"max_tokens":512}""").value)
    }

    @Test fun probeStrippingPreservesOtherNativeGenerationSettings() {
        val root = Json.parseToJsonElement(stripOutputCapAliases(
            """{"max_tokens":8192,"generationConfig":{"maxOutputTokens":8192,"temperature":0.4,"thinkingConfig":{"thinkingBudget":0}}}"""
        )).jsonObject
        assertFalse(root.containsKey("max_tokens"))
        val native = root.getValue("generationConfig").jsonObject
        assertFalse(native.containsKey("maxOutputTokens"))
        assertTrue(native.containsKey("temperature"))
        assertTrue(native.containsKey("thinkingConfig"))
    }

    @Test fun changingOnlyTheAuthProtocolDetachesOldAuxiliarySecrets() {
        val old = ProviderProfile("provider", "Fixture", ApiFormat.GOOGLE_AI_STUDIO,
            "https://example.invalid/v1", headerSecretRefs = mapOf("X-Tenant-Token" to "synthetic-ref"), revision = 1)
        assertEquals(old.headerSecretRefs, ProviderDestinationBinding.headerSecretRefsForSave(old, old.baseUrl))
        assertTrue(ProviderDestinationBinding.headerSecretRefsForSave(old, old.baseUrl,
            old.headerSecretRefs, ApiFormat.GOOGLE_VERTEX_EXPRESS).isEmpty())
    }
}
