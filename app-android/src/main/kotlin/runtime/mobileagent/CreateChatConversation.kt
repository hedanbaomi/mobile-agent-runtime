// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.CreateConversationUseCase
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.Utc
import java.time.LocalDate

/** The production ports use this container's database; no remote request is made by creation. */
internal fun AppContainer.createChatConversation(
    agentId: String,
    requestedWorkspaceId: String? = null,
    useAgentDefault: Boolean = true,
): Conversation {
    val agent = agents.get(agentId) ?: error("Agent 已不存在。")
    val workspace = if (useAgentDefault) threadWorkspacePort.takeIf { it.available }?.resolveNewThreadWorkspace(agentId)
        else requestedWorkspaceId
    require(workspace == null || threadWorkspaceRuntimePort.available) { threadWorkspaceRuntimePort.unavailableMessage }
    require(workspace == null || threadWorkspacePort.available) { threadWorkspacePort.unavailableMessage }
    return CreateConversationUseCase(db, conversations).create(agent.name + " · " + LocalDate.now(), {
        val frozen = if (threadWorkspaceRuntimePort.available) threadWorkspaceRuntimePort.createSnapshotWithWorkspace(agentId, workspace)
            else agents.createSnapshot(agentId)
        require(frozen.agentId == agentId && frozen.id.isNotBlank()) { "Workspace snapshot identity is inconsistent" }
        frozen
    }, { frozen -> captureMcpSnapshot(this, frozen.id, agentId) }, { conversation ->
        if (workspace != null) {
            val binding = ConversationWorkspaceBinding(conversation.id, workspace, Utc.nowIso(), 1L)
            require(threadWorkspacePort.bindConversationWorkspace(binding) == binding) { "会话工作区绑定保存返回了不一致的绑定。" }
        }
    })
}
