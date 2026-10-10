// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch

data class ChatSessionUi(
    val id: String,
    val title: String,
    val preview: String = "",
    val timeLabel: String = "",
    val unread: Boolean = false,
    val agentName: String = "",
    /** Stable agent identity used only for local sidebar grouping. */
    val agentId: String? = null,
    /** Friendly workspace label only; never a path, URI, or locator. */
    val workspaceLabel: String = "",
)

data class ChatMessageUi(
    val id: String,
    val role: String,
    val text: String,
    val timeLabel: String = "",
    val citationIds: List<String> = emptyList(),
    val streaming: Boolean = false,
    /** Real provider-returned reasoning only; blank means no reasoning UI. */
    val reasoning: String = "",
    /** True while the provider is still sending the reasoning part. */
    val reasoningStreaming: Boolean = false,
    /** Optional compact event summary for tool/diff/test rows. */
    val eventSummary: String = "",
)

/** A display projection only. Durable assistant/tool records keep their protocol order. */
data class ChatTimelineItem(
    val message: ChatMessageUi,
    val toolEvents: List<ChatMessageUi> = emptyList(),
    val sourceIds: Set<String> = setOf(message.id),
    val orderedMessages: List<ChatMessageUi> = listOf(message),
)

fun groupConversationMessages(messages: List<ChatMessageUi>): List<ChatTimelineItem> {
    val items = mutableListOf<ChatTimelineItem>()
    var active: ChatTimelineItem? = null
    fun flush() {
        active?.let(items::add)
        active = null
    }
    messages.forEach { next ->
        when (next.role.lowercase()) {
            "assistant" -> {
                val current = active
                active = if (current == null) ChatTimelineItem(next) else current.copy(
                    message = current.message.copy(
                        text = listOf(current.message.text, next.text).filter(String::isNotBlank).joinToString("\n\n"),
                        reasoning = listOf(current.message.reasoning, next.reasoning).filter(String::isNotBlank).joinToString("\n\n"),
                        reasoningStreaming = next.reasoningStreaming,
                        eventSummary = listOf(current.message.eventSummary, next.eventSummary).filter(String::isNotBlank).distinct().joinToString("\n"),
                        citationIds = (current.message.citationIds + next.citationIds).distinct(),
                        streaming = next.streaming,
                        timeLabel = next.timeLabel,
                    ),
                    sourceIds = current.sourceIds + next.id,
                    orderedMessages = current.orderedMessages + next,
                )
            }
            "tool" -> {
                val current = active
                if (current == null) items += ChatTimelineItem(next) else active = current.copy(
                    toolEvents = current.toolEvents + next,
                    sourceIds = current.sourceIds + next.id,
                    orderedMessages = current.orderedMessages + next,
                )
            }
            else -> {
                flush()
                items += ChatTimelineItem(next)
            }
        }
    }
    flush()
    return items
}

data class ChatCitationUi(
    val id: String,
    val title: String,
    val source: String,
    val excerpt: String,
    val location: String = "",
    val verified: Boolean = false,
    /** Validated evidence bytes supplied by the host; the UI never reads a path or secret. */
    val imageBytes: ByteArray? = null,
    /** Host-supplied immutable source identity; never inferred from titles or excerpts. */
    val knowledgeBaseId: String = "",
    val documentVersionId: String = "",
    val chunkId: String = "",
    val assetId: String? = null,
)

data class ChatToolApprovalUi(
    val id: String,
    val name: String,
    val summary: String,
    val confirmationRequired: Boolean = true,
    val externalEffect: Boolean = false,
    /** Structured details are rendered locally and are never sent back to the model. */
    val command: String? = null,
    val cwd: String? = null,
    val authority: String? = null,
    val dangerousMode: String? = null,
    val highRisk: Boolean = false,
)

data class ChatPromptLayerUi(val label: String, val text: String, val editable: Boolean = false)

data class ChatRequestPreviewUi(
    val method: String,
    val url: String,
    val headers: String = "",
    val body: String = "",
    val redacted: Boolean = true,
)

data class ChatAgentOptionUi(val id: String, val label: String)

data class ChatCompactionUi(
    val id: String, val state: String, val sourceMessageIds: List<String>,
    val summaryJson: String?, val modelId: String, val reason: String,
    val beforeUnits: Long, val afterUnits: Long, val inputTokens: Int, val outputTokens: Int,
    val inputHash: String, val createdAt: String,
)

enum class ChatThreadWorkspaceState {
    BOUND,
    UNBOUND_AGENT_DEFAULT_AVAILABLE,
    UNBOUND_NO_AGENT_DEFAULT,
}

/**
 * UI-only summary of the workspace owned by the selected Agent. Workspace
 * grants are configured in Agent settings and are shared by that Agent's
 * sessions; the chat surface never mutates them.
 */
data class ChatWorkspaceAccessUi(
    val agentLabel: String = "",
    val workspaceSummary: String = "",
    val systemAccessLabel: String = "",
    val permissionLabel: String = "",
    val notice: String = "",
    val threadWorkspaceState: ChatThreadWorkspaceState = ChatThreadWorkspaceState.UNBOUND_NO_AGENT_DEFAULT,
    val agentDefaultWorkspaceId: String? = null,
    val agentDefaultWorkspaceLabel: String = "",
    val agentDefaultUnavailable: Boolean = false,
)

/** Fixed copy is resolved at composition time so a language change updates existing sessions. */
fun ChatWorkspaceAccessUi.localizedWorkspaceSummary(zh: Boolean): String = workspaceSummary.ifBlank {
    when (threadWorkspaceState) {
        ChatThreadWorkspaceState.BOUND -> if (zh) "已绑定工作区" else "Bound workspace"
        ChatThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE -> if (zh) "当前会话无工作区" else "No workspace for this conversation"
        ChatThreadWorkspaceState.UNBOUND_NO_AGENT_DEFAULT -> if (zh) "未配置工作区" else "No workspace"
    }
}

fun ChatWorkspaceAccessUi.localizedPermission(zh: Boolean): String = permissionLabel.ifBlank {
    when {
        threadWorkspaceState == ChatThreadWorkspaceState.BOUND -> if (zh) "已绑定" else "Bound"
        threadWorkspaceState == ChatThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE ->
            if (zh) "当前会话未绑定；默认值仅用于新会话" else "Unbound conversation; the default applies only to new conversations"
        agentDefaultUnavailable -> if (zh) "默认工作区授权已撤销或不可用" else "Default workspace access was revoked or is unavailable"
        else -> if (zh) "尚未授权此会话" else "This conversation has no workspace access"
    }
}

