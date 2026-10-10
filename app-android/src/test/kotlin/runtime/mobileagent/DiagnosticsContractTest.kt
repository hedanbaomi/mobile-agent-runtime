// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import runtime.mobileagent.diagnostics.RollingDiagnosticLogStore

/** Literal byte contract from docs/DIAGNOSTICS.md; independent of cap arithmetic. */
class DiagnosticsContractTest {
    @Test
    fun documentedFileEventAndExportBudgetsRemainStable() {
        assertEquals(8_388_608, RollingDiagnosticLogStore.MAX_CURRENT_BYTES)
        assertEquals(8_388_608, RollingDiagnosticLogStore.MAX_PREVIOUS_BYTES)
        assertEquals(32_768, RollingDiagnosticLogStore.MAX_LAST_CRASH_BYTES)
        assertEquals(65_536, RollingDiagnosticLogStore.MAX_EVENT_BYTES)
        assertEquals(20_971_520, RollingDiagnosticLogStore.MAX_EXPORT_BYTES)
    }
}
