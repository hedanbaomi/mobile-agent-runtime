// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

/** Presentation/export projection only. Never persist this projection over the authoritative state. */
object TrpgProjection {
    fun eventForPlayer(event: TrpgEvent): TrpgEvent? = event.takeIf { it.visibility == TrpgVisibility.PLAYER }
        ?.copy(resources = event.resources.filter { it.visibility == TrpgVisibility.PLAYER })

    fun player(state: TrpgState): TrpgState {
        val campaign = state.campaign
        val party = campaign.party
        val actors = party.actors.filter { it.visibility == TrpgVisibility.PLAYER }
        val actorIds = actors.map { it.id }.toSet()
        val checks = campaign.checks.filter { it.request.visibility == TrpgVisibility.PLAYER &&
            (it.request.actorId == null || it.request.actorId in actorIds) }
        return state.copy(campaign = campaign.copy(
            preferences = campaign.preferences.copy(viewRole = TrpgViewRole.PLAYER,
                focusActorId = campaign.preferences.focusActorId?.takeIf { it in actorIds }),
            story = TrpgStoryProjection.player(campaign.story), atlas = TrpgAtlasProjection.player(campaign.atlas), origin = null,
            facts = campaign.facts.filter { it.visibility == TrpgVisibility.PLAYER }, checks = checks,
            party = party.copy(actors = actors,
                items = party.items.filter { it.visibility == TrpgVisibility.PLAYER && (it.ownerId == null || it.ownerId in actorIds) },
                conditions = party.conditions.filter { it.visibility == TrpgVisibility.PLAYER && it.actorId in actorIds },
                // A mixed hidden/public order cannot be represented without exposing hidden turns.
                encounter = party.encounter.takeIf { it.order.all { entry -> entry.actorId in actorIds } } ?: TrpgEncounter()),
        ))
    }
}
