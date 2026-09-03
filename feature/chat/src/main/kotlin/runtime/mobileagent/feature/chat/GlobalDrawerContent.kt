// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Safe presentation data for a workspace in the global drawer.  The label and
 * status are supplied by the host; this model deliberately has no URI, path,
 * locator, serial, or backend handle field.
 */
@Immutable
data class ChatWorkspaceUi(
    val id: String,
    val label: String,
    val statusLabel: String = "",
    val authorityLabel: String = "",
)

/** A navigation entry rendered in the drawer's lower configuration section. */
@Immutable
data class ChatDrawerDestinationUi(
    val route: String,
    val label: String,
)

/**
 * The single drawer surface used by the conversation shell.  The app shell
 * owns modal/permanent drawer mechanics; this composable owns only the
 * content projection, so Chat never needs a second private navigation model.
 */
@Composable
fun GlobalDrawerContent(
    state: ChatUiState,
    actions: ChatActions,
    destinations: List<ChatDrawerDestinationUi> = state.drawerDestinations,
    selectedRoute: String? = null,
    onNavigate: (String) -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val zh = state.language.equals("zh-CN", ignoreCase = true)
    var query by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = query.trim()
    fun matches(vararg values: String): Boolean = normalizedQuery.isBlank() ||
        values.any { it.contains(normalizedQuery, ignoreCase = true) }

    val matchingSessions = state.sessions.filter {
        matches(it.title, it.preview, it.agentName, it.workspaceLabel)
    }
    val visibleAgents = state.agents.filter { agent ->
        matches(agent.label) || matchingSessions.any { it.agentId == agent.id }
    }
    val visibleSessions = state.sessions.filter { session ->
        matches(session.title, session.preview, session.agentName, session.workspaceLabel) ||
            (session.agentId != null && state.agents.any {
                it.id == session.agentId && matches(it.label)
            })
    }
    val visibleWorkspaces = state.workspaces.filter {
        matches(it.label, it.statusLabel, it.authorityLabel)
    }
    val sessionsByAgent = visibleSessions.groupBy { it.agentId }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("global.drawer"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "drawer-header") {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (zh) "MobileAgentRuntime" else "MobileAgentRuntime",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (zh) "Agent 工作台" else "Agent workspace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("global.drawer.close"),
                ) {
                    Text(if (zh) "关闭" else "Close")
                }
            }
        }
        item(key = "drawer-new") {
            OutlinedButton(
                onClick = { actions.onNewSession(); onClose() },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("global.drawer.new"),
            ) {
                Text(if (zh) "新对话" else "New conversation")
            }
        }
        item(key = "drawer-search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("global.drawer.search"),
                singleLine = true,
                label = { Text(if (zh) "搜索" else "Search") },
                placeholder = { Text(if (zh) "搜索会话或工作区" else "Search conversations or workspaces") },
            )
        }

        item(key = "drawer-authority") {
            Text(
                text = if (state.currentAuthorityLabel.isBlank()) {
                    if (zh) "本机" else "This device"
                } else {
                    if (zh) "本机 · ${state.currentAuthorityLabel}" else "This device · ${state.currentAuthorityLabel}"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 2.dp)
                    .testTag("global.drawer.authority"),
            )
        }

        if (visibleWorkspaces.isNotEmpty()) {
            item(key = "drawer-workspaces-title") {
                Text(
                    if (zh) "工作区" else "Workspaces",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 2.dp)
                        .testTag("global.drawer.workspaces"),
                )
            }
            items(visibleWorkspaces, key = { "workspace:${it.id}" }) { workspace ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .testTag("global.drawer.workspace.${workspace.id}"),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NavigationDrawerItem(
                        selected = workspace.id == state.selectedWorkspaceId,
                        onClick = { actions.onSelectWorkspace(workspace.id); onClose() },
                        label = {
                            Column {
                                Text(workspace.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val detail = listOf(workspace.authorityLabel, workspace.statusLabel)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                if (detail.isNotBlank()) {
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (workspace.id == state.selectedWorkspaceId) {
                        TextButton(
                            onClick = { actions.onNewSessionForWorkspace(workspace.id); onClose() },
                            modifier = Modifier
                                .widthIn(min = 48.dp)
                                .testTag("global.drawer.workspace.new.${workspace.id}"),
                        ) {
                            Text(if (zh) "新建" else "New", maxLines = 1)
                        }
                    }
                }
            }
        }
        item(key = "drawer-open-workspace") {
            Column(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { actions.onOpenWorkspacePicker(); onClose() },
                    enabled = state.selectedAgentId != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("global.drawer.workspace.open"),
                ) {
                    Text(if (zh) "打开工作区" else "Open workspace")
                }
                if (state.selectedAgentId == null) {
                    Text(
                        if (zh) "请先选择或创建智能体。" else "Select or create an Agent first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .testTag("global.drawer.workspace.requires-agent"),
                    )
                }
            }
        }

        item(key = "drawer-sessions-divider") {
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        item(key = "drawer-sessions-title") {
            Text(
                if (zh) "最近对话" else "Recent conversations",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 2.dp)
                    .testTag("global.drawer.sessions"),
            )
        }
        if (visibleAgents.isEmpty() && visibleSessions.isEmpty()) {
            item(key = "drawer-empty") {
                Text(
                    if (zh) "暂无匹配的对话。" else "No matching conversations.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            visibleAgents.forEach { agent ->
                val sessions = sessionsByAgent[agent.id].orEmpty()
                item(key = "agent:${agent.id}") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(
                            agent.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { actions.onSelectAgent(agent.id) }
                                .testTag("global.drawer.agent.${agent.id}"),
                        )
                        TextButton(
                            onClick = { actions.onAuthorizeWorkspaceForAgent(agent.id); onClose() },
                            modifier = Modifier.testTag("global.drawer.agent.workspace.${agent.id}"),
                        ) {
                            Text(if (zh) "工作区" else "Workspace", maxLines = 1)
                        }
                        TextButton(
                            onClick = { actions.onNewSessionForAgent(agent.id); onClose() },
                            modifier = Modifier.testTag("global.drawer.agent.new.${agent.id}"),
                        ) {
                            Text(if (zh) "新建" else "New", maxLines = 1)
                        }
                    }
                }
                items(sessions, key = { "session:${it.id}" }) { session ->
                    NavigationDrawerItem(
                        selected = session.id == state.selectedSessionId,
                        onClick = { actions.onSelectSession(session.id); onClose() },
                        label = { DrawerSessionLabel(session) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("global.drawer.session.${session.id}"),
                    )
                }
            }
            val orphanSessions = sessionsByAgent[null].orEmpty() + visibleSessions.filter { session ->
                session.agentId != null && state.agents.none { it.id == session.agentId }
            }
            if (orphanSessions.isNotEmpty()) {
                item(key = "orphan-header") {
                    Text(
                        if (zh) "其他对话" else "Other conversations",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(orphanSessions.distinctBy { it.id }, key = { "orphan:${it.id}" }) { session ->
                    NavigationDrawerItem(
                        selected = session.id == state.selectedSessionId,
                        onClick = { actions.onSelectSession(session.id); onClose() },
                        label = { DrawerSessionLabel(session) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("global.drawer.session.${session.id}"),
                    )
                }
            }
        }

        if (destinations.isNotEmpty()) {
            item(key = "drawer-navigation-divider") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            item(key = "drawer-navigation-title") {
                Text(
                    if (zh) "管理" else "Manage",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 2.dp)
                        .testTag("global.drawer.navigation"),
                )
            }
            items(destinations.distinctBy { it.route }, key = { "destination:${it.route}" }) { destination ->
                NavigationDrawerItem(
                    selected = selectedRoute == destination.route,
                    onClick = { onNavigate(destination.route); onClose() },
                    label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("global.drawer.navigation.${destination.route}"),
                )
            }
        }
        item(key = "drawer-bottom-space") { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun DrawerSessionLabel(session: ChatSessionUi) {
    Column {
        Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val detail = listOf(session.workspaceLabel, session.agentName, session.timeLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
