// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.Authority
import runtime.mobileagent.domain.DangerousMode
import runtime.mobileagent.domain.isRootAuthority
import runtime.mobileagent.feature.agents.R as AgentsR
import runtime.mobileagent.feature.agents.RootDirectoryBrowserUiState
import runtime.mobileagent.feature.agents.RootDirectorySelectionCard
import runtime.mobileagent.feature.agents.RootWorkspacePermissionsUiState
import runtime.mobileagent.feature.agents.RootWorkspaceTestTags

internal fun isFullDeviceFilesEligibleForEditor(
    selectedAuthority: Authority,
    authorityReady: Boolean,
    dangerousModeBuildAllowed: Boolean,
    dangerousMode: DangerousMode,
    workspaceBusy: Boolean,
): Boolean = !selectedAuthority.isRootAuthority &&
    authorityReady &&
    dangerousModeBuildAllowed &&
    dangerousMode != DangerousMode.DISABLED &&
    !workspaceBusy

internal fun offlinePrivilegedAuthoritySummary(
    selectedAuthority: Authority,
    chinese: Boolean,
): String = if (selectedAuthority.isRootAuthority) {
    if (chinese) {
        "Root 通道授权记录已保留；当前会话未连接，需重新连接后生效。"
    } else {
        "Root channel consent is retained, but no verified Root session is currently connected."
    }
} else {
    if (chinese) {
        "ADB 级授权保持不变，但当前连接不可用。"
    } else {
        "ADB-level authorization is retained, but the current connection is unavailable."
    }
}

@Composable
internal fun RootAgentWorkspaceDialog(
    enabled: Boolean,
    chinese: Boolean,
    onConfirm: (path: String, writable: Boolean, metadata: Boolean, shell: Boolean) -> Unit,
    onClose: () -> Unit,
    message: String = "",
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
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("agents.workspace.root_path"),
        title = { Text(if (chinese) "绑定 Root 设备目录" else "Attach Root directory") },
        text = {
            // The dialog text itself may need to scroll on a short screen; the listing inside the
            // card is bounded separately, so this outer scroll is only the last-resort path to the
            // permissions and confirm action.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                RootDirectorySelectionCard(
                    enabled = enabled,
                    chinese = chinese,
                    onConfirm = onConfirm,
                    browse = browse,
                    onBrowse = onBrowse,
                    onBrowseEntry = onBrowseEntry,
                    onBrowseParent = onBrowseParent,
                    onBrowseMore = onBrowseMore,
                    shellConsentEnabled = shellConsentEnabled,
                    shellSettingsHint = shellSettingsHint,
                    shellSettingsLabel = shellSettingsLabel,
                    onOpenShellSettings = onOpenShellSettings,
                )
                if (message.isNotBlank()) Text(message, Modifier.testTag("agents.workspace.root_path.status"))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onClose) {
                Text(if (chinese) "取消" else "Cancel")
            }
        },
    )
}

/**
 * In-place permission edit for an already attached Root workspace.
 *
 * The host owns the call; this dialog only collects the desired intent and shows the failure. The
 * coordinator re-signs grants, so existing conversations keep their frozen snapshot and only new
 * conversations can bind the updated permission set.
 *
 * [state] is the single source of truth for the three checkboxes: no local copy is kept here. A
 * late-arriving live read must be reflected immediately, and Apply must submit exactly what the
 * user sees — keeping a `remember` shadow would let an unchecked Shell be submitted as granted.
 */
@Composable
internal fun RootWorkspacePermissionDialog(
    state: RootWorkspacePermissionsUiState,
    chinese: Boolean,
    onChange: (writable: Boolean, metadata: Boolean, shell: Boolean) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
    shellConsentEnabled: Boolean = true,
    shellSettingsHint: String? = null,
    shellSettingsLabel: String? = null,
    onOpenShellSettings: (() -> Unit)? = null,
) {
    val writable = state.writable
    val metadata = state.metadata
    val shell = state.shell
    val applyEnabled = state.canApply && (shellConsentEnabled || !shell)
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(RootWorkspaceTestTags.PERMISSIONS_DIALOG),
        title = { Text(stringResource(AgentsR.string.agents_root_permissions_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(state.displayName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(AgentsR.string.agents_root_permissions_new_chat_only),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.loading) {
                    Text(
                        stringResource(AgentsR.string.agents_root_permissions_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PermissionOption(
                    checked = writable,
                    enabled = state.canApply,
                    label = stringResource(AgentsR.string.agents_root_permissions_write),
                ) { enabled ->
                    // Write and metadata are independent intents: turning write off must not drop a
                    // metadata-read selection (the backend then grants READ only, never WRITE).
                    onChange(enabled, metadata, shell)
                }
                PermissionOption(
                    checked = metadata,
                    enabled = state.canApply,
                    label = stringResource(AgentsR.string.agents_root_permissions_metadata),
                ) { enabled ->
                    onChange(writable, enabled, shell)
                }
                PermissionOption(
                    checked = shell,
                    enabled = state.canApply && (shellConsentEnabled || shell),
                    label = stringResource(AgentsR.string.agents_root_permissions_shell),
                ) { enabled ->
                    onChange(writable, metadata, enabled)
                }
                if (!shellConsentEnabled && !shellSettingsHint.isNullOrBlank()) {
                    // The channel is connected but its independent Shell switch is off. This is a
                    // Settings action, not a reconnect: reconnecting cannot create the missing
                    // consent and would only churn the retained Root session.
                    Text(
                        shellSettingsHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(RootWorkspaceTestTags.SHELL_SETTINGS_HINT),
                    )
                    if (onOpenShellSettings != null && !shellSettingsLabel.isNullOrBlank()) {
                        TextButton(onClick = onOpenShellSettings) { Text(shellSettingsLabel) }
                    }
                }
                state.error?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApply,
                enabled = applyEnabled,
                modifier = Modifier.testTag(RootWorkspaceTestTags.PERMISSIONS_CONFIRM),
            ) { Text(stringResource(AgentsR.string.agents_root_permissions_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(if (chinese) "取消" else "Cancel") }
        },
    )
}

@Composable
private fun PermissionOption(checked: Boolean, enabled: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
