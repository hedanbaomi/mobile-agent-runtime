// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reservation -> settlement for an explicitly budgeted model invocation.
 *
 * The reviewer scenario: ceiling 6000, a first call is admitted with a smaller
 * reservation, the provider then reports a *larger* actual spend.  The paid
 * result is kept, but the next invocation must be refused.
 */
class ModelInvocationBudgetTest {
    @Test
    fun aLargerActualIsSettledAndBlocksTheNextInvocation() {
        val ceiling = 6_000
        val reservation = 2_314
        var ledger = 0L
        // First admission succeeds.
        assertTrue(admitsModelInvocation(storedTokens = 0, ledger = ledger, reservation = reservation, ceiling = ceiling))
        ledger += reservation
        // Provider reports 100 input + 7000 output = 7100 actual.
        ledger = settleReservedTokens(ledger, reservation, 7_100)
        assertEquals(7_100L, ledger)
        // The second invocation must not dispatch.
        assertFalse(admitsModelInvocation(storedTokens = 0, ledger = ledger, reservation = reservation, ceiling = ceiling))
    }

    @Test
    fun aSmallerActualReleasesTheDifference() {
        val ledger = settleReservedTokens(10_000L, reservation = 2_314, actualTokens = 1_000)
        assertEquals(8_686L, ledger)
    }

    @Test
    fun unknownUsageKeepsTheReservationAndNeverInventsConsumption() {
        assertEquals(2_314L, settleReservedTokens(2_314L, reservation = 2_314, actualTokens = null))
        assertFalse(admitsModelInvocation(storedTokens = 5_000, ledger = 2_314, reservation = 1_000, ceiling = 6_000))
    }

    @Test
    fun anExhaustedCeilingIsNeverAdmitted() {
        assertFalse(admitsModelInvocation(storedTokens = 6_000, ledger = 0, reservation = 1, ceiling = 6_000))
        assertFalse(admitsModelInvocation(storedTokens = 0, ledger = 0, reservation = 1, ceiling = 0))
        assertFalse(admitsModelInvocation(storedTokens = 0, ledger = 0, reservation = 0, ceiling = 6_000))
    }
}