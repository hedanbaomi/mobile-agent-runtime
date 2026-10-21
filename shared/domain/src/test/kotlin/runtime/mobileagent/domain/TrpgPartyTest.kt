// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*

class TrpgPartyTest {
    private val actor = TrpgActor("hero", "Hero", resources = listOf(TrpgResource("HP", 3, 10)),
        recovery = listOf(TrpgRecovery("HP", shortAmount = 2, longFull = true)))
    private val initial = TrpgState(campaign = TrpgCampaign(party = TrpgParty(actors = listOf(actor))))
    private fun apply(state: TrpgState, command: TrpgPartyCommand) = TrpgCommandReducer.apply(state, TrpgCommand(TrpgOperation.PARTY, party = command))

    @Test fun consumptionAppliesExactlyConfiguredEffectsAndRejectsPartialOrOverflow() {
        val potion = TrpgItem("potion", "Potion", "hero", 2, consumable = true, effects = listOf(TrpgResourceDelta("HP", 4)))
        val state = apply(initial, TrpgPartyCommand(TrpgPartyOperation.UPSERT_ITEM, item = potion))
        val consumed = apply(state, TrpgPartyCommand(TrpgPartyOperation.CONSUME_ITEM, id = "potion", amount = 2))
        assertEquals(0, consumed.campaign.party.items.single().quantity)
        assertEquals(11, consumed.campaign.party.actors.single().resources.single().current)
        assertThrows(IllegalArgumentException::class.java) { apply(consumed, TrpgPartyCommand(TrpgPartyOperation.CONSUME_ITEM, id = "potion", amount = 1)) }
        assertEquals(2, state.campaign.party.items.single().quantity)
        val huge = state.copy(campaign = state.campaign.copy(party = state.campaign.party.copy(items = listOf(potion.copy(effects = listOf(TrpgResourceDelta("HP", 1_000_000)))))))
        assertThrows(IllegalArgumentException::class.java) { apply(huge, TrpgPartyCommand(TrpgPartyOperation.CONSUME_ITEM, id = "potion", amount = 2)) }
    }

    @Test fun stableInitiativeTiesAndDurationTickOnlyOnFullRound() {
        val state = initial.copy(campaign = initial.campaign.copy(party = initial.campaign.party.copy(
            actors = listOf(actor, actor.copy(id = "ally")), conditions = listOf(TrpgCondition("dazed", "hero", "Dazed", remainingRounds = 2)))))
        var turn = apply(state, TrpgPartyCommand(TrpgPartyOperation.START_ENCOUNTER, initiative = listOf(TrpgInitiative("ally", 10), TrpgInitiative("hero", 10))))
        assertEquals("ally", turn.campaign.party.encounter.order.first().actorId)
        turn = apply(turn, TrpgPartyCommand(TrpgPartyOperation.NEXT_TURN))
        assertEquals(1, turn.campaign.party.encounter.round); assertEquals(2, turn.campaign.party.conditions.single().remainingRounds)
        turn = apply(turn, TrpgPartyCommand(TrpgPartyOperation.NEXT_TURN))
        assertEquals(2, turn.campaign.party.encounter.round); assertEquals(1, turn.campaign.party.conditions.single().remainingRounds)
        assertThrows(IllegalArgumentException::class.java) { apply(turn, TrpgPartyCommand(TrpgPartyOperation.REMOVE_ACTOR, id = "hero")) }
        assertThrows(IllegalArgumentException::class.java) { apply(turn, TrpgPartyCommand(TrpgPartyOperation.REST, id = "hero", rest = TrpgRest.LONG)) }
    }

    @Test fun restUsesExplicitPoliciesAndKeepsOverMaximumValues() {
        val short = apply(initial, TrpgPartyCommand(TrpgPartyOperation.REST, id = "hero", rest = TrpgRest.SHORT))
        assertEquals(5, short.campaign.party.actors.single().resources.single().current)
        val long = apply(short, TrpgPartyCommand(TrpgPartyOperation.REST, id = "hero", rest = TrpgRest.LONG))
        assertEquals(10, long.campaign.party.actors.single().resources.single().current)
        val over = apply(long, TrpgPartyCommand(TrpgPartyOperation.ADJUST_RESOURCE, id = "hero", resource = "HP", amount = 2))
        assertEquals(12, apply(over, TrpgPartyCommand(TrpgPartyOperation.REST, id = "hero", rest = TrpgRest.LONG)).campaign.party.actors.single().resources.single().current)
        assertEquals(initial.resources, short.resources)
    }

    @Test fun identitiesAndUnrelatedArgumentsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { apply(initial, TrpgPartyCommand(TrpgPartyOperation.NEXT_TURN, amount = 1)) }
        assertThrows(IllegalArgumentException::class.java) { apply(initial, TrpgPartyCommand(TrpgPartyOperation.UPSERT_ITEM, item = TrpgItem("x", "x", "foreign"))) }
        assertThrows(IllegalArgumentException::class.java) { apply(initial, TrpgPartyCommand(TrpgPartyOperation.START_ENCOUNTER, initiative = listOf(TrpgInitiative("hero", 1), TrpgInitiative("hero", 2)))) }
    }

    @Test fun deletingActorUnassignsInventoryAndRemovesOnlyTheirConditions() {
        val party = initial.campaign.party.copy(items = listOf(TrpgItem("sword", "Sword", "hero", equipped = true)),
            conditions = listOf(TrpgCondition("c", "hero", "Tired")))
        val focused = initial.campaign.copy(party = party, preferences = TrpgPreferences(focusActorId = "hero"))
        val removed = apply(initial.copy(campaign = focused), TrpgPartyCommand(TrpgPartyOperation.REMOVE_ACTOR, id = "hero"))
        assertTrue(removed.campaign.party.actors.isEmpty()); assertTrue(removed.campaign.party.conditions.isEmpty())
        assertNull(removed.campaign.party.items.single().ownerId); assertFalse(removed.campaign.party.items.single().equipped)
        assertNull(removed.campaign.preferences.focusActorId)
    }
}
