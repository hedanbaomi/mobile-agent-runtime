// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.feature.agents.AgentResourceBindingUi

class DisabledSkillBindingTest {
    @Test
    fun agentCardCountExcludesPausedMissingAndUnassociatedInstalls() {
        val associations = listOf("skill.active", "skill.paused", "skill.missing")
        assertEquals(1, activeSkillBindingCount(associations, setOf("skill.active", "skill.unassociated")))
        assertEquals(2, activeSkillBindingCount(associations, setOf("skill.active", "skill.paused")))
        assertEquals(0, activeSkillBindingCount(associations, emptySet()))
        assertEquals(3, associations.size)
    }

    @Test
    fun pausePreservesIntentWhileUnlinkPermanentlyDropsItUntilSkillIsAvailable() {
        val active = AgentResourceBindingUi("skill.one", "Skill", "skill", enabled = true)
        assertTrue(active.active)
        val paused = active.copy(available = false)
        assertTrue(paused.enabled)
        assertFalse(paused.active)
        assertEquals(paused, paused.withAssociation(true))

        val unlinked = paused.withAssociation(false)
        assertFalse(unlinked.enabled)
        assertFalse(unlinked.active)
        assertEquals(unlinked, unlinked.withAssociation(true))
        assertFalse(unlinked.copy(available = true, selectable = true).active)
        assertTrue(unlinked.copy(available = true, selectable = true).withAssociation(true).active)
        assertTrue(paused.copy(available = true).active)
    }

    @Test
    fun unavailableNewSkillCannotBecomeAnAssociation() {
        val unbound = AgentResourceBindingUi("skill.new", "Skill", "skill", enabled = false, available = false)
        assertEquals(unbound, unbound.withAssociation(true))
        assertFalse(unbound.active)
    }
}
