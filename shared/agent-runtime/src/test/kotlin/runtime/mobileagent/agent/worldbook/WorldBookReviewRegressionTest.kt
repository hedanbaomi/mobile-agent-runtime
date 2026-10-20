// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.*

class WorldBookReviewRegressionTest {
    @Test fun preventedRecursionStillConsumesSoftBudgetInLaterLoops() {
        val book = WorldBookFixture.book(entries = listOf(
            WorldBookFixture.entry("a", constant = true, preventRecursion = true, content = "x".repeat(50)),
            WorldBookFixture.entry("b", constant = true, content = "trigger"),
            WorldBookFixture.entry("c", keys = listOf("trigger"), content = "y".repeat(60)),
        ))
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hello"),
            settings = WorldBookFixture.settings(recursive = true), budgetUnits = 100, hardBudgetUnits = 1000)
        assertEquals(setOf("a", "b"), result.activated.map { it.entryUid }.toSet())
        assertEquals("budget-exhausted", result.decisionFor("c").reason)
        assertEquals(57, result.estimatedUnits)
    }

    @Test fun hardRejectedBodyDoesNotConsumeLaterSoftBudget() {
        val book = WorldBookFixture.book(entries = listOf(
            WorldBookFixture.entry("large", constant = true, ignoreBudget = true, content = "x".repeat(200)),
            WorldBookFixture.entry("small", constant = true, content = "fits"),
        ))
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hello"),
            budgetUnits = 50, hardBudgetUnits = 100)
        assertEquals(listOf("book:small"), result.activatedUids())
        assertEquals("hard-budget-exhausted", result.decisionFor("large").reason)
    }

    @Test fun multiGroupMembershipRemainsExclusiveAcrossRecursiveLoops() {
        val book = WorldBookFixture.book(entries = listOf(
            WorldBookFixture.entry("a", keys = listOf("initial"), content = "trigger", group = "g1, g2"),
            WorldBookFixture.entry("b", keys = listOf("trigger"), content = "second", group = "g2"),
        ))
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("initial"),
            settings = WorldBookFixture.settings(recursive = true))
        assertEquals(listOf("book:a"), result.activatedUids())
        assertEquals("group-already-activated:g2", result.decisionFor("b").reason)
    }

    @Test fun stickyGroupMemberRetainsPriorityOverItsOwnCooldown() {
        val book = WorldBookFixture.book(entries = listOf(
            WorldBookFixture.entry("a", keys = listOf("initial"), sticky = 5, cooldown = 10, group = "g"),
            WorldBookFixture.entry("b", keys = listOf("other"), group = "g"),
        ))
        val first = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("initial"), messageCount = 10)
        val second = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("other"), messageCount = 11,
            previousEffects = first.nextEffects)
        assertEquals(listOf("book:a"), second.activatedUids())
        assertEquals("sticky-active", second.decisionFor("a").reason)
        assertEquals("group-sticky-loser:g", second.decisionFor("b").reason)
        val expired = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("initial"), messageCount = 15,
            previousEffects = second.nextEffects)
        assertTrue(expired.activated.isEmpty())
        assertEquals("cooldown", expired.decisionFor("a").reason)
    }

    @Test fun boundedScanDoesNotMaterializeTheWholeHistoryBeforeTruncation() {
        // Reusing one valid message keeps input memory small; concatenating all 1,000 would need 1 GiB.
        val body = "x".repeat(1 shl 20)
        for (cap in listOf(32, WorldBookSettings.HARD_MAX_MESSAGE_SCAN_CHARS)) for (names in listOf(false, true)) {
            val request = WorldBookScanRequest(
                listOf(WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", keys = listOf("never"))))),
                List(1000) { WorldBookScanMessage(body, name = "user") },
                settings = WorldBookSettings(scanDepth = 1000, includeNames = names, maxMessageScanChars = cap),
            )
            val result = WorldBookEngine.evaluate(request, 1000, 1000) { it.length }
            assertTrue(result.activated.isEmpty())
            assertTrue("scan.text-truncated:$cap" in result.warnings)
        }
    }
}
