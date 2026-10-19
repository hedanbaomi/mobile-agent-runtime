// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reusable picker for an agent or conversation binding. It only chooses among books that
 * already exist and only reports the resulting id set; it never changes a book's global
 * enable state and never creates a book.
 *
 * @param language host UI language. Visible copy resolves through the app-localized
 * resources, so the parameter is informational for hosts that render one language.
 */
@Composable
fun WorldBookBindingDialog(
    books: List<WorldBookCardUi>,
    selectedIds: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER") language: String = "zh-CN",
    modifier: Modifier = Modifier,
) {
    var selected by remember(selectedIds) { mutableStateOf(selectedIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(WorldBookTestTags.BINDING_DIALOG),
        title = { Text(stringResource(R.string.wb_binding_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.wb_binding_body), style = MaterialTheme.typography.bodySmall)
                selected.firstNotNullOfOrNull { id -> books.firstOrNull { it.id == id && it.enabled } }?.let { primary ->
                    Text(stringResource(R.string.wb_binding_primary, primary.name), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.wb_binding_selected, selected.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (books.isEmpty()) {
                    Text(
                        stringResource(R.string.wb_binding_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                books.forEach { book ->
                    BindingRow(
                        book = book,
                        checked = selected.contains(book.id),
                        onToggle = { selected = selected.toggled(book.id) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                modifier = Modifier.testTag(WorldBookTestTags.BINDING_CONFIRM),
            ) {
                Text(stringResource(R.string.wb_binding_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(WorldBookTestTags.BINDING_CANCEL)) {
                Text(stringResource(R.string.wb_cancel))
            }
        },
    )
}

@Composable
private fun BindingRow(book: WorldBookCardUi, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag(WorldBookTestTags.bindingBook(book.id)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                book.name.ifBlank { book.id },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.wb_card_entries, book.entryCount) + " · " + worldBookToggleLabel(book.enabled),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (contains(id)) this - id else this + id
