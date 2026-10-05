// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.settings.SettingsActions
import runtime.mobileagent.feature.settings.SettingsScreen
import runtime.mobileagent.feature.settings.SettingsUiState

@RunWith(AndroidJUnit4::class)
class DiagnosticsLogLevelUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun userCanChooseDebugAndReturnToInfoWithoutEnablingDiagnostics() {
        val state = mutableStateOf(SettingsUiState(language = "zh-CN"))
        var enableRequests = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(state.value, SettingsActions(
                    onDiagnosticsEnabled = { enableRequests++ },
                    onDiagnosticsLogLevel = { state.value = state.value.copy(diagnosticsLogLevel = it) },
                ))
            }
        }
        compose.onNodeWithTag("settings.diagnostics.level.info").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("settings.diagnostics.level.debug").performClick()
        compose.onNodeWithTag("settings.diagnostics.level.debug").assertIsSelected()
        compose.onNodeWithText("DEBUG 记录详细进度和视觉处理文本；凭据会过滤，分享前检查敏感内容。").assertExists()
        compose.runOnIdle {
            assertFalse(state.value.diagnosticsEnabled)
            assertEquals(0, enableRequests)
        }
        compose.runOnUiThread { state.value = state.value.copy(language = "en-US") }
        compose.onNodeWithText("Log level").assertExists()
        compose.onNodeWithTag("settings.diagnostics.level.info").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("INFO records stages, results and errors, without Vision bodies.").assertExists()
    }
}
