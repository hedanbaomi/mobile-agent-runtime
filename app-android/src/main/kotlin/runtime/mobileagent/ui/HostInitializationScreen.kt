// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import runtime.mobileagent.R
import runtime.mobileagent.HostStartupFailure

@Composable
internal fun HostInitializationScreen(
    failed: Boolean,
    failure: HostStartupFailure? = null,
    exporting: Boolean = false,
    exportStatus: String? = null,
    onRetry: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (!failed) CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(if (failed) R.string.host_initialization_failed else R.string.host_initializing))
                if (failed) {
                    failure?.let { Text(stringResource(R.string.host_failure_details, it.stage.name, it.code.name)) }
                    Button(onClick = onRetry, enabled = !exporting) { Text(stringResource(R.string.host_retry)) }
                    OutlinedButton(onClick = onExport, enabled = !exporting && failure != null) {
                        Text(stringResource(R.string.host_export))
                    }
                    exportStatus?.let { Text(it) }
                }
            }
        }
    }
}
