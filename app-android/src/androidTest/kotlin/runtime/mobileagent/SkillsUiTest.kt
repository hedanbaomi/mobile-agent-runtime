// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import runtime.mobileagent.feature.skills.SkillDetailUi
import runtime.mobileagent.feature.skills.SkillMemoryAvailability
import runtime.mobileagent.feature.skills.SkillMemoryUi
import runtime.mobileagent.feature.skills.SkillSourceFileUi
import runtime.mobileagent.feature.skills.SkillUi
import runtime.mobileagent.feature.skills.SkillsScreen
import runtime.mobileagent.feature.skills.SkillsUiState
import androidx.compose.ui.unit.dp

@RunWith(AndroidJUnit4::class)
class SkillsUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun narrowScreenKeepsLongSkillDetailsScrollable() {
        compose.setContent {
            Box(Modifier.width(320.dp).height(640.dp)) {
                MaterialTheme {
                    SkillsScreen(longState())
                }
            }
        }

        compose.onNodeWithTag("skills.narrow.scroll")
            .assert(hasScrollAction())
            .performTouchInput { swipeUp() }
        compose.onNodeWithText("持久 Skill memory", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsSecuritySummaryInScrollableContent() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    MaterialTheme {
                        SkillsScreen(longState())
                    }
                }
            }
        }

        compose.onNodeWithTag("skills.narrow.scroll")
            .assert(hasScrollAction())
            .performTouchInput { swipeUp() }
        compose.onNodeWithText("安全边界", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun memoryStatusUsesExplicitNonSensitiveStates() {
        val states = listOf(
            SkillMemoryAvailability.ENABLED to "已启用：持久记忆已绑定当前 Agent 快照和本次授权。",
            SkillMemoryAvailability.UNAVAILABLE to "暂不可用：持久记忆绑定或后端当前不可用。",
            SkillMemoryAvailability.GRANT_LOST to "授权已丢失：请重新授予当前 Skill 的 memory 权限。",
            SkillMemoryAvailability.EMPTY to "暂无条目：持久记忆已绑定，但当前没有记忆条目。",
        )
        val availability = mutableStateOf(states.first().first)

        compose.setContent {
            Box(Modifier.width(320.dp).height(640.dp)) {
                MaterialTheme {
                    SkillsScreen(stateWithMemory(SkillMemoryUi(availability = availability.value)))
                }
            }
        }

        states.forEachIndexed { index, (nextAvailability, label) ->
            if (index > 0) {
                compose.runOnUiThread { availability.value = nextAvailability }
                compose.waitForIdle()
            }
            compose.onNodeWithTag("skills.memory.status", useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun memoryStatusRemainsReachableAtLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    MaterialTheme {
                        SkillsScreen(stateWithMemory(SkillMemoryUi(availability = SkillMemoryAvailability.GRANT_LOST)))
                    }
                }
            }
        }

        compose.onNodeWithTag("skills.narrow.scroll")
            .assert(hasScrollAction())
        compose.onNodeWithTag("skills.memory.status", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun securitySummaryDoesNotRenderRawManifestSecretsOrAbsolutePaths() {
        val secret = "TOP_SECRET_VALUE_123"
        compose.setContent {
            Box(Modifier.width(320.dp).height(640.dp)) {
                MaterialTheme {
                    SkillsScreen(
                        SkillsUiState(
                            selectedInstallId = "install-id",
                            detail = SkillDetailUi(
                                skill = SkillUi(
                                    installId = "install-id",
                                    name = "safe skill",
                                    classification = "B",
                                    enabled = true,
                                    license = "AGPL-3.0-only",
                                    packageHash = "a".repeat(64),
                                ),
                                preview = "authorization=$secret",
                                manifestJson = "{\"apiKey\":\"$secret\",\"endpoint\":\"https://private.example\"}",
                                files = listOf(SkillSourceFileUi("/sdcard/private/$secret.txt")),
                            ),
                        ),
                    )
                }
            }
        }

        assertTrue(
            "Raw Skill secret was rendered",
            compose.onAllNodesWithText(secret, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Raw package hash was rendered",
            compose.onAllNodesWithText("aaaaaaaaaaaa", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText("仅显示上面的权限、绑定与兼容性安全摘要；原始清单字段不会在此页面展开。", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("受限包内文件", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun longState(): SkillsUiState = SkillsUiState(
        selectedInstallId = "install-id",
        detail = SkillDetailUi(
            skill = SkillUi(
                installId = "install-id",
                name = "滚动测试 Skill",
                classification = "B",
                enabled = true,
                license = "AGPL-3.0-only",
                packageHash = "a".repeat(64),
            ),
            preview = "仅显示经过限制的 Skill 说明。",
            files = (1..80).map { SkillSourceFileUi("scripts/file-$it.py", "1 KiB", "纯文本预览，不执行") },
        ),
    )

    private fun stateWithMemory(memory: SkillMemoryUi): SkillsUiState = SkillsUiState(
        selectedInstallId = "install-id",
        detail = SkillDetailUi(
            skill = SkillUi(
                installId = "install-id",
                name = "内存状态测试 Skill",
                classification = "B",
                enabled = true,
                license = "AGPL-3.0-only",
            ),
            memory = memory,
        ),
    )
}
