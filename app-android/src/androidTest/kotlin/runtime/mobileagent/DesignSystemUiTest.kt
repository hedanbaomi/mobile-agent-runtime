// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.ui.AgentCard
import runtime.mobileagent.ui.AgentDesignDefaults
import runtime.mobileagent.ui.AgentEmptyState
import runtime.mobileagent.ui.AgentIconButton
import runtime.mobileagent.ui.AgentListRow
import runtime.mobileagent.ui.AgentRiskNotice
import runtime.mobileagent.ui.AgentStatusBanner
import runtime.mobileagent.ui.AgentStatusTone
import runtime.mobileagent.ui.AgentTopBar
import runtime.mobileagent.ui.AppThemeMode
import runtime.mobileagent.ui.MobileAgentTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme

@RunWith(AndroidJUnit4::class)
class DesignSystemUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun defaultThemeIsLightAndEasterEggRemainsExplicit() {
        val defaultPrimary = mutableStateOf(androidx.compose.ui.graphics.Color.Unspecified)
        val easterEggPrimary = mutableStateOf(androidx.compose.ui.graphics.Color.Unspecified)
        val mode = mutableStateOf(AppThemeMode.LIGHT)

        compose.setContent {
            MobileAgentTheme(mode = mode.value) {
                if (mode.value == AppThemeMode.LIGHT) {
                    defaultPrimary.value = MaterialTheme.colorScheme.primary
                } else {
                    easterEggPrimary.value = MaterialTheme.colorScheme.primary
                }
            }
        }
        compose.waitForIdle()
        assertEquals(androidx.compose.ui.graphics.Color(0xFF1A56DB), defaultPrimary.value)

        compose.runOnIdle {
            mode.value = AppThemeMode.CC66FF
        }
        compose.waitForIdle()
        assertEquals(androidx.compose.ui.graphics.Color(0xFF66CCFF), easterEggPrimary.value)
    }

    @Test
    fun sharedComponentsExposeAccessibleTouchTargetsAndStatusText() {
        compose.setContent {
            MobileAgentTheme {
                Column {
                    AgentTopBar(
                        title = "当前会话",
                        subtitle = "示例智能体",
                        onBack = {},
                    )
                    AgentIconButton(Icons.Filled.Add, "新建会话", onClick = {}, modifier = Modifier)
                    AgentListRow(
                        title = "示例工作区",
                        subtitle = "已授权 · 可读写",
                        onClick = {},
                        modifier = Modifier,
                    )
                    AgentStatusBanner("已连接", "当前权限通道可用。", AgentStatusTone.SUCCESS)
                    AgentRiskNotice("高风险操作需要明确的范围和确认。")
                    AgentEmptyState(title = "暂无会话", message = "创建一个会话即可开始。")
                }
            }
        }

        compose.onNodeWithContentDescription("返回").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("新建会话").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("示例工作区").assertIsDisplayed()
        compose.onNodeWithText("当前权限通道可用。").assertIsDisplayed()
        compose.onNodeWithText("高风险操作需要明确的范围和确认。").assertIsDisplayed()
        compose.onNodeWithText("暂无会话").assertIsDisplayed()
    }

    @Test
    fun narrowLargeFontKeepsRowsAndStatusReachableInsideScroll() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MobileAgentTheme {
                    Box(Modifier.fillMaxSize().height(640.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AgentCard {
                                AgentListRow(
                                    title = "一个需要换行的工作区名称",
                                    subtitle = "这个说明在大字体下也应继续显示，并且不会挤压操作区域。",
                                    onClick = {},
                                    modifier = Modifier,
                                )
                            }
                            AgentStatusBanner("状态", "长状态说明仍然可滚动查看。", AgentStatusTone.INFO, Modifier)
                            AgentEmptyState(title = "列表为空", message = "长说明在窄屏上仍然保留。", modifier = Modifier)
                            Box(Modifier.height(120.dp))
                            AgentRiskNotice("风险说明仍在同一个可滚动内容中。", Modifier)
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("一个需要换行的工作区名称").assertIsDisplayed()
        compose.onNodeWithText("长状态说明仍然可滚动查看。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("风险说明仍在同一个可滚动内容中。").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tokensRemainStableAndClickableRowsExposeClickSemantics() {
        compose.setContent {
            MobileAgentTheme {
                Column {
                    AgentListRow(
                        title = "可点击项目",
                        onClick = {},
                        modifier = Modifier.testTag("design.row"),
                    )
                    AgentStatusBanner("信息", "正文", modifier = Modifier.testTag("design.status"))
                }
            }
        }

        compose.onNodeWithTag("design.row").assertHasClickAction().assertHeightIsAtLeast(AgentDesignDefaults.tokens.listRowMinHeight)
        compose.onNodeWithTag("design.status").assertIsDisplayed()
        assertEquals(48.dp, AgentDesignDefaults.tokens.minTouchTarget)
        assertEquals(16.dp, AgentDesignDefaults.tokens.spacing.screen)
    }
}
