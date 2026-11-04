// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun WorkspacePickerUiState.showsDirectoryBrowser(): Boolean =
    mode == WorkspacePickerModeUi.PRIVILEGED && !rootDirectorySelectionAvailable

internal fun WorkspacePickerUiState.showsSafFallbackButton(): Boolean =
    mode == WorkspacePickerModeUi.PRIVILEGED && canUseSafFallback

internal fun LazyListScope.rootDirectorySelectionItem(
    state: WorkspacePickerUiState,
    actions: WorkspacePickerActions,
    chinese: Boolean,
    browse: RootDirectoryBrowserUiState? = null,
    onBrowseRootDirectory: (String) -> Unit = {},
    onBrowseRootEntry: (String) -> Unit = {},
    onBrowseRootParent: () -> Unit = {},
    onBrowseRootMore: () -> Unit = {},
    shellConsentEnabled: Boolean = true,
    shellSettingsHint: String? = null,
    shellSettingsLabel: String? = null,
    onOpenShellSettings: (() -> Unit)? = null,
) {
    if (state.rootDirectorySelectionAvailable) item(key = "root-directory-selection") {
        RootDirectorySelectionCard(
            enabled = state.authority.ready && state.attachPhase != WorkspacePickerAttachPhaseUi.ATTACHING,
            chinese = chinese,
            // A completed attach clears the field; a failed one keeps the typed draft so the
            // user can correct it instead of retyping the whole path.
            draftResetKey = state.attached?.workspaceId,
            onConfirm = actions.onConfirmRootDirectory,
            // The chat picker owns the browse state holder too. A host without a live port keeps
            // the manual absolute-path flow: null here renders no browser controls at all.
            browse = browse ?: RootDirectoryBrowserUiState(),
            onBrowse = onBrowseRootDirectory,
            onBrowseEntry = onBrowseRootEntry,
            onBrowseParent = onBrowseRootParent,
            onBrowseMore = onBrowseRootMore,
            shellConsentEnabled = shellConsentEnabled,
            shellSettingsHint = shellSettingsHint,
            shellSettingsLabel = shellSettingsLabel,
            onOpenShellSettings = onOpenShellSettings,
        )
    }
}

/**
 * Foreground-only input. It is intentionally absent from saved UI state and model tool schemas.
 *
 * The typed path survives a failed attach: confirming no longer clears the field, so the host can
 * keep the input on screen with the reason and let the user correct it. [draftResetKey] is the only
 * reset signal — the host changes it after a completed attach, and the draft starts over then.
 *
 * [browse] is optional: when the host has a live browse port the card also renders a real directory
 * listing (entries, parent, paging) beside the manual field. Without it the manual flow is
 * unchanged, so the dialog still works on a host that has not wired browsing yet.
 */
