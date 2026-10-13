// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent

import runtime.mobileagent.data.ChatDraftOwner
import runtime.mobileagent.data.ConversationCursor
import runtime.mobileagent.knowledge.Citation
import runtime.mobileagent.domain.ContextCompactionRecord
import runtime.mobileagent.domain.Message
import runtime.mobileagent.domain.MessageRole
import runtime.mobileagent.feature.chat.*

internal data class ChatReadResult(
    val state: ChatUiState,
    val citations: Map<String, Pair<Citation, String>>,
    val activeCursor: ConversationCursor?,
    val archivedCursor: ConversationCursor?,
    val messageCursor: String?,
)

/** Called on the storage dispatcher. It neither selects a page nor changes mutable UI state. */
internal suspend fun readChatHistory(
    container: AppContainer,
    previous: ChatUiState,
    savedSessionId: String?,
    savedAgentId: String?,
    inspectorOpen: Boolean,
    seedCitations: Map<String, Pair<Citation, String>>,
    messageUi: (Message) -> ChatMessageUi,
    restoreCitations: (String, MutableMap<String, Pair<Citation, String>>) -> Unit,
    citationUi: (Map<String, Pair<Citation, String>>) -> List<ChatCitationUi>,
    compactionUi: (ContextCompactionRecord) -> ChatCompactionUi,
    workspaceUi: (String?, String?, String?) -> ChatWorkspaceAccessUi,
    persistedPreviewHint: (String?) -> Boolean,
): ChatReadResult {
    val repository = container.conversations
    val active = repository.page()
    val archived = repository.page(archived = true)
    val selected = listOf(previous.selectedSessionId, savedSessionId,
        container.uiPreferences.getString("selected-conversation", null))
        .firstOrNull { it != null && repository.get(it) != null }
    val selectedConversation = selected?.let(repository::get)
    val conversations = (active.conversations + archived.conversations + listOfNotNull(selectedConversation)).distinctBy { it.id }
    val agents = container.agents.list()
    val snapshotAgents = container.agents.snapshotAgentIds(conversations.map { it.snapshotId })
    val agentId = selectedConversation?.let { snapshotAgents[it.snapshotId] }
        ?: previous.selectedAgentId ?: savedAgentId ?: container.uiPreferences.getString("selected-agent", null) ?: agents.firstOrNull()?.id
    val names = agents.associateBy { it.id }
    val page = selected?.let { repository.messagePage(it) }
    val source = linkedMapOf<String, Pair<Citation, String>>()
    if (previous.selectedSessionId == selected) source.putAll(seedCitations)
    page?.messages.orEmpty().forEach { restoreCitations(it.metadataJson, source) }
    val fresh = page?.messages.orEmpty().mapIndexed { index, message ->
        val ui = messageUi(message)
        if (index == page!!.messages.lastIndex && message.role == MessageRole.USER)
            ui.copy(eventSummary = "消息已在本机保存；尚无本轮完成确认，送达与处理结果未确认，不会自动重发。") else ui
    }
    val freshIds = fresh.map { it.id }.toSet()
    val messages = if (previous.selectedSessionId == selected) previous.messages.filterNot { it.id in freshIds } + fresh else fresh
    val moreMessages = if (previous.selectedSessionId == selected && previous.messages.size > fresh.size)
        previous.hasMoreMessages else page?.nextBeforeMessageId != null
    val labels = chatSessionWorkspaceLabels(conversations.map { it.id },
        bindingWorkspaceId = { container.threadWorkspacePort.conversationWorkspaceBinding(it)?.workspaceId },
        title = { container.runtimeIntegration.workspaceUiPresentation(it)?.title })
    fun session(conversation: runtime.mobileagent.domain.Conversation): ChatSessionUi {
        val owner = snapshotAgents[conversation.snapshotId]
        return ChatSessionUi(conversation.id, conversation.title, timeLabel = conversation.updatedAt.take(16),
            agentName = owner?.let { names[it]?.name } ?: "配置快照 " + conversation.snapshotId.take(8),
            agentId = owner, workspaceLabel = labels.getValue(conversation.id))
    }
    val draftOwner = chatDraftOwner(selected, agentId)
    val inspector = container.uiPreferences.getBoolean("request-inspector", true)
    return ChatReadResult(previous.copy(
        sessions = conversations.filterNot { it.archived }.map(::session),
        archivedSessions = conversations.filter { it.archived }.map(::session),
        selectedSessionArchived = selectedConversation?.archived == true,
        selectedSessionId = selected,
        selectedAgentId = agentId,
        agents = agents.map { ChatAgentOptionUi(it.id, it.name) },
        input = container.chatDraftPersistence.load(draftOwner),
        messages = messages,
        citations = citationUi(source),
        compactions = selected?.let { container.contextCompactions.list(it).map(compactionUi) }.orEmpty(),
        workspaceAccess = workspaceUi(selected, agentId, agentId?.let { names[it]?.name }),
        requestPreview = previous.requestPreview?.takeIf { inspector },
        requestInspectorAvailability = resolveRequestInspectorAvailability(inspector,
            inspector && previous.requestPreview != null, persistedPreviewHint(selected)),
        inspectorOpen = inspectorOpen,
        loading = false,
        error = null,
        hasMoreSessions = active.nextCursor != null,
        hasMoreArchivedSessions = archived.nextCursor != null,
        hasMoreMessages = moreMessages,
    ), source, active.nextCursor, archived.nextCursor,
        messages.firstOrNull()?.id?.takeIf { moreMessages })
}

internal fun chatDraftOwner(sessionId: String?, agentId: String?) = when {
    sessionId != null -> ChatDraftOwner("conversation", sessionId)
    agentId != null -> ChatDraftOwner("agent", agentId)
    else -> ChatDraftOwner("unassigned", "composer")
}
