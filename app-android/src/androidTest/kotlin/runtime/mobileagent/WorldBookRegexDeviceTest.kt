// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import runtime.mobileagent.agent.worldbook.WorldBookEngine
import runtime.mobileagent.domain.worldbook.*

/** Small safe inputs prove the same interruptible regex path executes on Android. */
class WorldBookRegexDeviceTest {
    @Test fun aTinyBudgetInterruptsEvenASimplePatternOnAndroid() {
        val result = evaluate("/abc/", "abc", WorldBookSettings(regexStepBudget = 1, includeNames = false))
        assertTrue(result.activated.isEmpty())
        assertTrue(result.warnings.toString(), result.warnings.any { it.contains("budget") })
        assertEquals(1, evaluate("/abc/", "abc").activated.size)
    }

    @Test fun asciiWholeWordAndRegexClassesAreIdenticalAcrossJvmAndAndroid() {
        assertEquals(1, evaluate("猫", "一只猫咪", WorldBookSettings(matchWholeWords = true, includeNames = false)).activated.size)
        assertTrue(evaluate("/\\w+/", "猫").activated.isEmpty())
        assertTrue(evaluate("/\\d+/", "١").activated.isEmpty())
        assertEquals(1, evaluate("/\\w+/", "abc").activated.size)
        assertEquals(1, evaluate("/\\d+/", "123").activated.size)
        for (pattern in listOf("/(a)\\1/", "/a(?=b)/", "/abc/u", "/abc/y")) {
            val result = evaluate(pattern, "aab abc")
            assertTrue(pattern, result.activated.isEmpty())
            assertTrue(pattern + result.warnings, result.warnings.isNotEmpty())
        }
    }

    @Test fun importedSafetyOverridesCannotBePublishedToTheDatabase() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MobileAgentApp
        app.ensureHostInitialized()
        val repository = app.container.worldBooks
        val before = repository.list().size
        for (settings in listOf(WorldBookSettings(regexStepBudget = Long.MAX_VALUE), WorldBookSettings(maxMessageScanChars = -1))) {
            try {
                repository.save(WorldBookDocument("invalid fixture", listOf(WorldBookEntry("0", listOf("/abc/"), "body")), settings = settings))
                fail("Unsafe native settings must not publish")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(before, repository.list().size)
    }

    private fun evaluate(key: String, text: String, settings: WorldBookSettings = WorldBookSettings(includeNames = false)): WorldBookEvaluation =
        WorldBookEngine.evaluate(WorldBookScanRequest(
            listOf(WorldBookSource("book", 1, WorldBookDocument("fixture", listOf(WorldBookEntry("0", listOf(key), "body"))))),
            listOf(WorldBookScanMessage(text)), settings = settings,
        ), 1000, 1000) { it.length }
}