fun ChatWorkspaceAccessUi.localizedNotice(zh: Boolean): String = notice.ifBlank {
    when (threadWorkspaceState) {
        ChatThreadWorkspaceState.BOUND -> if (zh) "会话工作区已固定；Agent 默认值变化不会改动此会话。"
            else "The conversation workspace is fixed; changes to the Agent default do not change this conversation."
        ChatThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE ->
            if (zh) "当前会话保持无工作区；Agent 默认工作区只会用于新建会话。"
            else "This conversation stays unbound; the Agent default workspace applies only to new conversations."
        ChatThreadWorkspaceState.UNBOUND_NO_AGENT_DEFAULT -> if (agentDefaultUnavailable) {
            if (zh) "Agent 默认工作区授权已撤销或不可用；系统不会自动恢复。"
            else "Agent default workspace access was revoked or is unavailable; it is never restored automatically."
        } else {
            if (zh) "当前会话未绑定工作区。" else "This conversation has no bound workspace."
        }
    }
}

/** Resolve every status field at the rendering boundary, preserving real workspace titles. */
fun ChatWorkspaceAccessUi.localized(zh: Boolean): ChatWorkspaceAccessUi = copy(
    agentLabel = agentLabel.ifBlank { if (zh) "当前智能体" else "Current Agent" },
    workspaceSummary = localizedWorkspaceSummary(zh),
    permissionLabel = localizedPermission(zh),
    systemAccessLabel = systemAccessLabel.ifBlank { if (zh) "未启用系统增强访问" else "System access is not enabled" },
    notice = localizedNotice(zh),
)

/**
 * The approval callback intentionally has no session/persistent option.  This
 * card grants the current invocation only; capability grants are a separate
 * policy/repository operation and must never be inferred from a button click.
 */
enum class ToolApprovalChoice { APPROVE, REJECT }

/** Safe, host-supplied state for the request inspector. */
enum class ChatRequestInspectorAvailability {
    DISABLED,
    NOT_PREPARED,
    CONTEXT_LOST,
    READY,
}

data class ChatUiState(
    val sessions: List<ChatSessionUi> = emptyList(),
    val archivedSessions: List<ChatSessionUi> = emptyList(),
    val selectedSessionArchived: Boolean = false,
    val sessionActions: List<SessionAction> = listOf(SessionAction.ARCHIVE),
    val selectedSessionId: String? = null,
    val agents: List<ChatAgentOptionUi> = emptyList(),
    val selectedAgentId: String? = null,
    val messages: List<ChatMessageUi> = emptyList(),
    val input: String = "",
    val streaming: Boolean = false,
    val status: String = "",
    val statusKind: String = "",
    val textDegradation: Boolean = false,
    val citations: List<ChatCitationUi> = emptyList(),
    val selectedCitationId: String? = null,
    val pendingTool: ChatToolApprovalUi? = null,
    val promptLayers: List<ChatPromptLayerUi> = emptyList(),
    val requestPreview: ChatRequestPreviewUi? = null,
    /**
     * Nullable for compatibility with hosts that only provide the legacy
     * preview field.  The UI derives READY/NOT_PREPARED in that case; a host
     * can provide DISABLED or CONTEXT_LOST explicitly without exposing data.
     */
    val requestInspectorAvailability: ChatRequestInspectorAvailability? = null,
    val inspectorOpen: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** Optional host supplied empty-state copy; a blank value uses the session-aware default. */
    val emptyMessage: String = "",
    val language: String = "zh-CN",
    val workspaceAccess: ChatWorkspaceAccessUi = ChatWorkspaceAccessUi(),
    /** Safe workspace summaries used by the global drawer; no URI or path. */
    val workspaces: List<ChatWorkspaceUi> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val currentAuthorityLabel: String = "",
    val drawerDestinations: List<ChatDrawerDestinationUi> = emptyList(),
    val modelLabel: String = "",
    val compactions: List<ChatCompactionUi> = emptyList(),
)

/** Conversation presentation shared by the app shell and chat route. */
@Composable
fun rememberConversationPresentation(source: State<ChatUiState>): State<ChatUiState> = remember(source) {
    // Draft changes are read by the composer. They must not invalidate the
    // drawer, transcript, or markdown layout on every IME edit.
    derivedStateOf { source.value.copy(input = "") }
}

