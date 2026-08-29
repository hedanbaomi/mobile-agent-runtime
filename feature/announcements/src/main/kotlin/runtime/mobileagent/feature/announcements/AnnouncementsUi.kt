// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.announcements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import runtime.mobileagent.announcements.AnnouncementAction
import runtime.mobileagent.announcements.AnnouncementActions
import runtime.mobileagent.announcements.CachedAnnouncement

data class AnnouncementsUiState(
    val items: List<CachedAnnouncement> = emptyList(),
    val status: String = "",
    val filter: String = "unread",
    val banner: CachedAnnouncement? = null,
    val modal: CachedAnnouncement? = null,
    val selected: CachedAnnouncement? = null,
    val baseUrl: String = "",
    val publicKeyHex: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val language: String = "zh-CN",
)

data class AnnouncementsActions(
    val onFilter: (String) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onOpen: (CachedAnnouncement) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onMarkAllRead: () -> Unit = {},
    val onDismiss: (CachedAnnouncement) -> Unit = {},
    val onAcknowledge: (CachedAnnouncement) -> Unit = {},
    val onSaveEndpoint: (String, String) -> Unit = { _, _ -> },
    val onAppRoute: (String) -> Unit = {},
    /** Called after an allowlisted action is selected; URLs/body text stay out of telemetry. */
    val onActionClicked: (CachedAnnouncement, AnnouncementAction) -> Unit = { _, _ -> },
)

@Composable
fun AnnouncementsScreen(state: AnnouncementsUiState, actions: AnnouncementsActions = AnnouncementsActions(), modifier: Modifier = Modifier) {
    val zh = state.language.equals("zh-CN", true)
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(if (zh) "公告" else "News", style = MaterialTheme.typography.headlineSmall)
                if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Button(onClick = actions.onRefresh, enabled = !state.loading) { Text(if (zh) "刷新" else "Refresh") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        state.banner?.let { BannerCard(it, actions, zh) }
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("unread" to if (zh) "未读" else "Unread", "all" to if (zh) "全部" else "All", "history" to if (zh) "历史" else "History").forEach { (key, label) -> FilterChip(selected = state.filter == key, onClick = { actions.onFilter(key) }, label = { Text(label) }) }
            OutlinedButton(onClick = actions.onMarkAllRead) { Text(if (zh) "全部标为已读" else "Mark all read") }
        }
        if (state.loading) {
            CircularProgressIndicator(Modifier.padding(top = 20.dp))
        } else if (state.items.isEmpty()) {
            Text(if (zh) "暂无公告。" else "No announcements available.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 20.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(state.items, key = { "${it.item.id}:${it.item.revision}" }) { record -> AnnouncementCard(record, actions, zh) }
            }
        }
        EndpointForm(state.baseUrl, state.publicKeyHex, actions.onSaveEndpoint, zh)
    }
    state.selected?.let { record ->
        AnnouncementDetailDialog(record, actions.onCloseDetail, zh) { action ->
            if (AnnouncementActions.allowed(action)) {
                actions.onActionClicked(record, action)
                when (action.type) {
                    "OPEN_HTTPS_URL" -> action.url?.let(uriHandler::openUri)
                    "OPEN_APP_ROUTE" -> action.url?.let(actions.onAppRoute)
                    "DISMISS" -> actions.onDismiss(record)
                    "ACKNOWLEDGE" -> actions.onAcknowledge(record)
                }
            }
        }
    }
    state.modal?.let { record ->
        val canDismiss = !record.item.mustAcknowledge && record.item.dismissible
        AlertDialog(
            onDismissRequest = { if (canDismiss) actions.onDismiss(record) },
            title = { Text(record.item.title) },
            text = { Text(record.item.bodyMarkdown, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                if (record.item.mustAcknowledge) Button(onClick = { actions.onAcknowledge(record) }) { Text(if (zh) "确认" else "Acknowledge") }
                else Button(onClick = { actions.onDismiss(record) }) { Text(if (zh) "忽略" else "Dismiss") }
            },
            dismissButton = if (canDismiss) ({ TextButton(onClick = { actions.onDismiss(record) }) { Text(if (zh) "关闭" else "Close") } }) else null,
        )
    }
}

@Composable
private fun BannerCard(record: CachedAnnouncement, actions: AnnouncementsActions, zh: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(if (zh) "置顶公告" else "Pinned notice", style = MaterialTheme.typography.labelLarge)
            Text(record.item.title, style = MaterialTheme.typography.titleMedium)
            Text(record.item.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onOpen(record) }) { Text(if (zh) "打开" else "Open") }
                if (record.item.dismissible) TextButton(onClick = { actions.onDismiss(record) }) { Text(if (zh) "忽略" else "Dismiss") }
            }
        }
    }
}

@Composable
private fun AnnouncementCard(record: CachedAnnouncement, actions: AnnouncementsActions, zh: Boolean) {
    val item = record.item
    val container = when (item.severity.name) {
        "CRITICAL" -> MaterialTheme.colorScheme.errorContainer
        "WARNING" -> MaterialTheme.colorScheme.tertiaryContainer
        "NOTICE" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = container, modifier = Modifier.fillMaxWidth().clickable { actions.onOpen(record) }) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = false, onClick = {}, enabled = false, label = { Text(item.severity.name) })
            }
            Text(item.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Text(
                if (zh) "${item.category.name} · 修订 ${item.revision}${if (item.mustAcknowledge) " · 需要确认" else ""}"
                else "${item.category.name} · rev ${item.revision}${if (item.mustAcknowledge) " · acknowledgement required" else ""}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AnnouncementDetailDialog(record: CachedAnnouncement, onClose: () -> Unit, zh: Boolean, onAction: (AnnouncementAction) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(record.item.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(record.item.summary, style = MaterialTheme.typography.bodySmall)
                Text(record.item.bodyMarkdown, modifier = Modifier.padding(top = 10.dp))
                record.item.actions.filter(AnnouncementActions::allowed).forEach { action ->
                    OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.padding(top = 8.dp)) { Text(action.label) }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(if (zh) "关闭" else "Close") } },
    )
}

@Composable
private fun EndpointForm(baseUrl: String, publicKeyHex: String, onSave: (String, String) -> Unit, zh: Boolean) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var key by remember(publicKeyHex) { mutableStateOf(publicKeyHex) }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(url, { url = it }, label = { Text(if (zh) "公告地址" else "Feed URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text(if (zh) "公钥" else "Public key") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onSave(url, key) }) { Text(if (zh) "保存公告设置" else "Save feed settings") }
    }
}
