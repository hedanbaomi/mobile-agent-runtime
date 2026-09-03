// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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

data class ChatCitationUi(
    val id: String,
    val title: String,
    val source: String,
    val excerpt: String,
    val location: String = "",
    val verified: Boolean = false,
    /** Validated evidence bytes supplied by the host; the UI never reads a path or secret. */
    val imageBytes: ByteArray? = null,
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
    val agentLabel: String = "当前智能体",
    val workspaceSummary: String = "未配置工作区",
    val systemAccessLabel: String = "未启用系统增强访问",
    val permissionLabel: String = "尚未授权",
    val notice: String = "",
    val threadWorkspaceState: ChatThreadWorkspaceState = ChatThreadWorkspaceState.UNBOUND_NO_AGENT_DEFAULT,
    val agentDefaultWorkspaceId: String? = null,
    val agentDefaultWorkspaceLabel: String = "",
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
)

data class ChatActions(
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
        var workspaceTargetLabel by rememberSaveable { mutableStateOf("当前智能体") }
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
                        onOpenWorkspace = { openWorkspace(state.selectedAgentId, state.agents.firstOrNull { it.id == state.selectedAgentId }?.label ?: "当前智能体") },
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
                        onOpenWorkspace = { openWorkspace(state.selectedAgentId, state.agents.firstOrNull { it.id == state.selectedAgentId }?.label ?: "当前智能体") },
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
) {
    BoxWithConstraints(modifier) {
        // The detail viewport gives up space first when the IME is visible,
        // while the action FlowRow remains outside the scroll container.  At
        // least 72 dp is retained so even a compact screen can inspect the
        // long command/summary and reach both actions.
        val compactApproval = maxHeight < 360.dp
        val approvalDetailMaxHeight = if (compactApproval) 48.dp else 168.dp
        Column(Modifier.fillMaxSize()) {
            if (state.pendingTool != null) {
                // Approval is a blocking interaction.  Give it the whole
                // conversation viewport so headers and empty-state copy cannot
                // push the confirmation actions behind the IME or bottom bar.
                ApprovalCard(
                    approval = state.pendingTool,
                    onChoice = actions.onToolApproval,
                    zh = state.language.equals("zh-CN", true),
                    detailMaxHeight = approvalDetailMaxHeight,
                    compact = compactApproval,
                )
            } else {
                ChatHeader(
                    state,
                    actions,
                    onOpenSidebar,
                    onOpenWorkspace,
                    minimal = minimalHeader,
                    showGlobalMenu = showGlobalMenu,
                )
                UnboundWorkspaceDefaultCard(state, actions)
                if (state.status.isNotBlank()) StatusLine(state.status, state.statusKind)
                if (state.loading) {
                    CenterState(if (state.language.equals("zh-CN", true)) "正在加载会话…" else "Loading conversations…", true, Modifier.weight(1f))
                } else if (state.error != null) {
                    CenterState(state.error, false, Modifier.weight(1f))
                } else if (state.messages.isEmpty()) {
                    CenterState(emptyConversationMessage(state, state.language.equals("zh-CN", true)), false, Modifier.weight(1f))
                } else {
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.messages.size, state.streaming) {
                        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
                    }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.messages, key = { it.id }) {
                            MessageBubble(
                                message = it,
                                citations = state.citations,
                                onCitation = actions.onOpenCitation,
                                zh = state.language.equals("zh-CN", true),
                                minimal = minimalHeader,
                            )
                        }
                    }
                }
                Composer(state, actions)
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
                state.workspaceAccess.notice.ifBlank {
                    if (zh) "当前会话保持无工作区；不会自动改绑。" else "This conversation stays unbound and is never changed automatically."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (zh) "Agent 默认工作区：${state.workspaceAccess.agentDefaultWorkspaceLabel}"
                else "Agent default workspace: ${state.workspaceAccess.agentDefaultWorkspaceLabel}",
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
                    if (zh) "在此工作区新建会话" else "New conversation in this workspace",
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
        Text(if (zh) "会话" else "Conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            if (zh) "会话" else "Conversations",
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
                if (state.sessions.isEmpty()) DropdownMenuItem(text = { Text(if (zh) "暂无会话" else "No conversations") }, onClick = { expanded = false }, enabled = false)
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
                Text(if (zh) "会话" else "Chats", maxLines = 1)
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
                Text(if (zh) "新会话" else "New chat", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AgentChooser(state, actions.onSelectAgent)
            OutlinedButton(
                onClick = onOpenWorkspace,
                modifier = Modifier.testTag("chat.workspace.open"),
            ) {
                Text(if (zh) "工作区" else "Files", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilterChip(
                selected = state.textDegradation,
                onClick = { actions.onToggleDegradation(!state.textDegradation) },
                label = { Text(if (zh) "纯文本" else "Text only", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            TextButton(
                onClick = actions.onOpenRequestInspector,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.testTag("chat.requestInspector.open"),
            ) {
                Text(if (zh) "查看请求" else "View request", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val workspace = state.workspaceAccess.workspaceSummary
        .takeIf { it.isNotBlank() }
        ?: if (zh) "未配置工作区" else "No workspace"
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
                    text = { Text(if (zh) "查看请求" else "Inspect request") },
                    onClick = { overflowOpen = false; actions.onOpenRequestInspector() },
                    modifier = Modifier.testTag("conversation.requestInspector.open"),
                )
                DropdownMenuItem(
                    text = { Text(if (zh) "新对话" else "New conversation") },
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
    val workspace = state.workspaceAccess.workspaceSummary.ifBlank {
        if (zh) "未配置工作区" else "No workspace"
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("conversation.context.sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (zh) "当前上下文" else "Current context", style = MaterialTheme.typography.headlineSmall)
            Text(if (zh) "智能体" else "Agent", style = MaterialTheme.typography.labelLarge)
            Text(agent, style = MaterialTheme.typography.bodyLarge)
            Text(if (zh) "工作区" else "Workspace", style = MaterialTheme.typography.labelLarge)
            Text(workspace, style = MaterialTheme.typography.bodyLarge)
            if (state.workspaceAccess.threadWorkspaceState == ChatThreadWorkspaceState.UNBOUND_AGENT_DEFAULT_AVAILABLE &&
                state.workspaceAccess.agentDefaultWorkspaceId != null
            ) {
                Text(if (zh) "Agent 默认工作区" else "Agent default workspace", style = MaterialTheme.typography.labelLarge)
                Text(state.workspaceAccess.agentDefaultWorkspaceLabel, style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = { onNewSessionAtDefault(requireNotNull(state.workspaceAccess.agentDefaultWorkspaceId)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("conversation.context.newAtDefault"),
                ) {
                    Text(if (zh) "在此工作区新建会话" else "New conversation in this workspace")
                }
            }
            if (state.workspaceAccess.systemAccessLabel.isNotBlank()) {
                Text(state.workspaceAccess.systemAccessLabel, style = MaterialTheme.typography.bodySmall)
            }
            if (state.workspaceAccess.permissionLabel.isNotBlank()) {
                Text(
                    if (zh) "权限：${state.workspaceAccess.permissionLabel}" else "Permission: ${state.workspaceAccess.permissionLabel}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.modelLabel.isNotBlank()) {
                Text(if (zh) "模型" else "Model", style = MaterialTheme.typography.labelLarge)
                Text(state.modelLabel, style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(
                onClick = onOpenWorkspace,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("conversation.context.workspace"),
            ) {
                Text(if (zh) "查看工作区" else "View workspace")
            }
            OutlinedButton(
                onClick = onOpenAgentSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("conversation.context.agentSettings"),
            ) {
                Text(if (zh) "管理智能体" else "Manage agent")
            }
            Button(
                onClick = onNewSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("conversation.context.new"),
            ) {
                Text(if (zh) "新建对话" else "New conversation")
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
            Text(if (zh) "无智能体" else "No agent", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
@OptIn(ExperimentalLayoutApi::class)
private fun MessageBubble(
    message: ChatMessageUi,
    citations: List<ChatCitationUi>,
    onCitation: (String) -> Unit,
    zh: Boolean,
    minimal: Boolean = false,
) {
    val user = message.role.equals("user", ignoreCase = true)
    val tool = message.role.equals("tool", ignoreCase = true) || message.eventSummary.isNotBlank()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        if (tool) {
            ToolEventRow(message = message, zh = zh, modifier = Modifier.fillMaxWidth())
        } else {
            val bubbleModifier = Modifier
                .fillMaxWidth(if (user) 0.86f else 0.94f)
                .testTag("conversation.message.${message.id}")
            val body: @Composable () -> Unit = {
                Column(Modifier.padding(if (minimal && !user) 4.dp else 12.dp)) {
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
                    Text(message.text, Modifier.padding(top = 4.dp))
                    if (message.streaming) Text(if (zh) "正在流式输出…" else "Streaming…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    if (message.timeLabel.isNotBlank()) Text(message.timeLabel, style = MaterialTheme.typography.labelSmall)
                    val known = message.citationIds.mapNotNull { id -> citations.firstOrNull { it.id == id } }
                    if (known.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        known.forEach { citation -> AssistChip(onClick = { onCitation(citation.id) }, label = { Text(if (zh) "来源：${citation.title}" else "Source: ${citation.title}") }) }
                    }
                }
            }
            if (minimal && !user) {
                Column(bubbleModifier) { body() }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                    modifier = bubbleModifier,
                ) { body() }
            }
        }
    }
}

@Composable
private fun ToolEventRow(message: ChatMessageUi, zh: Boolean, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.testTag("conversation.tool.${message.id}"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (zh) "工具" else "Tool",
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 8.dp),
    ) {
        Column(Modifier.padding(if (compact) 8.dp else 12.dp)) {
            Text(
                if (zh) "需要确认" else "Confirmation required",
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
                    Text(if (zh) "命令" else "Command", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(it)
                }
                approval.cwd?.let {
                    Text(if (zh) "工作目录" else "Working directory", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(it)
                }
                approval.authority?.let {
                    Text(if (zh) "权限通道" else "Authority", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(it)
                }
                approval.dangerousMode?.let {
                    Text(if (zh) "危险模式" else "Dangerous mode", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Text(it)
                }
                if (approval.highRisk) {
                    Text(if (zh) "高风险：需要重新确认" else "High risk: reconfirmation required", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
                Text(approval.summary, Modifier.padding(top = 4.dp))
                if (approval.externalEffect) {
                    Text(
                        if (zh) "此请求可能离开设备。" else "This request may leave the device.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    if (zh) "仅允许本次调用，不会创建会话或持久权限。" else "Allows this invocation only; it does not create a session or persistent grant.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (compact) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("chat.approval.actions"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = { onChoice(ToolApprovalChoice.APPROVE) },
                        modifier = Modifier.weight(1f).testTag("chat.approval.approve"),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (zh) "允许一次" else "Allow once", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onChoice(ToolApprovalChoice.REJECT) },
                        modifier = Modifier.weight(1f).testTag("chat.approval.reject"),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (zh) "拒绝" else "Reject", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("chat.approval.actions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onChoice(ToolApprovalChoice.APPROVE) },
                        modifier = Modifier.testTag("chat.approval.approve"),
                    ) { Text(if (zh) "允许一次" else "Allow once") }
                    OutlinedButton(
                        onClick = { onChoice(ToolApprovalChoice.REJECT) },
                        modifier = Modifier.testTag("chat.approval.reject"),
                    ) { Text(if (zh) "拒绝" else "Reject") }
                }
            }
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, actions: ChatActions) {
    val zh = state.language.equals("zh-CN", true)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        // Keep the first-message path responsive: the host receives the send
        // action immediately, while the input focus/IME is cleared locally.
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        actions.onSend()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = state.input,
            onValueChange = actions.onInput,
            enabled = !state.streaming && state.pendingTool == null,
            label = { Text(if (zh) "消息" else "Message") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier
                .weight(1f)
                .testTag("conversation.composer.input"),
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
            ) { Text(if (zh) "取消" else "Cancel") }
        } else {
            Button(
                onClick = ::submit,
                enabled = state.input.isNotBlank() && state.pendingTool == null,
                modifier = Modifier.testTag("conversation.composer.send"),
            ) { Text(if (zh) "发送" else "Send") }
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
                Text(citation.source, style = MaterialTheme.typography.labelLarge)
                if (citation.location.isNotBlank()) Text(citation.location, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                CitationImagePreview(citation, zh)
                Text(citation.excerpt, modifier = Modifier.padding(top = 12.dp))
                Text(if (citation.verified) { if (zh) "证据已验证" else "Verified evidence" } else { if (zh) "证据状态不可用" else "Evidence status unavailable" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
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
            if (zh) "引用图片为空；仍显示元数据和摘录。" else "The citation image is empty; metadata and excerpt remain available.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        return
    }
    if (bytes.size > MAX_CITATION_IMAGE_BYTES) {
        Text(
            if (zh) "引用图片文件过大，已拒绝解码；仍显示元数据和摘录。" else "The citation image is too large and was not decoded; metadata and excerpt remain available.",
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
            if (zh) "引用图片尺寸无效或过大，已拒绝解码；仍显示元数据和摘录。" else "The citation image dimensions are invalid or too large and were not decoded; metadata and excerpt remain available.",
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
            if (zh) "引用图片无法安全解码；仍显示元数据和摘录。" else "The citation image could not be decoded safely; metadata and excerpt remain available.",
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
                Text(if (zh) "请求检查器" else "Request inspector", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") }
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
                ChatRequestInspectorAvailability.CONTEXT_LOST -> if (zh) "请求检查器上下文已丢失，请返回对话后重试。" else "The request inspector context was lost. Return to the conversation and try again."
                ChatRequestInspectorAvailability.READY -> error("READY without a request is normalized above")
            }
            Text(message, modifier = Modifier.padding(top = 16.dp).testTag("chat.requestInspector.state"))
        } else {
            Text("${effectiveRequest.method} ${effectiveRequest.url}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            if (effectiveRequest.redacted) {
                Text(
                    if (zh) "敏感请求头与密钥已遮盖；以下内容仅来自脱敏请求检查数据。"
                    else "Sensitive headers and keys are redacted; the content below is supplied as redacted inspector data.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (effectiveRequest.headers.isNotBlank()) Text(effectiveRequest.headers, modifier = Modifier.padding(top = 10.dp))
            if (effectiveRequest.body.isNotBlank()) Text(effectiveRequest.body, modifier = Modifier.padding(top = 10.dp))
            if (layers.isNotEmpty()) {
                Text(if (zh) "提示词层" else "Prompt layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
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
        title = { Text(if (zh) "请求检查器" else "Request inspector") },
        text = {
            RequestInspectorScreen(request, layers, onClose, zh)
        },
        confirmButton = { Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
    )
}
