// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs
import kotlin.random.Random

/** How many of a dice term's dice are kept. */
enum class KeepMode(val wire: String) {
    HIGHEST("kh"),
    LOWEST("kl"),
}

/** A machine-readable Call of Cthulhu 7e result level. */
enum class CocOutcome(val wire: String) {
    CRITICAL_SUCCESS("critical_success"),
    EXTREME_SUCCESS("extreme_success"),
    HARD_SUCCESS("hard_success"),
    SUCCESS("success"),
    FAILURE("failure"),
    FUMBLE("fumble"),
}

sealed interface DiceRollResult {
    fun toJsonObject(): JsonObject
}

/** One physical die, in the order it was rolled. */
data class DieRoll(
    val termIndex: Int,
    val sides: Int,
    val value: Int,
    val kept: Boolean,
)

sealed interface DiceTermResult {
    val termIndex: Int
    val value: Long
    fun toJsonObject(): JsonObject
}

data class DiceTermRoll(
    override val termIndex: Int,
    val count: Int,
    val sides: Int,
    val keep: KeepMode?,
    val keepCount: Int?,
    val negative: Boolean,
    val rolls: List<Int>,
    val kept: List<Int>,
    val dropped: List<Int>,
    override val value: Long,
) : DiceTermResult {
    override fun toJsonObject(): JsonObject = buildJsonObject {
        put("index", termIndex)
        put("kind", "dice")
        put("count", count)
        put("sides", sides)
        keep?.let { put("keep", it.wire) }
        keepCount?.let { put("keepCount", it) }
        put("negative", negative)
        putJsonArray("rolls") { rolls.forEach { add(it) } }
        putJsonArray("kept") { kept.forEach { add(it) } }
        putJsonArray("dropped") { dropped.forEach { add(it) } }
        put("value", value)
    }
}

data class ConstantTermRoll(
    override val termIndex: Int,
    override val value: Long,
) : DiceTermResult {
    override fun toJsonObject(): JsonObject = buildJsonObject {
        put("index", termIndex)
        put("kind", "constant")
        put("value", value)
    }
}

data class StandardRoll(
    val expression: String,
    val terms: List<DiceTermResult>,
    val dice: List<DieRoll>,
    val kept: List<Int>,
    val total: Long,
    val dc: Int? = null,
    val success: Boolean? = null,
) : DiceRollResult {
    override fun toJsonObject(): JsonObject = buildJsonObject {
        put("mode", "dice")
        put("expression", expression)
        putJsonArray("terms") { terms.forEach { add(it.toJsonObject()) } }
        putJsonArray("dice") {
            dice.forEach { die ->
                addJsonObject {
                    put("term", die.termIndex)
                    put("sides", die.sides)
                    put("value", die.value)
                    put("kept", die.kept)
                }
            }
        }
        putJsonArray("kept") { kept.forEach { add(it) } }
        put("total", total)
        dc?.let { put("dc", it) }
        success?.let { put("success", it) }
    }
}

data class CocRoll(
    val skill: Int,
    val roll: Int,
    val tens: List<Int>,
    val selectedTens: Int,
    val ones: Int,
    val bonusDice: Int,
    val penaltyDice: Int,
    val netDice: Int,
    val hardSuccessAt: Int,
    val extremeSuccessAt: Int,
    val fumbleAt: Int,
    val outcome: CocOutcome,
    val success: Boolean,
    val hard: Boolean,
    val extreme: Boolean,
    val critical: Boolean,
    val fumble: Boolean,
) : DiceRollResult {
    override fun toJsonObject(): JsonObject = buildJsonObject {
        put("mode", "coc7")
        put("skill", skill)
        put("roll", roll)
        putJsonArray("tens") { tens.forEach { add(it) } }
        put("selectedTens", selectedTens)
        put("ones", ones)
        put("bonusDice", bonusDice)
        put("penaltyDice", penaltyDice)
        put("netDice", netDice)
        put("hardSuccessAt", hardSuccessAt)
        put("extremeSuccessAt", extremeSuccessAt)
        put("fumbleAt", fumbleAt)
        put("outcome", outcome.wire)
        put("success", success)
        put("hard", hard)
        put("extreme", extreme)
        put("critical", critical)
        put("fumble", fumble)
    }
}

