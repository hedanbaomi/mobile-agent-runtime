// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookCharacterFilter
import runtime.mobileagent.domain.worldbook.WorldBookContext
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookScanMessage
import runtime.mobileagent.domain.worldbook.WorldBookSettings
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffectType

/**
 * 安全护栏回归：不可提升的限额、includeNames、缺失锚点、宏展开总预算、hash 抗注入与语义失效。
 */
class WorldBookEngineGuardsTest {

    // ------------------------------------------------------------------ 限额

    @Test
    fun `engine clamps out of range settings and reports the fields`() {
        val warnings = ArrayList<String>()
        val raw = WorldBookSettings(
            scanDepth = 99_999,
            minActivations = -5,
            budgetPercent = 900,
            maxScanDepth = 5_000,
            maxRegexPatternLength = Int.MAX_VALUE,
            regexStepBudget = Long.MAX_VALUE,
            maxMessageScanChars = -5,
            maxRunExpansionChars = Int.MAX_VALUE,
        )
        val normalized = WorldBookEnginePlan.normalizeSettings(raw, warnings)
        assertEquals(WorldBookSettings.MAX_SCAN_DEPTH, normalized.scanDepth)
        assertEquals(0, normalized.minActivations)
        assertEquals(100, normalized.budgetPercent)
        assertEquals(WorldBookSettings.MAX_SCAN_DEPTH, normalized.maxScanDepth)
        assertEquals(WorldBookSettings.HARD_MAX_REGEX_PATTERN_LENGTH, normalized.maxRegexPatternLength)
        assertEquals(WorldBookSettings.HARD_MAX_REGEX_STEP_BUDGET, normalized.regexStepBudget)
        assertEquals(WorldBookSettings.HARD_MAX_MESSAGE_SCAN_CHARS, normalized.maxMessageScanChars)
        assertEquals(WorldBookSettings.HARD_MAX_RUN_EXPANSION_CHARS, normalized.maxRunExpansionChars)
        val warning = warnings.single { it.startsWith("settings.clamped:") }
        assertTrue(warning.contains("regexStepBudget"))
        assertTrue(warning.contains("maxMessageScanChars"))
    }

