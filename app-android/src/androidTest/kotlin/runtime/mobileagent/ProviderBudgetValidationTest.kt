// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.providers.ProviderDraft as UiProviderDraft
import runtime.mobileagent.feature.providers.parsePositiveProviderBudget
import runtime.mobileagent.feature.providers.providerBudgetError

/** Regression coverage for editable Provider budget fields and save-time validation. */
@RunWith(AndroidJUnit4::class)
class ProviderBudgetValidationTest {
    @Test
    fun clearingAFieldPreservesEmptyDraftAndShowsAnError() {
        val uiDraft = UiProviderDraft().copy(contextLimit = "")
        val vmDraft = ProviderDraft(
            providerId = "provider",
            name = "name",
            baseUrl = "https://example.invalid",
            modelId = "model",
            contextLimit = "",
        )

        assertEquals("", uiDraft.contextLimit)
        assertEquals("", vmDraft.contextLimit)
        assertNull(parsePositiveProviderBudget(uiDraft.contextLimit))
        assertTrue(providerBudgetError(uiDraft.contextLimit, uiDraft.outputLimit, zh = true)!!.contains("正整数"))
    }

    @Test
    fun invalidAndOutOfOrderBudgetsAreRejectedWithoutFallback() {
        assertNull(parsePositiveProviderBudget("0"))
        assertNull(parsePositiveProviderBudget("-1"))
        assertNull(parsePositiveProviderBudget("1.5"))
        assertNull(parsePositiveProviderBudget("2147483648"))
        assertTrue(providerBudgetError("1024", "2048", zh = false)!!.contains("cannot exceed"))
    }

    @Test
    fun validPositiveBudgetsAreAccepted() {
        assertEquals(1024, parsePositiveProviderBudget(" 1024 "))
        assertNull(providerBudgetError("4096", "1024", zh = false))
    }
}
