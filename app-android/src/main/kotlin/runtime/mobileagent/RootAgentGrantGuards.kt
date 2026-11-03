// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.feature.agents.AgentEditorUi
import runtime.mobileagent.feature.agents.AgentWorkspaceUi

internal object RootAgentGrantGuards {
    fun requireOrdinaryWorkspace(grantPort: AgentGrantPort?, workspaceId: String?) {
        if (grantPort != null && workspaceId != null && grantPort.isRootWorkspace(workspaceId)) {
            throw IllegalArgumentException("Root 工作区权限只能通过前台 Root 目录绑定授予。")
        }
    }

    fun preflightEditorGrantMutations(editor: AgentEditorUi, grantPort: AgentGrantPort?) {
        editor.workspaceGrantPreset?.let { preset -> requireOrdinaryWorkspace(grantPort, preset.workspaceId) }
        editor.grants.forEach { item ->
            if (item.policyStale && item.enabled) {
                requireOrdinaryWorkspace(grantPort, item.grant.workspaceId)
            }
        }
    }

    fun defaultOrdinaryPresetWorkspaceId(
        workspaces: List<AgentWorkspaceUi>,
        currentSelectedId: String? = null,
    ): String? {
        val ordinary = workspaces.filter { it.enabled && !it.isRoot }
        return currentSelectedId?.takeIf { selected -> ordinary.any { it.id == selected } }
            ?: ordinary.firstOrNull()?.id
    }
}
