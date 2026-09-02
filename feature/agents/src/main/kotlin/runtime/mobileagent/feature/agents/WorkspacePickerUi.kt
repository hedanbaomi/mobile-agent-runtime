// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class WorkspacePickerModeUi {
    PRIVILEGED,
    AUTHORITY_UNAVAILABLE,
    SAF_FALLBACK,
}

enum class WorkspacePickerLoadPhaseUi {
    IDLE,
    LOADING,
    CONTENT,
    ERROR,
}

enum class WorkspacePickerAttachPhaseUi {
    IDLE,
    ATTACHING,
    SUCCESS,
    NEEDS_NEW_THREAD,
    ERROR,
}

enum class WorkspacePickerErrorCodeUi {
    AUTHORITY_UNAVAILABLE,
    AUTHORITY_NOT_SELECTED,
    WORKSPACE_NOT_FOUND,
    PERMISSION_DENIED,
    URI_PERMISSION_REQUIRED,
    CONFLICT,
    UNSUPPORTED,
    PERSISTENCE_FAILED,
    UNKNOWN_OUTCOME,
}

data class WorkspacePickerAuthorityUi(
    val label: String = "未选择增强访问",
    val statusLabel: String = "未就绪",
    val selected: Boolean = false,
    val ready: Boolean = false,
)

data class WorkspacePickerLocationUi(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
)

data class WorkspacePickerBreadcrumbUi(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
)

data class WorkspacePickerEntryUi(
    val id: String,
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long? = null,
    val readable: Boolean = true,
    val writable: Boolean = false,
)

data class WorkspacePickerRecentUi(
    val id: String,
    val displayName: String,
    val authorityLabel: String,
    val statusLabel: String,
    val durablyAuthorized: Boolean,
    val enabled: Boolean = true,
)

data class WorkspacePickerAttachedUi(
    val workspaceId: String,
    val displayName: String,
    val statusLabel: String,
)

data class WorkspacePickerNewThreadUi(
    val agentId: String,
    val currentThreadId: String,
    val requestedWorkspaceId: String,
)

data class WorkspacePickerUiState(
    val mode: WorkspacePickerModeUi = WorkspacePickerModeUi.AUTHORITY_UNAVAILABLE,
    val authority: WorkspacePickerAuthorityUi = WorkspacePickerAuthorityUi(),
    val targetLabel: String = "当前目标",
    val locations: List<WorkspacePickerLocationUi> = emptyList(),
    val recentWorkspaces: List<WorkspacePickerRecentUi> = emptyList(),
    val breadcrumbs: List<WorkspacePickerBreadcrumbUi> = emptyList(),
    val currentLabel: String = "根目录",
    val entries: List<WorkspacePickerEntryUi> = emptyList(),
    val loadPhase: WorkspacePickerLoadPhaseUi = WorkspacePickerLoadPhaseUi.IDLE,
    val loading: Boolean = false,
    val listTruncated: Boolean = false,
    val currentDirectoryReadable: Boolean = false,
    val currentDirectoryWritable: Boolean = false,
    val canGoParent: Boolean = false,
    val canUseCurrentDirectory: Boolean = false,
    val canUseSafFallback: Boolean = false,
    val advancedPathAvailable: Boolean = false,
    val attachPhase: WorkspacePickerAttachPhaseUi = WorkspacePickerAttachPhaseUi.IDLE,
    val attached: WorkspacePickerAttachedUi? = null,
    val pendingNewThread: WorkspacePickerNewThreadUi? = null,
    val errorCode: WorkspacePickerErrorCodeUi? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
)

data class WorkspacePickerActions(
    val onRefresh: () -> Unit = {},
    val onOpenLocation: (String) -> Unit = {},
    val onOpenBreadcrumb: (String) -> Unit = {},
    val onOpenEntry: (String) -> Unit = {},
    val onGoParent: () -> Unit = {},
    val onUseCurrentDirectory: () -> Unit = {},
    val onUseSafFallback: () -> Unit = {},
    val onOpenRecent: (String) -> Unit = {},
    /** Advanced foreground-only path flow; the model has no access to this callback. */
    val onOpenAdvancedPath: () -> Unit = {},
)

/**
 * A standalone workspace picker surface.  It receives display-safe models
 * from the app VM, so the UI cannot accidentally render a path, URI, serial,
 * locator, or secret.  Directory handles remain in the VM and are referred to
 * by opaque UI ids only.
 */
