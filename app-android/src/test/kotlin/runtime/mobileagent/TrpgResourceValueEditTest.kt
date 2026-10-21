// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import runtime.mobileagent.ui.TRPG_RESOURCE_VALUE_LIMIT
import runtime.mobileagent.ui.resolveTrpgResourceValue

/** Pure rules for the state-sheet "tap value to edit" dialog (no UI/device required). */
class TrpgResourceValueEditTest {
    @Test fun absoluteValuesUseTheDomainBoundAndKeepValuesAboveMaximum() {
        assertEquals(50, resolveTrpgResourceValue(15, "50", asDelta = false))
        assertEquals(-7, resolveTrpgResourceValue(15, "-7", asDelta = false))
        assertEquals(TRPG_RESOURCE_VALUE_LIMIT, resolveTrpgResourceValue(0, "1000000", asDelta = false))
        assertEquals(-TRPG_RESOURCE_VALUE_LIMIT, resolveTrpgResourceValue(0, "-1000000", asDelta = false))
        assertNull(resolveTrpgResourceValue(0, "1000001", asDelta = false))
        assertNull(resolveTrpgResourceValue(0, "-1000001", asDelta = false))
        // Blank, whitespace-only, decimal and non-numeric input never become a default value.
        assertNull(resolveTrpgResourceValue(0, "", asDelta = false))
        assertNull(resolveTrpgResourceValue(0, "   ", asDelta = false))
        assertNull(resolveTrpgResourceValue(0, "1.5", asDelta = false))
        assertNull(resolveTrpgResourceValue(0, "abc", asDelta = false))
    }

    @Test fun deltasRejectOverflowWithoutSilentlyChangingTheRequestedAmount() {
        assertEquals(16, resolveTrpgResourceValue(15, "+1", asDelta = true))
        assertEquals(21, resolveTrpgResourceValue(15, "6", asDelta = true))
        assertEquals(15, resolveTrpgResourceValue(15, "0", asDelta = true))
        assertNull(resolveTrpgResourceValue(999_999, "5", asDelta = true))
        assertNull(resolveTrpgResourceValue(-999_999, "-5", asDelta = true))
        assertEquals(1_000_000, resolveTrpgResourceValue(-1_000_000, "2000000", asDelta = true))
        assertNull(resolveTrpgResourceValue(1, Long.MAX_VALUE.toString(), asDelta = true))
        // The resulting value, rather than a display maximum, defines the domain bound.
        assertNull(resolveTrpgResourceValue(0, "1000001", asDelta = true))
        assertNull(resolveTrpgResourceValue(0, "-1000001", asDelta = true))
        assertNull(resolveTrpgResourceValue(0, "x", asDelta = true))
    }

    @Test fun overMaximumResourceStaysEditableWithoutTouchingItsMaximum() {
        // Current above maximum is legal (temporary HP / custom rules); editing the current value
        // must not rewrite or clamp against the display maximum.
        assertEquals(30, resolveTrpgResourceValue(25, "30", asDelta = false))
        assertEquals(35, resolveTrpgResourceValue(25, "10", asDelta = true))
    }
}
