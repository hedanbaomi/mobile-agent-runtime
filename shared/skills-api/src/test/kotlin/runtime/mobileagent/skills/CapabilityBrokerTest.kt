// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityBrokerTest {
    @Test
    fun promptCannotEnlargeGrant() {
        val grant = PermissionGrant("g", "i", "hash", setOf("knowledge.search"))
        val effective = CapabilityBroker.effective(
            declared = setOf("knowledge.search", "network.http"),
            grant = grant,
            agentBound = setOf("knowledge.search", "network.http"),
            systemPolicy = setOf("knowledge.search", "network.http"),
            budgetRemaining = true,
        )
        assertEquals(setOf("knowledge.search"), effective)
    }

    @Test
    fun revokedGrantIsEmpty() {
        val grant = PermissionGrant("g", "i", "hash", setOf("knowledge.search"), revoked = true)
        assertTrue(
            CapabilityBroker.effective(
                setOf("knowledge.search"),
                grant,
                setOf("knowledge.search"),
                setOf("knowledge.search"),
                true,
            ).isEmpty(),
        )
    }
}