@Composable
fun WorkspacePickerScreen(
    state: WorkspacePickerUiState,
    actions: WorkspacePickerActions = WorkspacePickerActions(),
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(WorkspacePickerTestTags.SCREEN),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择工作区", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "目标：${state.targetLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = actions.onRefresh,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(WorkspacePickerTestTags.REFRESH),
                    ) { Text("刷新") }
                }
                Spacer(Modifier.height(8.dp))
                AuthorityCard(state.authority, state.mode)
                if (state.mode == WorkspacePickerModeUi.PRIVILEGED && state.canUseSafFallback) {
                    OutlinedButton(
                        onClick = actions.onUseSafFallback,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp)
                            .testTag(WorkspacePickerTestTags.SAF_FALLBACK),
                    ) { Text("改用普通文件夹授权（SAF）") }
                }
            }
        }

        if (state.mode == WorkspacePickerModeUi.AUTHORITY_UNAVAILABLE && state.canUseSafFallback) {
            item(key = "explicit-saf-fallback") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("增强访问当前不可用", fontWeight = FontWeight.SemiBold)
                        Text(
                            "不会自动切换通道。若要使用普通文件夹授权，请明确选择下方入口。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        OutlinedButton(
                            onClick = actions.onUseSafFallback,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .heightIn(min = 48.dp)
                                .testTag(WorkspacePickerTestTags.SAF_FALLBACK),
                        ) { Text("改用文件夹授权") }
                    }
                }
            }
        }

        if (state.mode == WorkspacePickerModeUi.SAF_FALLBACK) {
            item(key = "saf-mode") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("普通文件夹授权", fontWeight = FontWeight.SemiBold)
                        Text(
                            "请通过系统文件选择器选择文件夹；这是明确的普通权限入口。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        OutlinedButton(
                            onClick = actions.onUseSafFallback,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .heightIn(min = 48.dp)
                                .testTag(WorkspacePickerTestTags.SAF_FALLBACK),
                        ) { Text("打开文件选择器") }
                    }
                }
            }
        }

        if (state.recentWorkspaces.isNotEmpty()) {
            item(key = "recent-title") {
                Text("最近使用", style = MaterialTheme.typography.titleMedium)
            }
            items(state.recentWorkspaces, key = { "recent:${it.id}" }) { recent ->
                RecentWorkspaceRow(recent, actions.onOpenRecent)
            }
        }

        if (state.mode == WorkspacePickerModeUi.PRIVILEGED) {
            item(key = "locations-title") {
                Text("位置", style = MaterialTheme.typography.titleMedium)
            }
            if (state.locations.isEmpty()) {
                item(key = "locations-empty") {
                    Text("当前没有可用的快捷位置。", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                items(state.locations, key = { "location:${it.id}" }) { location ->
                    OutlinedButton(
                        onClick = { actions.onOpenLocation(location.id) },
                        enabled = location.enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag(WorkspacePickerTestTags.location(location.id)),
                    ) { Text(location.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }

            item(key = "breadcrumb") {
                BreadcrumbRow(state.breadcrumbs, actions.onOpenBreadcrumb)
            }
            item(key = "directory-actions") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = actions.onGoParent,
                        enabled = state.canGoParent && !state.loading,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(WorkspacePickerTestTags.PARENT),
                    ) { Text("上一级") }
                    Button(
                        onClick = actions.onUseCurrentDirectory,
                        enabled = state.canUseCurrentDirectory && !state.loading && state.attachPhase != WorkspacePickerAttachPhaseUi.ATTACHING,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(WorkspacePickerTestTags.USE_FOLDER),
                    ) { Text("使用此文件夹") }
                }
                Text(
                    "当前位置：${state.currentLabel} · ${directoryAccessLabel(state)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (state.advancedPathAvailable) {
                item(key = "advanced-path") {
                    OutlinedButton(
                        onClick = actions.onOpenAdvancedPath,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag(WorkspacePickerTestTags.ADVANCED_PATH),
                    ) { Text("高级：手动选择设备目录") }
                }
            }

            if (state.loading) {
                item(key = "loading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.testTag(WorkspacePickerTestTags.LOADING))
                        Text("正在读取目录…", modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }

            if (
                state.entries.isEmpty() &&
                state.locations.isEmpty() &&
                state.breadcrumbs.size > 1 &&
                !state.loading &&
                state.loadPhase == WorkspacePickerLoadPhaseUi.CONTENT
            ) {
                item(key = "empty-directory") {
                    Text("此文件夹为空。", style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.entries, key = { "entry:${it.id}" }) { entry ->
                WorkspaceEntryRow(entry, actions.onOpenEntry)
            }
            if (state.listTruncated) {
                item(key = "truncated") {
                    Text(
                        "目录较大，当前仅显示部分项目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.errorMessage != null) {
            item(key = "error") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.ERROR),
                ) {
                    Text(
                        state.errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        if (state.statusMessage != null) {
            item(key = "status") {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.STATUS),
                ) { Text(state.statusMessage, Modifier.padding(12.dp)) }
            }
        }
        if (state.attachPhase == WorkspacePickerAttachPhaseUi.ATTACHING) {
            item(key = "attaching") {
                Text("正在保存工作区…", modifier = Modifier.testTag(WorkspacePickerTestTags.ATTACHING))
            }
        }
        state.attached?.let { attached ->
            item(key = "attached") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.ATTACHED),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("工作区已添加", fontWeight = FontWeight.SemiBold)
                        Text(attached.displayName, modifier = Modifier.padding(top = 4.dp))
                        Text(attached.statusLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorityCard(authority: WorkspacePickerAuthorityUi, mode: WorkspacePickerModeUi) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.AUTHORITY),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(authority.label, style = MaterialTheme.typography.titleMedium)
            Text(
                when (mode) {
                    WorkspacePickerModeUi.PRIVILEGED -> "${authority.statusLabel} · 设备目录浏览"
                    WorkspacePickerModeUi.AUTHORITY_UNAVAILABLE -> "${authority.statusLabel} · 不会自动切换通道"
                    WorkspacePickerModeUi.SAF_FALLBACK -> "普通文件夹授权"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun BreadcrumbRow(
    breadcrumbs: List<WorkspacePickerBreadcrumbUi>,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.BREADCRUMB)) {
        Text("当前位置", style = MaterialTheme.typography.titleMedium)
        if (breadcrumbs.isEmpty()) {
            Text("根目录", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                breadcrumbs.forEachIndexed { index, crumb ->
                    if (index > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = { onOpen(crumb.id) },
                        enabled = crumb.enabled,
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text(crumb.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RecentWorkspaceRow(
    recent: WorkspacePickerRecentUi,
    onOpen: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(enabled = recent.enabled) { onOpen(recent.id) }
            .testTag(WorkspacePickerTestTags.recent(recent.id))
            .semantics { contentDescription = "最近工作区 ${recent.displayName}" },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(recent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${recent.authorityLabel} · ${recent.statusLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkspaceEntryRow(
    entry: WorkspacePickerEntryUi,
    onOpen: (String) -> Unit,
) {
    val access = when {
        !entry.readable -> "不可访问"
        entry.writable -> "可读写"
        else -> "只读"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (entry.directory && entry.readable) {
                    Modifier.clickable { onOpen(entry.id) }
                } else {
                    Modifier
                },
            )
            .testTag(WorkspacePickerTestTags.entry(entry.id)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (entry.directory) "文件夹" else "文件", style = MaterialTheme.typography.labelSmall)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(access, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!entry.directory && entry.sizeBytes != null) {
                Text(formatBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun directoryAccessLabel(state: WorkspacePickerUiState): String = when {
    !state.currentDirectoryReadable -> "不可访问"
    state.currentDirectoryWritable -> "可读写"
    else -> "只读"
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KiB"
    else -> "${bytes / (1024L * 1024L)} MiB"
}

object WorkspacePickerTestTags {
    const val SCREEN = "workspacePicker.screen"
    const val AUTHORITY = "workspacePicker.authority"
    const val REFRESH = "workspacePicker.refresh"
    const val SAF_FALLBACK = "workspacePicker.safFallback"
    const val BREADCRUMB = "workspacePicker.breadcrumb"
    const val PARENT = "workspacePicker.parent"
    const val USE_FOLDER = "workspacePicker.useFolder"
    const val ADVANCED_PATH = "workspacePicker.advancedPath"
    const val LOADING = "workspacePicker.loading"
    const val ERROR = "workspacePicker.error"
    const val STATUS = "workspacePicker.status"
    const val ATTACHING = "workspacePicker.attaching"
    const val ATTACHED = "workspacePicker.attached"

    fun location(id: String): String = "workspacePicker.location.$id"
    fun recent(id: String): String = "workspacePicker.recent.$id"
    fun entry(id: String): String = "workspacePicker.entry.$id"
}
