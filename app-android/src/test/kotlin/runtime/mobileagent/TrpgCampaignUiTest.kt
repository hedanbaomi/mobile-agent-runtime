// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.canSaveTrpgDraft
import runtime.mobileagent.ui.editableTrpgProjection
import runtime.mobileagent.ui.mergeCampaignForSave
import runtime.mobileagent.ui.togglePinnedResource
import runtime.mobileagent.ui.visibleTrpgChecks
import runtime.mobileagent.ui.visibleTrpgFacts

class TrpgCampaignUiTest {
    private fun check(id: String, visibility: TrpgVisibility) =
        TrpgCheck(TrpgCheckRequest(id = id, title = id, visibility = visibility))

    private fun fact(id: String, visibility: TrpgVisibility) =
        TrpgFact(id = id, text = id, visibility = visibility)

    @Test fun playerViewHidesGameMasterChecksAndFacts() {
        val checks = listOf(check("open", TrpgVisibility.PLAYER), check("secret", TrpgVisibility.GAME_MASTER))
        val facts = listOf(fact("known", TrpgVisibility.PLAYER), fact("hidden", TrpgVisibility.GAME_MASTER))
        assertEquals(listOf("open"), visibleTrpgChecks(checks, gameMasterView = false).map { it.request.id })
        assertEquals(listOf("known"), visibleTrpgFacts(facts, gameMasterView = false).map { it.id })
        assertEquals(listOf("open", "secret"), visibleTrpgChecks(checks, gameMasterView = true).map { it.request.id })
        assertEquals(listOf("known", "hidden"), visibleTrpgFacts(facts, gameMasterView = true).map { it.id })
    }

    @Test fun pinnedResourcesToggleAndStopAtEight() {
        var pinned = emptyList<String>()
        pinned = togglePinnedResource(pinned, "HP")
        pinned = togglePinnedResource(pinned, "SAN")
        assertEquals(listOf("HP", "SAN"), pinned)
        pinned = togglePinnedResource(pinned, "HP")
        assertEquals(listOf("SAN"), pinned)
        var full = (1..8).map { "R$it" }
        full = togglePinnedResource(full, "Ninth")
        assertEquals(8, full.size)
        assertEquals((1..8).map { "R$it" }, full)
        full = togglePinnedResource(full, "R3")
        assertEquals(7, full.size)
    }

    @Test fun savingAdoptsAuthoritativeCampaignWhileKeepingDraftPreferencesAndText() {
        val draftPrefs = TrpgPreferences(showRollDetails = false, pinnedResources = listOf("HP"), tone = "dark")
        val draft = TrpgState(playerName = "Aria", scene = "Tower", notes = "draft note",
            resources = listOf(TrpgResource("HP", 7, 10)),
            campaign = TrpgCampaign(preferences = draftPrefs))
        val authoritative = TrpgState(playerName = "stale", scene = "stale",
            campaign = TrpgCampaign(
                preferences = TrpgPreferences(tone = "old"),
                facts = listOf(TrpgFact("f1", "Door opened", status = TrpgFactStatus.CONFIRMED)),
                checks = listOf(TrpgCheck(TrpgCheckRequest("c1", "Spot"), TrpgCheckStatus.PENDING)),
            ))
        val merged = mergeCampaignForSave(draft, authoritative)
        assertEquals("Aria", merged.playerName)
        assertEquals("Tower", merged.scene)
        assertEquals("draft note", merged.notes)
        assertEquals(listOf(TrpgResource("HP", 7, 10)), merged.resources)
        assertEquals(draftPrefs, merged.campaign.preferences)
        assertEquals(listOf("f1"), merged.campaign.facts.map { it.id })
        assertEquals(listOf("c1"), merged.campaign.checks.map { it.request.id })
    }

    @Test fun externalHpOrPreferenceChangeBlocksOldDraftSaveButCommandOnlyChangesDoNot() {
        val baseline = TrpgState(playerName = "Aria", resources = listOf(TrpgResource("HP", 10, 10)))
        val commandOnly = baseline.copy(campaign = baseline.campaign.copy(
            facts = listOf(TrpgFact("f1", "Door opened")),
            checks = listOf(TrpgCheck(TrpgCheckRequest("c1", "Spot"))),
        ))
        assertTrue(canSaveTrpgDraft(baseline, commandOnly))
        val externalHp = baseline.copy(resources = listOf(TrpgResource("HP", 7, 10)))
        assertFalse(canSaveTrpgDraft(baseline, externalHp))
        val externalPrefs = baseline.copy(campaign = baseline.campaign.copy(
            preferences = baseline.campaign.preferences.copy(showRollDetails = false),
        ))
        assertFalse(canSaveTrpgDraft(baseline, externalPrefs))
        // Facts/checks never leak into the editable projection used for conflict detection.
        assertEquals(editableTrpgProjection(baseline), editableTrpgProjection(commandOnly))
    }
}
