// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp

/**
 * Two-step confirmation used for every destructive action (book and entry deletion, and
 * discarding an unsaved editor draft). The dialog only reports the decision; the host
 * performs the side effect.
 */
@Composable
internal fun WorldBookConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String,
    dialogTag: String,
    confirmTag: String,
    cancelTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(dialogTag),
        title = { Text(title) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(cancelTag)) {
                Text(cancelLabel)
            }
        },
    )
}
