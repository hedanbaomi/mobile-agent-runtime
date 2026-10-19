// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Export format chooser. The chosen kind is reported once, on confirm. */
@Composable
internal fun WorldBookExportDialog(
    onExport: (WorldBookExportKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(WorldBookExportKind.NATIVE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(WorldBookTestTags.EXPORT_DIALOG),
        title = { Text(stringResource(R.string.wb_export_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                WorldBookExportKind.entries.forEach { kind ->
                    ExportOption(kind = kind, selected = kind == selected, onSelect = { selected = kind })
                }
                Text(
                    stringResource(R.string.wb_export_note),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onExport(selected) },
                modifier = Modifier.testTag(WorldBookTestTags.EXPORT_CONFIRM),
            ) {
                Text(stringResource(R.string.wb_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(WorldBookTestTags.EXPORT_CANCEL)) {
                Text(stringResource(R.string.wb_cancel))
            }
        },
    )
}

@Composable
private fun ExportOption(kind: WorldBookExportKind, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp)
            .testTag(WorldBookTestTags.exportOption(kind)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(stringResource(exportLabel(kind)), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(exportHint(kind)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun exportLabel(kind: WorldBookExportKind): Int = when (kind) {
    WorldBookExportKind.NATIVE -> R.string.wb_export_native
    WorldBookExportKind.SILLY_TAVERN -> R.string.wb_export_st
    WorldBookExportKind.ORIGINAL -> R.string.wb_export_original
}

internal fun exportHint(kind: WorldBookExportKind): Int = when (kind) {
    WorldBookExportKind.NATIVE -> R.string.wb_export_native_hint
    WorldBookExportKind.SILLY_TAVERN -> R.string.wb_export_st_hint
    WorldBookExportKind.ORIGINAL -> R.string.wb_export_original_hint
}
