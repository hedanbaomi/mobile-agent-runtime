// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.knowledge.VisionBinding

class VisionConsentTargetTest {
    @Test
    fun displayNameIsSeparateFromBackendConsentIdentity() {
        val binding = VisionBinding("provider-id", "vision-model", "https://example.invalid/v1/", 3, 2, 3)
        val target = visionConsentTarget("My provider", binding)
        assertEquals("provider-id|vision-model|https://example.invalid/v1|provider:2|model:3", target.fingerprint)
        assertEquals("My provider · https://example.invalid/v1/ · vision-model · provider rev 2 / model rev 3", target.label)
        assertNotEquals(target.label, target.fingerprint)
        assertNotEquals(target.fingerprint, visionConsentTarget("My provider", binding.copy(modelRevision = 4)).fingerprint)
    }
}
