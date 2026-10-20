// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.domain.ToolResultPart
import runtime.mobileagent.feature.chat.ChatTrpgToolCardUi
import runtime.mobileagent.skills.DiceRoll

class TrpgFrontendProjectionTest {
    @Test fun onlySuccessfulNamedDiceResultsBecomeCheckCards() {
        assertTrue(parseTrpgToolCard(tool()) is ChatTrpgToolCardUi.StandardDice)
        for (status in listOf("FAILED", "UNKNOWN_OUTCOME", "COMPLETED_WITHHELD")) {
            assertNull(parseTrpgToolCard(tool(status = status)), status)
        }
    }

    @Test fun unrelatedToolCannotImpersonateADiceCheckWithItsJsonShape() {
        assertNull(parseTrpgToolCard(tool(name = "python_execute")))
        assertNull(parseTrpgToolCard(tool().copy(metadataJson = "{}")))
        assertNull(parseTrpgToolCard(tool().copy(parts = emptyList())))
        assertNull(parseTrpgToolCard(tool().copy(role = MessageRole.USER)))
    }

    @Test fun typedReceiptTakesPrecedenceOverDisplayText() {
        val card = parseTrpgToolCard(tool().copy(text = DICE.replace("\"total\":3", "\"total\":999")))
        assertEquals(3L, (card as ChatTrpgToolCardUi.StandardDice).total)
    }

    @Test fun malformedCocOutcomeCannotBecomeASuccessBadge() {
        val payload = """{"mode":"coc7","skill":60,"roll":51,"outcome":"unknown_success","hardSuccessAt":30,"extremeSuccessAt":12,"fumbleAt":100,"tens":[5],"ones":1}"""
        assertNull(parseTrpgToolCard(tool(payload = payload)))
    }

    @Test fun aStateReceiptCannotClaimAnotherConversation() {
        val payload = """{"conversationId":"other-chat","revision":1,"state":{}}"""
        assertNull(parseTrpgToolCard(tool(payload = payload, name = "session_state_update")))
    }

    @Test fun oversizedDisplayPayloadFallsBackToOrdinaryToolDetails() {
        val payload = DICE.dropLast(1) + ",\"extra\":\"" + "x".repeat(256 * 1024) + "\"}"
        assertNull(parseTrpgToolCard(tool(payload = payload)))
    }

    @Test fun fullHundredDiceReceiptAndBothCocSkillBoundariesRemainDisplayable() {
        val card = parseTrpgToolCard(tool(payload = DiceRoll.formatString(DiceRoll.roll("100d6"))))
        assertEquals(100, (card as ChatTrpgToolCardUi.StandardDice).dice.size)
        for (skill in listOf(1, 49, 50, 100)) {
            for ((bonus, penalty) in listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2)) {
                assertTrue(parseTrpgToolCard(tool(payload = DiceRoll.formatString(DiceRoll.cocRoll(skill, bonus, penalty))))
                    is ChatTrpgToolCardUi.CocCheck)
            }
        }
    }

    @Test fun deepOrDuplicateReceiptsFallBackWithoutCrashingTheChatProjection() {
        val deep = DICE.dropLast(1) + ",\"extra\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}"
        val duplicate = DICE.dropLast(1) + ",\"total\":4}"
        for (payload in listOf(deep, duplicate)) {
            assertNull(projectChatMessage(tool(payload = payload), false).trpgToolCard)
        }
    }

    private fun tool(payload: String = DICE, name: String = "dice_roll", status: String = "SUCCEEDED") = Message(
        "result", "chat", role = MessageRole.TOOL, text = payload, status = "COMPLETE",
        createdAt = "2026-10-21T00:00:00Z", parts = listOf(ToolResultPart("call", payload, status)),
        metadataJson = """{"toolName":"$name"}""",
    )

    companion object {
        private const val DICE = """{"mode":"dice","expression":"1d6","total":3,"dice":[{"term":0,"sides":6,"value":3,"kept":true}],"kept":[3]}"""
    }
}
