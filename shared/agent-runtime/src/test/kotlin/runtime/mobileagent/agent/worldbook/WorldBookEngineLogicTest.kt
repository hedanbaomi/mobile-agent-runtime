// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookContext
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookSelectiveLogic

/**
 * 关键词语义测试：常驻、四逻辑次要关键词、大小写、整词（含中文）。
 */
class WorldBookEngineLogicTest {

    @Test
    fun `constant entry activates without keys and disabled entry does not`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("c", constant = true),
                WorldBookFixture.entry("d", keys = listOf("hit"), enabled = false),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:c"), result.activatedUids())
        assertEquals(WorldBookDecisionStatus.INELIGIBLE, result.decisionFor("d").status)
        assertEquals("entry-disabled", result.decisionFor("d").reason)
    }

    @Test
    fun `primary key matches inside scan depth and misses outside`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", keys = listOf("needle"))))
        val near = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("old", "needle here"),
            settings = WorldBookFixture.settings(scanDepth = 1),
        )
        assertEquals(listOf("book:a"), near.activatedUids())

        val far = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("needle here", "newer"),
            settings = WorldBookFixture.settings(scanDepth = 1),
        )
        assertTrue(far.activated.isEmpty())
        assertEquals("primary-no-match", far.decisionFor("a").reason)
    }

    @Test
    fun `entry scan depth overrides global depth`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("deep", keys = listOf("needle"), scanDepth = 3)),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("needle here", "m2", "m3"),
            settings = WorldBookFixture.settings(scanDepth = 1),
        )
        assertEquals(listOf("book:deep"), result.activatedUids())
    }

    @Test
    fun `case sensitivity follows entry override then global setting`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", keys = listOf("Alpha"))))
        val insensitive = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("alpha"))
        assertEquals(listOf("book:a"), insensitive.activatedUids())

        val sensitive = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha"),
            settings = WorldBookFixture.settings(caseSensitive = true),
        )
        assertTrue(sensitive.activated.isEmpty())

        val override = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("alpha"),
            settings = WorldBookFixture.settings(caseSensitive = true),
        )
        assertTrue(override.activated.isEmpty())

        val entryOverride = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("b", keys = listOf("Alpha"), caseSensitive = false)),
        )
        val relaxed = WorldBookFixture.evaluate(
            listOf(entryOverride),
            WorldBookFixture.messages("alpha"),
            settings = WorldBookFixture.settings(caseSensitive = true),
        )
        assertEquals(listOf("book:b"), relaxed.activatedUids())
    }

    @Test
    fun `whole word matching respects boundaries`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", keys = listOf("cat"))))
        val substring = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("concatenate"))
        assertEquals(listOf("book:a"), substring.activatedUids())

        val wholeWord = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("concatenate"),
            settings = WorldBookFixture.settings(matchWholeWords = true),
        )
        assertTrue(wholeWord.activated.isEmpty())

        val boundary = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("a cat sleeps"),
            settings = WorldBookFixture.settings(matchWholeWords = true),
        )
        assertEquals(listOf("book:a"), boundary.activatedUids())
    }

    @Test
    fun `chinese defaults to substring and whole word uses ascii boundaries`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("cn", keys = listOf("猫"))))
        val substring = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("小猫钓鱼"))
        assertEquals(listOf("book:cn"), substring.activatedUids())

        // ST 的整词边界是 ASCII \W：两侧汉字都不算单词字符，因此仍命中。
        val asciiWholeWord = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("小猫钓鱼"),
            settings = WorldBookFixture.settings(matchWholeWords = true),
        )
        assertEquals(listOf("book:cn"), asciiWholeWord.activatedUids())

        // 关闭 ASCII 边界语义后使用 Unicode 语义，整词不再命中汉字内部。
        val unicodeWholeWord = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("小猫钓鱼"),
            settings = WorldBookFixture.settings(matchWholeWords = true, wholeWordAsciiBoundaries = false),
        )
        assertTrue(unicodeWholeWord.activated.isEmpty())

        val standalone = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("猫 在窗台"),
            settings = WorldBookFixture.settings(matchWholeWords = true, wholeWordAsciiBoundaries = false),
        )
        assertEquals(listOf("book:cn"), standalone.activatedUids())
    }

    @Test
    fun `four selective logics behave as ST documents them`() {
        fun run(logic: WorldBookSelectiveLogic, haystack: String): List<String> {
            val book = WorldBookFixture.book(
                entries = listOf(
                    WorldBookFixture.entry(
                        "e",
                        keys = listOf("primary"),
                        secondaryKeys = listOf("s1", "s2"),
                        selectiveLogic = logic,
                    ),
                ),
            )
            return WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages(haystack)).activatedUids()
        }

        assertEquals(listOf("book:e"), run(WorldBookSelectiveLogic.AND_ANY, "primary s1"))
        assertTrue(run(WorldBookSelectiveLogic.AND_ANY, "primary none").isEmpty())

        assertTrue(run(WorldBookSelectiveLogic.AND_ALL, "primary s1").isEmpty())
        assertEquals(listOf("book:e"), run(WorldBookSelectiveLogic.AND_ALL, "primary s1 s2"))

        assertEquals(listOf("book:e"), run(WorldBookSelectiveLogic.NOT_ANY, "primary none"))
        assertTrue(run(WorldBookSelectiveLogic.NOT_ANY, "primary s1").isEmpty())

        assertEquals(listOf("book:e"), run(WorldBookSelectiveLogic.NOT_ALL, "primary s1"))
        assertTrue(run(WorldBookSelectiveLogic.NOT_ALL, "primary s1 s2").isEmpty())
    }

    @Test
    fun `non selective entries ignore secondary keys`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("e", keys = listOf("primary"), secondaryKeys = listOf("nope"), selective = false),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("primary only"))
        assertEquals(listOf("book:e"), result.activatedUids())
    }

    @Test
    fun `slash delimited regex keys work and invalid ones never match`() {
        val good = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("r", keys = listOf("/foo\\d+/i"))))
        val matched = WorldBookFixture.evaluate(listOf(good), WorldBookFixture.messages("FOO42"))
        assertEquals(listOf("book:r"), matched.activatedUids())

        val bad = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("b", keys = listOf("/[/"))))
        val rejected = WorldBookFixture.evaluate(listOf(bad), WorldBookFixture.messages("anything"))
        assertTrue(rejected.activated.isEmpty())
        assertTrue(rejected.warnings.any { it.startsWith("regex.rejected") })
    }

    @Test
    fun `ccv3 use regex treats keys as raw patterns and ignores secondary keys`() {
        val raw = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("v3", keys = listOf("foo\\d+"), useRegex = true, secondaryKeys = listOf("zzz")),
            ),
        )
        val matched = WorldBookFixture.evaluate(listOf(raw), WorldBookFixture.messages("xx foo12 yy"))
        assertEquals(listOf("book:v3"), matched.activatedUids())

        val invalid = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("bad", keys = listOf("["), useRegex = true)),
        )
        val none = WorldBookFixture.evaluate(listOf(invalid), WorldBookFixture.messages("anything"))
        assertTrue(none.activated.isEmpty())
    }

    @Test
    fun `ccv3 use regex makes constant ignored so the regex key must match`() {
        val nonMatching = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("cm", keys = listOf("foo\\d+"), useRegex = true, constant = true),
            ),
        )
        val none = WorldBookFixture.evaluate(listOf(nonMatching), WorldBookFixture.messages("nothing here"))
        assertTrue(none.activated.isEmpty())
        assertEquals("primary-no-match", none.decisionFor("cm").reason)

        val matching = WorldBookFixture.evaluate(listOf(nonMatching), WorldBookFixture.messages("see foo42 here"))
        assertEquals(listOf("book:cm"), matching.activatedUids())
    }

    @Test
    fun `extra match sources feed the scan buffer`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("sc", keys = listOf("scenario-kw"), matchScenario = true),
                WorldBookFixture.entry("pd", keys = listOf("persona-kw"), matchPersonaDescription = true),
                WorldBookFixture.entry("cn", keys = listOf("creator-kw"), matchCreatorNotes = true),
                WorldBookFixture.entry("inj", keys = listOf("inject-kw")),
                WorldBookFixture.entry("no", keys = listOf("scenario-kw")),
            ),
        )
        val context = WorldBookContext(
            scenario = "a scenario-kw here",
            personaDescription = "a persona-kw here",
            creatorNotes = "a creator-kw here",
            injectedPrompts = listOf("an inject-kw here"),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("plain"), context = context)
        assertEquals(listOf("book:cn", "book:pd", "book:sc", "book:inj").sorted(), result.activatedUids().sorted())
        assertEquals("primary-no-match", result.decisionFor("no").reason)
    }

    @Test
    fun `matched keys are reported on the decision`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("m", keys = listOf("nope", "hit"))),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("hit"), result.decisionFor("m").matchedKeys)
    }

    @Test
    fun `generation type triggers filter entries`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("t", keys = listOf("hit"), triggers = listOf("continue"))),
        )
        val skipped = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(WorldBookDecisionStatus.INELIGIBLE, skipped.decisionFor("t").status)
        assertEquals("trigger-mismatch", skipped.decisionFor("t").reason)

        val matched = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            generationType = "continue",
        )
        assertEquals(listOf("book:t"), matched.activatedUids())
    }

    @Test
    fun `activation order is ascending by order with stable indexes`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("c", keys = listOf("hit"), order = 30),
                WorldBookFixture.entry("a", keys = listOf("hit"), order = 10),
                WorldBookFixture.entry("b", keys = listOf("hit"), order = 20),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("a", "b", "c"), result.activated.map { it.entryUid })
        assertEquals(listOf(0, 1, 2), result.activated.map { it.index })
    }

    @Test
    fun `probability extremes are deterministic`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("zero", keys = listOf("hit"), probability = 0, useProbability = true),
                WorldBookFixture.entry("off", keys = listOf("hit"), probability = 0, useProbability = false),
                WorldBookFixture.entry("full", keys = listOf("hit"), probability = 100),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:full", "book:off").sorted(), result.activatedUids().sorted())
        assertEquals("probability-failed", result.decisionFor("zero").reason)
        assertFalse(result.decisionFor("zero").status == WorldBookDecisionStatus.ACTIVATED)
    }

    @Test
    fun `empty content after macros is denied instead of activated`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("e", keys = listOf("hit"), content = "{{// only a comment}}")),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(result.activated.isEmpty())
        assertEquals("empty-content", result.decisionFor("e").reason)
    }
}