data class ChatActions(
    val onSessionAction: (String, SessionAction) -> Unit = { _, _ -> },
    val onRestoreSession: (String) -> Unit = {},
    val onInput: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onToggleDegradation: (Boolean) -> Unit = {},
    val onSelectSession: (String) -> Unit = {},
    val onNewSession: () -> Unit = {},
    val onNewSessionForAgent: (String) -> Unit = {},
    val onSelectAgent: (String) -> Unit = {},
    val onOpenCitation: (String) -> Unit = {},
    val onCloseCitation: () -> Unit = {},
    val onToolApproval: (ToolApprovalChoice) -> Unit = {},
    val onOpenRequestInspector: () -> Unit = {},
    val onCloseRequestInspector: () -> Unit = {},
    /** Opens Agent settings; workspace grants are changed there only. */
    val onOpenAgentSettings: (String?) -> Unit = {},
    val onSelectWorkspace: (String) -> Unit = {},
    val onNewSessionForWorkspace: (String) -> Unit = {},
    val onOpenWorkspacePicker: () -> Unit = {},
    /** Opens the SAF/workspace picker for a specific Agent without creating a session. */
    val onAuthorizeWorkspaceForAgent: (String) -> Unit = {},
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ChatScreen(state: ChatUiState, actions: ChatActions = ChatActions(), modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        var drawerOpen by rememberSaveable { mutableStateOf(false) }
        var workspaceOpen by rememberSaveable { mutableStateOf(false) }
        var workspaceTarget by rememberSaveable { mutableStateOf<String?>(null) }
        var workspaceTargetLabel by rememberSaveable { mutableStateOf("") }
        val drawerState = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val closeDrawer: () -> Unit = {
            drawerOpen = false
            scope.launch { drawerState.close() }
        }
        val openWorkspace: (String?, String) -> Unit = { targetId, targetLabel ->
            workspaceTarget = targetId
            workspaceTargetLabel = targetLabel
            workspaceOpen = true
            closeDrawer()
        }
        BackHandler(enabled = drawerOpen || workspaceOpen) {
            when {
                workspaceOpen -> workspaceOpen = false
                else -> closeDrawer()
            }
        }
        val content: @Composable () -> Unit = {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    ConversationSidebar(
                        state = state,
                        actions = actions,
                        onOpenWorkspace = openWorkspace,
                        modifier = Modifier.width(280.dp),
                    )
                    HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    ChatConversationContent(
                        state,
                        actions,
                        onOpenSidebar = {},
                        onOpenWorkspace = { openWorkspace(state.selectedAgentId, state.agents.firstOrNull { it.id == state.selectedAgentId }?.label.orEmpty()) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    ChatConversationContent(
                        state,
                        actions,
                        onOpenSidebar = {
                            drawerOpen = true
                            scope.launch { drawerState.open() }
                        },
                        onOpenWorkspace = { openWorkspace(state.selectedAgentId, state.agents.firstOrNull { it.id == state.selectedAgentId }?.label.orEmpty()) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
        if (wide) {
            content()
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = state.pendingTool == null,
                drawerContent = {
                    ModalDrawerSheet {
                        ConversationSidebar(
                            state = state,
                            actions = actions,
                            onClose = closeDrawer,
                            onOpenWorkspace = openWorkspace,
                            modifier = Modifier.fillMaxWidth(0.9f),
                        )
                    }
                },
            ) { content() }
        }
        if (workspaceOpen) {
            // Sessions only surface the Agent-owned workspace summary. Any
            // grant or backend change belongs to the Agent settings screen.
            val target = if (workspaceTarget == state.selectedAgentId || workspaceTarget == null) {
                state.workspaceAccess.copy(agentLabel = workspaceTargetLabel)
            } else {
                ChatWorkspaceAccessUi(
                    agentLabel = workspaceTargetLabel,
                    notice = if (state.language.equals("zh-CN", true)) {
                        "请进入该智能体设置查看和修改它的工作区。"
                    } else {
                        "Open this Agent's settings to view or change its workspace."
                    },
                )
            }
            ChatWorkspaceAccessSheet(
                state = target,
                zh = state.language.equals("zh-CN", true),
                onDismiss = { workspaceOpen = false },
                onOpenAgentSettings = {
                    workspaceOpen = false
                    actions.onOpenAgentSettings(workspaceTarget)
                },
            )
        }
    }
    state.selectedCitationId?.let { id -> state.citations.firstOrNull { it.id == id } }?.let {
        CitationDialog(it, actions.onCloseCitation, state.language.equals("zh-CN", true))
    }
}

/**
 * Canonical conversation surface for the global app shell.  It intentionally
 * owns no drawer or navigation state: the application shell supplies
 * [onOpenDrawer], while this surface keeps only conversation-local UI.
 * [ChatScreen] remains as a compatibility wrapper for older hosts and tests.
 */
@Composable
fun ConversationScreen(
    state: ChatUiState,
    actions: ChatActions = ChatActions(),
    onOpenDrawer: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    showGlobalMenu: Boolean = true,
    modifier: Modifier = Modifier,
    input: () -> String = { state.input },
) {
    Box(modifier.fillMaxSize()) {
        ChatConversationContent(
            state = state,
            actions = actions,
            onOpenSidebar = onOpenDrawer,
            onOpenWorkspace = onOpenWorkspace,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            minimalHeader = true,
            showGlobalMenu = showGlobalMenu,
            input = input,
        )
    }
    state.selectedCitationId?.let { id -> state.citations.firstOrNull { it.id == id } }?.let {
        CitationDialog(it, actions.onCloseCitation, state.language.equals("zh-CN", true))
    }
}

@Composable
private fun ChatConversationContent(
    state: ChatUiState,
    actions: ChatActions,
    onOpenSidebar: () -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier,
    minimalHeader: Boolean = false,
    showGlobalMenu: Boolean = true,
    input: () -> String = { state.input },
) {
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
                ChatHeader(
                    state,
                    actions,
                    onOpenSidebar,
                    onOpenWorkspace,
                    minimal = minimalHeader,
                    showGlobalMenu = showGlobalMenu,
                )
                UnboundWorkspaceDefaultCard(state, actions)
                if (state.selectedSessionArchived) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.language.equals("zh-CN", true)) "已归档的对话 · 只读" else "Archived conversation · Read only", Modifier.weight(1f))
                        TextButton(onClick = { state.selectedSessionId?.let(actions.onRestoreSession) }, modifier = Modifier.testTag("conversation.archive.restore")) {
                            Text(if (state.language.equals("zh-CN", true)) "恢复" else "Restore")
                        }
                    }
                }
                if (state.status.isNotBlank()) StatusLine(state.status, state.statusKind)
                if (state.textDegradation) {
                    Text(if (state.language.equals("zh-CN", true)) "纯文本模式：原始图片不会发送给模型，视觉证据可能不完整。"
                        else "Text-only mode: original images are not sent to the model; visual evidence may be incomplete.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag("conversation.visualDegradation"))
                }
                if (!state.streaming && (state.status.contains("压缩失败") || state.status.contains("预算不足") ||
                        state.status.contains("CONTEXT_OVERFLOW") || state.error?.contains("预算不足") == true ||
                        state.messages.lastOrNull()?.eventSummary?.let { it.contains("压缩失败") || it.contains("预算不足") } == true)) {
                    Text(if (state.language.equals("zh-CN", true)) "原始历史保留。可在智能体设置调整配置后新建会话；新会话不会自动复制历史或重做工具。"
                        else "Original history is retained. Adjust the Agent settings and start a new conversation; history and tool actions are not replayed.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = actions.onNewSession, modifier = Modifier.testTag("conversation.contextRecovery.new")) {
                        Text(if (state.language.equals("zh-CN", true)) "保留历史并新建会话" else "Keep history and start a new conversation")
                    }
                }
                val listState = rememberLazyListState()
                val scrollScope = rememberCoroutineScope()
                val timeline = remember(state.messages) { groupConversationMessages(state.messages) }
                val coveredIds = remember(state.compactions) {
                    state.compactions.lastOrNull { it.state == "SUCCEEDED" }?.sourceMessageIds.orEmpty().toSet()
                }
                ContextCompactionHistory(state.compactions, state.messages, state.language.equals("zh-CN", true)) { id ->
                    val index = timeline.indexOfFirst { id in it.sourceIds }
                    if (index >= 0) scrollScope.launch { listState.scrollToItem(index) }
                }
                if (state.loading) {
                    CenterState(if (state.language.equals("zh-CN", true)) "正在加载会话…" else "Loading conversations…", true, Modifier.weight(1f))
                } else if (state.error != null) {
                    CenterState(state.error, false, Modifier.weight(1f))
                } else if (state.messages.isEmpty()) {
                    CenterState(emptyConversationMessage(state, state.language.equals("zh-CN", true)), false, Modifier.weight(1f))
                } else {
                    LaunchedEffect(timeline.size, timeline.lastOrNull()?.sourceIds?.size, state.streaming) {
                        if (timeline.isNotEmpty()) listState.animateScrollToItem(timeline.lastIndex)
                    }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(timeline, key = { it.message.id }) { item ->
                            item.sourceIds.filter { it in coveredIds }.forEach { sourceId ->
                                Text(if (state.language.equals("zh-CN", true)) "已纳入上下文摘要 · 原文保留" else "Included in a context summary · original retained",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.testTag("conversation.summarized.$sourceId"))
                            }
                            MessageBubble(
                                message = item.message,
                                orderedMessages = item.orderedMessages,
                                citations = state.citations,
                                onCitation = actions.onOpenCitation,
                                zh = state.language.equals("zh-CN", true),
                                working = item == timeline.lastOrNull() && (state.streaming || state.pendingTool != null),
                            )
                        }
                    }
                }
                Composer(state, actions, input)
        }
        state.pendingTool?.let { pending ->
            // Keep the actual conversation visible behind the blocking sheet.
            // The full-size scrim intercepts touches on the underlying content.
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.30f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val compactApproval = maxHeight < 360.dp
                ApprovalCard(
                    approval = pending,
                    onChoice = actions.onToolApproval,
                    zh = state.language.equals("zh-CN", true),
                    detailMaxHeight = if (compactApproval) 48.dp else 168.dp,
                    compact = compactApproval,
                )
            }
        }
    }
}

