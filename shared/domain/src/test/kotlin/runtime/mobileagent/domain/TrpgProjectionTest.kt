// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*

class TrpgProjectionTest {
    @Test fun hiddenActorReferencesAndMixedInitiativeNeverReachPlayerExport() {
        val state = TrpgState(campaign = TrpgCampaign(
            preferences = TrpgPreferences(viewRole = TrpgViewRole.GAME_MASTER, focusActorId = "SECRET"),
            facts = listOf(TrpgFact("f", "SECRET secret", visibility = TrpgVisibility.GAME_MASTER)),
            checks = listOf(TrpgCheck(TrpgCheckRequest("c", "SECRET action", actorId = "SECRET"))),
            party = TrpgParty(actors = listOf(TrpgActor("hero", "Hero"), TrpgActor("SECRET", "SECRET actor", visibility = TrpgVisibility.GAME_MASTER)),
                items = listOf(TrpgItem("i", "SECRET item", ownerId = "SECRET")),
                conditions = listOf(TrpgCondition("d", "SECRET", "SECRET condition")),
                encounter = TrpgEncounter(listOf(TrpgInitiative("hero", 10), TrpgInitiative("SECRET", 12)), 1, 1)),
        ))
        val projected = TrpgProjection.player(state)
        assertFalse(Json.encodeToString(projected).contains("SECRET"))
        assertTrue(projected.campaign.party.encounter.order.isEmpty())
        assertEquals("Hero", projected.campaign.party.actors.single().name)
        assertTrue(Json.encodeToString(state).contains("SECRET"))
    }

    @Test fun playerJournalHidesSecretRollAndActorStatsWithoutMutatingOriginal() {
        val event = TrpgEvent("e", 1, TrpgEventKind.EDIT, "Settlement", createdAt = "now", resources = listOf(
            TrpgResourceChange("HP", 10, 8, "hero", "Hero"),
            TrpgResourceChange("HP", 100, 1, "SECRET", "SECRET boss", TrpgVisibility.GAME_MASTER),
        ))
        val projected = TrpgProjection.eventForPlayer(event)!!
        assertEquals(1, projected.resources.size)
        assertFalse(Json.encodeToString(projected).contains("SECRET"))
        assertEquals(2, event.resources.size)
        assertNull(TrpgProjection.eventForPlayer(event.copy(visibility = TrpgVisibility.GAME_MASTER)))
    }

    @Test fun turnAggregationSeparatesActorIdsEvenWhenResourceAndDisplayNamesMatch() {
        val events = listOf(
            TrpgEvent("a", 1, TrpgEventKind.EDIT, "A", createdAt = "now", resources = listOf(TrpgResourceChange("HP", 10, 8, "a", "Twin"))),
            TrpgEvent("b", 2, TrpgEventKind.EDIT, "B", createdAt = "now", resources = listOf(TrpgResourceChange("HP", 5, 3, "b", "Twin"))),
            TrpgEvent("c", 3, TrpgEventKind.EDIT, "C", createdAt = "now", resources = listOf(TrpgResourceChange("HP", 8, 6, "a", "Twin"))),
        )
        val changes = trpgTurnResourceChanges(events)
        assertEquals(2, changes.size)
        assertEquals(10, changes.single { it.actorId == "a" }.before)
        assertEquals(6, changes.single { it.actorId == "a" }.after)
    }
}
