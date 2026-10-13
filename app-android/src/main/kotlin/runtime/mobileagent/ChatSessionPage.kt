// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.ConversationCursor
import runtime.mobileagent.feature.chat.ChatSessionUi

/** A bounded batch of drawer labels, read on the storage dispatcher. */
internal fun AppContainer.readChatSessionPage(archived: Boolean, cursor: ConversationCursor, names: Map<String, String>): Pair<List<ChatSessionUi>, ConversationCursor?> {
    val page = conversations.page(archived, cursor)
    val owners = agents.snapshotAgentIds(page.conversations.map { it.snapshotId })
    val labels = chatSessionWorkspaceLabels(page.conversations.map { it.id },
        bindingWorkspaceId = { threadWorkspacePort.conversationWorkspaceBinding(it)?.workspaceId },
        title = { runtimeIntegration.workspaceUiPresentation(it)?.title })
    return page.conversations.map { conversation ->
        val owner = owners[conversation.snapshotId]
        ChatSessionUi(id = conversation.id, title = conversation.title, timeLabel = conversation.updatedAt.take(16),
            agentId = owner, agentName = owner?.let(names::get).orEmpty(), workspaceLabel = labels.getValue(conversation.id))
    } to page.nextCursor
}
