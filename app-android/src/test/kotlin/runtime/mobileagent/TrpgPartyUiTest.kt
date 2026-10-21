// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.ui.actorKindText
import runtime.mobileagent.ui.partyTurnLabel
import runtime.mobileagent.ui.restPreview
import runtime.mobileagent.ui.visiblePartyActors
import runtime.mobileagent.ui.visiblePartyConditions
import runtime.mobileagent.ui.visiblePartyItems

class TrpgPartyUiTest {
    private val hero = TrpgActor(id = "hero", name = "Aria", visibility = TrpgVisibility.PLAYER,
        resources = listOf(TrpgResource("HP", 5, 10)),
        recovery = listOf(TrpgRecovery("HP", shortAmount = 2, longFull = true)))
    private val secret = TrpgActor(id = "secret", name = "Doppelganger", visibility = TrpgVisibility.GAME_MASTER)
    private val party = TrpgParty(
        actors = listOf(hero, secret),
        items = listOf(
            TrpgItem("potion", "Potion", ownerId = "hero", consumable = true),
            TrpgItem("dagger", "Dagger", ownerId = "secret"),
            TrpgItem("stash", "Rope"),
        ),
        conditions = listOf(
            TrpgCondition("bless", "hero", "Blessed"),
            TrpgCondition("hidden-cond", "secret", "Cursed"),
        ),
        encounter = TrpgEncounter(order = listOf(TrpgInitiative("secret", 20), TrpgInitiative("hero", 10)), round = 2, turn = 0),
    )

    @Test fun playerViewHidesSecretActorsTheirItemsAndConditions() {
        assertEquals(listOf("hero"), visiblePartyActors(party, gameMasterView = false).map { it.id })
        assertEquals(listOf("potion", "stash"), visiblePartyItems(party, gameMasterView = false).map { it.id })
        assertEquals(listOf("bless"), visiblePartyConditions(party, gameMasterView = false).map { it.id })
        assertEquals(listOf("hero", "secret"), visiblePartyActors(party, gameMasterView = true).map { it.id })
        assertEquals(listOf("potion", "dagger", "stash"), visiblePartyItems(party, gameMasterView = true).map { it.id })
    }

    @Test fun hiddenCurrentCombatantCollapsesToGameMasterTurnWithoutLeakingScores() {
        assertEquals("第 2 轮 · 当前：主持人回合", partyTurnLabel(party, gameMasterView = false, chinese = true))
        assertEquals("Round 2 · GM's turn", partyTurnLabel(party, gameMasterView = false, chinese = false))
        assertEquals("Round 2 · Doppelganger", partyTurnLabel(party, gameMasterView = true, chinese = false))
        val empty = party.copy(encounter = TrpgEncounter())
        assertEquals("未开始先攻", partyTurnLabel(empty, gameMasterView = false, chinese = true))
    }

    @Test fun restPreviewShowsConfiguredRecoveryOnly() {
        assertEquals("HP +2 ≤10", restPreview(hero, TrpgRest.SHORT, chinese = true))
        assertEquals("HP 全满", restPreview(hero, TrpgRest.LONG, chinese = true))
        val noRecovery = hero.copy(recovery = emptyList())
        assertEquals("未配置恢复", restPreview(noRecovery, TrpgRest.SHORT, chinese = true))
        assertTrue(restPreview(hero, TrpgRest.SHORT, chinese = false).startsWith("HP +2"))
        assertEquals(listOf("玩家", "同伴", "NPC"), TrpgActorKind.entries.map { actorKindText(it, chinese = true) })
        assertEquals(listOf("player", "companion", "npc"), TrpgActorKind.entries.map { actorKindText(it, chinese = false) })
    }
}