@Composable
private fun UnboundWorkspaceDefaultCard(state: ChatUiState, actions: ChatActions) {
    if (state.workspaceAccess.threadWorkspaceState != ChatThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE) return
    val workspaceId = state.workspaceAccess.agentDefaultWorkspaceId ?: return
    val zh = state.language.equals("zh-CN", true)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("conversation.unbound.defaultAvailable"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                state.workspaceAccess.localizedNotice(zh),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.ui_agent_default_workspace_s_0ec344e3, (state.workspaceAccess.agentDefaultWorkspaceLabel)),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                onClick = { actions.onNewSessionForWorkspace(workspaceId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("conversation.unbound.newAtDefault"),
            ) {
                Text(
                    stringResource(R.string.ui_new_conversation_in_this_workspace_532596f7),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChatSessionList(state: ChatUiState, onSelect: (String) -> Unit, modifier: Modifier) {
    val zh = state.language.equals("zh-CN", true)
    Column(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text(stringResource(R.string.ui_conversations_23a085e0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (state.loading) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else if (state.sessions.isEmpty()) {
            Text(emptyConversationMessage(state, zh), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.sessions, key = { it.id }) { session ->
                    val selected = session.id == state.selectedSessionId
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(session.id) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(session.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (session.unread) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
                            }
                            if (session.preview.isNotBlank()) Text(session.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (session.timeLabel.isNotBlank()) Text(session.timeLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionChooser(state: ChatUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val zh = state.language.equals("zh-CN", true)
    val selected = state.sessions.firstOrNull { it.id == state.selectedSessionId }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.ui_conversations_23a085e0),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                Text(
                    selected?.title ?: if (zh) "选择会话" else "Choose conversation",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (state.sessions.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.ui_no_conversations_81fbc382)) }, onClick = { expanded = false }, enabled = false)
                else state.sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                session.title,
                                modifier = Modifier.widthIn(max = 220.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = { expanded = false; onSelect(session.id) },
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ChatHeader(
    state: ChatUiState,
    actions: ChatActions,
    onOpenSidebar: () -> Unit,
    onOpenWorkspace: () -> Unit,
    minimal: Boolean = false,
    showGlobalMenu: Boolean = true,
) {
    if (minimal) {
        ConversationTopBar(
            state,
            actions,
            onOpenSidebar,
            onOpenWorkspace,
            showGlobalMenu = showGlobalMenu,
        )
        return
    }
    val zh = state.language.equals("zh-CN", true)
    val session = state.sessions.firstOrNull { it.id == state.selectedSessionId }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    session?.title?.takeIf { it.isNotBlank() } ?: if (zh) "对话" else "Chat",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!session?.agentName.isNullOrBlank()) {
                    Text(
                        session?.agentName.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!session?.preview.isNullOrBlank()) {
                    Text(
                        session?.preview.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = onOpenSidebar,
                modifier = Modifier.testTag("chat.sidebar.open"),
            ) {
                Text(stringResource(R.string.ui_chats_b73b26c8), maxLines = 1)
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = actions.onNewSession,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(stringResource(R.string.ui_new_chat_6f463698), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AgentChooser(state, actions.onSelectAgent)
            OutlinedButton(
                onClick = onOpenWorkspace,
                modifier = Modifier.testTag("chat.workspace.open"),
            ) {
                Text(stringResource(R.string.ui_files_5c80b454), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilterChip(
                selected = state.textDegradation,
                onClick = { actions.onToggleDegradation(!state.textDegradation) },
                label = { Text(stringResource(R.string.ui_text_only_78afb413), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            TextButton(
                onClick = actions.onOpenRequestInspector,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.testTag("chat.requestInspector.open"),
            ) {
                Text(stringResource(R.string.ui_view_request_e63fbc35), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Compact, conversation-first header used by [ConversationScreen]. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConversationTopBar(
    state: ChatUiState,
    actions: ChatActions,
    onOpenDrawer: () -> Unit,
    onOpenWorkspace: () -> Unit,
    showGlobalMenu: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val zh = state.language.equals("zh-CN", true)
    val session = state.sessions.firstOrNull { it.id == state.selectedSessionId }
    val agent = session?.agentName?.takeIf { it.isNotBlank() }
        ?: state.agents.firstOrNull { it.id == state.selectedAgentId }?.label
        ?: if (zh) "未选择智能体" else "No agent selected"
    val workspace = state.workspaceAccess.localizedWorkspaceSummary(zh)
    var contextOpen by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("conversation.topBar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showGlobalMenu) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("conversation.drawer.open"),
            ) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = if (zh) "打开菜单" else "Open menu",
                )
            }
        }
        TextButton(
            onClick = { contextOpen = true },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag("conversation.context"),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$agent · $workspace",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.workspaceAccess.systemAccessLabel.isNotBlank()) {
                    Text(
                        state.workspaceAccess.systemAccessLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { overflowOpen = true },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("conversation.more"),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = if (zh) "更多选项" else "More options",
                )
            }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_inspect_request_dd0adbf1)) },
                    onClick = { overflowOpen = false; actions.onOpenRequestInspector() },
                    modifier = Modifier.testTag("conversation.requestInspector.open"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_new_conversation_9878af0c)) },
                    onClick = { overflowOpen = false; actions.onNewSession() },
                    modifier = Modifier.testTag("conversation.new"),
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (state.textDegradation) {
                                if (zh) "关闭纯文本模式" else "Disable text-only mode"
                            } else {
                                if (zh) "启用纯文本模式" else "Enable text-only mode"
                            },
                        )
                    },
                    onClick = {
                        overflowOpen = false
                        actions.onToggleDegradation(!state.textDegradation)
                    },
                    modifier = Modifier.testTag("conversation.textMode"),
                )
            }
        }
    }
    if (contextOpen) {
        ConversationContextSheet(
            state = state,
            onDismiss = { contextOpen = false },
            onOpenWorkspace = {
                contextOpen = false
                onOpenWorkspace()
            },
            onOpenAgentSettings = {
                contextOpen = false
                actions.onOpenAgentSettings(state.selectedAgentId)
            },
            onNewSession = {
                contextOpen = false
                actions.onNewSession()
            },
            onNewSessionAtDefault = { workspaceId ->
                contextOpen = false
                actions.onNewSessionForWorkspace(workspaceId)
            },
        )
    }
}

/** Context details are transient UI only; actions remain delegated to the host. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConversationContextSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenAgentSettings: () -> Unit,
    onNewSession: () -> Unit,
    onNewSessionAtDefault: (String) -> Unit,
) {
    val zh = state.language.equals("zh-CN", true)
    val agent = state.agents.firstOrNull { it.id == state.selectedAgentId }?.label
        ?: if (zh) "未选择智能体" else "No agent selected"
    val workspace = state.workspaceAccess.localizedWorkspaceSummary(zh)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("conversation.context.sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.ui_current_context_349f8670), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.ui_agent_252fc75c), style = MaterialTheme.typography.labelLarge)
            Text(agent, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.ui_workspace_58a2bde1), style = MaterialTheme.typography.labelLarge)
            Text(workspace, style = MaterialTheme.typography.bodyLarge)
            if (state.workspaceAccess.threadWorkspaceState == ChatThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE &&
                state.workspaceAccess.agentDefaultWorkspaceId != null
            ) {
                Text(stringResource(R.string.ui_agent_default_workspace_e459a394), style = MaterialTheme.typography.labelLarge)
                Text(state.workspaceAccess.agentDefaultWorkspaceLabel, style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = { onNewSessionAtDefault(requireNotNull(state.workspaceAccess.agentDefaultWorkspaceId)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("conversation.context.newAtDefault"),
                ) {
                    Text(stringResource(R.string.ui_new_conversation_in_this_workspace_532596f7))
                }
            }
            if (state.workspaceAccess.systemAccessLabel.isNotBlank()) {
                Text(state.workspaceAccess.systemAccessLabel, style = MaterialTheme.typography.bodySmall)
            }
            if (state.workspaceAccess.localizedPermission(zh).isNotBlank()) {
                Text(
                    stringResource(R.string.ui_permission_s_76ae437c, (state.workspaceAccess.localizedPermission(zh))),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.modelLabel.isNotBlank()) {
                Text(stringResource(R.string.ui_model_44c0cd42), style = MaterialTheme.typography.labelLarge)
                Text(state.modelLabel, style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(
                onClick = onOpenWorkspace,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("conversation.context.workspace"),
            ) {
                Text(stringResource(R.string.ui_view_workspace_b7713d6d))
            }
            OutlinedButton(
                onClick = onOpenAgentSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("conversation.context.agentSettings"),
            ) {
                Text(stringResource(R.string.ui_manage_agent_fbcf94f0))
            }
            Button(
                onClick = onNewSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("conversation.context.new"),
            ) {
                Text(stringResource(R.string.ui_new_conversation_843829cd))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AgentChooser(state: ChatUiState, onSelect: (String) -> Unit) {
    val zh = state.language.equals("zh-CN", true)
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val selected = state.agents.firstOrNull { it.id == state.selectedAgentId }
    if (state.agents.isEmpty()) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 112.dp, max = 180.dp),
        ) {
            Text(stringResource(R.string.ui_no_agent_222531b8), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.widthIn(max = 180.dp),
            ) {
                Text(
                    selected?.label ?: if (zh) "选择智能体" else "Choose agent",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.agents.forEach { agent ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                agent.label,
                                modifier = Modifier.widthIn(max = 180.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = { expanded = false; onSelect(agent.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: String, kind: String) {
    val color = when (kind.lowercase()) {
        "failed", "error" -> MaterialTheme.colorScheme.error
        "waiting" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(status, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ContextCompactionHistory(records: List<ChatCompactionUi>, messages: List<ChatMessageUi>, zh: Boolean, onSource: (String) -> Unit) {
    if (records.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("conversation.compaction.open")) {
        Text(if (zh) "上下文摘要 · ${records.count { it.state == "SUCCEEDED" }} 次完成" else
            "Context summaries · ${records.count { it.state == "SUCCEEDED" }} completed")
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.ui_context_compaction_history_3a8b9983)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("conversation.compaction.history"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ui_summaries_use_this_session_s_model_df6e6f50))
                records.asReversed().forEach { record ->
                    val label = when (record.state) {
                        "PREPARED" -> if (zh) "已准备" else "Prepared"
                        "DISPATCHED" -> if (zh) "正在压缩" else "Compressing"
                        "SUCCEEDED" -> if (zh) "已完成" else "Completed"
                        "UNKNOWN_OUTCOME" -> if (zh) "结果未知，不自动重试" else "Outcome unknown; no automatic retry"
                        "CANCELLED" -> if (zh) "已取消" else "Cancelled"
                        else -> if (zh) "未完成，原始上下文保留" else "Failed; original context retained"
                    }
                    Text("$label · ${record.createdAt.take(19)}", style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.ui_model_s_covers_s_original_messages_8990b847, (record.modelId), (record.sourceMessageIds.size)))
                    Text(stringResource(R.string.ui_conservative_input_estimate_s_s_units_baea5d49, (record.beforeUnits), (record.afterUnits), (record.inputTokens), (record.outputTokens)))
                    record.summaryJson?.let { Text(it, modifier = Modifier.testTag("conversation.compaction.summary.${record.id}")) }
                    var sourcesOpen by remember(record.id) { mutableStateOf(false) }
                    var sourceLimit by remember(record.id) { mutableStateOf(50) }
                    TextButton(onClick = { sourcesOpen = !sourcesOpen }) { Text(stringResource(R.string.ui_inspect_covered_originals_0fb5f0e7)) }
                    if (sourcesOpen) {
                        val byId = messages.associateBy { it.id }
                        record.sourceMessageIds.take(sourceLimit).forEach { id ->
                            TextButton(onClick = { open = false; onSource(id) },
                                modifier = Modifier.testTag("conversation.compaction.source.$id")) {
                                Text(byId[id]?.let { "${it.role}: ${it.text.take(160)}" } ?: id,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (sourceLimit < record.sourceMessageIds.size) TextButton(onClick = { sourceLimit += 50 }) {
                            Text(stringResource(R.string.ui_more_originals_8d54d6be))
                        }
                        Text("SHA-256: ${record.inputHash}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.ui_close_6cf4a777)) } },
    )
}

/**
 * Whether a message renders as a compact tool event row.  Only a tool message does: an
 * assistant message always keeps its answer bubble even when it carries a summary
 * (R3 QA P3 — a retrieval-coverage notice used to REPLACE the answer text through the
 * row's `eventSummary.ifBlank { text }`, capped at two lines).
 */
fun isToolEventRow(role: String): Boolean = role.equals("tool", ignoreCase = true)

/**
 * The supplementary disclosure to show under a message, or null when it adds nothing:
 * blank, or already contained in the visible answer text (a terminal error message is
 * part of both and must not be printed twice).
 */
fun secondaryNoticeOf(message: ChatMessageUi): String? =
    message.eventSummary.takeIf { it.isNotBlank() && !message.text.contains(it) }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MessageBubble(
    message: ChatMessageUi,
    orderedMessages: List<ChatMessageUi> = listOf(message),
    citations: List<ChatCitationUi>,
    onCitation: (String) -> Unit,
    zh: Boolean,
    working: Boolean = false,
) {
    val user = message.role.equals("user", ignoreCase = true)
    val assistant = message.role.equals("assistant", ignoreCase = true)
    val replyParts = remember(orderedMessages) { orderedMessages.filter { it.role.equals("assistant", true) } }
    val presentation = remember(replyParts, message.citationIds, citations) {
        presentReply(replyParts.map { it.text }, message.citationIds, citations)
    }
    val tools = remember(orderedMessages) { orderedMessages.filter { isToolEventRow(it.role) } }
    var toolsOpen by rememberSaveable(message.id) { mutableStateOf(false) }
    var copied by remember(presentation.copyText) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(working) { if (working) toolsOpen = false }
    val aqua = MaterialTheme.colorScheme.background == Color(0xFFF2F9FD)
    val userInk = if (aqua) Color(0xFF003B52) else MaterialTheme.colorScheme.onPrimary
    // Only a tool message becomes a compact event row.  An assistant message keeps
    // its answer bubble even when it carries an event summary: R3 QA P3 showed a
    // retrieval-coverage notice REPLACING the answer text (the row renders
    // `eventSummary.ifBlank { text }`, capped at two lines), so a real reply looked
    // like a bare notice card.
    val tool = isToolEventRow(message.role)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (tool) {
            if (working) ToolEventRow(message = message, zh = zh, modifier = Modifier.fillMaxWidth())
            else ToolDetailsButton(message.id, tools.size, zh) { toolsOpen = true }
        } else {
            val bubbleModifier = Modifier
                .fillMaxWidth(0.82f)
                .testTag("conversation.message.${message.id}")
            val body: @Composable () -> Unit = {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (user) { if (zh) "你" else "You" } else message.role,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (message.reasoning.isNotBlank()) {
                        ReasoningDisclosure(
                            messageId = message.id,
                            text = message.reasoning,
                            streaming = message.reasoningStreaming,
                            zh = zh,
                        )
                    }
                    var replyIndex = 0
                    orderedMessages.forEach { part ->
                        val displayedText = if (part.role.equals("assistant", true)) presentation.texts[replyIndex++] else part.text
                        if (isToolEventRow(part.role)) {
                            if (working) ToolEventRow(part, zh, Modifier.padding(top = 4.dp))
                        }
                        else if (part.text.isNotBlank()) SelectionContainer {
                            if (part.role.equals("assistant", true)) MarkdownText(
                                displayedText, Modifier.padding(top = 4.dp), presentation.markers, onCitation,
                            )
                            else Text(part.text, Modifier.padding(top = 4.dp))
                        }
                    }
                    if (!user && orderedMessages.none { it.text.isNotBlank() || it.reasoning.isNotBlank() || isToolEventRow(it.role) }) {
                        Text(stringResource(R.string.ui_no_visible_output_yet_check_the_66aa196b),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("conversation.emptyAnswer.${message.id}"))
                    }
                    // Supplementary disclosure, never a substitute for the answer:
                    // shown only when it adds information the answer text does not
                    // already carry (an error message is already part of the text).
                    val notice = secondaryNoticeOf(message)
                    if (notice != null) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (user) userInk else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp).testTag("conversation.notice.${message.id}"),
                        )
                    }
                    if (message.streaming) Text(stringResource(R.string.ui_streaming_96edaa09), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    if (message.timeLabel.isNotBlank()) Text(message.timeLabel, style = MaterialTheme.typography.labelSmall)
                    if (!working && tools.isNotEmpty()) ToolDetailsButton(message.id, tools.size, zh) { toolsOpen = true }
                    if (assistant && !working && presentation.copyText.isNotBlank()) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(presentation.copyText)); copied = true },
                            modifier = Modifier.testTag("conversation.copy.${message.id}"),
                        ) {
                            Text(if (copied) { if (zh) "已复制" else "Copied" }
                                else { if (zh) "复制回复" else "Copy reply" })
                        }
                    }
                }
            }
            val shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomEnd = if (user) 5.dp else 18.dp,
                bottomStart = if (user) 18.dp else 5.dp,
            )
            val container = if (user && aqua) Color(0xFF66CCFF) else if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
            // A tall message's rounded clipping layer can reject hits on visible
            // links/buttons after scrolling. Draw the same rounded decoration
            // without that layer; body padding keeps content inside its corners.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent,
                    contentColor = if (user) userInk else MaterialTheme.colorScheme.onSurface,
                ),
                shape = RectangleShape,
                modifier = bubbleModifier.background(container, shape)
                    .then(if (user) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)),
            ) { body() }
        }
    }
    if (toolsOpen && !working) ToolDetailsPage(tools, zh) { toolsOpen = false }
}

