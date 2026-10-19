// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Offline test input plus its report. Running the test is a host callback: this section
 * never reads a book itself, and it shows whatever the host reported.
 */
@Composable
internal fun WorldBookPreviewSection(
    state: WorldBooksUiState,
    actions: WorldBooksActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(R.string.wb_offline_test), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.previewInput,
            onValueChange = actions.onPreviewInput,
            label = { Text(stringResource(R.string.wb_preview_input_hint)) },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(WorldBookTestTags.PREVIEW_INPUT),
        )
        Button(
            onClick = actions.onPreview,
            modifier = Modifier.padding(top = 6.dp).testTag(WorldBookTestTags.PREVIEW_RUN),
        ) {
            Text(stringResource(R.string.wb_preview_run))
        }
        state.preview?.let { preview ->
            WorldBookRunPanel(preview = preview, language = state.language, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