/**
 * Deterministic, allocation-bounded TRPG dice resolution.
 *
 * The expression grammar is deliberately small: `NdM`, optional `khK`/`klK`,
 * and signed constant or dice terms joined by `+`/`-`. Parentheses,
 * multiplication, division, nesting and repeated keep modifiers are rejected.
 * The whole expression and every global limit are validated before the first
 * random draw, so invalid input cannot consume randomness.
 *
 * The JSON format returned by [execute] and [format] is public and stable for
 * host/UI consumers. The app layer decides which agents see the `dice_roll`
 * tool; this module only registers the pure computation.
 */
object DiceRoll {
    const val MAX_EXPRESSION_CHARS = 256
    const val MAX_TOTAL_DICE = 100
    const val MIN_SIDES = 2
    const val MAX_SIDES = 1_000_000
    const val MAX_ABS_CONSTANT = 1_000_000L
    const val MAX_ABS_TOTAL = 1_000_000_000L
    const val MIN_SKILL = 1
    const val MAX_SKILL = 100
    const val MAX_BONUS_PENALTY_DICE = 2
    const val MAX_ABS_DC = 1_000_000_000

    private val EXPRESSION_PATTERN =
        Regex("""\d+(?:d\d+(?:(?:kh|kl)\d+)?)?(?:[+-]\d+(?:d\d+(?:(?:kh|kl)\d+)?)?)*""")

    private sealed interface ParsedTerm {
        data class Constant(val value: Long) : ParsedTerm
        data class Dice(
            val count: Int,
            val sides: Int,
            val keep: KeepMode?,
            val keepCount: Int?,
            val negative: Boolean,
        ) : ParsedTerm
    }

    /** Check a proposed request without drawing any random values. */
    fun validateExpression(expression: String, dc: Int? = null) {
        require(dc == null || dc in -MAX_ABS_DC..MAX_ABS_DC)
        parseExpression(expression)
    }

    /** Roll a validated dice expression. A present [dc] means `total >= dc` succeeds. */
    fun roll(expression: String, random: Random = Random.Default, dc: Int? = null): StandardRoll {
        require(dc == null || dc in -MAX_ABS_DC..MAX_ABS_DC) { "dc is outside the supported range" }
        val parsed = parseExpression(expression)
        var total = 0L
        val termResults = ArrayList<DiceTermResult>(parsed.size)
        val dice = ArrayList<DieRoll>()
        val kept = ArrayList<Int>()

        parsed.forEachIndexed { index, term ->
            when (term) {
                is ParsedTerm.Constant -> {
                    total = checkedTotal(total + term.value)
                    termResults += ConstantTermRoll(index, term.value)
                }
                is ParsedTerm.Dice -> {
                    val rolls = ArrayList<Int>(term.count)
                    repeat(term.count) { rolls += random.nextInt(1, term.sides + 1) }
                    val keptIndices = keptIndices(rolls, term.keep, term.keepCount)
                    val termKept = ArrayList<Int>()
                    val termDropped = ArrayList<Int>()
                    rolls.forEachIndexed { dieIndex, value ->
                        val isKept = dieIndex in keptIndices
                        dice += DieRoll(index, term.sides, value, isKept)
                        if (isKept) {
                            termKept += value
                            kept += value
                        } else {
                            termDropped += value
                        }
                    }
                    val subtotal = termKept.sumOf { it.toLong() }
                    val value = if (term.negative) -subtotal else subtotal
                    total = checkedTotal(total + value)
                    termResults += DiceTermRoll(
                        termIndex = index,
                        count = term.count,
                        sides = term.sides,
                        keep = term.keep,
                        keepCount = term.keepCount,
                        negative = term.negative,
                        rolls = rolls.toList(),
                        kept = termKept.toList(),
                        dropped = termDropped.toList(),
                        value = value,
                    )
                }
            }
        }

        return StandardRoll(
            expression = expression.trim(),
            terms = termResults,
            dice = dice,
            kept = kept.toList(),
            total = total,
            dc = dc,
            success = dc?.let { total >= it },
        )
    }