@Composable
private fun ToolDetailsButton(messageId: String, count: Int, zh: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("conversation.tools.open.$messageId")) {
        Text(stringResource(R.string.ui_tool_records_s_42bfa81f, (count)))
    }
}

@Composable
private fun ToolDetailsPage(tools: List<ChatMessageUi>, zh: Boolean, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp).testTag("conversation.tools.detail")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_tool_records_a37123a8), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose, modifier = Modifier.testTag("conversation.tools.close")) {
                        Text(stringResource(R.string.ui_back_to_reply_ed5e8395))
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tools, key = { it.id }) { ToolEventRow(it, zh, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun ToolEventRow(message: ChatMessageUi, zh: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.testTag("conversation.tool.${message.id}"),
    ) {
        Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ui_tool_cc2baf94),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                message.eventSummary.ifBlank { message.text },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) { if (zh) "收起" else "Collapse" } else { if (zh) "查看并复制工具结果" else "View and copy tool result" }) }
        if (expanded) SelectionContainer { Text(message.text, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun ReasoningDisclosure(
    messageId: String,
    text: String,
    streaming: Boolean,
    zh: Boolean,
) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .testTag("conversation.reasoning.$messageId"),
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("conversation.reasoning.toggle.$messageId"),
        ) {
            Text(
                when {
                    streaming && !expanded -> if (zh) "思考中…" else "Thinking…"
                    expanded -> if (zh) "收起思考" else "Hide reasoning"
                    else -> if (zh) "显示思考" else "Show reasoning"
                },
                maxLines = 1,
            )
        }
        if (expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("conversation.reasoning.body.$messageId"),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ApprovalCard(
    approval: ChatToolApprovalUi,
    onChoice: (ToolApprovalChoice) -> Unit,
    zh: Boolean,
    detailMaxHeight: androidx.compose.ui.unit.Dp,
    compact: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape))
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ui_confirmation_required_b2fc4b04),
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                approval.name,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = detailMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag("chat.approval.details"),
            ) {
                approval.command?.let {
                    Text(stringResource(R.string.ui_command_9cfdf51b), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(it)
                }
                approval.cwd?.let {
                    Text(stringResource(R.string.ui_working_directory_a7efee9a), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(it)
                }
                approval.authority?.let {
                    Text(stringResource(R.string.ui_authority_b04ffce4), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(it)
                }
                approval.dangerousMode?.let {
                    Text(stringResource(R.string.ui_dangerous_mode_de499d98), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(it)
                }
                if (approval.highRisk) {
                    Text(stringResource(R.string.ui_high_risk_reconfirmation_required_0a28d24e), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
                Text(approval.summary, Modifier.padding(top = 4.dp))
                if (approval.externalEffect) {
                    Text(
                        stringResource(R.string.ui_this_request_may_leave_the_device_de053073),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.ui_allows_this_invocation_only_it_does_a84a39e1),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (compact) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("chat.approval.actions"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { onChoice(ToolApprovalChoice.REJECT) },
                        modifier = Modifier.weight(1f).testTag("chat.approval.reject"),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.ui_reject_52e8be4e), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    Button(
                        onClick = { onChoice(ToolApprovalChoice.APPROVE) },
                        modifier = Modifier.weight(1f).testTag("chat.approval.approve"),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.ui_allow_once_5c4277ed), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("chat.approval.actions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onChoice(ToolApprovalChoice.REJECT) },
                        modifier = Modifier.testTag("chat.approval.reject"),
                    ) { Text(stringResource(R.string.ui_reject_52e8be4e)) }
                    Button(
                        onClick = { onChoice(ToolApprovalChoice.APPROVE) },
                        modifier = Modifier.testTag("chat.approval.approve"),
                    ) { Text(stringResource(R.string.ui_allow_once_5c4277ed)) }
                }
            }
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, actions: ChatActions, input: () -> String) {
    val text = input()
    var field by remember(state.selectedSessionId, state.selectedAgentId) { mutableStateOf(TextFieldValue(text)) }
    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    SideEffect { if (field.text != text) field = value }
    val zh = state.language.equals("zh-CN", true)
    val aqua = MaterialTheme.colorScheme.background == Color(0xFFF2F9FD)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var submitting by remember { mutableStateOf(false) }
    fun submit() {
        if (submitting) return
        submitting = true
        try {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            actions.onSend()
        } finally {
            submitting = false
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                field = it
                if (it.text != text) actions.onInput(it.text)
            },
            enabled = state.pendingTool == null && !state.selectedSessionArchived,
            placeholder = { Text(stringResource(R.string.ui_ask_a_follow_up_93697d6f)) },
            minLines = 1,
            maxLines = 5,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = if (zh) "消息" else "Message" }
                .testTag("conversation.composer.input"),
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (state.streaming) {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    keyboard?.hide()
                    actions.onCancel()
                },
                modifier = Modifier.testTag("conversation.composer.cancel"),
            ) { Text(stringResource(R.string.ui_cancel_998b9c48)) }
        } else {
            Button(
                onClick = ::submit,
                enabled = text.isNotBlank() && state.pendingTool == null && !state.selectedSessionArchived,
                shape = CircleShape,
                colors = if (aqua) ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF66CCFF),
                    contentColor = Color(0xFF003B52),
                ) else ButtonDefaults.buttonColors(),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp).testTag("conversation.composer.send"),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (zh) "发送" else "Send") }
        }
    }
}

