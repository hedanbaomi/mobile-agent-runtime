// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import runtime.mobileagent.domain.TextPart
import runtime.mobileagent.domain.ToolCallPart
import runtime.mobileagent.domain.trpg.TrpgProjection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ToolResultPart
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgStateValidation
import runtime.mobileagent.feature.chat.ChatTrpgToolCardUi
import runtime.mobileagent.skills.CocOutcome
import runtime.mobileagent.skills.DiceRoll
import runtime.mobileagent.skills.TrpgToolArguments

private val trpgSummaryJson = Json { ignoreUnknownKeys = true }

internal fun parseTrpgToolCard(message: Message): ChatTrpgToolCardUi? {
    if (message.role != MessageRole.TOOL) return null
    val receipt = message.parts.filterIsInstance<ToolResultPart>().singleOrNull() ?: return null
    if (receipt.status != "SUCCEEDED") return null
    return runCatching {
        // Tool identity is recorded by the Host; arbitrary tool output with the same
        // JSON shape must retain the ordinary tool presentation. Older receipts
        // without identity also fall back without changing their stored content.
        val name = TrpgToolArguments.parse(message.metadataJson)["toolName"]?.jsonPrimitive?.contentOrNull
        if (name !in setOf("dice_roll", "session_state_read", "session_state_update", "campaign_action")) return null
        val obj = TrpgToolArguments.parseResult(receipt.resultJson)
        when (name) {
            "dice_roll" -> when (obj["mode"]?.jsonPrimitive?.contentOrNull) {
                "dice" -> parseStandardDiceCard(obj)
                "coc7" -> parseCocCheckCard(obj)
                else -> null
            }
            else -> trpgSummaryJson.decodeFromJsonElement<TrpgSession>(obj).let {
                TrpgStateValidation.validate(it)
                if (it.conversationId == message.conversationId) ChatTrpgToolCardUi.CampaignState(it.copy(state = TrpgProjection.player(it.state))) else null
            }
        }
    }.getOrNull()
}

private fun parseStandardDiceCard(obj: JsonObject): ChatTrpgToolCardUi.StandardDice? {
    val expr = obj["expression"]?.jsonPrimitive?.content ?: return null
    val total = obj["total"]?.jsonPrimitive?.longOrNull ?: return null
    if (expr.isBlank() || expr.length > DiceRoll.MAX_EXPRESSION_CHARS ||
        total !in -DiceRoll.MAX_ABS_TOTAL..DiceRoll.MAX_ABS_TOTAL) return null
    val dc = obj["dc"]?.jsonPrimitive?.intOrNull
    val pass = obj["success"]?.jsonPrimitive?.booleanOrNull
    if (dc != null && (dc !in -DiceRoll.MAX_ABS_DC..DiceRoll.MAX_ABS_DC || pass != (total >= dc))) return null
    if (dc == null && pass != null) return null
    val rawDice = obj["dice"]?.jsonArray ?: return null
    if (rawDice.size > DiceRoll.MAX_TOTAL_DICE) return null
    val dice = rawDice.map { item ->
        val die = item.jsonObject
        val value = die["value"]?.jsonPrimitive?.intOrNull ?: return null
        val sides = die["sides"]?.jsonPrimitive?.intOrNull ?: return null
        if (sides !in DiceRoll.MIN_SIDES..DiceRoll.MAX_SIDES || value !in 1..sides) return null
        val kept = die["kept"]?.jsonPrimitive?.booleanOrNull ?: return null
        value to kept
    }
    return ChatTrpgToolCardUi.StandardDice(expr, total, dc, pass, dice)
}

