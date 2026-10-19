// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Book list: search, create, import, export, per-book enable switch and selection, plus
 * the offline test section. The whole pane is one lazy list, so a narrow screen scrolls
 * instead of clipping and the keyboard cannot cover the search field.
 *
 * Delete and duplicate stay in the editor, where the book under edit is unambiguous, so
 * no list action silently targets "whatever is selected".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorldBookListPane(
    state: WorldBooksUiState,
    actions: WorldBooksActions,
    modifier: Modifier = Modifier,
) {
    var exportOpen by remember { mutableStateOf(false) }
    val visible = state.visibleBooks()

    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp).testTag(WorldBookTestTags.LIST),
    ) {
        item(key = "title") {
            Text(
                stringResource(R.string.wb_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        item(key = "status") {
            WorldBooksStatusRow(
                status = state.status,
                error = state.error,
                loading = state.loading,
                actions = actions,
            )
        }
        item(key = "query") {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQuery,
                label = { Text(stringResource(R.string.wb_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(WorldBookTestTags.QUERY),
            )
        }
        item(key = "commands") {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(onClick = actions.onCreate, modifier = Modifier.testTag(WorldBookTestTags.CREATE)) {
                    Text(stringResource(R.string.wb_create))
                }
                OutlinedButton(onClick = actions.onImport, modifier = Modifier.testTag(WorldBookTestTags.IMPORT)) {
                    Text(stringResource(R.string.wb_import))
                }
                OutlinedButton(onClick = { exportOpen = true }, modifier = Modifier.testTag(WorldBookTestTags.EXPORT)) {
                    Text(stringResource(R.string.wb_export))
                }
            }
        }
        item(key = "preview") { WorldBookPreviewSection(state, actions) }
        if (visible.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(if (state.books.isEmpty()) R.string.wb_empty else R.string.wb_empty_search),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp).testTag(WorldBookTestTags.EMPTY),
                )
            }
        }
        items(visible, key = { it.id }) { book -> WorldBookCard(book, actions, Modifier.padding(top = 8.dp)) }
        item(key = "tail") { Spacer(Modifier.height(32.dp)) }
    }

    if (exportOpen) {
        WorldBookExportDialog(
            onExport = { kind ->
                exportOpen = false
                actions.onExport(kind)
            },
            onDismiss = { exportOpen = false },
        )
    }
}
