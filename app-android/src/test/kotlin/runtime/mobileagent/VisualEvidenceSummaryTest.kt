// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The degradation notice is host copy supplied by the caller; the runtime warning inside the tool
 * result payload stays verbatim data and is never translated. Bilingual wording is asserted by
 * `HostChatTextDeviceTest` against the real resources.
 */
class VisualEvidenceSummaryTest {
    private val template = "DEGRADED: %1\$s"
    private val fallback = "SYNTHETIC_FALLBACK_REASON"

    @Test fun runtimeBudgetReasonRemainsVisible() {
        val summary = toolVisualEvidenceSummary(
            """{"textDegradation":true,"warning":"7 images: RUN_IMAGE_BUDGET_EXCEEDED"}""",
            template,
            fallback,
        )
        assertEquals("DEGRADED: 7 images: RUN_IMAGE_BUDGET_EXCEEDED", summary)
        assertTrue(requireNotNull(summary).contains("RUN_IMAGE_BUDGET_EXCEEDED"))
    }

    @Test fun legacyResultWithoutWarningRetainsDisclosure() {
        val summary = toolVisualEvidenceSummary("""{"textDegradation":true}""", template, fallback)
        assertEquals("DEGRADED: $fallback", summary)
    }

    @Test fun warningPercentAndBlankAreHandledAsData() {
        assertEquals(
            "DEGRADED: 50% of images dropped",
            toolVisualEvidenceSummary("""{"textDegradation":true,"warning":"50% of images dropped"}""", template, fallback),
        )
        assertEquals(
            "DEGRADED: $fallback",
            toolVisualEvidenceSummary("""{"textDegradation":true,"warning":"   "}""", template, fallback),
        )
    }

    @Test fun normalOrMalformedToolResultsHaveNoDegradationLabel() {
        listOf("{}", "[]", "broken", """{"textDegradation":false,"warning":"producer warning"}""")
            .forEach { assertNull(toolVisualEvidenceSummary(it, template, fallback)) }
    }
}