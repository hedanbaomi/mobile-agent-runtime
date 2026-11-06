// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnitBudgetTest {
    @Test
    fun utf8UnitsChargeMalformedSurrogatesInsteadOfUnderstating() {
        assertEquals(0L, conservativeUtf8Units(""))
        assertEquals(3L, conservativeUtf8Units("abc"))
        assertEquals(2L, conservativeUtf8Units("\u00e9"))
        assertEquals(6L, conservativeUtf8Units("\u4e2d\u6587"))
        assertEquals(4L, conservativeUtf8Units("\uD83D\uDE00"))
        assertEquals(3L, conservativeUtf8Units("\uD83D"), "unpaired high surrogate")
        assertEquals(3L, conservativeUtf8Units("\uDE00"), "unpaired low surrogate")
    }

    @Test
    fun saturatingArithmeticClampsInsteadOfWrapping() {
        assertEquals(Long.MAX_VALUE, saturatingAddUnits(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, saturatingAddUnits(Long.MAX_VALUE - 1L, 2L))
        assertEquals(Long.MIN_VALUE, saturatingAddUnits(Long.MIN_VALUE, -1L))
        assertEquals(
            Long.MAX_VALUE,
            saturatingAddUnits(conservativeUtf8Units("x".repeat(1_000)), Long.MAX_VALUE),
        )
        assertEquals(7L, saturatingAddUnits(3L, 4L))
        assertEquals(0L, saturatingMultiplyUnits(0L, Long.MAX_VALUE))
        assertEquals(0L, saturatingMultiplyUnits(Long.MAX_VALUE, 0L))
        assertEquals(Long.MAX_VALUE, saturatingMultiplyUnits(Long.MAX_VALUE / 2L + 1L, 2L))
        assertEquals(Long.MAX_VALUE, saturatingMultiplyUnits(-1L, 2L))
        assertEquals(Long.MAX_VALUE, saturatingMultiplyUnits(2L, -1L))
        assertEquals(12L, saturatingMultiplyUnits(3L, 4L))
    }
}
