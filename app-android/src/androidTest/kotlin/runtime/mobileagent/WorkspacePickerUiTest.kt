// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import runtime.mobileagent.feature.agents.WorkspacePickerAuthorityUi
import runtime.mobileagent.feature.agents.WorkspacePickerBreadcrumbUi
import runtime.mobileagent.feature.agents.WorkspacePickerEntryUi
import runtime.mobileagent.feature.agents.WorkspacePickerLoadPhaseUi
import runtime.mobileagent.feature.agents.WorkspacePickerLocationUi
import runtime.mobileagent.feature.agents.WorkspacePickerModeUi
import runtime.mobileagent.feature.agents.WorkspacePickerScreen
import runtime.mobileagent.feature.agents.WorkspacePickerTestTags
import runtime.mobileagent.feature.agents.WorkspacePickerUiState

@RunWith(AndroidJUnit4::class)
class WorkspacePickerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun compactLayoutKeepsFolderActionReachable() {
        composeRule.setContent {
            MaterialTheme {
                WorkspacePickerScreen(
                    state = sampleState(entries = List(24) { index ->
                        WorkspacePickerEntryUi("entry-$index", "module-$index", directory = true)
                    }),
                )
            }
        }

        composeRule.onNodeWithTag(WorkspacePickerTestTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(WorkspacePickerTestTags.USE_FOLDER).assertIsDisplayed()
        composeRule.onNodeWithText("使用此文件夹").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun largeFontKeepsBreadcrumbAndDisabledDirectoryActionVisible() {
        composeRule.setContent {
            MaterialTheme {
                WorkspacePickerScreen(
                    state = sampleState(
                        currentDirectoryReadable = false,
                        canUseCurrentDirectory = false,
                        breadcrumbs = listOf(
                            WorkspacePickerBreadcrumbUi("depth:0", "根目录", enabled = true),
                            WorkspacePickerBreadcrumbUi("depth:1", "项目", enabled = false),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(WorkspacePickerTestTags.BREADCRUMB).assertIsDisplayed()
        composeRule.onNodeWithTag(WorkspacePickerTestTags.USE_FOLDER).assertIsNotEnabled()
    }

    @Test
    fun loadMoreButtonAppearsWhenContinuationAvailable() {
        var loads = 0
        val dialogState = sampleState(
            entries = List(8) { index ->
                WorkspacePickerEntryUi("entry-$index", "module-$index", directory = true)
            },
        ).copy(canLoadMore = true, listTruncated = true)
        composeRule.setContent {
            MaterialTheme {
                WorkspacePickerScreen(
                    state = dialogState,
                    actions = runtime.mobileagent.feature.agents.WorkspacePickerActions(
                        onLoadMore = { loads += 1 },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(WorkspacePickerTestTags.SCREEN).assertIsDisplayed()
        // The static notice must be replaced by the load-more action, never duplicated.
        assertTrue(
            composeRule.onAllNodesWithText("目录较大，当前仅显示部分项目。").fetchSemanticsNodes().isEmpty(),
        )
        // The action sits below the fold in a lazy list: scroll the screen
        // until the item composes, then interact with it.
        composeRule.onNodeWithTag(WorkspacePickerTestTags.SCREEN)
            .performScrollToNode(hasTestTag(WorkspacePickerTestTags.LOAD_MORE))
        composeRule.onNodeWithTag(WorkspacePickerTestTags.LOAD_MORE).assertIsDisplayed()
        composeRule.onNodeWithText("加载更多").assertIsDisplayed()
        composeRule.onNodeWithTag(WorkspacePickerTestTags.LOAD_MORE).performClick()
        assertTrue(loads == 1)
    }

    @Test
    fun staticTruncationNoticeWithoutContinuationHasNoLoadMore() {
        composeRule.setContent {
            MaterialTheme {
                WorkspacePickerScreen(state = sampleState().copy(listTruncated = true))
            }
        }

        composeRule.onNodeWithText("目录较大，当前仅显示部分项目。").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag(WorkspacePickerTestTags.LOAD_MORE).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun privilegedRootLandingDoesNotPretendTheHiddenDeviceRootIsEmpty() {
        composeRule.setContent {
            MaterialTheme {
                WorkspacePickerScreen(
                    state = sampleState(
                        entries = emptyList(),
                        canUseCurrentDirectory = false,
                        breadcrumbs = listOf(
                            WorkspacePickerBreadcrumbUi("depth:0", "根目录", enabled = false),
                        ),
                    ).copy(
                        currentLabel = "根目录",
                        locations = listOf(
                            WorkspacePickerLocationUi("entry-storage", "内部存储", enabled = true),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("内部存储").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("此文件夹为空。").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag(WorkspacePickerTestTags.USE_FOLDER).assertIsNotEnabled()
    }

    private fun sampleState(
        entries: List<WorkspacePickerEntryUi> = listOf(
            WorkspacePickerEntryUi("entry-dir", "项目", directory = true),
            WorkspacePickerEntryUi("entry-file", "README.md", directory = false, sizeBytes = 128),
        ),
        currentDirectoryReadable: Boolean = true,
        canUseCurrentDirectory: Boolean = true,
        breadcrumbs: List<WorkspacePickerBreadcrumbUi> = listOf(
            WorkspacePickerBreadcrumbUi("depth:0", "根目录", enabled = false),
        ),
    ) = WorkspacePickerUiState(
        mode = WorkspacePickerModeUi.PRIVILEGED,
        authority = WorkspacePickerAuthorityUi("Shizuku", "已连接", selected = true, ready = true),
        breadcrumbs = breadcrumbs,
        currentLabel = "项目",
        entries = entries,
        loadPhase = WorkspacePickerLoadPhaseUi.CONTENT,
        currentDirectoryReadable = currentDirectoryReadable,
        canUseCurrentDirectory = canUseCurrentDirectory,
        canGoParent = breadcrumbs.size > 1,
    )
}
