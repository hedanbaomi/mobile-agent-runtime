// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One row of the book list. Tapping the text or the button asks the host to open the
 * editor; the switch only reports the requested on/off value and never flips local
 * state, so both paths stay host-driven.
 */
@Composable
internal fun WorldBookCard(
    book: WorldBookCardUi,
    actions: WorldBooksActions,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().testTag(WorldBookTestTags.card(book.id))) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f)
                        .clickable { actions.onSelect(book.id) }
                        .testTag(WorldBookTestTags.cardOpen(book.id)),
                ) {
                    Text(
                        book.name.ifBlank { book.id },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.description.isNotBlank()) {
                        Text(
                            book.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.wb_card_entries, book.entryCount) +
                            " · " + stringResource(R.string.wb_card_revision, book.revision),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Switch(
                        checked = book.enabled,
                        onCheckedChange = { actions.onToggleBook(book.id, it) },
                        modifier = Modifier.testTag(WorldBookTestTags.cardToggle(book.id)),
                    )
                    Text(worldBookToggleLabel(book.enabled), style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedButton(
                onClick = { actions.onSelect(book.id) },
                modifier = Modifier.padding(top = 8.dp).testTag(WorldBookTestTags.cardSelect(book.id)),
            ) {
                Text(stringResource(R.string.wb_select))
            }
        }
    }
}
