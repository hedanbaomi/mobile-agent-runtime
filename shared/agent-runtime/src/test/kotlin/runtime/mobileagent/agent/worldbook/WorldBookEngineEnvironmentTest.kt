// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookCharacterFilter
import runtime.mobileagent.domain.worldbook.WorldBookContext
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookPosition
import runtime.mobileagent.domain.worldbook.WorldBookRole

/**
 * 外部环境与装饰器：向量/自动化/Persona/标签缺失时的显式裁决，以及 CCv3 装饰器。
 */
class WorldBookEngineEnvironmentTest {

    @Test
    fun `vectorized entry with keywords still activates by keyword`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("v", keys = listOf("hit"), vectorized = true)),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:v"), result.activatedUids())
        assertTrue(result.warnings.any { it.startsWith("env.vector-unavailable") })
    }

    @Test
    fun `vectorized entry without keywords needs the vector extension`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("v", vectorized = true)))
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(result.activated.isEmpty())
        assertEquals(WorldBookDecisionStatus.NEEDS_EXTERNAL, result.decisionFor("v").status)
        assertEquals("vector-no-keywords", result.decisionFor("v").reason)
    }

    @Test
    fun `automation id does not exclude keyword activation`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("a", keys = listOf("hit"), automationId = "qr-1")),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:a"), result.activatedUids())
        assertTrue(result.warnings.any { it.startsWith("env.automation-not-executed") })
    }

    @Test
    fun `persona matching without persona context is reported`() {
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("p", keys = listOf("persona-kw"), matchPersonaDescription = true)),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("nothing"))
        assertTrue(result.warnings.contains("env.persona-unavailable"))
        assertEquals("primary-no-match", result.decisionFor("p").reason)
    }

    @Test
    fun `character name filter uses the provided file name`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry(
                    "n",
                    keys = listOf("hit"),
                    order = 100,
                ).copy(characterFilter = WorldBookCharacterFilter(isExclude = false, names = listOf("Hero.png"))),
            ),
        )
        val allowed = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(characterFileName = "Hero.png"),
        )
        assertEquals(listOf("book:n"), allowed.activatedUids())

        val blocked = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(characterFileName = "Villain.png"),
        )
        assertTrue(blocked.activated.isEmpty())
        assertEquals("character-filter-names", blocked.decisionFor("n").reason)

        // 文件名缺失时与 ST 一致：非排除型过滤器判定为“被过滤”。
        val missing = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(missing.activated.isEmpty())
        assertTrue(missing.warnings.any { it.startsWith("env.character-file-name-missing") })
    }

    @Test
    fun `character tag filter uses provided tags and reports missing tag map`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("t", keys = listOf("hit")).copy(
                    characterFilter = WorldBookCharacterFilter(isExclude = false, tags = listOf("fantasy")),
                ),
            ),
        )
        val allowed = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("hit"),
            context = WorldBookContext(characterTags = listOf("fantasy", "other")),
        )
        assertEquals(listOf("book:t"), allowed.activatedUids())

        val missing = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(missing.activated.isEmpty())
        assertTrue(missing.warnings.any { it.startsWith("env.tag-map-unavailable") })
    }

    @Test
    fun `activate and dont activate decorators are honoured`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("on", order = 100, content = "@@activate\nreal body"),
                WorldBookFixture.entry("off", keys = listOf("hit"), order = 200, content = "@@dont_activate\nbody"),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:on"), result.activatedUids())
        assertEquals("decorator-activate", result.decisionFor("on").reason)
        assertEquals("real body", result.activated.single().content)
        assertEquals(WorldBookDecisionStatus.DENIED, result.decisionFor("off").status)
        assertEquals("decorator-dont-activate", result.decisionFor("off").reason)
    }

    @Test
    fun `escaped triple at decorator is ignored exactly like ST`() {
        // ST parseDecorators 对首个 `@@@...` 行直接 continue（既不登记装饰器，也不进入正文），
        // 因此 `@@@activate` 单独出现时该条目没有装饰器、没有关键词 → 只按 no-keys 跳过。
        val book = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("esc", order = 100, content = "@@@activate\nbody")),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("nothing"))
        assertTrue(result.activated.isEmpty())
        assertEquals("no-keys", result.decisionFor("esc").reason)
        assertFalse(result.decisionFor("esc").status == WorldBookDecisionStatus.DENIED)
    }

    @Test
    fun `unsupported decorators block activation with an explicit decision`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry(
                    "cond",
                    keys = listOf("hit"),
                    order = 100,
                    content = "@@dont_activate_after_match\nbody",
                ),
                WorldBookFixture.entry("unknown", keys = listOf("hit"), order = 200, content = "@@foobar\nbody"),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(result.activated.isEmpty())
        assertEquals("decorator-unsupported:@@dont_activate_after_match", result.decisionFor("cond").reason)
        assertEquals(WorldBookDecisionStatus.NEEDS_EXTERNAL, result.decisionFor("cond").status)
        assertEquals("decorator-unsupported:@@foobar", result.decisionFor("unknown").reason)
        assertTrue(result.warnings.any { it.startsWith("decorator.unsupported") })
    }

    @Test
    fun `scan depth decorator overrides the global depth`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("sd", keys = listOf("deep-kw"), order = 100, content = "@@scan_depth 3\nbody"),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(book),
            WorldBookFixture.messages("deep-kw", "m2", "hit"),
            settings = WorldBookFixture.settings(scanDepth = 1),
        )
        assertEquals(listOf("book:sd"), result.activatedUids())
    }

    @Test
    fun `depth role and position decorators shape the activation`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry(
                    "d",
                    keys = listOf("hit"),
                    order = 100,
                    content = "@@depth 2\n@@role assistant\n@@position after_char\nbody",
                ),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        val activation = result.activated.single()
        assertEquals(2, activation.depth)
        assertEquals(WorldBookRole.ASSISTANT, activation.role)
        assertEquals(WorldBookPosition.AFTER_CHAR, activation.position)
    }

    @Test
    fun `invalid decorator arguments degrade to unsupported`() {
        val book = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("bad", keys = listOf("hit"), content = "@@position sideways\nbody"),
                WorldBookFixture.entry("bad2", keys = listOf("hit"), content = "@@scan_depth xx\nbody"),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertTrue(result.activated.isEmpty())
        assertEquals("decorator-unsupported:@@position", result.decisionFor("bad").reason)
        assertEquals("decorator-unsupported:@@scan_depth", result.decisionFor("bad2").reason)
    }

    @Test
    fun `no external notice is emitted for entries that do not need it`() {
        val book = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("plain", keys = listOf("hit"))))
        val result = WorldBookFixture.evaluate(listOf(book), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:plain"), result.activatedUids())
        assertFalse(result.warnings.any { it.startsWith("env.vector") })
        assertFalse(result.warnings.any { it.startsWith("env.automation") })
    }
}
