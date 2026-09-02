// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import runtime.mobileagent.domain.CapabilityGrant
import runtime.mobileagent.domain.CapabilityId
import runtime.mobileagent.domain.GrantLifetime
import runtime.mobileagent.domain.WorkspaceBackendType
import runtime.mobileagent.domain.WorkspaceScope
import runtime.mobileagent.feature.agents.AgentEditorUi
import runtime.mobileagent.feature.agents.AgentGrantDraftUi
import runtime.mobileagent.feature.agents.AgentGrantUi
import runtime.mobileagent.feature.agents.AgentTestTags
import runtime.mobileagent.feature.agents.AgentTrustedSkillUi
import runtime.mobileagent.feature.agents.AgentWorkspaceUi
import runtime.mobileagent.feature.agents.AgentWorkspaceAccessPreset
import runtime.mobileagent.feature.agents.AgentWorkspaceGrantPresetUi
import runtime.mobileagent.feature.agents.AgentWorkspaceAccessUi
import runtime.mobileagent.feature.agents.AgentsActions
import runtime.mobileagent.feature.agents.AgentsScreen
import runtime.mobileagent.feature.agents.AgentsUiState
import runtime.mobileagent.integration.WorkspaceAccessItem
import runtime.mobileagent.integration.WorkspaceAccessStatus
import runtime.mobileagent.ui.selectDurablyAuthorizedWorkspace

