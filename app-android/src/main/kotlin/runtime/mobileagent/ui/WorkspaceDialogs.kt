// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import runtime.mobileagent.R

@Composable
internal fun PrivilegedWorkspaceBrowserDialog(
    page: runtime.mobileagent.skills.tooling.WorkspaceDirectoryPage?,
    currentLabel: String,
    busy: Boolean,
    error: String,
    chinese: Boolean,
    onOpenDirectory: (runtime.mobileagent.skills.tooling.WorkspaceDirectoryEntry) -> Unit,
    onUp: () -> Unit,
    onLoadMore: () -> Unit,
    onAttach: () -> Unit,
    onClose: () -> Unit,
) {
    AppLocalizedResources(if (chinese) "zh-CN" else "en-US") {
        AlertDialog(
            onDismissRequest = onClose,
            modifier = Modifier.testTag("agents.workspace.browser"),
            title = { Text(stringResource(R.string.workspace_browser_title)) },
            text = {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.workspace_browser_current, currentLabel),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    item {
                        Text(
                            stringResource(R.string.workspace_browser_privacy_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
                    if (busy) {
                        item { CircularProgressIndicator(Modifier.size(28.dp)) }
                    } else {
                        val directories = page?.entries.orEmpty().filter {
                            it.type == runtime.mobileagent.skills.tooling.WorkspaceEntryType.DIRECTORY
                        }
                        items(directories) { entry ->
                            Card(
                                Modifier.fillMaxWidth().clickable(enabled = entry.handle != null) { onOpenDirectory(entry) },
                            ) {
                                Text(entry.name, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (directories.isEmpty()) {
                            item { Text(stringResource(R.string.workspace_browser_empty)) }
                        }
                        if (page?.continuation != null) {
                            item {
                                TextButton(onClick = onLoadMore, modifier = Modifier.testTag("agents.workspace.browser.load_more")) {
                                    Text(stringResource(R.string.workspace_browser_load_more))
                                }
                            }
                        } else if (page?.truncated == true) {
                            item { Text(stringResource(R.string.workspace_browser_incomplete)) }
                        }
                    }
                    if (page?.parent != null) {
                        item {
                            TextButton(onClick = onUp, enabled = !busy) {
                                Text(stringResource(R.string.workspace_browser_up))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onAttach, enabled = page != null && !busy) {
                    Text(stringResource(R.string.workspace_browser_attach))
                }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.workspace_dialog_cancel)) }
            },
        )
    }
}

@Composable
internal fun WiredPathDialog(
    value: String,
    chinese: Boolean,
    onValue: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    AppLocalizedResources(if (chinese) "zh-CN" else "en-US") {
        AlertDialog(
            onDismissRequest = onClose,
            modifier = Modifier.testTag("agents.workspace.wired_path"),
            title = { Text(stringResource(R.string.workspace_wired_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.workspace_wired_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValue,
                        singleLine = true,
                        label = { Text(stringResource(R.string.workspace_wired_label)) },
                    )
                }
            },
            confirmButton = {
                Button(onClick = onConfirm, enabled = value.startsWith("/") && value != "/") {
                    Text(stringResource(R.string.workspace_wired_attach))
                }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.workspace_dialog_cancel)) }
            },
        )
    }
}

@Composable
internal fun FullDeviceFilesConfirmationDialog(
    chinese: Boolean,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    AppLocalizedResources(if (chinese) "zh-CN" else "en-US") {
        AlertDialog(
            onDismissRequest = onClose,
            modifier = Modifier.testTag("agents.workspace.full_device_confirm"),
            title = { Text(stringResource(R.string.workspace_full_device_title)) },
            text = { Text(stringResource(R.string.workspace_full_device_body)) },
            confirmButton = {
                Button(onClick = onConfirm) { Text(stringResource(R.string.workspace_full_device_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.workspace_dialog_cancel)) }
            },
        )
    }
}