    /**
     * Call of Cthulhu 7e percentile check.
     *
     * One ones die is shared by all tens dice. A bonus die selects the lowest
     * real percentile and a penalty die selects the highest; `00` with a `0`
     * ones die is 100, never 0. Thresholds are `floor(skill / 2)` and
     * `floor(skill / 5)`. A roll of 1 is a critical success. A skill below 50
     * fumbles on 96-100; a skill of 50 or more fumbles only on 100.
     */
    fun cocRoll(
        skill: Int,
        bonusDice: Int = 0,
        penaltyDice: Int = 0,
        random: Random = Random.Default,
    ): CocRoll {
        require(skill in MIN_SKILL..MAX_SKILL) { "skill must be between $MIN_SKILL and $MAX_SKILL" }
        require(bonusDice in 0..MAX_BONUS_PENALTY_DICE) { "bonusDice must be between 0 and $MAX_BONUS_PENALTY_DICE" }
        require(penaltyDice in 0..MAX_BONUS_PENALTY_DICE) { "penaltyDice must be between 0 and $MAX_BONUS_PENALTY_DICE" }

        val net = bonusDice - penaltyDice
        val tensCount = 1 + abs(net)
        val tens = ArrayList<Int>(tensCount)
        repeat(tensCount) { tens += random.nextInt(0, 10) }
        val ones = random.nextInt(0, 10)
        val percentiles = tens.map { tensValue ->
            if (tensValue == 0 && ones == 0) 100 else tensValue * 10 + ones
        }
        val selectedIndex = when {
            net > 0 -> percentiles.indices.minByOrNull { percentiles[it] } ?: 0
            net < 0 -> percentiles.indices.maxByOrNull { percentiles[it] } ?: 0
            else -> 0
        }
        val roll = percentiles[selectedIndex]
        val hardAt = skill / 2
        val extremeAt = skill / 5
        val fumbleAt = if (skill < 50) 96 else 100
        val critical = roll == 1
        val fumble = !critical && roll >= fumbleAt
        val hard = roll <= hardAt
        val extreme = roll <= extremeAt
        val success = roll <= skill && !fumble
        val outcome = when {
            critical -> CocOutcome.CRITICAL_SUCCESS
            fumble -> CocOutcome.FUMBLE
            extreme -> CocOutcome.EXTREME_SUCCESS
            hard -> CocOutcome.HARD_SUCCESS
            success -> CocOutcome.SUCCESS
            else -> CocOutcome.FAILURE
        }
        return CocRoll(
            skill = skill,
            roll = roll,
            tens = tens.toList(),
            selectedTens = tens[selectedIndex],
            ones = ones,
            bonusDice = bonusDice,
            penaltyDice = penaltyDice,
            netDice = net,
            hardSuccessAt = hardAt,
            extremeSuccessAt = extremeAt,
            fumbleAt = fumbleAt,
            outcome = outcome,
            success = success,
            hard = hard,
            extreme = extreme,
            critical = critical,
            fumble = fumble,
        )
    }

    /**
     * Execute one tool-style request.
     *
     * Dice mode: `expression` plus optional `dc`.
     * CoC mode: `skill` plus optional `bonusDice`/`penaltyDice`; `expression`
     * and `dc` are forbidden. All type and cross-parameter validation happens
     * before any random draw.
     */
    fun execute(args: JsonObject, random: Random = Random.Default): JsonObject {
        val allowed = setOf("expression", "dc", "skill", "bonusDice", "penaltyDice")
        val unknown = args.keys - allowed
        require(unknown.isEmpty()) { "Unknown parameter: ${unknown.first()}" }

        if ("skill" in args) {
            require("expression" !in args) { "CoC skill checks cannot mix expression" }
            require("dc" !in args) { "CoC skill checks do not use dc" }
            val skill = args.requiredInt("skill")
            val bonusDice = args.optionalInt("bonusDice") ?: 0
            val penaltyDice = args.optionalInt("penaltyDice") ?: 0
            return cocRoll(skill, bonusDice, penaltyDice, random).toJsonObject()
        }

        require("bonusDice" !in args) { "bonusDice requires a CoC skill check" }
        require("penaltyDice" !in args) { "penaltyDice requires a CoC skill check" }
        val expression = args.requiredString("expression")
        val dc = args.optionalInt("dc")
        return roll(expression, random, dc).toJsonObject()
    }

    /** Public JSON projection used by the tool and by host-side manual rolls. */
    fun format(result: DiceRollResult): JsonObject = result.toJsonObject()

    fun formatString(result: DiceRollResult): String = format(result).toString()

