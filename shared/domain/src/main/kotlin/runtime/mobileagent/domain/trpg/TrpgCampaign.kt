// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain.trpg

import kotlinx.serialization.Serializable

@Serializable enum class TrpgDiceMode { PLAYER, AUTOMATIC }
@Serializable enum class TrpgViewRole { PLAYER, GAME_MASTER }
@Serializable enum class TrpgResponseStyle { BALANCED, CONCISE, DESCRIPTIVE }
@Serializable enum class TrpgVisibility { PLAYER, GAME_MASTER }
@Serializable enum class TrpgFactStatus { PROPOSED, CONFIRMED, CORRECTED }
@Serializable enum class TrpgCheckStatus { PENDING, ROLLED, RESOLVED, CANCELLED }

@Serializable
data class TrpgPreferences(
    val setupComplete: Boolean = false,
    val diceMode: TrpgDiceMode = TrpgDiceMode.PLAYER,
    val viewRole: TrpgViewRole = TrpgViewRole.PLAYER,
    val responseStyle: TrpgResponseStyle = TrpgResponseStyle.BALANCED,
    val ruleEdition: String = "",
    val tone: String = "",
    val boundaries: String = "",
    val showRollDetails: Boolean = true,
    val pinnedResources: List<String> = emptyList(),
    val focusActorId: String? = null,
)

@Serializable
data class TrpgFact(
    val id: String,
    val text: String,
    val status: TrpgFactStatus = TrpgFactStatus.PROPOSED,
    val sourceMessageId: String? = null,
    val sourceNote: String = "",
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER,
)

@Serializable
data class TrpgCheckRequest(
    val id: String,
    val title: String,
    val expression: String = "1d20",
    val dc: Int? = null,
    val cocSkill: Int? = null,
    val bonusDice: Int = 0,
    val penaltyDice: Int = 0,
    val actorId: String? = null,
    val visibility: TrpgVisibility = TrpgVisibility.PLAYER,
)

/** Written only by the bound dice path. Import is historical data, not proof of a local invocation. */
@Serializable
data class TrpgCheckRoll(val id: String, val resultJson: String, val summary: String, val createdAt: String)

@Serializable
data class TrpgCheck(
    val request: TrpgCheckRequest,
    val status: TrpgCheckStatus = TrpgCheckStatus.PENDING,
    val roll: TrpgCheckRoll? = null,
    val resolution: String = "",
)

@Serializable
data class TrpgCampaign(
    val preferences: TrpgPreferences = TrpgPreferences(),
    val facts: List<TrpgFact> = emptyList(),
    val checks: List<TrpgCheck> = emptyList(),
    val party: TrpgParty = TrpgParty(),
    val story: TrpgStory = TrpgStory(),
    val atlas: TrpgAtlas = TrpgAtlas(),
    val origin: TrpgBranchOrigin? = null,
)

object TrpgCampaignValidation {
    fun validate(campaign: TrpgCampaign) {
        TrpgPartyValidation.validate(campaign.party)
        TrpgStoryValidation.validate(campaign.story)
        TrpgAtlasValidation.validate(campaign.atlas)
        campaign.origin?.let {
            require(it.sourceConversationId.isNotBlank() && it.sourceConversationId.length <= 256)
            require(it.checkpointId.isNotBlank() && it.checkpointId.length <= 200); require(it.sourceRevision >= 0)
        }
        with(campaign.preferences) {
            focusActorId?.let(::id)
            require(ruleEdition.length <= 128 && tone.length <= 512 && boundaries.length <= 2048)
            require(pinnedResources.size <= 8 && pinnedResources.distinct().size == pinnedResources.size)
            require(pinnedResources.all { it.length in 1..64 })
        }
        require(campaign.facts.size <= 64 && campaign.facts.map { it.id }.distinct().size == campaign.facts.size)
        campaign.facts.forEach(::validate)
        require(campaign.checks.size <= 64 && campaign.checks.map { it.request.id }.distinct().size == campaign.checks.size)
        campaign.checks.forEach { check ->
            validate(check.request)
            require(check.resolution.length <= 2048)
            require(if (check.status == TrpgCheckStatus.PENDING || check.status == TrpgCheckStatus.CANCELLED) check.roll == null else check.roll != null) {
                "Pending checks cannot have rolls, and rolled/resolved checks require one"
            }
            check.roll?.let { roll ->
                id(roll.id)
                require(roll.resultJson.length in 1..16384 && roll.summary.length in 1..1024 && roll.createdAt.length in 1..64)
            }
        }
    }

    fun validate(fact: TrpgFact) {
        id(fact.id)
        require(fact.text.isNotBlank() && fact.text.length <= 2048 && fact.sourceNote.length <= 512)
        fact.sourceMessageId?.let { require(it.isNotBlank() && it.length <= 256) }
    }

    fun validate(check: TrpgCheckRequest) {
        id(check.id)
        require(check.title.isNotBlank() && check.title.length <= 128)
        require(check.expression.isNotBlank() && check.expression.length <= 256)
        require(check.dc == null || check.dc in -1_000_000..1_000_000)
        require(check.cocSkill == null || check.cocSkill in 1..100)
        require(check.bonusDice in 0..2 && check.penaltyDice in 0..2)
        require(check.bonusDice == 0 || check.penaltyDice == 0)
        if (check.cocSkill == null) require(check.bonusDice == 0 && check.penaltyDice == 0)
        check.actorId?.let(::id)
    }

    fun id(value: String) { require(value.isNotBlank() && value.length <= 128) { "Invalid campaign entity id" } }
}
