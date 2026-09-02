// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.providers.ConnectionCheckUi
import runtime.mobileagent.feature.providers.ProbeOperation
import runtime.mobileagent.feature.providers.ProbePhase
import runtime.mobileagent.feature.providers.ProbeCheckUi
import runtime.mobileagent.feature.providers.ProviderCardUi
import runtime.mobileagent.feature.providers.ProviderModelUi
import runtime.mobileagent.feature.providers.ProviderProbeUiState
import runtime.mobileagent.feature.providers.ProvidersActions
import runtime.mobileagent.feature.providers.ProvidersScreen
import runtime.mobileagent.feature.providers.ProvidersUiState
import runtime.mobileagent.provider.CapabilityCheck
import runtime.mobileagent.provider.CapabilityCheckStatus

/**
 * Provider UI regression tests use only typed state.  They intentionally do
 * not construct a ViewModel or contact a real service provider.
 */
@RunWith(AndroidJUnit4::class)
class ProvidersTypedUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun successfulConnectionRendersSuccessWithoutFailureCopy() {
        compose.setContent {
            MaterialTheme {
                ProvidersScreen(
                    state = state(
                        probe = ProviderProbeUiState(
                            phase = ProbePhase.SUCCESS,
                            operation = ProbeOperation.CONNECTION,
                            connection = ConnectionCheckUi(
                                success = true,
                                latencyMs = 842,
                            ),
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithText("连接成功", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("模型响应正常 · 842 ms", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("连接失败", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun partialCapabilityKeepsConnectionSuccessAndRendersTypedSubcheck() {
        compose.setContent {
            MaterialTheme {
                ProvidersScreen(
                    state = state(
                        probe = ProviderProbeUiState(
                            phase = ProbePhase.PARTIAL,
                            operation = ProbeOperation.CAPABILITY,
                            connection = ConnectionCheckUi(success = true, latencyMs = 12),
                            checks = listOf(
                                ProbeCheckUi(CapabilityCheck.METADATA, CapabilityCheckStatus.VERIFIED),
                                ProbeCheckUi(CapabilityCheck.STREAM, CapabilityCheckStatus.VERIFIED),
                                ProbeCheckUi(CapabilityCheck.TOOLS, CapabilityCheckStatus.UNSUPPORTED, 404),
                                ProbeCheckUi(CapabilityCheck.IMAGE, CapabilityCheckStatus.NOT_DECLARED),
                            ),
                            charged = true,
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithText("部分能力已确认", useUnmergedTree = true).assertIsDisplayed()
        // The independent ConnectionCheckUi remains visible while the
        // capability operation reports a partial result.
        compose.onAllNodesWithText("连接成功", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .also { assertEquals(1, it.size) }
        compose.onNodeWithText("工具调用：不支持", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("本次检查可能产生服务商费用。", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("连接失败", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun providerActionsFitNarrowScreenAndLongModelIdDoesNotOverflow() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    ProvidersScreen(
                        state = state(
                            probe = ProviderProbeUiState(),
                        ).copy(
                            models = listOf(
                                ProviderModelUi(
                                    id = "model",
                                    modelId = "openai-very-long-model-identifier-that-must-ellipsis-on-narrow-screens",
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        compose.onNodeWithTag("provider.testConnection", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("provider.capabilityProbe", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("provider.overflow", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("服务商更多操作", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("编辑", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .also { assertEquals(0, it.size) }
        compose.onNodeWithTag("provider.overflow", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("provider.overflow.edit", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun state(probe: ProviderProbeUiState): ProvidersUiState = ProvidersUiState(
        providers = listOf(
            ProviderCardUi(
                id = "provider",
                name = "Example",
                baseUrl = "https://example.invalid",
                apiFormat = "OPENAI_COMPATIBLE",
                modelCount = 1,
                secretConfigured = true,
            ),
        ),
        selectedProviderId = "provider",
        models = listOf(ProviderModelUi(id = "model", modelId = "demo")),
        probe = probe,
    )
}
