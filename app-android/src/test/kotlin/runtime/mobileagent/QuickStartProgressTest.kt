// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuickStartProgressTest {
    @Test fun resumesFromSavedFactsWithoutTreatingAConfiguredAgentAsASuccessfulRun() {
        assertEquals(QuickStartStep.MODEL, quickStartStep(false, false, false))
        assertEquals(QuickStartStep.AGENT, quickStartStep(true, false, false))
        assertEquals(QuickStartStep.MESSAGE, quickStartStep(true, true, false))
        assertEquals(QuickStartStep.COMPLETE, quickStartStep(true, true, true))
        assertEquals(QuickStartStep.MODEL, quickStartStep(false, true, false))
    }
}
