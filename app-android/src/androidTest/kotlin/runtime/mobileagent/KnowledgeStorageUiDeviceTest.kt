// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.knowledge.KnowledgeActions
import runtime.mobileagent.feature.knowledge.KnowledgeScreen
import runtime.mobileagent.feature.knowledge.KnowledgeUiState

@RunWith(AndroidJUnit4::class)
class KnowledgeStorageUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun usageQuotaValidationAndReclaimAreReachable() {
        val gib = 1L shl 30
        val state = mutableStateOf(KnowledgeUiState(language = "en", storageUsedBytes = 3 * gib,
            storageQuotaBytes = 2 * gib, foreignKeyIssueCount = 1))
        var reclaimed = 0
        var saved = 0L
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { KnowledgeScreen(state.value, KnowledgeActions(
                    onCollectStorage = { reclaimed++ }, onConfigureStorageQuota = { saved = it })) }
            }
        }
        compose.onNodeWithText("Local storage 3072.0 MiB / 2048.0 MiB").assertIsDisplayed()
        compose.onNodeWithText("Over the limit. Reading, deletion and raising the limit remain available.").assertIsDisplayed()
        compose.onNodeWithText("Reclaim deleted content").performClick()
        compose.runOnIdle { assertEquals(1, reclaimed) }
        compose.onNodeWithText("Storage budget").performClick()
        compose.onNodeWithText("GiB (1–64)").performTextReplacement("65")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("GiB (1–64)").performTextReplacement("4")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(4 * gib, saved); state.value = state.value.copy(loading = true) }
        compose.onNodeWithText("Reclaim deleted content").assertIsNotEnabled()
    }
}