@Composable
fun RootDirectorySelectionCard(
    enabled: Boolean,
    chinese: Boolean,
    onConfirm: (String, Boolean, Boolean, Boolean) -> Unit,
    draftResetKey: Any? = null,
    browse: RootDirectoryBrowserUiState = RootDirectoryBrowserUiState(),
    onBrowse: (String) -> Unit = {},
    onBrowseEntry: (String) -> Unit = {},
    onBrowseParent: () -> Unit = {},
    onBrowseMore: () -> Unit = {},
    shellConsentEnabled: Boolean = true,
    shellSettingsHint: String? = null,
    shellSettingsLabel: String? = null,
    onOpenShellSettings: (() -> Unit)? = null,
) {
    var path by remember(draftResetKey) { mutableStateOf("") }
    var writable by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(false) }
    var shell by remember { mutableStateOf(false) }
    // A channel-wide Shell consent can be withdrawn while this dialog is open. Stale local intent
    // must not submit a Shell grant the channel can no longer support.
    LaunchedEffect(shellConsentEnabled) {
        if (!shellConsentEnabled) shell = false
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.root_workspace_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.root_workspace_notice))
            OutlinedTextField(value = path, onValueChange = { if (it.length <= 4096) path = it },
                label = { Text(stringResource(R.string.root_workspace_path)) },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (browse.browsable) {
                Text(
                    stringResource(R.string.agents_root_browse_manual_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onBrowse(path.ifBlank { "/" }) },
                        enabled = enabled && !browse.loading,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag(RootWorkspaceTestTags.BROWSE),
                    ) { Text(stringResource(R.string.agents_root_browse_start)) }
                    OutlinedButton(
                        onClick = onBrowseParent,
                        enabled = enabled && !browse.loading && browse.path.length > 1,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag(RootWorkspaceTestTags.BROWSE_PARENT),
                    ) { Text(stringResource(R.string.agents_root_browse_parent)) }
                }
                if (browse.loading) {
                    Text(
                        stringResource(R.string.agents_root_browse_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                browse.error?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(RootWorkspaceTestTags.BROWSE_ERROR),
                    )
                }
                if (browse.entries.isEmpty() && !browse.loading && browse.error == null) {
                    Text(
                        stringResource(R.string.agents_root_browse_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (browse.entries.isNotEmpty()) {
                    // The listing is the only part allowed to grow: a bounded, independently
                    // scrollable viewport keeps the permissions and the confirm action reachable
                    // on a portrait phone even when a directory has hundreds of entries.
                    key(browse.path) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState())
                                .testTag(RootWorkspaceTestTags.BROWSE_LIST),
                        ) {
                            browse.entries.filter { it.directory }.forEach { entry ->
                                TextButton(
                                    onClick = {
                                        // The listing path and the manual draft move together so the user can
                                        // confirm exactly the directory that was just opened.
                                        path = entry.path
                                        onBrowseEntry(entry.path)
                                    },
                                    enabled = enabled && entry.directory,
                                    modifier = Modifier.fillMaxWidth()
                                        .testTag("${RootWorkspaceTestTags.BROWSE_ENTRY}:${entry.name}"),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        // An alias row must show the target the service actually observed,
                                        // not only the link name. The typed selection stays the target path.
                                        Text(
                                            entry.targetPath?.let { "→ $it" } ?: entry.path,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (browse.hasMore) {
                    TextButton(
                        onClick = onBrowseMore,
                        enabled = enabled && !browse.loading,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag(RootWorkspaceTestTags.BROWSE_MORE),
                    ) { Text(stringResource(R.string.agents_root_browse_more)) }
                }
            }
            RootOption(writable, enabled, stringResource(R.string.root_workspace_write)) { writable = it }
            RootOption(metadata, enabled, stringResource(R.string.root_workspace_metadata)) { metadata = it }
            RootOption(shell, enabled && shellConsentEnabled, stringResource(R.string.root_workspace_shell)) { shell = it }
            if (!shellConsentEnabled && enabled && !shellSettingsHint.isNullOrBlank()) {
                // The channel is connected but its independent Shell switch is off. This is a
                // Settings action, never a reconnect prompt: pointing at "reconnect" sends the
                // user to the wrong repair and can revoke the retained Root consent.
                Text(
                    shellSettingsHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(RootWorkspaceTestTags.SHELL_SETTINGS_HINT),
                )
                if (onOpenShellSettings != null && !shellSettingsLabel.isNullOrBlank()) {
                    TextButton(onClick = onOpenShellSettings, enabled = enabled) {
                        Text(shellSettingsLabel)
                    }
                }
            }
            Button(onClick = { onConfirm(path, writable, metadata, shell) },
                enabled = enabled && path.startsWith('/') && '\u0000' !in path,
                modifier = Modifier.fillMaxWidth().testTag(RootWorkspaceTestTags.ATTACH_CONFIRM)) {
                Text(stringResource(R.string.root_workspace_confirm))
            }
        }
    }
}

@Composable
private fun RootOption(value: Boolean, enabled: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(value, onChange, enabled = enabled)
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
    }
}
