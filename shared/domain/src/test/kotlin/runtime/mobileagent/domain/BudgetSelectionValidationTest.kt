// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The editor and the save path share this single decision function. */
class BudgetSelectionValidationTest {
    @Test
    fun manualContextRequiresAPositiveNumber() {
        assertEquals(
            BudgetValidationError.MANUAL_CONTEXT_REQUIRED,
            validateBudgetSelection(null, 4096, ContextLimitMode.MANUAL, OutputLimitMode.MANUAL, null, false),
        )
    }

    @Test
    fun autoContextWithoutAKnownWindowIsAllowed() {
        assertNull(validateBudgetSelection(null, null, ContextLimitMode.AUTO, OutputLimitMode.AUTO, null, false))
    }

    @Test
    fun autoContextWithAFilledButInvalidWindowIsRejected() {
        assertEquals(
            BudgetValidationError.DECLARED_WINDOW_INVALID,
            validateBudgetSelection(null, null, ContextLimitMode.AUTO, OutputLimitMode.AUTO, null, true),
        )
    }

    /** The hidden legacy number must not block an AUTO save. */
    @Test
    fun hiddenLegacyContextNeverDecidesAnAutoSave() {
        assertNull(validateBudgetSelection(32_768, 65_536, ContextLimitMode.AUTO, OutputLimitMode.MANUAL, 131_072, true))
    }

    @Test
    fun manualOutputIsComparedOnlyAgainstAnEffectiveWindow() {
        // AUTO + unknown window: no comparison against the hidden value.
        assertNull(validateBudgetSelection(1, 65_536, ContextLimitMode.AUTO, OutputLimitMode.MANUAL, null, false))
        // AUTO + known window: compared against the declared window.
        assertEquals(
            BudgetValidationError.OUTPUT_EXCEEDS_WINDOW,
            validateBudgetSelection(1, 65_536, ContextLimitMode.AUTO, OutputLimitMode.MANUAL, 32_768, true),
        )
        // MANUAL context: compared against the manual window.
        assertEquals(
            BudgetValidationError.OUTPUT_EXCEEDS_WINDOW,
            validateBudgetSelection(32_768, 65_536, ContextLimitMode.MANUAL, OutputLimitMode.MANUAL, null, false),
        )
    }

    @Test
    fun manualOutputStillRequiresAPositiveNumber() {
        assertEquals(
            BudgetValidationError.MANUAL_OUTPUT_REQUIRED,
            validateBudgetSelection(32_768, null, ContextLimitMode.MANUAL, OutputLimitMode.MANUAL, null, false),
        )
    }
}