    private fun JsonObject.requiredInt(key: String): Int =
        optionalInt(key) ?: throw IllegalArgumentException("$key must be an integer")

    private fun JsonObject.optionalInt(key: String): Int? {
        val element = this[key] ?: return null
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be an integer")
        if (primitive.isString) throw IllegalArgumentException("$key must be an integer")
        return primitive.intOrNull ?: throw IllegalArgumentException("$key must be an integer")
    }

    private fun JsonObject.requiredString(key: String): String {
        val element = this[key] ?: throw IllegalArgumentException("$key is required")
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be a string")
        if (!primitive.isString) throw IllegalArgumentException("$key must be a string")
        return primitive.content
    }

    private fun parseExpression(expression: String): List<ParsedTerm> {
        require(expression.length <= MAX_EXPRESSION_CHARS) {
            "Expression exceeds $MAX_EXPRESSION_CHARS characters"
        }
        val text = expression.trim()
        require(text.isNotEmpty()) { "Expression is empty" }
        require(text.none { it.isWhitespace() }) { "Expression must not contain whitespace" }
        require(EXPRESSION_PATTERN.matches(text)) { "Expression is not a supported dice formula" }

        val terms = ArrayList<ParsedTerm>()
        var index = 0
        var totalDice = 0L
        var maxTotal = 0L
        var minTotal = 0L

        while (index < text.length) {
            var negative = false
            if (index > 0) {
                negative = text[index] == '-'
                index++
            }

            val numberStart = index
            while (index < text.length && text[index].isDigit()) index++
            val number = text.substring(numberStart, index).toIntOrNull()
                ?: throw IllegalArgumentException("Dice count or constant is out of range")

            if (index < text.length && text[index] == 'd') {
                index++
                val sidesStart = index
                while (index < text.length && text[index].isDigit()) index++
                val sides = text.substring(sidesStart, index).toIntOrNull()
                    ?: throw IllegalArgumentException("Die size is out of range")

                var keep: KeepMode? = null
                var keepCount: Int? = null
                if (index < text.length && text[index] == 'k') {
                    keep = if (text[index + 1] == 'h') KeepMode.HIGHEST else KeepMode.LOWEST
                    index += 2
                    val keepStart = index
                    while (index < text.length && text[index].isDigit()) index++
                    keepCount = text.substring(keepStart, index).toIntOrNull()
                        ?: throw IllegalArgumentException("Keep count is out of range")
                }

                require(number >= 1) { "Dice count must be at least 1" }
                require(sides in MIN_SIDES..MAX_SIDES) {
                    "Die size must be between $MIN_SIDES and $MAX_SIDES"
                }
                require(keepCount == null || keepCount in 1..number) {
                    "Keep count must be between 1 and the number of dice"
                }
                totalDice += number
                require(totalDice <= MAX_TOTAL_DICE) { "At most $MAX_TOTAL_DICE dice may be rolled" }

                terms += ParsedTerm.Dice(number, sides, keep, keepCount, negative)
                val kept = (keepCount ?: number).toLong()
                if (negative) {
                    maxTotal -= kept
                    minTotal -= kept * sides
                } else {
                    maxTotal += kept * sides
                    minTotal += kept
                }
            } else {
                val value = if (negative) -number.toLong() else number.toLong()
                require(abs(value) <= MAX_ABS_CONSTANT) {
                    "Constant must be between -$MAX_ABS_CONSTANT and $MAX_ABS_CONSTANT"
                }
                terms += ParsedTerm.Constant(value)
                maxTotal += value
                minTotal += value
            }
        }

        require(maxTotal <= MAX_ABS_TOTAL && minTotal >= -MAX_ABS_TOTAL) {
            "Possible total is outside the supported range"
        }
        return terms
    }

    private fun keptIndices(rolls: List<Int>, keep: KeepMode?, keepCount: Int?): Set<Int> {
        if (keep == null) return rolls.indices.toSet()
        val count = keepCount ?: error("keep count is missing")
        val order = rolls.indices.sortedWith(compareBy<Int> { rolls[it] }.thenBy { it })
        return if (keep == KeepMode.HIGHEST) order.takeLast(count).toSet() else order.take(count).toSet()
    }

    private fun checkedTotal(value: Long): Long {
        require(value in -MAX_ABS_TOTAL..MAX_ABS_TOTAL) { "Dice total is outside the supported range" }
        return value
    }
}
