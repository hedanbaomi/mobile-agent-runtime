// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.agent.worldbook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.worldbook.WorldBookDecisionStatus
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffect
import runtime.mobileagent.domain.worldbook.WorldBookTimedEffectType

/**
 * 定时效果（sticky / cooldown / delay，跨重启）与包含组行为。
 */
class WorldBookEngineEffectsGroupTest {

    private fun stickyOf(effects: List<WorldBookTimedEffect>): WorldBookTimedEffect =
        effects.first { it.type == WorldBookTimedEffectType.STICKY }

    @Test
    fun `sticky keeps entry active while the message count advances`() {
        val source = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("s", keys = listOf("hit"), sticky = 3, order = 100)),
        )
        val first = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 10,
            budgetUnits = 10_000,
        )
        assertEquals(listOf("book:s"), first.activatedUids())
        val sticky = stickyOf(first.nextEffects)
        assertEquals(10, sticky.start)
        assertEquals(13, sticky.end)
        assertFalse(sticky.protected)

        // 同一消息数（聊天未推进）：ST 会丢弃该效果。
        val sameTurn = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("nothing"),
            messageCount = 10,
            previousEffects = first.nextEffects,
        )
        assertTrue(sameTurn.activated.isEmpty())

        // 消息数推进后 sticky 生效，即使关键词已不在消息里。
        val advanced = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("nothing"),
            messageCount = 11,
            previousEffects = first.nextEffects,
        )
        assertEquals(listOf("book:s"), advanced.activatedUids())
        assertEquals("sticky-active", advanced.decisionFor("s").reason)

        // 到期后失效。
        val expired = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("nothing"),
            messageCount = 13,
            previousEffects = advanced.nextEffects,
        )
        assertTrue(expired.activated.isEmpty())
    }

    @Test
    fun `cooldown suppresses after advancing and expires at its end`() {
        val source = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("c", keys = listOf("hit"), cooldown = 3, order = 100)),
        )
        val first = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 10,
            budgetUnits = 10_000,
        )
        assertEquals(listOf("book:c"), first.activatedUids())

        val suppressed = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 11,
            previousEffects = first.nextEffects,
        )
        assertTrue(suppressed.activated.isEmpty())
        assertEquals("cooldown", suppressed.decisionFor("c").reason)

        val expired = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 13,
            previousEffects = first.nextEffects,
        )
        assertEquals(listOf("book:c"), expired.activatedUids())
    }

    @Test
    fun `ended sticky transitions into a protected cooldown`() {
        val source = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("sc", keys = listOf("hit"), sticky = 1, cooldown = 5, order = 100),
            ),
        )
        val first = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 10,
            budgetUnits = 10_000,
        )
        val advanced = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("nothing"),
            messageCount = 11,
            previousEffects = first.nextEffects,
        )
        assertTrue(advanced.activated.isEmpty())
        assertEquals("cooldown", advanced.decisionFor("sc").reason)
        val cooldown = advanced.nextEffects.first { it.type == WorldBookTimedEffectType.COOLDOWN }
        assertTrue(cooldown.protected)
        assertEquals(11, cooldown.start)
        assertEquals(16, cooldown.end)
    }

    @Test
    fun `editing an entry invalidates its timed effect by hash`() {
        val original = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("h", keys = listOf("hit"), content = "v1", sticky = 5)),
        )
        val first = WorldBookFixture.evaluate(
            listOf(original),
            WorldBookFixture.messages("hit"),
            messageCount = 10,
            budgetUnits = 10_000,
        )
        val edited = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("h", keys = listOf("hit"), content = "v2 edited", sticky = 5)),
        )
        val afterEdit = WorldBookFixture.evaluate(
            listOf(edited),
            WorldBookFixture.messages("nothing"),
            messageCount = 11,
            previousEffects = first.nextEffects,
        )
        assertTrue(afterEdit.activated.isEmpty())
        assertTrue(afterEdit.warnings.any { it.startsWith("effect.hash-changed") })
    }

    @Test
    fun `delay suppresses until the message count reaches it`() {
        val source = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("d", keys = listOf("hit"), delay = 5)),
        )
        val early = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 3,
        )
        assertTrue(early.activated.isEmpty())
        assertEquals("delayed", early.decisionFor("d").reason)

        val ready = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 5,
        )
        assertEquals(listOf("book:d"), ready.activatedUids())
    }

    @Test
    fun `delay effects are not persisted and are reported`() {
        val source = WorldBookFixture.book(
            entries = listOf(WorldBookFixture.entry("d", keys = listOf("hit"), delay = 5)),
        )
        val previous = listOf(
            WorldBookTimedEffect(
                type = WorldBookTimedEffectType.DELAY,
                bookId = "book",
                entryUid = "d",
                hash = 1L,
                start = 0,
                end = 5,
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 1,
            previousEffects = previous,
        )
        assertTrue(result.warnings.any { it.startsWith("effect.delay-not-persisted") })
        assertTrue(result.nextEffects.none { it.type == WorldBookTimedEffectType.DELAY })
    }

    @Test
    fun `unknown book effects are kept until their end passes`() {
        val source = WorldBookFixture.book(entries = listOf(WorldBookFixture.entry("a", keys = listOf("hit"))))
        val orphan = WorldBookTimedEffect(
            type = WorldBookTimedEffectType.STICKY,
            bookId = "gone",
            entryUid = "9",
            hash = 42L,
            start = 0,
            end = 20,
        )
        val kept = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("nothing"),
            messageCount = 5,
            previousEffects = listOf(orphan),
        )
        assertTrue(kept.warnings.any { it.startsWith("effect.book-unavailable") })
        assertTrue(kept.nextEffects.any { it.bookId == "gone" })

        val dropped = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("nothing"),
            messageCount = 20,
            previousEffects = listOf(orphan),
        )
        assertTrue(dropped.nextEffects.none { it.bookId == "gone" })
    }

    @Test
    fun `group override picks the highest order entry`() {
        val source = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("lo", keys = listOf("hit"), group = "g", groupOverride = true, order = 10),
                WorldBookFixture.entry("hi", keys = listOf("hit"), group = "g", groupOverride = true, order = 20),
                WorldBookFixture.entry("plain", keys = listOf("hit"), group = "g", order = 30),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(source), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:hi"), result.activatedUids())
        assertEquals("group-override-loser:g", result.decisionFor("lo").reason)
        assertEquals("group-override-loser:g", result.decisionFor("plain").reason)
    }

    @Test
    fun `group weighted roll is deterministic per seed and honours zero weight`() {
        fun run(heavyFirst: Boolean, seed: Long): List<String> {
            val heavy = WorldBookFixture.entry("heavy", keys = listOf("hit"), group = "g", groupWeight = 100, order = 10)
            val light = WorldBookFixture.entry("light", keys = listOf("hit"), group = "g", groupWeight = 0, order = 20)
            val entries = if (heavyFirst) listOf(heavy, light) else listOf(light, heavy)
            val result = WorldBookFixture.evaluate(
                listOf(WorldBookFixture.book(entries = entries)),
                WorldBookFixture.messages("hit"),
                seed = seed,
            )
            return result.activatedUids()
        }

        repeat(20) { seed ->
            assertEquals(listOf("book:heavy"), run(heavyFirst = true, seed = seed.toLong()))
            assertEquals(listOf("book:heavy"), run(heavyFirst = false, seed = seed.toLong()))
        }
    }

    @Test
    fun `group scoring keeps the best scoring entry`() {
        val source = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("two", keys = listOf("alpha", "beta"), group = "g", order = 10),
                WorldBookFixture.entry("one", keys = listOf("alpha"), group = "g", order = 20),
            ),
        )
        val result = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("alpha beta"),
            settings = WorldBookFixture.settings(useGroupScoring = true),
        )
        assertEquals(listOf("book:two"), result.activatedUids())
        assertEquals("group-score-loser:g", result.decisionFor("one").reason)
    }

    @Test
    fun `group scoring can be enabled per entry`() {
        val source = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("two", keys = listOf("alpha", "beta"), group = "g", useGroupScoring = true, order = 10),
                WorldBookFixture.entry("one", keys = listOf("alpha"), group = "g", useGroupScoring = true, order = 20),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(source), WorldBookFixture.messages("alpha beta"))
        assertEquals(listOf("book:two"), result.activatedUids())
    }

    @Test
    fun `sticky member wins its inclusion group`() {
        val source = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("s", keys = listOf("hit"), group = "g", sticky = 5, order = 10),
                WorldBookFixture.entry("o", keys = listOf("other"), group = "g", order = 20),
            ),
        )
        val first = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit"),
            messageCount = 10,
            budgetUnits = 10_000,
        )
        assertEquals(listOf("book:s"), first.activatedUids())
        assertTrue(first.nextEffects.any { it.type == WorldBookTimedEffectType.STICKY })

        val second = WorldBookFixture.evaluate(
            listOf(source),
            WorldBookFixture.messages("hit other"),
            messageCount = 11,
            previousEffects = first.nextEffects,
        )
        assertEquals(listOf("book:s"), second.activatedUids())
        assertEquals("group-sticky-loser:g", second.decisionFor("o").reason)
    }

    @Test
    fun `multi group membership is split on commas`() {
        val source = WorldBookFixture.book(
            entries = listOf(
                WorldBookFixture.entry("a", keys = listOf("hit"), group = "g1, g2", groupOverride = true, order = 10),
                WorldBookFixture.entry("b", keys = listOf("hit"), group = "g2", groupOverride = true, order = 20),
            ),
        )
        val result = WorldBookFixture.evaluate(listOf(source), WorldBookFixture.messages("hit"))
        assertEquals(listOf("book:b"), result.activatedUids())
        assertEquals(WorldBookDecisionStatus.DENIED, result.decisionFor("a").status)
    }
}
