// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.*

class TrpgStateEditingTest {
    @Test fun adjustmentsPreserveTemporaryValuesAndOnlyClampDomainBounds() {
        assertEquals(16, adjustTrpgResource(TrpgResource("HP", 15, 10), 1).current)
        assertEquals(14, adjustTrpgResource(TrpgResource("HP", 15, 10), -1).current)
        assertEquals(1_000_000, adjustTrpgResource(TrpgResource("HP", 1_000_000), 1).current)
        assertEquals(-1_000_000, adjustTrpgResource(TrpgResource("Debt", -1_000_000), -1).current)
    }

    @Test fun invalidMaximumOrBoundsNeverBecomeAnUnlimitedResource() {
        val state = TrpgState()
        for (invalid in listOf("abc", "2147483648", "-1", "1000001")) {
            assertThrows(IllegalArgumentException::class.java) { addTrpgResource(state, "Morale", "7", invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { addTrpgResource(state, "Morale", "1000001", "") }
        assertNull(addTrpgResource(state, "Morale", "7", " ").resources.single().maximum)
        val added = addTrpgResource(state, "Morale", "7", "10")
        assertThrows(IllegalArgumentException::class.java) { addTrpgResource(added, " Morale ", "9", "10") }
    }

    @Test fun starterOnlyAddsMissingFieldsAndRejectsCapacityWithoutDroppingAnything() {
        val hp = TrpgResource("HP", 7, 15)
        val custom = TrpgResource("Soul debt", -2)
        val base = TrpgState(playerName = "PC", scene = "Tower", notes = "Keep me", resources = listOf(hp, custom))
        val next = trpgStarterState(TrpgRuleset.DND_3R, base, true)
        assertEquals(listOf(hp, custom), next.resources.take(2))
        assertEquals(1, next.resources.count { it.name == "HP" })
        assertEquals(base.notes, next.notes)
        assertEquals(next, trpgStarterState(TrpgRuleset.DND_3R, next, true))
        val full = base.copy(resources = (1..32).map { TrpgResource("R$it", it) })
        assertThrows(IllegalArgumentException::class.java) { trpgStarterState(TrpgRuleset.COC_7, full, false) }
        assertEquals(32, full.resources.size)
    }

    @Test fun appendingANotePreservesContentAndRejectsOverflow() {
        assertEquals("Old\nDice", appendTrpgNote(TrpgState(notes = "Old"), "Dice").notes)
        val full = TrpgState(notes = "x".repeat(8192))
        assertThrows(IllegalArgumentException::class.java) { appendTrpgNote(full, "Dice") }
        assertEquals(8192, full.notes.length)
    }
}
