// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun WorkspacePickerUiState.showsDirectoryBrowser(): Boolean =
    mode == WorkspacePickerModeUi.PRIVILEGED && !rootDirectorySelectionAvailable

internal fun WorkspacePickerUiState.showsSafFallbackButton(): Boolean =
    mode == WorkspacePickerModeUi.PRIVILEGED && canUseSafFallback

internal fun LazyListScope.rootDirectorySelectionItem(state: WorkspacePickerUiState, actions: WorkspacePickerActions, chinese: Boolean) {
    if (state.rootDirectorySelectionAvailable) item(key = "root-directory-selection") {
        RootDirectorySelectionCard(
            enabled = state.authority.ready && state.attachPhase != WorkspacePickerAttachPhaseUi.ATTACHING,
            chinese = chinese, onConfirm = actions.onConfirmRootDirectory,
        )
    }
}

/** Foreground-only input. It is intentionally absent from saved UI state and model tool schemas. */
@Composable
fun RootDirectorySelectionCard(
    enabled: Boolean,
    chinese: Boolean,
    onConfirm: (String, Boolean, Boolean, Boolean) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var writable by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(false) }
    var shell by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.root_workspace_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.root_workspace_notice))
            OutlinedTextField(value = path, onValueChange = { if (it.length <= 4096) path = it },
                label = { Text(stringResource(R.string.root_workspace_path)) },
                enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
            RootOption(writable, enabled, stringResource(R.string.root_workspace_write)) { writable = it }
            RootOption(metadata, enabled, stringResource(R.string.root_workspace_metadata)) { metadata = it }
            RootOption(shell, enabled, stringResource(R.string.root_workspace_shell)) { shell = it }
            Button(onClick = { onConfirm(path, writable, metadata, shell); path = "" },
                enabled = enabled && path.startsWith('/') && '\u0000' !in path,
                modifier = Modifier.fillMaxWidth()) {
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
