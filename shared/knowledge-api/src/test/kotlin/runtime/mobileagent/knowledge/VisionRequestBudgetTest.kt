// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.knowledge

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisionRequestBudgetTest {
    @Test fun unknownWindowUsesExplicitConservativeDefault() {
        val budget = VisionRequestBudget()
        assertEquals(VisionRequestBudget.UNKNOWN_WINDOW_TOKENS, budget.planningWindowTokens)
        assertEquals(32_768L, budget.planningWindowTokens)
        assertEquals(28_672L, budget.effectiveInputUnits)
    }

    @Test fun knownWindowIsMinOfAvailableAndWindowMinusOutput() {
        assertEquals(80_000L,
            VisionRequestBudget(contextWindowTokens = 100_000, outputReserveTokens = 20_000).effectiveInputUnits)
        assertEquals(50_000L,
            VisionRequestBudget(contextWindowTokens = 100_000, outputReserveTokens = 20_000,
                availableInputUnits = 50_000).effectiveInputUnits)
        assertEquals(7L,
            VisionRequestBudget(contextWindowTokens = 1_000, outputReserveTokens = 0,
                availableInputUnits = 7).effectiveInputUnits)
    }

    @Test fun outputReserveAtOrAboveWindowYieldsNoAdmission() {
        assertEquals(0L, VisionRequestBudget(contextWindowTokens = 4_096, outputReserveTokens = 4_096).effectiveInputUnits)
        assertEquals(0L, VisionRequestBudget(contextWindowTokens = 4_096, outputReserveTokens = 8_000).effectiveInputUnits)
    }

    @Test fun requestUnitsCountImageOverheadTextAndExtraHints() {
        val budget = VisionRequestBudget(imageInputUnits = 100, requestOverheadUnits = 10)
        assertEquals(215L, budget.requestUnits("abc", "de", imageCount = 2))
        assertEquals(110L, budget.requestUnits("", null, imageCount = 1))
        assertEquals(10L, budget.requestUnits("", null, imageCount = 0))
    }

    @Test fun unitsAreConservativeUtf8BytesNotTokens() {
        val budget = VisionRequestBudget(imageInputUnits = 0, requestOverheadUnits = 0)
        assertEquals(3L, budget.requestUnits("漢"))
        assertEquals(4L, budget.requestUnits("😀"))
        assertEquals(2L, budget.requestUnits("é"))
        assertEquals(1L, budget.requestUnits("a"))
        assertTrue(budget.fits("漢漢漢", imageCount = 0))
        assertFalse(budget.fits("漢".repeat(10_000), imageCount = 0))
    }

    @Test fun arithmeticSaturatesAtLongMaxInsteadOfWrapping() {
        val huge = VisionRequestBudget(requestOverheadUnits = Long.MAX_VALUE - 1, imageInputUnits = Long.MAX_VALUE)
        val total = huge.requestUnits("abc", "de", imageCount = 2)
        assertTrue(total >= 0L)
        assertEquals(Long.MAX_VALUE, total)
        val wide = VisionRequestBudget(contextWindowTokens = Int.MAX_VALUE, outputReserveTokens = 0,
            availableInputUnits = Long.MAX_VALUE)
        assertTrue(wide.effectiveInputUnits > 0L)
    }

    @Test fun fitsComparesAgainstEffectiveCapacity() {
        val budget = VisionRequestBudget(contextWindowTokens = 10_000, outputReserveTokens = 4_096,
            imageInputUnits = 4_096, requestOverheadUnits = 1_024)
        assertEquals(5_904L, budget.effectiveInputUnits)
        assertTrue(budget.fits("", null, imageCount = 1))
        assertFalse(budget.fits("z".repeat(1_000), null, imageCount = 1))
    }

    @Test fun negativeAndZeroValuesAreRejectedOrBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            VisionRequestBudget(contextWindowTokens = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionRequestBudget(availableInputUnits = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionRequestBudget(outputReserveTokens = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionRequestBudget(imageInputUnits = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionRequestBudget(requestOverheadUnits = -1)
        }
    }
}
