// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import runtime.mobileagent.R

@Composable
internal fun DatabaseRecoveryScreen(busy: Boolean, message: String?, onExport: () -> Unit, onStartNew: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    MobileAgentTheme {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.database_recovery_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.database_recovery_help))
            Button(onClick = onExport, enabled = !busy, modifier = Modifier.testTag("database.recovery.export")) {
                Text(stringResource(R.string.database_recovery_export))
            }
            TextButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.testTag("database.recovery.startNew")) {
                Text(stringResource(R.string.database_recovery_start_new))
            }
            message?.let { Text(it) }
        }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.database_recovery_start_new)) },
            text = { Text(stringResource(R.string.database_recovery_confirm)) },
            confirmButton = { Button(onClick = { confirm = false; onStartNew() }) { Text(stringResource(R.string.database_recovery_continue)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.database_recovery_cancel)) } },
        )
    }
}