/** UI seam checks for the grant editor; these render no Application or repository internals. */
class AgentsGrantUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test
    fun unavailableGrantPortFailsClosedInsteadOfSilentlySaving() {
        val failure = runCatching {
            AgentGrantPort.EMPTY.saveGrant(
                CapabilityGrant(
                    grantId = "grant.empty",
                    agentId = "agent.one",
                    capability = CapabilityId(CapabilityId.FILE_LIST),
                ),
            )
        }.exceptionOrNull()

        assertEquals("授权存储未就绪；请稍后重试。", failure?.message)
        assertNull(runCatching { AgentGrantPort.EMPTY.bindSnapshot(fixtureBinding()) }.getOrNull())
    }

    @Test
    fun contextFreeEditorRejectsTaskAndSessionBeforeAnyGrantPortWrite() {
        listOf(GrantLifetime.TASK, GrantLifetime.SESSION).forEach { lifetime ->
            val port = RecordingGrantPort()
            val editor = fixtureEditor().copy(
                grantDraft = AgentGrantDraftUi(lifetime = lifetime),
            )
            val failure = runCatching {
                saveAgentGrantDraft(
                    editor = editor,
                    agentId = "agent.one",
                    grantPort = port,
                    createdAt = "2026-08-30T00:00:00Z",
                )
            }.exceptionOrNull()

            assertEquals(AGENT_EDITOR_LIFETIME_CONTEXT_ERROR, failure?.message)
            assertEquals(0, port.policyReads)
            assertEquals(0, port.saveCalls)
        }
    }

    @Test
    fun contextFreeEditorPersistsOnlyOnceOrPersistentWithoutOwnerIdentity() {
        listOf(GrantLifetime.ONCE, GrantLifetime.PERSISTENT).forEach { lifetime ->
            val port = RecordingGrantPort()
            val persisted = saveAgentGrantDraft(
                editor = fixtureEditor().copy(grantDraft = AgentGrantDraftUi(lifetime = lifetime)),
                agentId = "agent.one",
                grantPort = port,
                createdAt = "2026-08-30T00:00:00Z",
            )

            assertEquals(lifetime, persisted.lifetime)
            assertNull(persisted.taskId)
            assertNull(persisted.sessionId)
            assertNull(persisted.pathScope)
            assertNull(persisted.packageHash)
            assertEquals(1, port.policyReads)
            assertEquals(1, port.saveCalls)
        }
    }

    @Test
    fun workspacePresetPersistsTheCompleteReadableToolSetInOneAction() {
        val port = RecordingGrantPort()
        val editor = fixtureEditor().copy(
            grants = emptyList(),
            workspaceGrantPreset = AgentWorkspaceGrantPresetUi(
                workspaceId = "workspace.one",
                access = AgentWorkspaceAccessPreset.READ_ONLY,
            ),
        )

        val persisted = saveAgentWorkspaceGrantPreset(
            editor = editor,
            agentId = "agent.one",
            grantPort = port,
            createdAt = "2026-08-31T00:00:00Z",
        )

        assertEquals(
            setOf(
                CapabilityId.WORKSPACE_ENUMERATE,
                CapabilityId.FILE_LIST,
                CapabilityId.FILE_STAT,
                CapabilityId.FILE_READ_TEXT,
            ),
            persisted.map { it.capability.value }.toSet(),
        )
        assertTrue(persisted.all { it.workspaceId == "workspace.one" })
        assertTrue(persisted.all { it.lifetime == GrantLifetime.PERSISTENT })
        assertEquals(4, port.saveCalls)
    }

    @Test
    fun workspacePresetRejectsWriteAccessWhenProviderIsReadOnlyBeforeWriting() {
        val port = RecordingGrantPort()
        val editor = fixtureEditor().copy(
            grants = emptyList(),
            workspaceGrantPreset = AgentWorkspaceGrantPresetUi(
                workspaceId = "workspace.one",
                access = AgentWorkspaceAccessPreset.READ_WRITE,
            ),
        )

        val failure = runCatching {
            saveAgentWorkspaceGrantPreset(editor, "agent.one", port)
        }.exceptionOrNull()

        assertEquals("该工作区仅有读取权限，不能授予读写工具。", failure?.message)
        assertEquals(0, port.saveCalls)
    }

    @Test
    fun grantEditorOffersSimpleWorkspacePresetBeforeAdvancedSingleGrantFlow() {
        var editor by mutableStateOf(fixtureEditor())
        composeRule.setContent {
            MaterialTheme {
                AgentsScreen(
                    state = AgentsUiState(
                        selectedAgentId = "agent.one",
                        summary = editor,
                        editor = editor,
                        editorOpen = true,
                    ),
                    actions = AgentsActions(onEditorChange = { editor = it }),
                )
            }
        }

        composeRule.onNodeWithTag(AgentTestTags.WORKSPACE_PRESET, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AgentTestTags.WORKSPACE_PRESET_READ_ONLY, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertEquals(AgentWorkspaceAccessPreset.READ_ONLY, editor.workspaceGrantPreset?.access)
        composeRule.onNodeWithText("保存后请用此智能体新建会话；已有会话的工具不会变化。", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun grantEditorRemainsReachableOnSmallScreen() {
        var editor by mutableStateOf(fixtureEditor())
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.55f)) {
                Box(Modifier.width(320.dp).height(640.dp)) {
                    MaterialTheme {
                        AgentsScreen(
                            state = AgentsUiState(
                                selectedAgentId = "agent.one",
                                summary = editor,
                                editor = editor,
                                editorOpen = true,
                            ),
                            actions = AgentsActions(onEditorChange = { editor = it }),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(AgentTestTags.EDITOR, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentTestTags.GRANTS, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AgentTestTags.GRANT_ADD, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertNotNull(editor.grantDraft)
        composeRule.onNodeWithTag(AgentTestTags.GRANT_SCOPE, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("${AgentTestTags.GRANT_LIFETIME}.once", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun shellGrantUsesHighRiskCopyAtLargeFontWithoutDevicePath() {
        val editor = fixtureEditor().copy(
            grantDraft = AgentGrantDraftUi(capability = CapabilityId(CapabilityId.SHELL_EXECUTE)),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    AgentsScreen(
                        state = AgentsUiState(
                            selectedAgentId = "agent.one",
                            summary = editor,
                            editor = editor,
                            editorOpen = true,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(AgentTestTags.GRANT_SHELL, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("仅填写工作区内的相对范围，不是设备路径。", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("content://", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("a".repeat(64), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun workspaceAccessCardExposesOnlySafeActionsAndKeepsFullDeviceExplicit() {
        var safChosen = false
        var privilegedChosen = false
        var fullDeviceEnabled = false
        val editor = fixtureEditor().copy(
            workspaceAccess = AgentWorkspaceAccessUi(
                selectedWorkspaceName = "Documents",
                selectedBackendLabel = "用户授权文件",
                availableWorkspaceCount = 2,
                canChooseSaf = true,
                canBrowsePrivileged = true,
                fullDeviceFilesEligible = true,
            ),
        )
        composeRule.setContent {
            Box(Modifier.width(320.dp).height(640.dp)) {
                MaterialTheme {
                    AgentsScreen(
                        state = AgentsUiState(
                            selectedAgentId = "agent.one",
                            summary = editor,
                            editor = editor,
                            editorOpen = true,
                        ),
                        actions = AgentsActions(
                            onChooseSafWorkspace = { safChosen = true },
                            onBrowsePrivilegedWorkspace = { privilegedChosen = true },
                            onToggleFullDeviceFiles = { fullDeviceEnabled = it },
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(AgentTestTags.WORKSPACE_ACCESS, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AgentTestTags.WORKSPACE_ACCESS_SAF, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(AgentTestTags.WORKSPACE_ACCESS_PRIVILEGED, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(AgentTestTags.WORKSPACE_ACCESS_FULL_DEVICE, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertTrue(safChosen)
        assertTrue(privilegedChosen)
        assertTrue(fullDeviceEnabled)
        assertTrue(
            composeRule.onAllNodesWithText("content://", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun fullDeviceAuthorizationRemainsVisibleAndRevocableWhileAuthorityIsOffline() {
        var requested: Boolean? = null
        val editor = fixtureEditor().copy(
            workspaceAccess = AgentWorkspaceAccessUi(
                selectedWorkspaceName = "Documents",
                selectedBackendLabel = "ADB 级目录",
                availableWorkspaceCount = 2,
                canChooseSaf = true,
                canBrowsePrivileged = false,
                fullDeviceFilesEnabled = true,
                fullDeviceFilesEligible = false,
                status = "完整设备访问授权已保留；当前连接不可用，连接恢复后继续生效。",
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                AgentsScreen(
                    state = AgentsUiState(
                        selectedAgentId = "agent.one",
                        summary = editor,
                        editor = editor,
                        editorOpen = true,
                    ),
                    actions = AgentsActions(onToggleFullDeviceFiles = { requested = it }),
                )
            }
        }

        composeRule.onNodeWithText("完整设备访问授权已保留；当前连接不可用，连接恢复后继续生效。")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AgentTestTags.WORKSPACE_ACCESS_FULL_DEVICE, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertEquals(false, requested)
    }

    @Test
    fun disconnectedAdbWorkspaceRemainsSelectedFromDurableAuthorization() {
        val offlineWorkspace = WorkspaceAccessItem(
            workspaceId = "workspace.offline",
            displayName = "ADB 目录",
            backendType = WorkspaceBackendType.PRIVILEGED,
            scope = WorkspaceScope.SELECTED_DIRECTORY,
            readable = false,
            writable = false,
            status = WorkspaceAccessStatus.UNAVAILABLE,
            durablyAuthorized = true,
            grantedCapabilities = setOf(CapabilityId(CapabilityId.FILE_READ_TEXT)),
            grantRevision = 7L,
        )

        assertEquals(
            offlineWorkspace,
            selectDurablyAuthorizedWorkspace(
                listOf(offlineWorkspace),
                WorkspaceScope.SELECTED_DIRECTORY,
            ),
        )
    }

    @Test
    fun staleScopedLifetimeDraftShowsContextErrorAndNoScopedLifetimeControls() {
        val editor = fixtureEditor().copy(
            grantDraft = AgentGrantDraftUi(lifetime = GrantLifetime.SESSION),
        )
        composeRule.setContent {
            MaterialTheme {
                AgentsScreen(
                    state = AgentsUiState(
                        selectedAgentId = "agent.one",
                        summary = editor,
                        editor = editor,
                        editorOpen = true,
                    ),
                )
            }
        }

        composeRule.onNodeWithTag(AgentTestTags.GRANT_LIFETIME_CONTEXT, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("${AgentTestTags.GRANT_LIFETIME}.task", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithTag("${AgentTestTags.GRANT_LIFETIME}.session", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithTag("${AgentTestTags.GRANT_LIFETIME}.once", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("${AgentTestTags.GRANT_LIFETIME}.persistent", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private class RecordingGrantPort : AgentGrantPort {
        override val available = true
        var policyReads = 0
        var saveCalls = 0

        override fun currentPolicyVersion(): Long {
            policyReads += 1
            return 9L
        }

        override fun saveGrant(grant: CapabilityGrant): CapabilityGrant {
            saveCalls += 1
            return grant
        }
    }

    private fun fixtureEditor(): AgentEditorUi {
        val grant = CapabilityGrant(
            grantId = "grant.one",
            agentId = "agent.one",
            capability = CapabilityId(CapabilityId.FILE_READ_TEXT),
            workspaceId = "workspace.one",
            pathScope = "docs",
            // Existing scoped grants need their concrete runtime owner even when merely displayed.
            lifetime = GrantLifetime.SESSION,
            sessionId = "session.one",
            policyVersion = 4,
            createdAt = "2026-08-30T00:00:00Z",
        )
        return AgentEditorUi(
            id = "agent.one",
            name = "Review agent",
            prompt = "Use the selected workspace.",
            grants = listOf(AgentGrantUi(grant, workspaceName = "Documents")),
            trustedSkills = listOf(
                AgentTrustedSkillUi(
                    installId = "skill.one",
                    packageHash = "a".repeat(64),
                    name = "Trusted Skill",
                    enabled = true,
                    trusted = true,
                    capabilities = setOf(CapabilityId.FILE_READ_TEXT),
                    grantRevision = 2,
                ),
            ),
            workspaces = listOf(
                AgentWorkspaceUi(
                    id = "workspace.one",
                    displayName = "Documents",
                    backendType = WorkspaceBackendType.INTERNAL,
                    readable = true,
                    writable = false,
                    quotaBytes = 1024,
                    maxFileBytes = 1024,
                    enabled = true,
                    revision = 1,
                ),
            ),
        )
    }

    private fun fixtureBinding() = runtime.mobileagent.domain.SnapshotGrantBinding(
        snapshotId = "snapshot.one",
        grantId = "grant.empty",
        capability = CapabilityId(CapabilityId.FILE_LIST),
    )
}
