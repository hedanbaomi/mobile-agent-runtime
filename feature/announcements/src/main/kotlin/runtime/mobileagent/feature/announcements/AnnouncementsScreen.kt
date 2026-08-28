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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import runtime.mobileagent.announcements.AnnouncementAction
import runtime.mobileagent.announcements.AnnouncementActions
import runtime.mobileagent.announcements.CachedAnnouncement
import runtime.mobileagent.feature.announcements.R as AnnR

@Composable
fun AnnouncementsScreen(
    items: List<CachedAnnouncement>,
    status: String,
    filter: String,
    banner: CachedAnnouncement?,
    modal: CachedAnnouncement?,
    selected: CachedAnnouncement?,
    baseUrl: String,
    publicKeyHex: String,
    onFilter: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (CachedAnnouncement) -> Unit,
    onCloseDetail: () -> Unit,
    onMarkAllRead: () -> Unit,
    onDismiss: (CachedAnnouncement) -> Unit,
    onAcknowledge: (CachedAnnouncement) -> Unit,
    onSaveEndpoint: (String, String) -> Unit,
    onAppRoute: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(AnnR.string.ann_title), style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        banner?.let {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(AnnR.string.ann_pinned_notice), style = MaterialTheme.typography.labelLarge)
                    Text(it.item.title)
                    Text(it.item.summary, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onDismiss(it) }) { Text(stringResource(AnnR.string.ann_dismiss)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("unread" to stringResource(AnnR.string.ann_filter_unread), "all" to stringResource(AnnR.string.ann_filter_all), "history" to stringResource(AnnR.string.ann_filter_history)).forEach { (key, label) ->
                FilterChip(selected = filter == key, onClick = { onFilter(key) }, label = { Text(label) })
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh) { Text(stringResource(AnnR.string.ann_refresh)) }
            OutlinedButton(onClick = onMarkAllRead) { Text(stringResource(AnnR.string.ann_mark_all_read)) }
        }
        if (items.isEmpty()) {
            Text(stringResource(AnnR.string.ann_empty), modifier = Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { "${it.item.id}:${it.item.revision}" }) { record ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(record) }.padding(vertical = 8.dp),
                    ) {
                        Text(record.item.title, style = MaterialTheme.typography.titleMedium)
                        Text(record.item.summary, style = MaterialTheme.typography.bodySmall)
                        Text("rev ${record.item.revision} / ${record.item.category}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        EndpointForm(baseUrl, publicKeyHex, onSaveEndpoint)
    }
    selected?.let { record ->
        AnnouncementDetailScreen(
            record = record,
            onClose = onCloseDetail,
            onAction = { action -> handleAction(action, uriHandler::openUri, onAppRoute, { onDismiss(record) }, { onAcknowledge(record) }) },
        )
    }
    modal?.let { record ->
        val forceAck = record.item.mustAcknowledge
        val canDismiss = !forceAck && record.item.dismissible
        AlertDialog(
            onDismissRequest = { if (canDismiss) onDismiss(record) },
            title = { Text(record.item.title) },
            text = { Text(record.item.bodyMarkdown) },
            confirmButton = {
                if (forceAck) {
                    Button(onClick = { onAcknowledge(record) }) { Text(stringResource(AnnR.string.ann_acknowledge)) }
                } else {
                    Button(onClick = { onDismiss(record) }) { Text(stringResource(AnnR.string.ann_dismiss)) }
                }
            },
            dismissButton = if (canDismiss) {
                { OutlinedButton(onClick = { onDismiss(record) }) { Text(stringResource(AnnR.string.ann_close)) } }
            } else {
                null
            },
        )
    }
}

@Composable
fun AnnouncementDetailScreen(
    record: CachedAnnouncement,
    onClose: () -> Unit,
    onAction: (AnnouncementAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(record.item.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(record.item.bodyMarkdown)
                record.item.actions.forEach { action ->
                    if (AnnouncementActions.allowed(action)) {
                        OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(action.label)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text(stringResource(AnnR.string.ann_close)) } },
    )
}

@Composable
private fun EndpointForm(baseUrl: String, publicKeyHex: String, onSave: (String, String) -> Unit) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var key by remember(publicKeyHex) { mutableStateOf(publicKeyHex) }
    Column(Modifier.padding(top = 12.dp)) {
        OutlinedTextField(url, { url = it }, label = { Text(stringResource(AnnR.string.ann_base_url)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text(stringResource(AnnR.string.ann_public_key)) }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onSave(url, key) }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(AnnR.string.ann_save_endpoint))
        }
    }
}

private fun handleAction(
    action: AnnouncementAction,
    openHttps: (String) -> Unit,
    onAppRoute: (String) -> Unit,
    onDismiss: () -> Unit,
    onAck: () -> Unit,
) {
    if (!AnnouncementActions.allowed(action)) return
    when (action.type) {
        "OPEN_HTTPS_URL" -> action.url?.let(openHttps)
        "OPEN_APP_ROUTE" -> action.url?.let(onAppRoute)
        "DISMISS" -> onDismiss()
        "ACKNOWLEDGE" -> onAck()
    }
}