private fun emptyConversationMessage(state: ChatUiState, chinese: Boolean): String =
    state.emptyMessage.ifBlank {
        if (state.selectedSessionId != null) {
            if (chinese) "暂无消息，发送第一条消息。" else "No messages yet. Send the first message."
        } else {
            if (chinese) "新建会话以开始。" else "Create a conversation to start."
        }
    }

@Composable
private fun CenterState(label: String, loading: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun CitationDialog(citation: ChatCitationUi, onClose: () -> Unit, zh: Boolean) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(citation.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (citation.location.isNotBlank()) Text(citation.location, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                CitationImagePreview(citation, zh)
                SelectionContainer { Text(citation.excerpt, modifier = Modifier.padding(top = 12.dp).testTag("conversation.citation.excerpt")) }
                Text(if (citation.verified) { if (zh) "证据已验证" else "Verified evidence" } else { if (zh) "证据状态不可用" else "Evidence status unavailable" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(stringResource(R.string.ui_close_6cf4a777)) } },
    )
}

private const val MAX_CITATION_IMAGE_BYTES = 16 * 1024 * 1024
private const val MAX_CITATION_IMAGE_SOURCE_DIMENSION = 32_768
private const val MAX_CITATION_IMAGE_SOURCE_AREA = 268_435_456L
private const val MAX_CITATION_IMAGE_DIMENSION = 2_048

/**
 * Displays only bounded evidence supplied by the host. Bounds are inspected
 * before allocating pixels, and malformed or oversized data stays text-only.
 */
@Composable
private fun CitationImagePreview(citation: ChatCitationUi, zh: Boolean) {
    val bytes = citation.imageBytes ?: return
    if (bytes.isEmpty()) {
        Text(
            stringResource(R.string.ui_the_citation_image_is_empty_metadata_5e639676),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        return
    }
    if (bytes.size > MAX_CITATION_IMAGE_BYTES) {
        Text(
            stringResource(R.string.ui_the_citation_image_is_too_large_d59c4e23),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        return
    }

    val bounds = remember(bytes) {
        runCatching {
            BitmapFactory.Options().also { options ->
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }.getOrNull()
    }
    val width = bounds?.outWidth ?: -1
    val height = bounds?.outHeight ?: -1
    val boundsAccepted = width > 0 && height > 0 &&
        width <= MAX_CITATION_IMAGE_SOURCE_DIMENSION &&
        height <= MAX_CITATION_IMAGE_SOURCE_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_CITATION_IMAGE_SOURCE_AREA
    if (!boundsAccepted) {
        Text(
            stringResource(R.string.ui_the_citation_image_dimensions_are_invalid_52f6d487),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        return
    }

    val sample = remember(width, height) { citationImageSample(width, height) }
    val bitmap = remember(bytes, sample) {
        runCatching {
            BitmapFactory.Options().also { options ->
                options.inSampleSize = sample
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
            }.let { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
        }.getOrNull()?.takeIf {
            it.width in 1..MAX_CITATION_IMAGE_DIMENSION && it.height in 1..MAX_CITATION_IMAGE_DIMENSION
        }
    }
    if (bitmap == null) {
        Text(
            stringResource(R.string.ui_the_citation_image_could_not_be_e9fd2779),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = if (zh) "引用图片：${citation.title}" else "Citation image: ${citation.title}",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .padding(top = 10.dp),
        )
    }
}

private fun citationImageSample(width: Int, height: Int): Int {
    var sample = 1
    while ((width + sample - 1) / sample > MAX_CITATION_IMAGE_DIMENSION ||
        (height + sample - 1) / sample > MAX_CITATION_IMAGE_DIMENSION
    ) {
        if (sample >= MAX_CITATION_IMAGE_DIMENSION) return MAX_CITATION_IMAGE_DIMENSION
        sample = (sample shl 1).coerceAtMost(MAX_CITATION_IMAGE_DIMENSION)
    }
    return sample
}

@Composable
fun RequestInspectorScreen(
    request: ChatRequestPreviewUi?,
    layers: List<ChatPromptLayerUi>,
    onClose: () -> Unit,
    zh: Boolean,
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
    availability: ChatRequestInspectorAvailability = request?.let { ChatRequestInspectorAvailability.READY }
        ?: ChatRequestInspectorAvailability.NOT_PREPARED,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showPageTitle) {
                Text(stringResource(R.string.ui_request_inspector_4337f973), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = onClose) { Text(stringResource(R.string.ui_close_6cf4a777)) }
        }
        // An explicit DISABLED state is authoritative even when a caller still
        // holds an older in-memory preview.  Keep this guard at the rendering
        // boundary so stale URL, headers, body, and prompt layers cannot leak.
        val effectiveAvailability = when {
            availability == ChatRequestInspectorAvailability.DISABLED -> ChatRequestInspectorAvailability.DISABLED
            request != null -> ChatRequestInspectorAvailability.READY
            availability == ChatRequestInspectorAvailability.READY -> ChatRequestInspectorAvailability.CONTEXT_LOST
            else -> availability
        }
        val effectiveRequest = request.takeIf { effectiveAvailability == ChatRequestInspectorAvailability.READY }
        if (effectiveRequest == null) {
            val message = when (effectiveAvailability) {
                ChatRequestInspectorAvailability.DISABLED -> if (zh) "请求检查器已关闭，请到设置开启。" else "Request inspection is disabled. Enable it in Settings."
                ChatRequestInspectorAvailability.NOT_PREPARED -> if (zh) "请求尚未准备。发送消息并完成请求准备后，这里会显示脱敏请求。" else "The request is not prepared yet. A redacted request will appear after a message is prepared."
                ChatRequestInspectorAvailability.CONTEXT_LOST -> if (zh) "此前的请求预览未保留。请求内容仅保存在本次进程内；确认运行状态后主动发送新消息，才能查看新的脱敏请求。不会为检查器自动重发旧请求。" else "The earlier request preview is no longer available. Request contents are kept only in this process. Check the run status before explicitly sending a new message to inspect a new redacted request. Old requests are not resent for inspection."
                ChatRequestInspectorAvailability.READY -> error("READY without a request is normalized above")
            }
            Text(message, modifier = Modifier.padding(top = 16.dp).testTag("chat.requestInspector.state"))
        } else {
            Text("${effectiveRequest.method} ${effectiveRequest.url}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            if (effectiveRequest.redacted) {
                Text(
                    stringResource(R.string.ui_sensitive_headers_and_keys_are_redacted_09373fcc),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (effectiveRequest.headers.isNotBlank()) Text(effectiveRequest.headers, modifier = Modifier.padding(top = 10.dp))
            if (effectiveRequest.body.isNotBlank()) Text(effectiveRequest.body, modifier = Modifier.padding(top = 10.dp))
            if (layers.isNotEmpty()) {
                Text(stringResource(R.string.ui_prompt_layers_17c857e1), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
                layers.forEach { layer ->
                    Text(layer.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Text(layer.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RequestInspectorDialog(request: ChatRequestPreviewUi, layers: List<ChatPromptLayerUi>, onClose: () -> Unit, zh: Boolean) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ui_request_inspector_4337f973)) },
        text = {
            RequestInspectorScreen(request, layers, onClose, zh)
        },
        confirmButton = { Button(onClick = onClose) { Text(stringResource(R.string.ui_close_6cf4a777)) } },
    )
}
