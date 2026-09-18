// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The install approval and the Run fee authorization are separate decisions: an
 * approved `model.invoke` scope alone never authorizes spending inside a Run, and
 * the Run ceiling is additionally clamped to what the approved scope allows.
 */
class ModelInvokeRunAuthorizationTest {
    @Test
    fun anApprovedScopeAloneNeverAuthorizesARun() {
        assertNull(modelInvokeRunTokens(null, listOf(4_096)))
        assertNull(modelInvokeRunTokens(0, listOf(4_096)))
        assertNull(modelInvokeRunTokens(-1, listOf(4_096)))
    }

    @Test
    fun aUserNumberWithoutAnApprovedScopeStaysRefused() {
        assertNull(modelInvokeRunTokens(65_536, emptyList()))
        assertNull(modelInvokeRunTokens(65_536, listOf(0)))
        assertNull(modelInvokeRunTokens(65_536, listOf(-5)))
    }

    @Test
    fun theRunCeilingIsTheSmallerOfTheUserNumberAndTheApprovedScopes() {
        assertEquals(4_096, modelInvokeRunTokens(65_536, listOf(4_096)))
        assertEquals(65_536, modelInvokeRunTokens(65_536, listOf(200_000)))
        // Several bound Skills: the tightest approved ceiling wins.
        assertEquals(2_048, modelInvokeRunTokens(65_536, listOf(8_192, 2_048, 16_384)))
    }

    @Test
    fun thePersistedRunBudgetCarriesTheAuthorizationOnlyWhenItExists() {
        val without = Json.parseToJsonElement(
            runBudgetJson(maxModelRounds = 8, maxModelRoundsPerSegment = 4, maxCompactionsPerRun = 2),
        ).jsonObject
        assertFalse("maxModelTokens" in without.keys)
        assertEquals(8, without["maxModelRounds"]!!.jsonPrimitive.int)
        assertEquals(20, without["maxToolCalls"]!!.jsonPrimitive.int)
        assertEquals(180_000, without["maxRuntimeMs"]!!.jsonPrimitive.int)
        assertEquals(4, without["maxModelRoundsPerSegment"]!!.jsonPrimitive.int)
        assertEquals(2, without["maxCompactionsPerRun"]!!.jsonPrimitive.int)

        val authorized = Json.parseToJsonElement(
            runBudgetJson(maxModelRounds = 8, maxModelRoundsPerSegment = 4, maxCompactionsPerRun = 2, modelInvokeTokens = 4_096),
        ).jsonObject
        assertEquals(4_096, authorized["maxModelTokens"]!!.jsonPrimitive.int)

        assertThrows<IllegalArgumentException> {
            runBudgetJson(maxModelRounds = 0, maxModelRoundsPerSegment = 4, maxCompactionsPerRun = 2)
        }
        assertThrows<IllegalArgumentException> {
            runBudgetJson(maxModelRounds = 8, maxModelRoundsPerSegment = 4, maxCompactionsPerRun = 2, modelInvokeTokens = 0)
        }
    }

    @Test
    fun thePolicyCarriesTheUserNumberAndRejectsNonsense() {
        val parsed = AgentContextPolicy.fromJson("{\"pythonModelRunTokens\":32768}")
        assertEquals(32_768, parsed.pythonModelRunTokens)
        assertNull(AgentContextPolicy.fromJson("{}").pythonModelRunTokens)
        assertThrows<IllegalArgumentException> { AgentContextPolicy(pythonModelRunTokens = 0) }
        assertThrows<IllegalArgumentException> { AgentContextPolicy.fromJson("{\"pythonModelRunTokens\":\"big\"}") }
    }

    @Test
    fun theAdmissionRuleStaysAFailClosedComparison() {
        assertTrue(admitsModelInvocation(storedTokens = 0, ledger = 0, reservation = 100, ceiling = 100))
        assertFalse(admitsModelInvocation(storedTokens = 0, ledger = 0, reservation = 101, ceiling = 100))
        assertFalse(admitsModelInvocation(storedTokens = 0, ledger = 0, reservation = 100, ceiling = 0))
        assertFalse(admitsModelInvocation(storedTokens = 0, ledger = 0, reservation = 0, ceiling = 100))
    }
}
