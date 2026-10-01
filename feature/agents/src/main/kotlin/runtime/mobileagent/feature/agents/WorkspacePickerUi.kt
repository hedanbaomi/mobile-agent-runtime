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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
    val currentWorkspaceId: String = "",
    val requestedWorkspaceId: String,
    val requiresGrantCommit: Boolean = false,
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
    val loadingMore: Boolean = false,
    val listTruncated: Boolean = false,
    val canLoadMore: Boolean = false,
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
    val onLoadMore: () -> Unit = {},
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
private val LocalWorkspaceChinese = staticCompositionLocalOf { true }

@Composable
fun WorkspacePickerScreen(
    state: WorkspacePickerUiState,
    actions: WorkspacePickerActions = WorkspacePickerActions(),
    modifier: Modifier = Modifier,
    showPageTitle: Boolean = true,
    chinese: Boolean = true,
) {
    val zh = chinese
    CompositionLocalProvider(LocalWorkspaceChinese provides chinese) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(WorkspacePickerTestTags.SCREEN),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (showPageTitle) {
                            Text((if (zh) "选择工作区" else "Choose workspace"), style = MaterialTheme.typography.headlineSmall)
                        }
                        Text(
                            (if (zh) "目标：${state.targetLabel}" else "Target: ${state.targetLabel}"),
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
                    ) { Text((if (zh) "刷新" else "Refresh")) }
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
                    ) { Text((if (zh) "改用普通文件夹授权（SAF）" else "Use standard folder access (SAF)")) }
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
                        Text((if (zh) "增强访问当前不可用" else "Enhanced access is unavailable"), fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (zh) "不会自动切换通道。若要使用普通文件夹授权，请明确选择下方入口。" else "The channel will not change automatically. Choose below to use standard folder access."),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        OutlinedButton(
                            onClick = actions.onUseSafFallback,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .heightIn(min = 48.dp)
                                .testTag(WorkspacePickerTestTags.SAF_FALLBACK),
                        ) { Text((if (zh) "改用文件夹授权" else "Use folder access")) }
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
                        Text((if (zh) "普通文件夹授权" else "Standard folder access"), fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (zh) "请通过系统文件选择器选择文件夹；这是明确的普通权限入口。" else "Choose a folder in the system picker to grant standard folder access."),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        OutlinedButton(
                            onClick = actions.onUseSafFallback,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .heightIn(min = 48.dp)
                                .testTag(WorkspacePickerTestTags.SAF_FALLBACK),
                        ) { Text((if (zh) "打开文件选择器" else "Open system picker")) }
                    }
                }
            }
        }

        if (state.recentWorkspaces.isNotEmpty()) {
            item(key = "recent-title") {
                Text((if (zh) "最近使用" else "Recent workspaces"), style = MaterialTheme.typography.titleMedium)
            }
            items(state.recentWorkspaces, key = { "recent:${it.id}" }) { recent ->
                RecentWorkspaceRow(recent, actions.onOpenRecent)
            }
        }

        if (state.mode == WorkspacePickerModeUi.PRIVILEGED) {
            item(key = "locations-title") {
                Text((if (zh) "位置" else "Locations"), style = MaterialTheme.typography.titleMedium)
            }
            if (state.locations.isEmpty()) {
                item(key = "locations-empty") {
                    Text((if (zh) "当前没有可用的快捷位置。" else "No quick locations are available."), style = MaterialTheme.typography.bodySmall)
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
                    ) { Text(if (!zh && location.label == "内部存储") "Internal storage" else location.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                    ) { Text((if (zh) "上一级" else "Parent folder")) }
                    Button(
                        onClick = actions.onUseCurrentDirectory,
                        enabled = state.canUseCurrentDirectory && !state.loading && state.attachPhase != WorkspacePickerAttachPhaseUi.ATTACHING,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(WorkspacePickerTestTags.USE_FOLDER),
                    ) { Text((if (zh) "使用此文件夹" else "Use this folder")) }
                }
                Text(
                    (if (zh) "当前位置：${state.currentLabel} · ${directoryAccessLabel(state, zh)}" else "Current location: ${workspaceCurrentLabel(state)} · ${directoryAccessLabel(state, zh)}"),
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
                    ) { Text((if (zh) "高级：手动选择设备目录" else "Advanced: choose device directory manually")) }
                }
            }

            if (state.loading) {
                item(key = "loading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.testTag(WorkspacePickerTestTags.LOADING))
                        Text((if (zh) "正在读取目录…" else "Reading directory…"), modifier = Modifier.padding(start = 12.dp))
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
                    Text((if (zh) "此文件夹为空。" else "This folder is empty."), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.entries, key = { "entry:${it.id}" }) { entry ->
                WorkspaceEntryRow(entry, actions.onOpenEntry)
            }
            if (state.canLoadMore) {
                item(key = "load-more") {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.listTruncated) {
                            Text(
                                (if (zh) "目录较大，已显示部分项目。" else "Some items are shown in this large directory."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = actions.onLoadMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(WorkspacePickerTestTags.LOAD_MORE),
                        ) { Text(if (state.loadingMore) (if (zh) "正在加载…" else "Loading…") else (if (zh) "加载更多" else "Load more")) }
                    }
                }
            } else if (state.listTruncated) {
                item(key = "truncated") {
                    Text(
                        (if (zh) "目录较大，当前仅显示部分项目。" else "Only some items are shown in this large directory."),
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
                        workspacePickerText(state.errorMessage, zh),
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
                ) { Text(workspacePickerText(state.statusMessage, zh), Modifier.padding(12.dp)) }
            }
        }
        if (state.attachPhase == WorkspacePickerAttachPhaseUi.ATTACHING) {
            item(key = "attaching") {
                Text((if (zh) "正在保存工作区…" else "Saving workspace…"), modifier = Modifier.testTag(WorkspacePickerTestTags.ATTACHING))
            }
        }
        state.attached?.let { attached ->
            item(key = "attached") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.ATTACHED),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text((if (zh) "工作区已添加" else "Workspace added"), fontWeight = FontWeight.SemiBold)
                        Text(attached.displayName, modifier = Modifier.padding(top = 4.dp))
                        Text(workspacePickerText(attached.statusLabel, zh), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

}

@Composable
private fun AuthorityCard(authority: WorkspacePickerAuthorityUi, mode: WorkspacePickerModeUi) {
    val zh = LocalWorkspaceChinese.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.AUTHORITY),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(workspacePickerText(authority.label, zh), style = MaterialTheme.typography.titleMedium)
            Text(
                when (mode) {
                    WorkspacePickerModeUi.PRIVILEGED -> (if (zh) "${workspacePickerText(authority.statusLabel, zh)} · 设备目录浏览" else "${workspacePickerText(authority.statusLabel, zh)} · Device directory browsing")
                    WorkspacePickerModeUi.AUTHORITY_UNAVAILABLE -> (if (zh) "${workspacePickerText(authority.statusLabel, zh)} · 不会自动切换通道" else "${workspacePickerText(authority.statusLabel, zh)} · No automatic channel switching")
                    WorkspacePickerModeUi.SAF_FALLBACK -> (if (zh) "普通文件夹授权" else "Standard folder access")
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
    val zh = LocalWorkspaceChinese.current
    Column(Modifier.fillMaxWidth().testTag(WorkspacePickerTestTags.BREADCRUMB)) {
        Text((if (zh) "当前位置" else "Current location"), style = MaterialTheme.typography.titleMedium)
        if (breadcrumbs.isEmpty()) {
            Text((if (zh) "根目录" else "Root directory"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
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
                    ) { Text(if (!zh && crumb.id == "depth:0" && crumb.label == "根目录") "Root directory" else crumb.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
    val zh = LocalWorkspaceChinese.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(enabled = recent.enabled) { onOpen(recent.id) }
            .testTag(WorkspacePickerTestTags.recent(recent.id))
            .semantics { contentDescription = (if (zh) "最近工作区 ${recent.displayName}" else "Recent workspace ${recent.displayName}") },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(recent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${workspacePickerText(recent.authorityLabel, zh)} · ${workspacePickerText(recent.statusLabel, zh)}",
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
    val zh = LocalWorkspaceChinese.current
    val access = when {
        !entry.readable -> (if (zh) "不可访问" else "Unavailable")
        entry.writable -> (if (zh) "可读写" else "Read and write")
        else -> (if (zh) "只读" else "Read only")
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
            Text(if (entry.directory) (if (zh) "文件夹" else "Folder") else (if (zh) "文件" else "File"), style = MaterialTheme.typography.labelSmall)
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

private fun directoryAccessLabel(state: WorkspacePickerUiState, zh: Boolean): String = when {
    !state.currentDirectoryReadable -> (if (zh) "不可访问" else "Unavailable")
    state.currentDirectoryWritable -> (if (zh) "可读写" else "Read and write")
    else -> (if (zh) "只读" else "Read only")
}

/** Only the VM's synthetic depth-zero root has a localized display name. */
private fun workspaceCurrentLabel(state: WorkspacePickerUiState): String =
    if (state.breadcrumbs.lastOrNull()?.id == "depth:0" && state.currentLabel == "根目录") "Root directory"
    else state.currentLabel

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
    const val LOAD_MORE = "workspacePicker.loadMore"
    const val ERROR = "workspacePicker.error"
    const val STATUS = "workspacePicker.status"
    const val ATTACHING = "workspacePicker.attaching"
    const val ATTACHED = "workspacePicker.attached"

    fun location(id: String): String = "workspacePicker.location.$id"
    fun recent(id: String): String = "workspacePicker.recent.$id"
    fun entry(id: String): String = "workspacePicker.entry.$id"
}

/** Translate only application labels; user names and folder names stay verbatim. */
internal fun workspacePickerText(value: String, zh: Boolean): String = if (zh) value else when (value) {
    "未选择增强访问" -> "No enhanced access selected"
    "未就绪" -> "Not ready"
    "已连接" -> "Connected"
    "正在连接" -> "Connecting"
    "授权保留，当前未连接" -> "Authorized, currently disconnected"
    "未选择" -> "Not selected"
    "不可用" -> "Unavailable"
    "可用" -> "Available"
    "授权已失效" -> "Authorization expired"
    "已撤销" -> "Revoked"
    "已停用" -> "Disabled"
    "普通文件夹授权" -> "Standard folder access"
    "当前目标" -> "Current target"
    "根目录" -> "Root directory"
    "请通过系统文件选择器选择工作区。" -> "Choose a workspace in the system picker."
    "正在打开最近工作区…" -> "Opening recent workspace…"
    "已打开最近工作区。" -> "Recent workspace opened."
    "工作区已添加。" -> "Workspace added."
    "工作区属于当前会话上下文，切换将创建新会话。" -> "Changing the workspace creates a new conversation."
    "正在确认工作区切换…" -> "Confirming workspace change…"
    "当前增强访问不可用。" -> "Enhanced access is unavailable."
    "尚未选择增强访问。" -> "No enhanced access is selected."
    "工作区不存在或已移除。" -> "The workspace no longer exists."
    "当前目录不可访问。" -> "This directory is unavailable."
    "需要先完成文件夹授权。" -> "Grant folder access first."
    "当前权限通道不支持此操作。" -> "The selected access channel does not support this operation."
    "内部存储" -> "Internal storage"
    "下载" -> "Downloads"
    "文档" -> "Documents"
    "工作区授权已失效，请重新选择文件夹。" -> "Workspace access expired. Select the folder again."
    "工作区状态已变化，请刷新后重试。" -> "Workspace state changed. Refresh and try again."
    "当前通道不支持此工作区操作。" -> "This channel does not support this workspace operation."
    "工作区保存失败，请稍后重试。" -> "Workspace could not be saved. Try again later."
    "工作区操作结果未知，请检查状态后再试。" -> "Workspace outcome is unknown. Check the state before retrying."
    else -> when {
        value.endsWith("当前不可用；不会自动切换通道。") -> value.removeSuffix("当前不可用；不会自动切换通道。") + " is unavailable; the channel will not change automatically."
        else -> value
    }
}
