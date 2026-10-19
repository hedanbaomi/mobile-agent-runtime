// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookScanState

/**
 * 递归、延迟层、预算与硬预算边界。
 *
 * 注意：激活列表按 `order` 升序输出（ST 先降序稳定排序再逐位置 unshift 的结果），
 * 因此多数用例给出显式 `order` 以便断言稳定顺序；同 `order` 的规则单独验证。
 */
class WorldBookEngineRecursionBudgetTest {

    @Test
    fun `recursion activates entries triggered by injected content`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("alpha"), content = "contains beta token", order = 100),
                WorldBookFixture.entry("b", keys = listOf("beta"), order = 200),
            ),
        )
        val messages = WorldBookFixture.messages("alpha here")
        val nonRecursive = WorldBookFixture.evaluate(listOf(book), messages)
        assertEquals(listOf("book:a"), nonRecursive.activatedUids())

        val recursive = WorldBookFixture.evaluate(
            listOf(book),
            messages,
            settings = WorldBookFixture.settings(recursive = true),
        )
        assertEquals(listOf("book:a", "book:b"), recursive.activatedUids())
        assertTrue(recursive.loops >= 2)
        assertEquals(WorldBookScanState.RECURSION, recursive.decisionFor("b").scanState)
    }

    @Test
    fun `prevent recursion keeps content out of the recursion buffer`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry(
                    "a",
                    keys = listOf("alpha"),
                    content = "contains beta token",
                    preventRecursion = true,
                    order = 100,
                ),
                WorldBookFixture.entry("b", keys = listOf("beta"), order = 200),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha here"),
            settings = WorldBookFixture.settings(recursive = true),
        )
        assertEquals(listOf("book:a"), result.activatedUids())
    }

    @Test
    fun `exclude recursion blocks entries only during recursive scans`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("alpha"), content = "contains beta token", order = 100),
                WorldBookFixture.entry("b", keys = listOf("beta"), excludeRecursion = true, order = 200),
                WorldBookFixture.entry("c", keys = listOf("gamma"), content = "contains delta token", order = 110),
                WorldBookFixture.entry("d", keys = listOf("delta"), order = 120),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha gamma"),
            settings = WorldBookFixture.settings(recursive = true),
        )
        assertEquals(listOf("book:a", "book:c", "book:d"), result.activatedUids())
        assertEquals(WorldBookDecisionStatus.DENIED, result.decisionFor("b").status)
        assertEquals("exclude-recursion", result.decisionFor("b").reason)
    }

    @Test
    fun `recursion cycle terminates without duplicate activation`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("alpha"), content = "beta", order = 100),
                WorldBookFixture.entry("b", keys = listOf("beta"), content = "alpha", order = 200),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha"),
            settings = WorldBookFixture.settings(recursive = true),
        )
        assertEquals(listOf("book:a", "book:b"), result.activatedUids())
        assertTrue(result.loops <= 8, "loops=${result.loops}")
        assertEquals(2, result.activated.size)
    }

    @Test
    fun `max recursion steps bounds the loop`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("alpha"), content = "beta", order = 100),
                WorldBookFixture.entry("b", keys = listOf("beta"), content = "gamma", order = 200),
                WorldBookFixture.entry("c", keys = listOf("gamma"), order = 300),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha"),
            settings = WorldBookFixture.settings(recursive = true, maxRecursionSteps = 2),
        )
        assertEquals(2, result.loops)
        assertEquals(listOf("book:a", "book:b"), result.activatedUids())
        assertTrue(result.warnings.any { it.startsWith("recursion.max-steps-reached") })
    }

    @Test
    fun `delay until recursion waits for the recursive layer`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("alpha"), content = "beta", order = 100),
                WorldBookFixture.entry("d", keys = listOf("beta"), delayUntilRecursion = 1, order = 200),
            ),
        )
        // 只有延迟条目命中（alpha 不在消息里），因此在 INITIAL 层被 delayUntilRecursion 挡住。
        val blocked = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("beta"))
        assertTrue(blocked.activatedUids().isEmpty())
        assertEquals("delay-until-recursion", blocked.decisionFor("d").reason)
        assertEquals("primary-no-match", blocked.decisionFor("a").reason)

        val recursive = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha"),
            settings = WorldBookFixture.settings(recursive = true),
        )
        assertEquals(listOf("book:a", "book:d"), recursive.activatedUids())
        assertEquals(WorldBookScanState.RECURSION, recursive.decisionFor("d").scanState)
    }

    @Test
    fun `min activations deepens the scan until satisfied`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("deep", keys = listOf("deep-kw"))))
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("deep-kw", "m2", "m3"),
            settings = WorldBookFixture.settings(scanDepth = 1, minActivations = 1),
        )
        assertEquals(listOf("book:deep"), result.activatedUids())
        assertTrue(result.loops >= 2)
    }

    @Test
    fun `min activations stops at depth limit with warning`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("x", keys = listOf("never"))))
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("m1", "m2"),
            settings = WorldBookFixture.settings(scanDepth = 1, minActivations = 3, minActivationsDepthMax = 1),
        )
        assertTrue(result.activated.isEmpty())
        assertTrue(result.warnings.any { it.startsWith("min-activations.depth-exceeded") })
    }

    @Test
    fun `soft budget denies oversized entries and ignore budget crosses it`() {
        val big = "x".repeat(50)
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("hit"), content = big, order = 100),
                WorldBookFixture.entry("b", keys = listOf("hit"), content = big, ignoreBudget = true, order = 200),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            budgetUnits = 50,
            hardBudgetUnits = 10_000,
        )
        assertEquals(listOf("book:b"), result.activatedUids())
        assertEquals("budget-exhausted", result.decisionFor("a").reason)
    }

    @Test
    fun `hard budget caps every entry including ignore budget and constant`() {
        // 同 order → 处理顺序 = 书内顺序 a,b,c；预算按处理顺序分配（与 ST 的 newEntries 顺序一致）。
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("hit"), content = "x".repeat(60), order = 100),
                WorldBookFixture.entry("b", keys = listOf("hit"), content = "y".repeat(60), ignoreBudget = true, order = 100),
                WorldBookFixture.entry("c", constant = true, content = "z".repeat(60), order = 100),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            budgetUnits = 10_000,
            hardBudgetUnits = 100,
        )
        assertEquals(listOf("book:a"), result.activatedUids())
        assertEquals("hard-budget-exhausted", result.decisionFor("b").reason)
        assertEquals("hard-budget-exhausted", result.decisionFor("c").reason)
        assertEquals(60, result.estimatedUnits)
        assertTrue(result.estimatedUnits <= 100)
    }

    @Test
    fun `zero hard budget activates nothing at all`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", constant = true),
                WorldBookFixture.entry("b", keys = listOf("hit"), ignoreBudget = true),
                WorldBookFixture.entry("c", keys = listOf("hit")),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            budgetUnits = 10_000,
            hardBudgetUnits = 0,
        )
        assertTrue(result.activated.isEmpty())
        assertEquals(0, result.estimatedUnits)
        assertEquals(3, result.decisions.size)
        assertTrue(result.decisions.all { it.reason == "hard-budget-exhausted" })
    }

    @Test
    fun `negative hard budget also activates nothing`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", constant = true)))
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            budgetUnits = 100,
            hardBudgetUnits = -5,
        )
        assertTrue(result.activated.isEmpty())
        assertEquals(0, result.hardBudgetUnits)
    }

    @Test
    fun `budget overflow stops recursion`() {
        // 同 order → 处理顺序 a,b,c：a（ignoreBudget）先注入，c 触发软预算溢出，递归因此不发生。
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry(
                    "a",
                    keys = listOf("alpha"),
                    content = "beta " + "x".repeat(200),
                    ignoreBudget = true,
                    order = 100,
                ),
                WorldBookFixture.entry("b", keys = listOf("beta"), order = 100),
                WorldBookFixture.entry("c", keys = listOf("alpha"), content = "small", order = 100),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha"),
            settings = WorldBookFixture.settings(recursive = true),
            budgetUnits = 50,
            hardBudgetUnits = 10_000,
        )
        assertEquals(listOf("book:a"), result.activatedUids())
        assertEquals("budget-exhausted", result.decisionFor("c").reason)
        // 溢出后不再递归，因此等待 beta 的条目从未激活。
        assertEquals(WorldBookDecisionStatus.SKIPPED, result.decisionFor("b").status)
    }

    @Test
    fun `estimated units aggregate activated content`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("hit"), content = "12345", order = 100),
                WorldBookFixture.entry("b", keys = listOf("hit"), content = "1234567890", order = 200),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(15, result.estimatedUnits)
        assertEquals(5, result.activated.first { it.entryUid == "a" }.estimatedUnits)
    }

    @Test
    fun `equal orders keep the ST unshift order`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("hit"), order = 100),
                WorldBookFixture.entry("b", keys = listOf("hit"), order = 100),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        // ST：降序稳定排序后逐位置 unshift → 同 order 按激活顺序倒序。
        assertEquals(listOf("b", "a"), result.activated.map { it.entryUid })
    }

    @Test
    fun `hard budget of zero is reported in the evaluation header`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", constant = true)))
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("x"),
            budgetUnits = 1234,
            hardBudgetUnits = 0,
        )
        assertEquals(1234, result.budgetUnits)
        assertEquals(0, result.hardBudgetUnits)
        assertEquals(0, result.loops)
    }
}
