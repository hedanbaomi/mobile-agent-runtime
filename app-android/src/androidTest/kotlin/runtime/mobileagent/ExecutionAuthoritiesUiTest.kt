// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.feature.settings.AuthorityUiState
import runtime.mobileagent.feature.settings.SettingsActions
import runtime.mobileagent.feature.settings.SettingsScreen
import runtime.mobileagent.feature.settings.SettingsUiState
import runtime.mobileagent.feature.settings.WiredPairingUiState

class ExecutionAuthoritiesUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun settingsShowsPeerAuthoritiesAndHidesExcludedPositiveRows() {
        composeRule.setContent {
            SettingsScreen(SettingsUiState(language = "zh-CN"))
        }

        composeRule.onNodeWithTag("settings.authorities").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings.authority.selected").assertIsDisplayed()
        composeRule.onNodeWithText("已启用（逐次确认）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Shizuku").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("有线 ADB").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("信任：未配置").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("用户授权文件（SAF）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings.dangerous_mode").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings.dangerous_mode.fail_closed").assertIsDisplayed()
        assertNoText("外部命令运行时")
        assertNoText("无线 ADB shell")
        assertNoText("设备策略（DPC）")
        assertNoText("打开开发者选项")
    }

    @Test
    fun compactSettingsScrollReachesSafAndDangerousModeControls() {
        composeRule.setContent {
            SettingsScreen(SettingsUiState(language = "zh-CN"))
        }

        composeRule.onNodeWithTag("settings.screen").assertIsDisplayed()
        // The action row is horizontally scrollable inside the settings' vertical scroll.
        // Scroll the stable vertical container first so the leaf button has real bounds.
        composeRule.onNodeWithTag("settings.workspace.saf").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings.saf.authorize").assertIsDisplayed()
        composeRule.onNodeWithTag("settings.dangerous_mode").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings.dangerous_mode.selector").assertIsDisplayed()
    }

    @Test
    fun lostSafGrantKeepsExplicitReauthorizationAndRevokeAvailable() {
        composeRule.setContent {
            SettingsScreen(
                SettingsUiState(
                    language = "zh-CN",
                    safWorkspace = runtime.mobileagent.feature.settings.SafWorkspaceUiState(
                        configured = false,
                        readGranted = true,
                        persisted = false,
                        status = "GRANT_LOST",
                    ),
                ),
            )
        }

        composeRule.onNodeWithTag("settings.saf.reauthorize").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag("settings.saf.revoke").performScrollTo().assertIsEnabled()
    }

    @Test
    fun dangerousModeRequiresRiskConfirmationAndKeepsShellUnavailableVisible() {
        var selected: String? = null
        var state by mutableStateOf(
            SettingsUiState(
                language = "zh-CN",
                selectedAuthority = "SHIZUKU",
                shizukuAuthority = AuthorityUiState(
                    authority = "SHIZUKU",
                    platformGrant = "GRANTED",
                    availability = "TEMPORARILY_UNAVAILABLE",
                    connection = "DISCONNECTED",
                ),
                dangerousModeBuildAllowed = true,
            ),
        )
        composeRule.setContent {
            SettingsScreen(
                state,
                runtime.mobileagent.feature.settings.SettingsActions(
                    onSetDangerousMode = {
                        selected = it
                        state = state.copy(dangerousMode = it)
                    },
                ),
            )
        }

        composeRule.onNodeWithTag("settings.dangerous_mode.selector").performScrollTo().performClick()
        composeRule.onNodeWithTag("settings.dangerous_mode.option.${DangerousMode.ENABLED_AUTONOMOUS.name}").performClick()
        composeRule.onNodeWithTag("settings.dangerous_mode.risk_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("确认开启").performClick()
        assert(selected == DangerousMode.ENABLED_AUTONOMOUS.name)
        composeRule.onNodeWithText("危险模式：已开启 · Shell：当前不可用").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun buildDeniedStillAllowsClearingDurableDangerousMode() {
        var disabled = false
        composeRule.setContent {
            SettingsScreen(
                SettingsUiState(
                    language = "zh-CN",
                    dangerousMode = DangerousMode.DISABLED.name,
                    dangerousModeDurable = DangerousMode.ENABLED_AUTONOMOUS.name,
                    dangerousModeBuildAllowed = false,
                    dangerousModeBuildKnown = true,
                ),
                SettingsActions(onDisableDangerousMode = { disabled = true }),
            )
        }

        composeRule.onNodeWithTag("settings.dangerous_mode.durable_fail_closed")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings.dangerous_mode.selector")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("settings.dangerous_mode.option.DISABLED")
            .assertIsEnabled()
            .performClick()
        assertTrue(disabled)
    }

    @Test
    fun replacingSavedTrustRequiresSecondRiskConfirmation() {
        var replaceExistingTrust: Boolean? = null
        composeRule.setContent {
            SettingsScreen(
                SettingsUiState(
                    language = "zh-CN",
                    wiredAdbAuthority = AuthorityUiState(
                        authority = "WIRED_ADB",
                        availability = "READY",
                        configured = true,
                        trust = "TRUSTED",
                    ),
                ),
                SettingsActions(onRequestWiredPairing = { replaceExistingTrust = it }),
            )
        }

        // Scroll the stable vertical provider container before entering its
        // horizontally scrollable action row.
        composeRule.onNodeWithTag("settings.authority.wired_adb").performScrollTo()
        composeRule.onNodeWithTag("settings.authority.wired_adb.primary").performClick()
        // The dialog's text body is an independently scrollable node. Assert
        // presence by title, then assert the fixed action slot is reachable
        // before confirming the replacement.
        composeRule.onNodeWithText("确认替换已保存信任").assertExists()
        composeRule.onNodeWithTag("settings.wired_adb.replace.confirm").assertIsDisplayed()
        composeRule.onNodeWithTag("settings.wired_adb.replace.confirm").performClick()
        assertTrue(replaceExistingTrust == true)
    }

    @Test
    fun foregroundPairingShowsInstructionsAndKeepsTokenHiddenUntilRevealed() {
        val token = "abcdef0123456789".repeat(4)
        var completed = false
        var cancelled = false
        composeRule.setContent {
            SettingsScreen(
                SettingsUiState(
                    language = "zh-CN",
                    wiredPairing = WiredPairingUiState(
                        hasToken = true,
                        expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                        remainingAttempts = 3,
                    ),
                ),
                SettingsActions(
                    onCompleteWiredPairing = { completed = true },
                    onCancelWiredPairing = { cancelled = true },
                    onWiredPairingToken = { token },
                ),
            )
        }

        composeRule.onNodeWithTag("settings.wired_adb.pairing")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("settings.wired_adb.pairing.instructions")
            .assertIsDisplayed()
        composeRule.onNodeWithText("令牌已隐藏；点击“查看令牌”后才能复制。")
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText(token, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithTag("settings.wired_adb.pairing.reveal").performClick()
        composeRule.onNodeWithTag("settings.wired_adb.pairing.token").assertIsDisplayed()
        composeRule.onNodeWithTag("settings.wired_adb.pairing.copy").assertIsEnabled()
        composeRule.onNodeWithTag("settings.wired_adb.pairing.complete").performClick()
        composeRule.onNodeWithTag("settings.wired_adb.pairing.cancel").performClick()
        assertTrue(completed)
        assertTrue(cancelled)
    }

    private fun assertNoText(text: String) {
        assertTrue(
            "Unexpected excluded settings row: $text",
            composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }
}
