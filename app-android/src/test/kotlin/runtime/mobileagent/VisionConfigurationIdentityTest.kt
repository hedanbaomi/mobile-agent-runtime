// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ApiFormat
import runtime.mobileagent.domain.ModelProfile
import runtime.mobileagent.domain.ModelRole
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.ProviderProfile

class VisionConfigurationIdentityTest {
    private fun provider() = ProviderProfile(id = "provider.fp", name = "Fingerprint", apiFormat = ApiFormat.OPENAI_COMPATIBLE, baseUrl = "https://fp.example.invalid/v1", revision = 1)

    private fun model(mode: OutputLimitMode, limit: Int) = ModelProfile(
        id = "profile.fp", providerId = "provider.fp", role = ModelRole.CHAT, modelId = "fp-model",
        capabilities = setOf("image", "stream"), contextLimit = 32_768, outputLimit = limit,
        revision = 1, outputLimitMode = mode,
    )

    @Test
    fun manualIdentityKeepsTheLegacyNumericSlot() {
        val identity = visionConfigurationIdentity(provider(), model(OutputLimitMode.MANUAL, 512))
        assertTrue(identity.contains("512"), identity)
        assertFalse(identity.contains("auto"), identity)
    }

    @Test
    fun automaticIdentityIsMarkedAndDiffersFromManual() {
        val auto = visionConfigurationIdentity(provider(), model(OutputLimitMode.AUTO, 0))
        assertTrue(auto.contains("auto"), auto)
        assertNotEquals(visionConfigurationIdentity(provider(), model(OutputLimitMode.MANUAL, 0)), auto)
        assertNotEquals(visionConfigurationIdentity(provider(), model(OutputLimitMode.MANUAL, 512)), auto)
    }

    @Test
    fun fingerprintsOfAutoAndManualDifferAndStayDeterministic() {
        val auto = visionProfileBinding(provider(), model(OutputLimitMode.AUTO, 0)).fingerprint
        val manualZero = visionProfileBinding(provider(), model(OutputLimitMode.MANUAL, 0)).fingerprint
        val manual512 = visionProfileBinding(provider(), model(OutputLimitMode.MANUAL, 512)).fingerprint
        assertNotEquals(manualZero, auto)
        assertNotEquals(manual512, auto)
        assertNotEquals(manualZero, manual512)
        assertEquals(manual512, visionProfileBinding(provider(), model(OutputLimitMode.MANUAL, 512)).fingerprint)
    }
}