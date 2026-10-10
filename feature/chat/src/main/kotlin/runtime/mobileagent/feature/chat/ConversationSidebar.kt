// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConversationSidebar(
    state: ChatUiState,
    actions: ChatActions,
    onClose: () -> Unit = {},
    onOpenWorkspace: (String?, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val zh = state.language.equals("zh-CN", true)
    var newChatMenuOpen by remember { mutableStateOf(false) }
    val sessionsByAgent = remember(state.sessions, state.agents) {
        state.sessions.groupBy { it.agentId }
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .testTag("chat.sidebar"),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ui_conversations_23a085e0),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(
                    onClick = { newChatMenuOpen = true },
                    modifier = Modifier.testTag("chat.sidebar.new"),
                ) {
                    Text(stringResource(R.string.ui_new_5a5d1d13), maxLines = 1)
                }
                DropdownMenu(
                    expanded = newChatMenuOpen,
                    onDismissRequest = { newChatMenuOpen = false },
                ) {
                    if (state.agents.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_no_agent_available_2361a44f)) },
                            onClick = { newChatMenuOpen = false },
                            enabled = false,
                        )
                    } else {
                        state.agents.forEach { agent ->
                            DropdownMenuItem(
                                text = { Text(agent.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.testTag("chat.sidebar.new.agent.${agent.id}"),
                                onClick = {
                                    newChatMenuOpen = false
                                    actions.onNewSessionForAgent(agent.id)
                                    onClose()
                                },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.loading) {
            Text(stringResource(R.string.ui_loading_b84913a0), style = MaterialTheme.typography.bodySmall)
        } else if (state.sessions.isEmpty() && state.agents.isEmpty()) {
            Text(sidebarEmptyMessage(state, zh), style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.agents.forEach { agent ->
                    val sessions = sessionsByAgent[agent.id].orEmpty()
                    item(key = "agent:${agent.id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat.sidebar.agent.${agent.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { actions.onSelectAgent(agent.id); onClose() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    agent.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            TextButton(
                                onClick = { onOpenWorkspace(agent.id, agent.label) },
                                modifier = Modifier.testTag("chat.sidebar.workspace"),
                            ) {
                                Text(stringResource(R.string.ui_files_5c80b454), maxLines = 1)
                            }
                        }
                    }
                    items(sessions, key = { it.id }) { session ->
                        SessionSidebarItem(
                            session = session,
                            selected = session.id == state.selectedSessionId,
                            sessionActions = state.sessionActions,
                            onAction = actions.onSessionAction,
                            zh = zh,
                            onClick = { actions.onSelectSession(session.id); onClose() },
                            onOpenWorkspace = { onOpenWorkspace(session.agentId, session.agentName.ifBlank { "当前智能体" }) },
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    item(key = "new:${agent.id}") {
                        TextButton(
                            onClick = { actions.onNewSessionForAgent(agent.id); onClose() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp)
                                .testTag("chat.sidebar.newForAgent"),
                        ) {
                            Text(stringResource(R.string.ui_new_under_this_agent_39126f72), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val orphanSessions = sessionsByAgent[null].orEmpty() + state.sessions.filter { session ->
                    session.agentId != null && state.agents.none { it.id == session.agentId }
                }
                if (orphanSessions.isNotEmpty()) {
                    item(key = "orphan-header") {
                        Text(
                            stringResource(R.string.ui_other_conversations_540cce6a),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(orphanSessions.distinctBy { it.id }, key = { it.id }) { session ->
                        SessionSidebarItem(
                            session = session,
                            selected = session.id == state.selectedSessionId,
                            sessionActions = state.sessionActions,
                            onAction = actions.onSessionAction,
                            zh = zh,
                            onClick = { actions.onSelectSession(session.id); onClose() },
                            onOpenWorkspace = { onOpenWorkspace(session.agentId, session.agentName.ifBlank { "当前智能体" }) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSidebarItem(
    session: ChatSessionUi,
    selected: Boolean,
    sessionActions: List<SessionAction>,
    onAction: (String, SessionAction) -> Unit,
    zh: Boolean,
    onClick: () -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SessionActionItem(session.id, sessionActions, zh, onClick, onAction, modifier) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("chat.sidebar.session.${session.id}"),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(session.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(
                    onClick = onOpenWorkspace,
                    modifier = Modifier.testTag("chat.sidebar.sessionWorkspace"),
                ) {
                    Text(stringResource(R.string.ui_files_5c80b454), maxLines = 1)
                }
                if (session.unread) {
                    Text(
                        stringResource(R.string.ui_unread_0af1e493),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (session.preview.isNotBlank()) {
                Text(session.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (session.timeLabel.isNotBlank()) {
                Text(session.timeLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    }
}

private fun sidebarEmptyMessage(state: ChatUiState, zh: Boolean): String =
    state.emptyMessage.takeIf { it.isNotBlank() }
        ?: if (zh) "新建会话以开始。" else "Create a conversation to start."

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ChatWorkspaceAccessSheet(
    state: ChatWorkspaceAccessUi,
    zh: Boolean,
    onDismiss: () -> Unit,
    onOpenAgentSettings: () -> Unit,
) {
    val display = state.localized(zh)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("chat.workspace.sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.ui_workspace_access_9224cbf6), style = MaterialTheme.typography.headlineSmall)
            Text(display.agentLabel, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ui_current_workspace_s_6c60ef34, (display.workspaceSummary)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.ui_system_access_s_22db32ee, (display.systemAccessLabel)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(R.string.ui_permission_s_05813ab1, (display.permissionLabel)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (display.notice.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(display.notice, Modifier.padding(12.dp))
                }
            }
            OutlinedButton(
                onClick = onOpenAgentSettings,
                modifier = Modifier.fillMaxWidth().testTag("chat.workspace.agentSettings"),
            ) {
                Text(stringResource(R.string.ui_manage_agent_workspace_a54c525c))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
