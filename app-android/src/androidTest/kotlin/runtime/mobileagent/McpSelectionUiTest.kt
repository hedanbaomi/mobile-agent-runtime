// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.McpActions
import runtime.mobileagent.ui.McpSettingsScreen

@RunWith(AndroidJUnit4::class)
class McpSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun checkboxSemanticsTrackViewModelSelectionInBothDirections() {
        val app = ApplicationProvider.getApplicationContext<MobileAgentApp>()
        app.ensureHostInitialized()
        val vm = McpViewModel(app, SavedStateHandle())
        compose.runOnUiThread {
            vm.state.value = McpUiState(
                discovered = true,
                tools = listOf(McpUiTool("test.echo", "", "{}", "a".repeat(64), "mcp:test", false, false)),
            )
        }
        compose.setContent {
            MaterialTheme {
                McpSettingsScreen(vm.state.value, McpActions(onToggleTool = vm::toggleTool))
            }
        }
        val checkbox = compose.onNodeWithTag("mcp.tool.test.echo")
        checkbox.performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(setOf("test.echo"), vm.state.value.selectedToolNames) }
        checkbox.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(emptySet<String>(), vm.state.value.selectedToolNames) }
    }
}
