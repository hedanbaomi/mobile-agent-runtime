// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.feature.agents.RootDirectoryBrowseEntryUi
import runtime.mobileagent.feature.agents.RootDirectoryBrowserUiState
import runtime.mobileagent.feature.agents.RootDirectorySelectionCard
import runtime.mobileagent.feature.agents.RootWorkspacePermissionsUiState
import runtime.mobileagent.feature.agents.RootWorkspaceTestTags
import runtime.mobileagent.ui.RootAgentWorkspaceDialog
import runtime.mobileagent.ui.RootWorkspacePermissionDialog

class RootWorkspacePermissionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun loadedIntentIsVisibleAndApplyUsesExactlyThoseCheckboxValues() {
        var state by mutableStateOf(RootWorkspacePermissionsUiState("workspace", "Root folder", false, false, false, loading = true))
        var submitted: RootWorkspacePermissionsUiState? = null
        compose.setContent {
            MaterialTheme {
                RootWorkspacePermissionDialog(state, false,
                    onChange = { w, m, s -> state = state.copy(writable = w, metadata = m, shell = s) },
                    onApply = { submitted = state }, onClose = {})
            }
        }
        compose.onNodeWithTag(RootWorkspaceTestTags.PERMISSIONS_CONFIRM).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, metadata = true, shell = true) }
        val boxes = compose.onAllNodes(isToggleable())
        boxes[0].assertIsOff()
        boxes[1].assertIsOn().assertIsEnabled()
        boxes[2].assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag(RootWorkspaceTestTags.PERMISSIONS_CONFIRM).performClick()
        compose.runOnIdle {
            assertFalse(requireNotNull(submitted).writable)
            assertTrue(requireNotNull(submitted).metadata)
            assertFalse(requireNotNull(submitted).shell)
        }
    }

    @Test fun failedPermissionReadCannotOverwriteIntent() {
        compose.setContent {
            MaterialTheme {
                RootWorkspacePermissionDialog(
                    RootWorkspacePermissionsUiState("workspace", "Root folder", false, false, false, loadFailed = true),
                    false, onChange = { _, _, _ -> fail("Failed read must disable edits") },
                    onApply = { fail("Failed read must disable Apply") }, onClose = {})
            }
        }
        compose.onNodeWithTag(RootWorkspaceTestTags.PERMISSIONS_CONFIRM).assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[0].assertIsNotEnabled()
    }

    /**
     * A portrait listing can contain hundreds of directories. Only the list viewport may grow and
     * scroll; the permission switches and the confirm action must stay reachable below it.
     */
    @Test fun longDirectoryPageKeepsConfirmReachable() {
        val entries = (1..60).map { index ->
            RootDirectoryBrowseEntryUi(
                name = "dir$index",
                path = "/data/dir$index",
                directory = true,
            )
        }
        compose.setContent {
            MaterialTheme {
                RootAgentWorkspaceDialog(
                    enabled = true,
                    chinese = false,
                    onConfirm = { _, _, _, _ -> },
                    onClose = {},
                    browse = RootDirectoryBrowserUiState(
                        path = "/data",
                        entries = entries,
                        nextCursor = "dir60",
                        browsable = true,
                    ),
                )
            }
        }
        val listHeight = compose.onNodeWithTag(RootWorkspaceTestTags.BROWSE_LIST)
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue("Directory list must have a bounded viewport",
            listHeight <= 200f * compose.activity.resources.displayMetrics.density + 1f)
        compose.onNodeWithTag(RootWorkspaceTestTags.ATTACH_CONFIRM)
            .performScrollTo()
            .assertIsDisplayed()
        saveScreen("root-long-directory-list")
    }

    /** Directory aliases must show the observed target; files never become browser rows. */
    @Test fun aliasShowsTargetAndFilesAreNotListed() {
        compose.setContent {
            MaterialTheme {
                RootDirectorySelectionCard(
                    enabled = true,
                    chinese = false,
                    onConfirm = { _, _, _, _ -> },
                    browse = RootDirectoryBrowserUiState(
                        path = "/",
                        browsable = true,
                        entries = listOf(
                            RootDirectoryBrowseEntryUi(
                                name = "sdcard",
                                path = "/storage/emulated/0",
                                directory = true,
                                targetPath = "/storage/emulated/0",
                            ),
                            RootDirectoryBrowseEntryUi(
                                name = "note.txt",
                                path = "/note.txt",
                                directory = false,
                            ),
                        ),
                    ),
                )
            }
        }
        compose.onNodeWithText("→ /storage/emulated/0").assertIsDisplayed()
        compose.onNodeWithText("note.txt").assertDoesNotExist()
        saveScreen("root-directory-alias", compose.onNodeWithTag(RootWorkspaceTestTags.BROWSE_LIST))
    }

    private fun saveScreen(name: String, node: SemanticsNodeInteraction? = null) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = node?.captureToImage()?.asAndroidBitmap()
            ?: requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            .outputStream().use { output ->
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
        bitmap.recycle()
    }

    /**
     * A connected channel whose independent Shell switch is off is a Settings repair, not a
     * reconnect. The hint and its Settings action must be visible, and Shell must not be submittable.
     */
    @Test fun shellConsentOffPointsAtSettingsAndDisablesShell() {
        var openedSettings = false
        compose.setContent {
            MaterialTheme {
                RootAgentWorkspaceDialog(
                    enabled = true,
                    chinese = false,
                    onConfirm = { _, _, _, _ -> },
                    onClose = {},
                    shellConsentEnabled = false,
                    shellSettingsHint =
                        "Root Shell is disabled for this channel. Open Settings and enable Independent Root Shell confirmation.",
                    shellSettingsLabel = "Settings",
                    onOpenShellSettings = { openedSettings = true },
                )
            }
        }
        compose.onNodeWithTag(RootWorkspaceTestTags.SHELL_SETTINGS_HINT)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(openedSettings) }
        compose.onAllNodes(isToggleable())[2].assertIsNotEnabled()
    }

    @Test fun permissionEditorCannotApplyShellWhenChannelConsentIsOff() {
        var state by mutableStateOf(
            RootWorkspacePermissionsUiState("workspace", "Root folder", false, false, true),
        )
        compose.setContent {
            MaterialTheme {
                RootWorkspacePermissionDialog(
                    state,
                    false,
                    onChange = { writable, metadata, shell ->
                        state = state.copy(writable = writable, metadata = metadata, shell = shell)
                    },
                    onApply = {},
                    onClose = {},
                    shellConsentEnabled = false,
                    shellSettingsHint =
                        "Root Shell is disabled for this channel. Open Settings and enable Independent Root Shell confirmation.",
                    shellSettingsLabel = "Settings",
                )
            }
        }
        compose.onNodeWithTag(RootWorkspaceTestTags.PERMISSIONS_CONFIRM).assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[2].performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag(RootWorkspaceTestTags.PERMISSIONS_CONFIRM).assertIsEnabled()
    }
}
