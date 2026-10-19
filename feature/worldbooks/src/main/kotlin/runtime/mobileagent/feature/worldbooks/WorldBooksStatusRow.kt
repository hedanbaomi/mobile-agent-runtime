// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Host status line, error banner and loading marker. The error text is rendered exactly
 * as the host supplies it; this module never appends an exception or a call stack.
 */
@Composable
internal fun WorldBooksStatusRow(
    status: String,
    error: String?,
    loading: Boolean,
    actions: WorldBooksActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).testTag(WorldBookTestTags.STATUS),
            )
        }
        if (!error.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).testTag(WorldBookTestTags.ERROR),
                    )
                    TextButton(
                        onClick = actions.onDismissError,
                        modifier = Modifier.testTag(WorldBookTestTags.ERROR_DISMISS),
                    ) {
                        Text(stringResource(R.string.wb_error_dismiss))
                    }
                }
            }
        }
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.wb_loading),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp).testTag(WorldBookTestTags.LOADING),
                )
            }
        }
    }
}

/** Shared "active / off" wording so no switch state is communicated by color alone. */
@Composable
internal fun worldBookToggleLabel(enabled: Boolean): String =
    stringResource(if (enabled) R.string.wb_toggle_enabled else R.string.wb_toggle_disabled)
