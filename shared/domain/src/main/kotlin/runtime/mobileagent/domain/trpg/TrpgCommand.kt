// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable

@Serializable enum class TrpgOperation { REQUEST_CHECK, RESOLVE_CHECK, CANCEL_CHECK, UPSERT_FACT, REMOVE_FACT, ARCHIVE_CHECK, PARTY, STORY, ATLAS }

/** One explicit operation; fields belonging to another operation are rejected. */
@Serializable
data class TrpgCommand(
    val operation: TrpgOperation,
    val id: String? = null,
    val check: TrpgCheckRequest? = null,
    val fact: TrpgFact? = null,
    val note: String? = null,
    val party: TrpgPartyCommand? = null,
    val story: TrpgStoryCommand? = null,
    val atlas: TrpgAtlasCommand? = null,
) {
    fun validate() {
        if (operation != TrpgOperation.PARTY) require(party == null)
        if (operation != TrpgOperation.STORY) require(story == null)
        if (operation != TrpgOperation.ATLAS) require(atlas == null)
        when (operation) {
            TrpgOperation.ATLAS -> { require(id == null && check == null && fact == null && note == null); requireNotNull(atlas).validate() }
            TrpgOperation.STORY -> { require(id == null && check == null && fact == null && note == null); requireNotNull(story).validate() }
            TrpgOperation.PARTY -> { require(id == null && check == null && fact == null && note == null); requireNotNull(party).validate() }
            TrpgOperation.REQUEST_CHECK -> { require(id == null && fact == null && note == null); TrpgCampaignValidation.validate(requireNotNull(check)) }
            TrpgOperation.UPSERT_FACT -> { require(id == null && check == null && note == null); TrpgCampaignValidation.validate(requireNotNull(fact)) }
            TrpgOperation.REMOVE_FACT, TrpgOperation.CANCEL_CHECK, TrpgOperation.ARCHIVE_CHECK -> {
                require(check == null && fact == null && note == null); TrpgCampaignValidation.id(requireNotNull(id))
            }
            TrpgOperation.RESOLVE_CHECK -> {
                require(check == null && fact == null); TrpgCampaignValidation.id(requireNotNull(id))
                require(!note.isNullOrBlank() && note.length <= 2048)
            }
        }
    }
}

object TrpgCommandReducer {
    fun apply(state: TrpgState, command: TrpgCommand): TrpgState {
        command.validate()
        if (command.operation == TrpgOperation.PARTY) return TrpgPartyReducer.apply(state, checkNotNull(command.party))
        val campaign = state.campaign
        val next = when (command.operation) {
            TrpgOperation.ATLAS -> campaign.copy(atlas = TrpgAtlasReducer.apply(campaign.atlas, checkNotNull(command.atlas)))
            TrpgOperation.STORY -> campaign.copy(story = TrpgStoryReducer.apply(campaign.story, checkNotNull(command.story)))
            TrpgOperation.PARTY -> error("Handled above")
            TrpgOperation.ARCHIVE_CHECK -> {
                val check = campaign.checks.singleOrNull { it.request.id == command.id } ?: error("Check is missing")
                require(check.status == TrpgCheckStatus.RESOLVED || check.status == TrpgCheckStatus.CANCELLED) { "Finish the check before archiving" }
                campaign.copy(checks = campaign.checks.filterNot { it.request.id == command.id })
            }
            TrpgOperation.REQUEST_CHECK -> {
                val request = requireNotNull(command.check)
                request.actorId?.let { actorId ->
                    val actor = campaign.party.actors.singleOrNull { it.id == actorId } ?: error("Check actor is missing")
                    require(actor.visibility == TrpgVisibility.PLAYER || request.visibility == TrpgVisibility.GAME_MASTER) {
                        "A hidden actor requires a hidden check"
                    }
                }
                require(campaign.checks.none { it.request.id == request.id }) { "Check identity already exists" }
                campaign.copy(checks = campaign.checks + TrpgCheck(request))
            }
            TrpgOperation.RESOLVE_CHECK, TrpgOperation.CANCEL_CHECK -> {
                val check = campaign.checks.singleOrNull { it.request.id == command.id } ?: error("Check is missing")
                val resolved = command.operation == TrpgOperation.RESOLVE_CHECK
                require(if (resolved) check.status == TrpgCheckStatus.ROLLED else check.status == TrpgCheckStatus.PENDING) { "Check is not eligible for this operation" }
                campaign.copy(checks = campaign.checks.map {
                    if (it.request.id == command.id) it.copy(status = if (resolved) TrpgCheckStatus.RESOLVED else TrpgCheckStatus.CANCELLED,
                        resolution = command.note.orEmpty()) else it
                })
            }
            TrpgOperation.UPSERT_FACT -> {
                val fact = requireNotNull(command.fact)
                campaign.copy(facts = if (campaign.facts.any { it.id == fact.id }) campaign.facts.map { if (it.id == fact.id) fact else it }
                    else campaign.facts + fact)
            }
            TrpgOperation.REMOVE_FACT -> {
                require(campaign.facts.any { it.id == command.id }) { "Fact is missing" }
                campaign.copy(facts = campaign.facts.filterNot { it.id == command.id })
            }
        }
        return state.copy(campaign = next).also(TrpgStateValidation::validate)
    }
}
