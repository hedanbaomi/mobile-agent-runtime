// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisualEvidenceSummaryTest {
    @Test fun runtimeBudgetReasonRemainsVisible() {
        val summary = toolVisualEvidenceSummary("""{"textDegradation":true,"warning":"7 images: RUN_IMAGE_BUDGET_EXCEEDED"}""")
        assertTrue(requireNotNull(summary).contains("RUN_IMAGE_BUDGET_EXCEEDED"))
        assertTrue(summary.contains("7 images"))
    }

    @Test fun legacyResultWithoutWarningRetainsDisclosure() {
        assertTrue(requireNotNull(toolVisualEvidenceSummary("""{"textDegradation":true}""")).contains("未向模型发送原始图片"))
    }

    @Test fun normalOrMalformedToolResultsHaveNoDegradationLabel() {
        listOf("{}", "[]", "broken", """{"textDegradation":false,"warning":"producer warning"}""")
            .forEach { assertNull(toolVisualEvidenceSummary(it)) }
    }
}