    @Test
    fun `settings within range are untouched and produce no warning`() {
        val warnings = ArrayList<String>()
        val raw = WorldBookFixture.settings(scanDepth = 3, regexStepBudget = 1_000)
        assertEquals(raw, WorldBookEnginePlan.normalizeSettings(raw, warnings))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `negative message scan chars cannot crash and do not shrink to nothing`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", keys = listOf("hit"))))
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            settings = WorldBookFixture.settings(maxMessageScanChars = -5),
        )
        assertEquals(listOf("book:a"), result.activatedUids())
        assertTrue(result.warnings.any { it.startsWith("settings.clamped:") })
    }

    @Test
    fun `huge regex step budget is capped and normal matching still works`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("r", keys = listOf("/foo\\d+/"))))
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("foo42"),
            settings = WorldBookFixture.settings(regexStepBudget = Long.MAX_VALUE),
        )
        assertEquals(listOf("book:r"), result.activatedUids())
        assertTrue(result.warnings.any { it.contains("regexStepBudget") })
    }

    @Test
    fun `negative message count is clamped to zero`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("s", keys = listOf("hit"), sticky = 3)),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            messageCount = -10,
        )
        assertEquals(listOf("book:s"), result.activatedUids())
        val sticky = result.nextEffects.single { it.type == WorldBookTimedEffectType.STICKY }
        assertEquals(0, sticky.start)
        assertEquals(3, sticky.end)
    }

    // ------------------------------------------------------------------ includeNames

    @Test
    fun `include names controls whether participant names feed the scan buffer`() {
        val messages = listOf(WorldBookScanMessage(text = "hello", role = "user", name = "Alice"))
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("n", keys = listOf("Alice"))))

        val withNames = WorldBookFixture.evaluate(
            listOf(book),
            messages,
            settings = WorldBookFixture.settings(includeNames = true),
        )
        assertEquals(listOf("book:n"), withNames.activatedUids())

        val withoutNames = WorldBookFixture.evaluate(
            listOf(book),
            messages,
            settings = WorldBookFixture.settings(includeNames = false),
        )
        assertTrue(withoutNames.activated.isEmpty())
        assertEquals("primary-no-match", withoutNames.decisionFor("n").reason)

        // 原始正文与名字不被改写。
        assertEquals("hello", messages.single().text)
        assertEquals("Alice", messages.single().name)
    }

    @Test
    fun `blank participant name is skipped when include names is on`() {
        val messages = listOf(WorldBookScanMessage(text = "hello", role = "user", name = "   "))
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("n", keys = listOf("hello"))))
        val result = WorldBookFixture.evaluate(listOf(book), messages)
        assertEquals(listOf("book:n"), result.activatedUids())
    }

    // ------------------------------------------------------------------ 缺失锚点

    @Test
    fun `author note anchors need context and do not advance effects`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry(
                    "an",
                    keys = listOf("hit"),
                    position = WorldBookPosition.AN_TOP,
                    sticky = 5,
                ),
            ),
        )
        val missing = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(missing.activated.isEmpty())
        assertEquals(WorldBookDecisionStatus.NEEDS_EXTERNAL, missing.decisionFor("an").status)
        assertEquals("missing-anchor:author-note", missing.decisionFor("an").reason)
        assertTrue(missing.nextEffects.isEmpty())
        assertTrue(missing.warnings.any { it.startsWith("missing-anchor:author-note") })

        val provided = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(authorNote = "note"),
        )
        assertEquals(listOf("book:an"), provided.activatedUids())
        assertTrue(provided.nextEffects.any { it.type == WorldBookTimedEffectType.STICKY })
    }

    @Test
    fun `example message anchors need example context`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("em", keys = listOf("hit"), position = WorldBookPosition.EM_BOTTOM),
            ),
        )
        val missing = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals("missing-anchor:example-messages", missing.decisionFor("em").reason)
        assertEquals(WorldBookDecisionStatus.NEEDS_EXTERNAL, missing.decisionFor("em").status)

        val provided = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(exampleMessages = listOf("example")),
        )
        assertEquals(listOf("book:em"), provided.activatedUids())
    }

    @Test
    fun `outlet anchors need a real outlet in the current prompt`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("o", keys = listOf("hit"), position = WorldBookPosition.OUTLET, outletName = "facts"),
            ),
        )
        val offline = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(offline.activated.isEmpty())
        assertEquals("missing-anchor:outlet:facts", offline.decisionFor("o").reason)
        assertEquals(WorldBookDecisionStatus.NEEDS_EXTERNAL, offline.decisionFor("o").status)

        val online = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(availableOutlets = setOf("facts", "other")),
        )
        assertEquals(listOf("book:o"), online.activatedUids())
    }

    @Test
    fun `blank outlet name is reported as missing anchor`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("o", keys = listOf("hit"), position = WorldBookPosition.OUTLET)),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(availableOutlets = setOf("facts")),
        )
        assertEquals("missing-anchor:outlet", result.decisionFor("o").reason)
    }

    // ------------------------------------------------------------------ 宏展开总预算

    @Test
    fun `per entry macro output limit rejects oversized expansion`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("m", keys = listOf("hit"), content = "{{description}}".repeat(20)),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(characterDescription = "x".repeat(100_000)),
        )
        assertTrue(result.activated.isEmpty())
        assertEquals(WorldBookMacros.REJECT_OUTPUT_LIMIT, result.decisionFor("m").reason)
        assertTrue(result.warnings.any { it.startsWith(WorldBookMacros.REJECT_OUTPUT_LIMIT) })
    }

    @Test
    fun `run level macro budget stops later expansions with an explicit reason`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                // ST processes higher insertion order first for budget selection.
                WorldBookFixture.entry("first", keys = listOf("hit"), content = "{{description}}", order = 200),
                WorldBookFixture.entry("second", keys = listOf("hit"), content = "{{description}}", order = 100),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            settings = WorldBookFixture.settings(maxRunExpansionChars = 200),
            context = WorldBookContext(characterDescription = "y".repeat(100)),
        )
        assertEquals(listOf("book:first"), result.activatedUids())
        assertEquals(WorldBookMacros.REJECT_RUN_LIMIT, result.decisionFor("second").reason)
        assertEquals(WorldBookDecisionStatus.DENIED, result.decisionFor("second").status)
        assertTrue(result.warnings.any { it.startsWith(WorldBookMacros.REJECT_RUN_LIMIT) })
    }

    // ------------------------------------------------------------------ hash

    @Test
    fun `canonical hash is not injectable through delimiters`() {
        val base = WorldBookFixture.entry("h")
        val injected = base.copy(keys = listOf("a\u0002b"))
        val split = base.copy(keys = listOf("a", "b"))
        assertNotEquals(WorldBookHash.of("book", injected), WorldBookHash.of("book", split))

        val nulCase = base.copy(keys = listOf("a\u0000b"))
        assertNotEquals(WorldBookHash.of("book", nulCase), WorldBookHash.of("book", split))

        val adjacent = base.copy(keys = listOf("ab"))
        assertNotEquals(WorldBookHash.of("book", adjacent), WorldBookHash.of("book", split))

        // null 与空串必须不同。
        assertNotEquals(
            WorldBookHash.of("book", base.copy(sticky = null)),
            WorldBookHash.of("book", base.copy(sticky = 0)),
        )
        assertNotEquals(
            WorldBookHash.of("book", base.copy(scanDepth = null)),
            WorldBookHash.of("book", base.copy(scanDepth = 0)),
        )
        // 长度前缀下同内容同 hash。
        assertEquals(WorldBookHash.of("book", injected), WorldBookHash.of("book", injected))
    }

    @Test
    fun `semantic edits invalidate timed effects`() {
        fun stickyRun(entry: runtime.mobileagent.domain.worldbook.WorldBookEntry) =
            WorldBookFixture.evaluate(
                listOf(WorldBookFixture.book(entries = listOf(entry))),
                WorldBookFixture.messages("hit"),
                messageCount = 10,
                budgetUnits = 10_000,
            )

        // useProbability = false：首轮必定激活（用药概率不作为测试前提），但字段仍在 hash 覆盖范围内。
        val base = WorldBookFixture.entry(
            "s",
            keys = listOf("hit"),
            sticky = 5,
            probability = 50,
            useProbability = false,
            group = "g0",
        )
        val mutators: List<Pair<String, (runtime.mobileagent.domain.worldbook.WorldBookEntry) ->
        runtime.mobileagent.domain.worldbook.WorldBookEntry>> = listOf(
            "probability" to { it.copy(probability = 51) },
            "useProbability" to { it.copy(useProbability = true) },
            "selective" to { it.copy(selective = false) },
            "selectiveLogic" to { it.copy(selectiveLogic = runtime.mobileagent.domain.worldbook.WorldBookSelectiveLogic.AND_ALL) },
            "group" to { it.copy(group = "g1") },
            "groupWeight" to { it.copy(groupWeight = 5) },
            "groupOverride" to { it.copy(groupOverride = true) },
            "position" to { it.copy(position = WorldBookPosition.AFTER_CHAR) },
            "depth" to { it.copy(depth = 9) },
            "ignoreBudget" to { it.copy(ignoreBudget = true) },
            "excludeRecursion" to { it.copy(excludeRecursion = true) },
            "matchScenario" to { it.copy(matchScenario = true) },
            "characterFilter" to { it.copy(characterFilter = WorldBookCharacterFilter(true, listOf("X.png"))) },
            "trigger" to { it.copy(triggers = listOf("continue")) },
        )

        val first = stickyRun(base)
        assertTrue(first.nextEffects.any { it.type == WorldBookTimedEffectType.STICKY })
        for ((name, mutate) in mutators) {
            val edited = mutate(base)
            val after = WorldBookFixture.evaluate(
                listOf(WorldBookFixture.book(entries = listOf(edited))),
                WorldBookFixture.messages("nothing"),
                messageCount = 11,
                previousEffects = first.nextEffects,
            )
            assertTrue(after.activated.isEmpty(), "edited field '$name' must not reuse the old sticky effect")
            assertTrue(
                after.warnings.any { it.startsWith("effect.hash-changed") },
                "edited field '$name' must report effect.hash-changed",
            )
        }
    }
}
