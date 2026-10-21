// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.data

import java.util.UUID
import kotlin.random.Random
import runtime.mobileagent.domain.trpg.*
import runtime.mobileagent.skills.CocRoll
import runtime.mobileagent.skills.DiceRoll
import runtime.mobileagent.skills.StandardRoll

internal object TrpgCheckSupport {
    fun validate(request: TrpgCheckRequest) {
        TrpgCampaignValidation.validate(request)
        if (request.cocSkill == null) DiceRoll.validateExpression(request.expression, request.dc)
    }

    fun roll(state: TrpgState, checkId: String, at: String, random: Random): TrpgState {
        val check = state.campaign.checks.singleOrNull { it.request.id == checkId } ?: error("Check is missing")
        require(check.status == TrpgCheckStatus.PENDING && check.roll == null) { "Check has already been rolled" }
        val request = check.request
        validate(request)
        val cocSkill = request.cocSkill
        val result = if (cocSkill != null) DiceRoll.cocRoll(cocSkill, request.bonusDice, request.penaltyDice, random)
            else DiceRoll.roll(request.expression, random, request.dc)
        val summary = when (result) {
            is StandardRoll -> "${request.title}: ${result.expression} = ${result.total}" + (result.dc?.let { " / DC $it: ${result.success}" } ?: "")
            is CocRoll -> "${request.title}: CoC7 ${result.roll}/${result.skill} (${result.outcome.wire})"
        }
        val evidence = TrpgCheckRoll(UUID.randomUUID().toString(), result.toJsonObject().toString(), summary, at)
        return state.copy(campaign = state.campaign.copy(checks = state.campaign.checks.map {
            if (it.request.id == checkId) it.copy(status = TrpgCheckStatus.ROLLED, roll = evidence) else it
        })).also(TrpgStateValidation::validate)
    }
}
