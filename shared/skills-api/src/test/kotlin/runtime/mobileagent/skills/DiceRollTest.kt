// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DiceRollTest {
    /** Scripted range draws let the tests prove order and boundary behavior without faking outcomes. */
    private class ScriptedRandom(vararg values: Int) : Random() {
        private val queue = ArrayDeque(values.toList())
        var draws = 0
            private set

        override fun nextBits(bitCount: Int): Int = error("nextBits must not be used")

        override fun nextInt(from: Int, until: Int): Int {
            check(queue.isNotEmpty()) { "No scripted value left" }
            val value = queue.removeFirst()
            check(value in from until until) { "Scripted $value is outside [$from, $until)" }
            draws++
            return value
        }
    }

    private object NoDrawRandom : Random() {
        override fun nextBits(bitCount: Int): Int = error("Randomness must not be consumed")
        override fun nextInt(from: Int, until: Int): Int = error("Randomness must not be consumed")
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun broker(random: Random): ToolBroker = ToolBroker(
        emptySet(),
        ToolContext(search = { _, _, _ -> error("No I/O") }, readDocument = { _, _ -> error("No I/O") }),
        random = random,
    )

    @Test
    fun reportsEveryDieKeptDiceAndTotalInRollOrder() {
        val random = ScriptedRandom(1, 6, 3, 4)
        val result = DiceRoll.roll("4d6kh3+7", random)

        assertEquals("4d6kh3+7", result.expression)
        assertEquals(listOf(1, 6, 3, 4), result.dice.map { it.value })
        assertEquals(listOf(false, true, true, true), result.dice.map { it.kept })
        assertEquals(listOf(6, 3, 4), result.kept)
        assertEquals(20L, result.total)
        assertEquals(4, random.draws)

        val dice = result.terms[0] as DiceTermRoll
        assertEquals(KeepMode.HIGHEST, dice.keep)
        assertEquals(3, dice.keepCount)
        assertEquals(listOf(1), dice.dropped)
        assertEquals(13L, dice.value)
        assertEquals(7L, result.terms[1].value)
        assertEquals(2, result.terms.size)
    }

    @Test
    fun keepsLowestDiceAndAppliesNegativeDiceTerms() {
        val result = DiceRoll.roll("4d6kl2-1d4", ScriptedRandom(6, 1, 5, 2, 3))

        assertEquals(listOf(1, 2, 3), result.kept)
        assertEquals(0L, result.total)
        val negative = result.terms[1] as DiceTermRoll
        assertTrue(negative.negative)
        assertEquals(-3L, negative.value)
        assertEquals(listOf(3), negative.rolls)
    }

    @Test
    fun supportsTheRequiredExampleExpressions() {
        assertEquals(8, DiceRoll.roll("8d6", ScriptedRandom(*IntArray(8) { 1 })).dice.size)

        val advantage = DiceRoll.roll("2d20kh1+7", ScriptedRandom(13, 7))
        assertEquals(listOf(13), advantage.kept)
        assertEquals(20L, advantage.total)

        val fourKeepThree = DiceRoll.roll("4d6kh3", ScriptedRandom(1, 2, 3, 4))
        assertEquals(listOf(2, 3, 4), fourKeepThree.kept)
        assertEquals(9L, fourKeepThree.total)
    }

    @Test
    fun dcComparesOnlyTheTotalAndDoesNotInventNaturalTwentyRules() {
        val hit = DiceRoll.roll("1d20+5", ScriptedRandom(10), dc = 15)
        assertEquals(15L, hit.total)
        assertTrue(hit.success!!)

        val miss = DiceRoll.roll("1d20+5", ScriptedRandom(9), dc = 15)
        assertEquals(14L, miss.total)
        assertFalse(miss.success!!)

        val naturalTwenty = DiceRoll.roll("1d20", ScriptedRandom(20), dc = 21)
        assertEquals(20L, naturalTwenty.total)
        assertFalse(naturalTwenty.success!!)
    }

    @Test
    fun rejectsInvalidExpressionsBeforeConsumingRandomness() {
        val invalid = listOf(
            "", "   ", "1d6 2", "0d6", "1d1", "1d1000001", "101d6", "100d6+1d6",
            "2d6kh0", "2d6kh3", "2d6kl3", "2d6kh1kl1", "2d6*2", "2d6/2", "(1d6)",
            "d6", "1d", "1d6+", "+1d6", "-1d6", "1d6++2", "1d6+-2", "1d6.5",
            "1d6kh", "1d6kd1", "2d6KH1", "1d6+1000001", "1d6-1000001",
            "999999999999999999999d6",
        )
        invalid.forEach { expression ->
            assertThrows(IllegalArgumentException::class.java) { DiceRoll.roll(expression, NoDrawRandom) }
        }
    }

    @Test
    fun acceptsExactGlobalBoundariesAndRejectsTheNextValue() {
        val exactLength = "1d6" + "+0".repeat(126) + "0"
        assertEquals(256, exactLength.length)
        assertEquals(4L, DiceRoll.roll(exactLength, ScriptedRandom(4)).total)
        assertThrows(IllegalArgumentException::class.java) {
            DiceRoll.roll(exactLength + "0", NoDrawRandom)
        }

        val hundred = DiceRoll.roll("100d6", ScriptedRandom(*IntArray(100) { 1 }))
        assertEquals(100, hundred.dice.size)
        assertEquals(100L, hundred.total)

        assertEquals(1_000_000L, DiceRoll.roll("1d1000000", ScriptedRandom(1_000_000)).total)
        assertEquals(1_000_001L, DiceRoll.roll("1d6+1000000", ScriptedRandom(1)).total)
        assertEquals(-999_999L, DiceRoll.roll("1d6-1000000", ScriptedRandom(1)).total)
    }

    @Test
    fun cocBonusDieSelectsLowestRealPercentileAndDoesNotTurn00IntoZero() {
        val random = ScriptedRandom(0, 5, 0)
        val result = DiceRoll.cocRoll(60, bonusDice = 1, random = random)

        assertEquals(listOf(0, 5), result.tens)
        assertEquals(5, result.selectedTens)
        assertEquals(0, result.ones)
        assertEquals(50, result.roll)
        assertEquals(3, random.draws)
        assertEquals(CocOutcome.SUCCESS, result.outcome)
        assertTrue(result.success)
        assertFalse(result.hard)
    }

    @Test
    fun cocPenaltyDieSelectsHighestRealPercentile() {
        val result = DiceRoll.cocRoll(60, penaltyDice = 1, random = ScriptedRandom(0, 5, 0))

        assertEquals(listOf(0, 5), result.tens)
        assertEquals(0, result.selectedTens)
        assertEquals(100, result.roll)
        assertEquals(CocOutcome.FUMBLE, result.outcome)
        assertTrue(result.fumble)
        assertFalse(result.success)
    }

    @Test
    fun cocBonusAndPenaltyDiceCancelByNetValue() {
        val random = ScriptedRandom(4, 7)
        val result = DiceRoll.cocRoll(60, bonusDice = 2, penaltyDice = 2, random = random)

        assertEquals(0, result.netDice)
        assertEquals(listOf(4), result.tens)
        assertEquals(47, result.roll)
        assertEquals(2, random.draws)
    }

    @Test
    fun cocThresholdsUseFloorAndFumbleAt96OnlyBelowSkill50() {
        val skill49Hard = DiceRoll.cocRoll(49, random = ScriptedRandom(2, 4))
        assertEquals(24, skill49Hard.hardSuccessAt)
        assertEquals(9, skill49Hard.extremeSuccessAt)
        assertEquals(96, skill49Hard.fumbleAt)
        assertEquals(24, skill49Hard.roll)
        assertEquals(CocOutcome.HARD_SUCCESS, skill49Hard.outcome)
        assertTrue(skill49Hard.hard)
        assertFalse(skill49Hard.extreme)
        assertTrue(skill49Hard.success)

        val skill49Extreme = DiceRoll.cocRoll(49, random = ScriptedRandom(0, 9))
        assertEquals(9, skill49Extreme.roll)
        assertEquals(CocOutcome.EXTREME_SUCCESS, skill49Extreme.outcome)
        assertTrue(skill49Extreme.extreme)

        val skill49Success = DiceRoll.cocRoll(49, random = ScriptedRandom(2, 5))
        assertEquals(25, skill49Success.roll)
        assertEquals(CocOutcome.SUCCESS, skill49Success.outcome)
        assertFalse(skill49Success.hard)

        val skill49Fumble96 = DiceRoll.cocRoll(49, random = ScriptedRandom(9, 6))
        assertEquals(96, skill49Fumble96.roll)
        assertEquals(CocOutcome.FUMBLE, skill49Fumble96.outcome)
        assertTrue(skill49Fumble96.fumble)
        assertFalse(skill49Fumble96.success)

        val skill49Fumble100 = DiceRoll.cocRoll(49, random = ScriptedRandom(0, 0))
        assertEquals(100, skill49Fumble100.roll)
        assertEquals(CocOutcome.FUMBLE, skill49Fumble100.outcome)

        val skill49Failure95 = DiceRoll.cocRoll(49, random = ScriptedRandom(9, 5))
        assertEquals(95, skill49Failure95.roll)
        assertEquals(CocOutcome.FAILURE, skill49Failure95.outcome)
        assertFalse(skill49Failure95.fumble)

        val skill50Hard = DiceRoll.cocRoll(50, random = ScriptedRandom(2, 5))
        assertEquals(25, skill50Hard.hardSuccessAt)
        assertEquals(10, skill50Hard.extremeSuccessAt)
        assertEquals(100, skill50Hard.fumbleAt)
        assertEquals(25, skill50Hard.roll)
        assertEquals(CocOutcome.HARD_SUCCESS, skill50Hard.outcome)

        val skill50Failure96 = DiceRoll.cocRoll(50, random = ScriptedRandom(9, 6))
        assertEquals(96, skill50Failure96.roll)
        assertEquals(CocOutcome.FAILURE, skill50Failure96.outcome)
        assertFalse(skill50Failure96.fumble)

        val skill50Fumble100 = DiceRoll.cocRoll(50, random = ScriptedRandom(0, 0))
        assertEquals(100, skill50Fumble100.roll)
        assertEquals(CocOutcome.FUMBLE, skill50Fumble100.outcome)
        assertTrue(skill50Fumble100.fumble)
    }

    @Test
    fun cocCriticalOneWinsBeforeOtherLevels() {
        val result = DiceRoll.cocRoll(49, random = ScriptedRandom(0, 1))

        assertEquals(1, result.roll)
        assertEquals(CocOutcome.CRITICAL_SUCCESS, result.outcome)
        assertTrue(result.critical)
        assertTrue(result.success)
        assertFalse(result.fumble)
    }

    @Test
    fun rejectsAmbiguousOrInvalidRequestsBeforeDrawing() {
        val invalid = listOf(
            "{}",
            """{"expression":"1d6","skill":50}""",
            """{"skill":50,"dc":10}""",
            """{"expression":"1d6","bonusDice":1}""",
            """{"expression":"1d6","penaltyDice":1}""",
            """{"skill":0}""",
            """{"skill":101}""",
            """{"skill":50,"bonusDice":3}""",
            """{"skill":50,"penaltyDice":-1}""",
            """{"skill":50,"bonusDice":1.5}""",
            """{"skill":"50"}""",
            """{"expression":"1d6","unknown":1}""",
            """{"expression":7}""",
            """{"expression":"1d6","dc":1.5}""",
            """{"expression":"1d6","dc":"5"}""",
            """{"expression":"1d6","dc":true}""",
            """{"expression":"1d6","dc":null}""",
            """{"expression":"1d6","dc":1000000001}""",
            """{"expression":"1d6","dc":-1000000001}""",
            """{"skill":50,"bonusDice":0.0}""",
        )
        invalid.forEach { json ->
            assertThrows(IllegalArgumentException::class.java) {
                DiceRoll.execute(args(json), NoDrawRandom)
            }
        }
    }

    @Test
    fun formatIsPublicAndReportsAllDiceKeptDiceAndTotal() {
        val json = DiceRoll.format(DiceRoll.roll("4d6kh3+7", ScriptedRandom(1, 6, 3, 4)))

        assertEquals("dice", json.getValue("mode").jsonPrimitive.content)
        assertEquals("4d6kh3+7", json.getValue("expression").jsonPrimitive.content)
        assertEquals(20L, json.getValue("total").jsonPrimitive.long)
        assertEquals(
            listOf(1, 6, 3, 4),
            json.getValue("dice").jsonArray.map { it.jsonObject.getValue("value").jsonPrimitive.int },
        )
        assertEquals(
            listOf(6, 3, 4),
            json.getValue("kept").jsonArray.map { it.jsonPrimitive.int },
        )
        assertEquals(3, json.getValue("dice").jsonArray.count { it.jsonObject.getValue("kept").jsonPrimitive.boolean })
        assertNull(json["dc"])
        assertNull(json["success"])
        assertEquals(2, json.getValue("terms").jsonArray.size)
    }

    @Test
    fun toolIsRegisteredAsPureComputation() {
        val spec = BuiltinTools.byName.getValue("dice_roll")
        assertEquals("", spec.capability)
        assertFalse(spec.sideEffect)
        assertTrue(BuiltinTools.all.any { it.name == "dice_roll" })

        val schema = Json.parseToJsonElement(spec.parametersJson).jsonObject
        assertFalse(schema.getValue("additionalProperties").jsonPrimitive.boolean)
        assertNull(schema["required"])
        assertEquals(5, schema.getValue("properties").jsonObject.size)
    }

    @Test
    fun brokerReplayKeepsOriginalRollsAndRejectsChangedArguments() {
        val random = ScriptedRandom(1, 2, 3, 4, 5, 6)
        val tools = broker(random)
        val call = ToolCall("roll-1", "dice_roll", """{"expression":"3d6"}""")

        val first = tools.invoke(call) as ToolResult.Value
        val replay = tools.invoke(call) as ToolResult.Value
        assertEquals(first.json, replay.json)
        assertEquals(3, random.draws)
        assertTrue(tools.authorizeReplay(call))

        assertTrue(tools.invoke(call.copy(argumentsJson = """{"expression":"3d6","dc":10}""")) is ToolResult.Invalid)
        val second = tools.invoke(ToolCall("roll-2", "dice_roll", """{"expression":"3d6"}""")) as ToolResult.Value
        assertNotEquals(first.json, second.json)
        assertEquals(6, random.draws)
    }

    @Test
    fun brokerRoutesCocRollAndRejectsMixedArgumentsWithoutDrawing() {
        val invalid = listOf(
            """{"expression":"1d6","skill":50}""",
            """{"skill":50,"dc":10}""",
            """{"expression":"1d6","bonusDice":1}""",
            """{"skill":50,"bonusDice":3}""",
            """{"expression":"1d6","dc":1.5}""",
        )
        val rejecting = broker(NoDrawRandom)
        invalid.forEachIndexed { index, json ->
            assertTrue(rejecting.invoke(ToolCall("bad-$index", "dice_roll", json)) is ToolResult.Invalid, json)
        }

        val tools = broker(ScriptedRandom(0, 5, 0))
        val result = tools.invoke(ToolCall("coc", "dice_roll", """{"skill":60,"bonusDice":1}""")) as ToolResult.Value
        val json = Json.parseToJsonElement(result.json).jsonObject
        assertEquals("coc7", json.getValue("mode").jsonPrimitive.content)
        assertEquals(50, json.getValue("roll").jsonPrimitive.int)
        assertEquals("success", json.getValue("outcome").jsonPrimitive.content)
    }
}
