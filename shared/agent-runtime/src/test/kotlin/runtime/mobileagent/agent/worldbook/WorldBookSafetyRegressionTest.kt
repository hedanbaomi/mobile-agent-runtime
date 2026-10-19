// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.*

class WorldBookSafetyRegressionTest {
    @Test fun exhaustedMacroBudgetRejectsBeforeScanningOrDrawingRandomness() {
        val budget = MacroBudget(1)
        assertFalse(budget.tryConsume(2))
        val random = DeterministicRandom(7)
        val result = WorldBookMacros.substitute("{{random::first::second}}", WorldBookContext(), random, 7, budget = budget)
        assertEquals(WorldBookMacros.REJECT_RUN_LIMIT, result.rejected)
        assertEquals(2L, budget.usedChars)
        assertEquals(DeterministicRandom(7).nextDouble(), random.nextDouble())
    }

    @Test fun streamingExpansionChargesBeforeAppendingAndKeysShareTheSameRunBudget() {
        val limited = WorldBookMacros.substitute("{{persona}}", WorldBookContext(personaDescription = "x".repeat(100)),
            DeterministicRandom(1), 1, budget = MacroBudget(30))
        assertEquals(WorldBookMacros.REJECT_RUN_LIMIT, limited.rejected)
        assertEquals("", limited.text)
        val entries = (0..4).map { WorldBookEntry(it.toString(), listOf("{{persona}}"), "body") }
        val result = WorldBookEngine.evaluate(WorldBookScanRequest(
            listOf(WorldBookSource("b", 1, WorldBookDocument("fixture", entries))), listOf(WorldBookScanMessage("x")),
            settings = WorldBookSettings(maxRunExpansionChars = 30), context = WorldBookContext(personaDescription = "x"),
        ), 1000, 1000) { it.length }
        assertTrue(result.warnings.any { it.contains(WorldBookMacros.REJECT_RUN_LIMIT) }, result.warnings.toString())
        assertTrue(result.activated.isEmpty())
    }
}
