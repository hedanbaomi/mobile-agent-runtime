// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.AgentWorkspaceDefaultRepository
import runtime.mobileagent.data.CreateConversationUseCase
import runtime.mobileagent.data.RootWorkspaceRepository
import runtime.mobileagent.data.WorkspaceRepository
import runtime.mobileagent.domain.Conversation
import runtime.mobileagent.domain.ConversationWorkspaceBinding
import runtime.mobileagent.domain.Utc
import java.time.LocalDate

/**
 * A Root default that cannot be sealed must never degrade into an unbound new conversation and
 * then issue a paid request. The ordinary unresolved/default-cleared cases still mean "unbound".
 */
internal object NewThreadRootDefaultGuard {
    fun requireResolved(
        configuredWorkspaceId: String?,
        resolvedWorkspaceId: String?,
        isRootWorkspace: (String) -> Boolean,
    ) {
        if (resolvedWorkspaceId != null || configuredWorkspaceId == null) return
        // A read failure is not evidence that this is an ordinary workspace. Treating it as
        // non-Root would create an unbound conversation and still send the paid request, so the
        // failure is classified and propagated to the caller instead.
        val root = try {
            isRootWorkspace(configuredWorkspaceId)
        } catch (failure: Exception) {
            throw ChatRootErrors.checkFailed(configuredWorkspaceId).apply { initCause(failure) }
        }
        if (!root) return
        throw ChatRootErrors.unavailable(ChatRootErrors.NEW_THREAD_STAGE, configuredWorkspaceId)
    }
}

/** A loaded UI page is only a projection; the selected conversation owns the Agent identity. */
internal fun AppContainer.creationAgentId(selectedConversationId: String?, selectedAgentId: String?): String {
    if (selectedConversationId == null) return selectedAgentId ?: error("请先创建并选择 Agent。")
    val conversation = conversations.get(selectedConversationId) ?: error("会话已不存在。")
    val snapshot = agents.getSnapshot(conversation.snapshotId) ?: error("会话的 Agent 配置快照不可用。")
    return snapshot.agentId.takeIf { it.isNotBlank() } ?: error("会话的 Agent 身份不可用。")
}

/** The production ports use this container's database; no remote request is made by creation. */
internal fun AppContainer.createChatConversation(
    agentId: String,
    requestedWorkspaceId: String? = null,
    useAgentDefault: Boolean = true,
): Conversation {
    val agent = agents.get(agentId) ?: error("Agent 已不存在。")
    val configuredDefault = if (useAgentDefault) AgentWorkspaceDefaultRepository(db).get(agentId)?.workspaceId
        else null
    val workspace = if (useAgentDefault) threadWorkspacePort.takeIf { it.available }?.resolveNewThreadWorkspace(agentId)
        else requestedWorkspaceId
    NewThreadRootDefaultGuard.requireResolved(configuredDefault, workspace) { id ->
        RootWorkspaceRepository(db).getOrigin(id) != null ||
            WorkspaceRepository(db).get(id)?.rootReference?.startsWith("root:") == true
    }
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
