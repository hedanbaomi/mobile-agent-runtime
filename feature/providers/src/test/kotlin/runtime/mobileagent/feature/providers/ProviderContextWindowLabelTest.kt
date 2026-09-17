// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import runtime.mobileagent.domain.ContextLimitMode
import runtime.mobileagent.domain.OutputLimitMode
import runtime.mobileagent.domain.contextWindowTarget
import runtime.mobileagent.domain.contextWindowTargetKey

/**
 * The provider list must state what the runtime will actually rely on.  A
 * recorded window for another endpoint or model is stale, an unknown window
 * stays unknown, and the legacy numeric column is never shown as if it were the
 * effective automatic window.
 */
class ProviderContextWindowLabelTest {
    private val current = contextWindowTarget("provider", "https://api.example/v1", "model-x")

    private fun model(
        mode: ContextLimitMode = ContextLimitMode.AUTO,
        limit: Int? = 32_768,
        windowValue: String = "",
        recordedFor: String = "",
        recorded: Boolean = false,
    ) = ProviderModelUi(
        id = "m", modelId = "model-x",
        contextLimit = limit,
        contextLimitMode = mode.name,
        contextWindowValue = windowValue,
        contextWindowTarget = recordedFor,
        contextWindowRecorded = recorded,
    )

    @Test
    fun automaticWithoutARecordedWindowIsShownAsUnknown() {
        val label = contextWindowLabel(model(), current, zh = false)
        assertTrue(label.contains("unknown"), label)
        assertTrue(model().contextLimit == 32_768, "the legacy numeric column must not be presented as effective")
    }

    @Test
    fun automaticWithAMatchingRecordedWindowIsShownAsEffectiveForThatTargetOnly() {
        val label = contextWindowLabel(
            model(windowValue = "131072", recordedFor = current, recorded = true), current, zh = false,
        )
        assertTrue(label.contains("effective 131072"), label)
        assertTrue(label.contains("this target only"), label)
    }

    @Test
    fun automaticWithAChangedTargetIsShownAsStaleAndNotAsTheRecordedNumber() {
        val label = contextWindowLabel(
            model(
                windowValue = "131072",
                recordedFor = contextWindowTargetKey("provider", 1, "https://api.example/v1", "model-y"),
                recorded = true,
            ),
            current, zh = false,
        )
        assertTrue(label.contains("stale"), label)
        assertTrue(label.contains("current unknown"), label)

        // The same recorded window for the *same* provider/endpoint/model is
        // accepted even though the row still carries the legacy revision key.
        val sameTarget = contextWindowLabel(
            model(
                windowValue = "131072",
                recordedFor = contextWindowTargetKey("provider", 7, "https://api.example/v1/", "model-x"),
                recorded = true,
            ),
            current, zh = false,
        )
        assertTrue(sameTarget.contains("effective 131072"), sameTarget)
    }

    @Test
    fun manualModeReportsTheUserNumberAndNotTheRecordedWindow() {
        val label = contextWindowLabel(
            model(mode = ContextLimitMode.MANUAL, limit = 65_536, windowValue = "131072", recordedFor = current, recorded = true),
            current, zh = false,
        )
        assertTrue(label.contains("manual 65536"), label)
    }

    @Test
    fun outputLabelNeverClaimsProviderDefaultForAManualCap() {
        assertEquals(
            "output follow provider",
            outputLimitLabel(ProviderModelUi(id = "m", modelId = "x", outputLimitMode = OutputLimitMode.AUTO.name), false),
        )
        assertEquals(
            "output manual 4096",
            outputLimitLabel(ProviderModelUi(id = "m", modelId = "x", outputLimitMode = OutputLimitMode.MANUAL.name, outputLimit = 4096), false),
        )
    }
}