private fun parseCocCheckCard(obj: JsonObject): ChatTrpgToolCardUi.CocCheck? {
    val skill = obj["skill"]?.jsonPrimitive?.intOrNull ?: return null
    val roll = obj["roll"]?.jsonPrimitive?.intOrNull ?: return null
    if (skill !in DiceRoll.MIN_SKILL..DiceRoll.MAX_SKILL || roll !in 1..100) return null
    val hardAt = obj["hardSuccessAt"]?.jsonPrimitive?.intOrNull ?: return null
    val extremeAt = obj["extremeSuccessAt"]?.jsonPrimitive?.intOrNull ?: return null
    val fumbleAt = obj["fumbleAt"]?.jsonPrimitive?.intOrNull ?: return null
    if (hardAt != skill / 2 || extremeAt != skill / 5 || fumbleAt != if (skill < 50) 96 else 100) return null
    val outcome = obj["outcome"]?.jsonPrimitive?.content ?: return null
    val expected = when {
        roll == 1 -> CocOutcome.CRITICAL_SUCCESS
        roll >= fumbleAt -> CocOutcome.FUMBLE
        roll <= extremeAt -> CocOutcome.EXTREME_SUCCESS
        roll <= hardAt -> CocOutcome.HARD_SUCCESS
        roll <= skill -> CocOutcome.SUCCESS
        else -> CocOutcome.FAILURE
    }
    if (outcome != expected.wire) return null
    val tens = obj["tens"]?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: return null } ?: return null
    val ones = obj["ones"]?.jsonPrimitive?.intOrNull ?: return null
    if (tens.size !in 1..3 || tens.any { it !in 0..9 } || ones !in 0..9) return null
    if (tens.none { (if (it == 0 && ones == 0) 100 else it * 10 + ones) == roll }) return null
    return ChatTrpgToolCardUi.CocCheck(skill, roll, outcome, hardAt, extremeAt, fumbleAt, tens, ones)
}

internal fun trpgToolEventSummary(card: ChatTrpgToolCardUi): String {
    return when (card) {
        is ChatTrpgToolCardUi.StandardDice -> {
            val dcSuffix = if (card.dc != null && card.success != null) {
                " (DC ${card.dc} · ${if (card.success == true) "成功" else "失败"})"
            } else {
                ""
            }
            "🎲 掷骰 ${card.expression} = ${card.total}$dcSuffix"
        }
        is ChatTrpgToolCardUi.CocCheck -> "🐙 CoC7 检定 ${card.roll}/${card.skill} · ${card.outcome}"
        is ChatTrpgToolCardUi.CampaignState -> {
            val session = card.session
            val resSummary = session.state.resources.take(4).joinToString(" ") {
                "${it.name}:${it.current}${it.maximum?.let { m -> "/$m" }.orEmpty()}"
            }
            listOfNotNull(
                "📜 跑团状态 #${session.revision}",
                session.state.scene.takeIf { it.isNotBlank() },
                resSummary.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }
    }
}

/** Raw state tool receipts may contain GM secrets, even when malformed or no longer parseable.
 * Keep the durable message untouched and make every ordinary chat/source/recap surface fail closed.
 */
internal fun trpgMessageForDisplay(message: Message): Message {
    val stateTools = setOf("session_state_read", "session_state_update", "campaign_action")
    val toolName = runCatching { TrpgToolArguments.parse(message.metadataJson)["toolName"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    if (message.role == MessageRole.TOOL && toolName in stateTools) {
        val receipt = message.parts.filterIsInstance<ToolResultPart>().singleOrNull()
        val projected = receipt?.takeIf { it.status == "SUCCEEDED" }?.let {
            runCatching {
                val session = trpgSummaryJson.decodeFromJsonElement<TrpgSession>(TrpgToolArguments.parseResult(it.resultJson))
                TrpgStateValidation.validate(session)
                require(session.conversationId == message.conversationId)
                trpgSummaryJson.encodeToString(session.copy(state = TrpgProjection.player(session.state)))
            }.getOrNull()
        }
        val safeText = projected ?: "跑团状态操作已记录；请在团务面板查看可见资料。 / Campaign operation recorded; open the campaign panel."
        return message.copy(text = safeText, parts = receipt?.let {
            listOf(it.copy(resultJson = safeText))
        } ?: listOf(TextPart(safeText)))
    }
    // Arguments are historical data, but can also contain a hidden fact before the result exists.
    if (message.parts.filterIsInstance<ToolCallPart>().any { it.name in stateTools }) {
        return message.copy(parts = message.parts.map { part ->
            if (part is ToolCallPart && part.name in stateTools) part.copy(argumentsJson = "{}") else part
        })
    }
    return message
}
