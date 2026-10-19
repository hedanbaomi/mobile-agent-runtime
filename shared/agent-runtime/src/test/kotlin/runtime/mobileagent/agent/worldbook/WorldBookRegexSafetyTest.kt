// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus

/**
 * 正则护栏（RE2/J 线性引擎）：
 *  - 不支持的语法（回溯引用/环视/`u y d v` 标志）**显式拒绝**并给出稳定原因码，不静默回退；
 *  - 线性引擎下经典“回溯爆炸”模式不再失控（可断言正确匹配 + 时限）；
 *  - 步数预算对小预算确定性中断（负向），超大输入/超长模式/超大程序被拒；
 *  - 正常 Unicode 与 ASCII 整词边界行为。
 */
class WorldBookRegexSafetyTest {

    private fun elapsedMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun run(keys: List<String>, message: String, budget: Long = 2_000_000) =
        WorldBookFixture.evaluate(
            listOf(WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("r", keys = keys)))),
            WorldBookFixture.messages(message),
            settings = WorldBookFixture.settings(regexStepBudget = budget),
        )

    @Test
    fun `unsupported syntax is detected before compiling`() {
        assertEquals("regex.unsupported-backreference", WorldBookRegex.unsupportedSyntax("(a)\\1"))
        assertEquals("regex.unsupported-backreference", WorldBookRegex.unsupportedSyntax("(?<x>a)\\k<x>"))
        assertEquals("regex.unsupported-lookaround", WorldBookRegex.unsupportedSyntax("foo(?=bar)"))
        assertEquals("regex.unsupported-lookaround", WorldBookRegex.unsupportedSyntax("(?<!x)y"))
        assertEquals("regex.unsupported-group", WorldBookRegex.unsupportedSyntax("(?>a)"))
        assertNull(WorldBookRegex.unsupportedSyntax("(a+)+$"))
        assertNull(WorldBookRegex.unsupportedSyntax("[\\1]"))
        assertNull(WorldBookRegex.unsupportedSyntax("a{2,4}"))
    }

    @Test
    fun `backreference keys are rejected explicitly and never match`() {
        val result = run(listOf("/^(a)\\1$/"), "aa")
        assertTrue(result.activated.isEmpty())
        assertEquals("primary-no-match", result.decisionFor("r").reason)
        assertTrue(result.warnings.any { it.contains("regex.unsupported-backreference") })
    }

    @Test
    fun `lookaround keys are rejected explicitly and never match`() {
        val forward = run(listOf("/foo(?=bar)/"), "foobar")
        assertTrue(forward.activated.isEmpty())
        assertTrue(forward.warnings.any { it.contains("regex.unsupported-lookaround") })

        val behind = run(listOf("/(?<=x)y/"), "xy")
        assertTrue(behind.activated.isEmpty())
        assertTrue(behind.warnings.any { it.contains("regex.unsupported-lookaround") })
    }

    @Test
    fun `unsupported flags are rejected while i and s are supported`() {
        for (flag in listOf('u', 'y', 'd', 'v')) {
            val result = run(listOf("/abc/$flag"), "abc")
            assertTrue(result.activated.isEmpty(), "flag $flag must not be accepted")
            assertTrue(result.warnings.any { it.contains("regex.unsupported-flag-$flag") })
        }

        val insensitive = run(listOf("/ABC/i"), "abc")
        assertEquals(listOf("book:r"), insensitive.activatedUids())

        val dotAll = run(listOf("/a.c/s"), "a\nc")
        assertEquals(listOf("book:r"), dotAll.activatedUids())
    }

    @Test
    fun `nested quantifiers are linear and still match correctly`() {
        val match = run(listOf("/(a+)+$/"), "a".repeat(64))
        assertEquals(listOf("book:r"), match.activatedUids())
        assertTrue(match.warnings.isEmpty())

        val noMatch = run(listOf("/(a+)+$/"), "a".repeat(64) + "!")
        assertTrue(noMatch.activated.isEmpty())
        assertEquals("primary-no-match", noMatch.decisionFor("r").reason)
    }

    @Test
    fun `classic backtracking sample does not hang the scan`() {
        lateinit var result: runtime.mobileagent.domain.worldbook.WorldBookEvaluation
        val elapsed = elapsedMillis {
            result = run(listOf("/(a|aa)+$/"), "a".repeat(64) + "!")
        }
        assertTrue(result.activated.isEmpty())
        assertTrue(elapsed < 2_000, "elapsed=${elapsed}ms")
    }

    @Test
    fun `unanchored literal scanning stays bounded on large input`() {
        lateinit var noMatch: runtime.mobileagent.domain.worldbook.WorldBookEvaluation
        val elapsed = elapsedMillis {
            noMatch = run(listOf("/a*a*b/"), "a".repeat(20_000))
        }
        assertTrue(noMatch.activated.isEmpty())
        assertTrue(elapsed < 3_000, "elapsed=${elapsed}ms")

        val match = run(listOf("/a*a*b/"), "a".repeat(2_000) + "b")
        assertEquals(listOf("book:r"), match.activatedUids())
    }

    @Test
    fun `step budget deterministically interrupts a linear scan`() {
        val result = run(listOf("/z/"), "a".repeat(10_000), budget = 500)
        assertTrue(result.activated.isEmpty())
        assertTrue(
            result.warnings.any { it.contains("regex.step-budget-exhausted") },
            "warnings=${result.warnings}",
        )
    }

    @Test
    fun `oversized regex pattern is rejected`() {
        val pattern = "/" + "a".repeat(600) + "/"
        val result = run(listOf(pattern), "a".repeat(600))
        assertTrue(result.activated.isEmpty())
        assertTrue(result.warnings.any { it.contains("regex.pattern-too-long") })
    }

    @Test
    fun `oversized regex program is rejected with a stable reason`() {
        lateinit var result: runtime.mobileagent.domain.worldbook.WorldBookEvaluation
        val elapsed = elapsedMillis {
            result = run(listOf("/(?:(?:a){1,500}b){1,500}/"), "a".repeat(64))
        }
        assertTrue(result.activated.isEmpty())
        assertTrue(
            result.warnings.any {
                it.contains("regex.program-too-large") || it.contains("regex.invalid") ||
                    it.contains("regex.pattern-too-long")
            },
            "warnings=${result.warnings}",
        )
        assertTrue(elapsed < 3_000, "elapsed=${elapsed}ms")
    }

    @Test
    fun `unicode patterns work normally`() {
        val cjk = run(listOf("/猫+/"), "小猫猫")
        assertEquals(listOf("book:r"), cjk.activatedUids())

        val letters = run(listOf("/\\p{L}+/"), "汉字 abc")
        assertEquals(listOf("book:r"), letters.activatedUids())

        val asciiWholeWord = WorldBookFixture.evaluate(
            listOf(WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("w", keys = listOf("cat"))))),
            WorldBookFixture.messages("a cat sleeps"),
            settings = WorldBookFixture.settings(matchWholeWords = true),
        )
        assertEquals(listOf("book:w"), asciiWholeWord.activatedUids())
    }

    @Test
    fun `ccv3 raw regex keys keep their original field and never fall back`() {
        val entry = WorldBookFixture.entry("v", keys = listOf("(?=x)"), useRegex = true)
        val result = WorldBookFixture.evaluate(
            listOf(WorldBookFixture.book(entries = listOf(entry))),
            WorldBookFixture.messages("x"),
        )
        assertTrue(result.activated.isEmpty())
        assertTrue(result.warnings.any { it.contains("regex.unsupported-lookaround") })
        assertEquals(WorldBookDecisionStatus.SKIPPED, result.decisionFor("v").status)
        // 字段原样保留（未被改写/清空）。
        assertEquals(listOf("(?=x)"), entry.keys)
        assertNotNull(result.decisionFor("v").reason)
    }
}
