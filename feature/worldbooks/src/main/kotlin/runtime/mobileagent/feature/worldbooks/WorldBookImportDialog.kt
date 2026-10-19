// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Import preview. The dialog is read-only: it lists what the host detected and the
 * compatibility notices that came with it. Confirming is what asks the host to write;
 * dismissing leaves the pending import untouched.
 *
 * SillyTavern scripts, personas, character cards and vector extensions are reported as
 * data only. This screen never executes an imported expression or script.
 */
@Composable
internal fun WorldBookImportDialog(import: WorldBookImportUi, actions: WorldBooksActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelImport,
        modifier = Modifier.testTag(WorldBookTestTags.IMPORT_DIALOG),
        title = { Text(stringResource(R.string.wb_import_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.wb_import_source, import.name), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.wb_import_entries, import.entryCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    stringResource(R.string.wb_import_compat),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.wb_import_requires_confirm),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.wb_import_notices),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (import.hasNotices()) {
                    import.notices.forEachIndexed { index, notice ->
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp).testTag(WorldBookTestTags.importNotice(index)),
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.wb_import_no_notices),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = actions.onConfirmImport, modifier = Modifier.testTag(WorldBookTestTags.IMPORT_CONFIRM)) {
                Text(stringResource(R.string.wb_import))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelImport, modifier = Modifier.testTag(WorldBookTestTags.IMPORT_CANCEL)) {
                Text(stringResource(R.string.wb_cancel))
            }
        },
    )
}
