// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.chat.ChatScreen
import runtime.mobileagent.feature.chat.ChatWorkspaceAccessUi
import runtime.mobileagent.feature.chat.ChatThreadWorkspaceState
import runtime.mobileagent.feature.chat.ConversationScreen
import runtime.mobileagent.feature.chat.ChatUiState
import runtime.mobileagent.feature.settings.SettingsDiagnosticsFeedback
import runtime.mobileagent.feature.settings.SettingsScreen
import runtime.mobileagent.feature.settings.SettingsUiState

@RunWith(AndroidJUnit4::class)
class LocaleReactiveStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun unconfiguredWorkspaceUpdatesLanguageWithoutRebuildingWorkspaceState() {
        val state = mutableStateOf(ChatUiState(language = "en-US"))
        compose.setContent { MaterialTheme { ConversationScreen(state.value) } }
        compose.onNodeWithText("No agent selected · No workspace", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("未配置工作区", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnUiThread { state.value = state.value.copy(language = "zh-CN") }
        compose.onNodeWithText("未选择智能体 · 未配置工作区", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun openUnboundWorkspaceSheetResolvesEveryStatusAfterLanguageChange() {
        val state = mutableStateOf(ChatUiState(language = "en-US"))
        compose.setContent {
            MaterialTheme { Box(Modifier.width(320.dp).height(640.dp)) { ChatScreen(state.value) } }
        }
        compose.onNodeWithTag("chat.workspace.open").performClick()
        listOf("Current workspace: No workspace", "Permission: This conversation has no workspace access",
            "System access: System access is not enabled", "This conversation has no bound workspace.").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.runOnUiThread { state.value = state.value.copy(language = "zh-CN") }
        listOf("当前工作区：未配置工作区", "权限状态：尚未授权此会话", "系统增强访问：未启用系统增强访问",
            "当前会话未绑定工作区。").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.onAllNodesWithText("Current workspace: No workspace", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun boundWorkspaceSheetKeepsRealTitleWhileLocalizingStatus() {
        val state = mutableStateOf(ChatUiState(language = "en-US", workspaceAccess = ChatWorkspaceAccessUi(
            workspaceSummary = "研究资料", threadWorkspaceState = ChatThreadWorkspaceState.BOUND,
        )))
        compose.setContent {
            MaterialTheme { Box(Modifier.width(320.dp).height(640.dp)) { ChatScreen(state.value) } }
        }
        compose.onNodeWithTag("chat.workspace.open").performClick()
        compose.onNodeWithText("Current workspace: 研究资料", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Permission: Bound", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The conversation workspace is fixed; changes to the Agent default do not change this conversation.", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { state.value = state.value.copy(language = "zh-CN") }
        compose.onNodeWithText("当前工作区：研究资料", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("权限状态：已绑定", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("会话工作区已固定；Agent 默认值变化不会改动此会话。", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun existingDiagnosticsFeedbackUpdatesWhenLanguageChanges() {
        val state = mutableStateOf(SettingsUiState(language = "en-US", diagnosticsEnabled = true,
            diagnosticsFeedback = SettingsDiagnosticsFeedback.ENABLED))
        compose.setContent { MaterialTheme { SettingsScreen(state.value) } }
        compose.onNodeWithTag("settings.diagnostics.feedback", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Diagnostics enabled.", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnUiThread { state.value = state.value.copy(language = "zh-CN") }
        compose.onNodeWithText("诊断记录已开启。", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Diagnostics enabled.", useUnmergedTree = true).assertCountEquals(0)
    }
}
