// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookContext

/** 宏替换：已实现的安全子集、外部宏保留、确定性。 */
class WorldBookMacrosTest {

    private val context = WorldBookContext(
        characterName = "Rin",
        userName = "Ann",
        characterDescription = "DESC",
        characterPersonality = "PERS",
        characterDepthPrompt = "DEPTH",
        personaDescription = "PERSONA",
        scenario = "SCEN",
        creatorNotes = "NOTES",
        exampleMessages = listOf("EX1", "EX2"),
    )

    private fun substitute(content: String, seed: Long = 7L): WorldBookMacros.Result =
        WorldBookMacros.substitute(content, context, DeterministicRandom(seed), seed)

    @Test
    fun `environment macros come from the context`() {
        val result = substitute(
            "{{user}}|{{char}}|{{description}}|{{personality}}|{{scenario}}|{{persona}}" +
                "|{{charDepthPrompt}}|{{creatorNotes}}|{{mesExamples}}|<USER>|<CHAR>",
        )
        assertEquals(
            "Ann|Rin|DESC|PERS|SCEN|PERSONA|DEPTH|NOTES|EX1\nEX2|Ann|Rin",
            result.text,
        )
        assertTrue(result.preserved.isEmpty())
    }

    @Test
    fun `utility macros are safe`() {
        val result = substitute("a{{newline}}b{{noop}}c{{trim}}d{{reverse:abc}}{{// hidden}}{{comment: x}}")
        assertEquals("a\nbcdcba", result.text)
    }

    @Test
    fun `hidden keys leave the prompt but stay available for recursion`() {
        val result = substitute("{{hidden_key:secret}}visible")
        assertEquals("visible", result.text)
        assertEquals(listOf("secret"), result.hiddenKeys)
    }

    @Test
    fun `external macros are preserved and reported`() {
        val result = substitute("{{time}} {{date}} {{getvar::x}} {{outlet::k}} {{input}} {{original}}")
        assertTrue(result.text.contains("{{time}}"))
        assertTrue(result.text.contains("{{getvar::x}}"))
        assertTrue(result.text.contains("{{outlet::k}}"))
        assertTrue(result.preserved.containsAll(listOf("time", "date", "getvar", "outlet", "input", "original")))
    }

    @Test
    fun `pick random and roll are deterministic per seed`() {
        val first = substitute("{{pick::a::b::c}}|{{random::a::b::c}}|{{roll:6}}|{{roll:d6}}|{{pick:a,b}}")
        val second = substitute("{{pick::a::b::c}}|{{random::a::b::c}}|{{roll:6}}|{{roll:d6}}|{{pick:a,b}}")
        assertEquals(first.text, second.text)
        val parts = first.text.split('|')
        assertTrue(parts[0] in listOf("a", "b", "c"))
        assertTrue(parts[1] in listOf("a", "b", "c"))
        assertTrue(parts[2].toInt() in 1..6)
        assertTrue(parts[3].toInt() in 1..6)
        assertTrue(parts[4] in listOf("a", "b"))
    }

    @Test
    fun `escaped commas stay inside list items`() {
        val parts = (0 until 8).map { seed ->
            substitute("{{random:a\\,b::c::d}}", seed.toLong()).text
        }
        assertTrue(parts.all { it in listOf("a,b", "c", "d") })
    }

    @Test
    fun `unknown macros are left untouched`() {
        val result = substitute("keep {{my_custom_macro}} as is")
        assertEquals("keep {{my_custom_macro}} as is", result.text)
        assertEquals(listOf("my_custom_macro"), result.preserved)
    }

    @Test
    fun `plain text without macros is returned unchanged`() {
        val result = substitute("just text")
        assertEquals("just text", result.text)
        assertTrue(result.preserved.isEmpty())
    }

    @Test
    fun `per entry output cap rejects oversized expansion with a reason`() {
        val result = WorldBookMacros.substitute(
            content = "{{description}}",
            context = context.copy(characterDescription = "0123456789"),
            random = DeterministicRandom(1L),
            seedSalt = 1L,
            maxOutputChars = 8,
        )
        assertEquals(WorldBookMacros.REJECT_OUTPUT_LIMIT, result.rejected)
        assertEquals("", result.text)
    }

    @Test
    fun `run level budget stops further expansions with a reason`() {
        val budget = MacroBudget(20)
        val context = context.copy(characterDescription = "x")
        val first = WorldBookMacros.substitute(
            "{{description}}", context, DeterministicRandom(1L), 1L, budget = budget,
        )
        assertNull(first.rejected)
        val second = WorldBookMacros.substitute(
            "{{description}}", context, DeterministicRandom(1L), 1L, budget = budget,
        )
        assertEquals(WorldBookMacros.REJECT_RUN_LIMIT, second.rejected)
        assertEquals("", second.text)
        assertTrue(budget.usedChars > 20)
    }

    @Test
    fun `input cap rejects oversized macro content`() {
        val oversized = "x".repeat(WorldBookMacros.MAX_INPUT_CHARS + 1) + "{{user}}"
        val result = substitute(oversized)
        assertEquals(WorldBookMacros.REJECT_INPUT_LIMIT, result.rejected)
    }

    @Test
    fun `hidden keys and preserved macros are bounded`() {
        val hidden = substitute("{{hidden_key:k}}".repeat(200))
        assertTrue(hidden.hiddenKeys.size <= 64)

        val many = (0 until 40).joinToString(" ") { "{{custom_macro_$it}}" }
        val preserved = substitute(many)
        assertTrue(preserved.preserved.size <= 32)
    }

    @Test
    fun `expanded context is not re-expanded in a second pass`() {
        // 单遍替换：上下文里再出现宏不会被二次放大，只作为残留宏被报告。
        val result = WorldBookMacros.substitute(
            content = "{{description}}",
            context = context.copy(characterDescription = "{{persona}}"),
            random = DeterministicRandom(1L),
            seedSalt = 1L,
        )
        assertEquals("{{persona}}", result.text)
        assertTrue(result.preserved.contains("persona"))
    }
}
