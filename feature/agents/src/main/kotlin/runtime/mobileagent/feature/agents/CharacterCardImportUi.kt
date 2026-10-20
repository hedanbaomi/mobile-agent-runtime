// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

data class CharacterCardImportUi(
    val name: String,
    val format: String,
    val creator: String = "",
    val description: String = "",
    val notices: List<String> = emptyList(),
    val userName: String = "User",
    val greetings: List<String> = emptyList(),
    val greetingIndex: Int = 0,
    val useGreeting: Boolean = true,
    val worldBookName: String = "",
    val worldBookEntries: Int = 0,
    val hasWorldBook: Boolean = false,
    val importWorldBook: Boolean = true,
    val mode: runtime.mobileagent.domain.AgentMode = runtime.mobileagent.domain.AgentMode.NORMAL,
)

@Composable
internal fun CharacterCardImportDialog(state: AgentsUiState, actions: AgentsActions) {
    val card = state.characterImport ?: return
    val busy = state.characterImportBusy
    AlertDialog(
        onDismissRequest = { if (!busy) actions.onCancelCharacterImport() },
        modifier = Modifier.testTag("agents.character_import.preview"),
        title = { Text(stringResource(R.string.character_card_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(card.format + if (card.creator.isBlank()) "" else " · ${card.creator}")
                OutlinedTextField(card.name, { actions.onCharacterImportChange(card.copy(name = it)) },
                    enabled = !busy, label = { Text(stringResource(R.string.character_card_name)) },
                    modifier = Modifier.fillMaxWidth().testTag("agents.character_import.name"))
                CharacterCardImportModeSelector(card, busy, actions)
                OutlinedTextField(card.userName, { actions.onCharacterImportChange(card.copy(userName = it)) },
                    enabled = !busy, label = { Text(stringResource(R.string.character_card_player_macro)) },
                    modifier = Modifier.fillMaxWidth().testTag("agents.character_import.user_name"))
                if (card.description.isNotBlank()) Text(card.description, style = MaterialTheme.typography.bodySmall)
                if (card.greetings.isNotEmpty()) {
                    ImportChoice(card.useGreeting, !busy,
                        stringResource(R.string.character_card_use_greeting)) {
                        actions.onCharacterImportChange(card.copy(useGreeting = it))
                    }
                    if (card.useGreeting) {
                        val index = card.greetingIndex.coerceIn(card.greetings.indices)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1} / ${card.greetings.size}", Modifier.weight(1f))
                            TextButton(enabled = !busy && index > 0, onClick = {
                                actions.onCharacterImportChange(card.copy(greetingIndex = index - 1))
                            }) { Text(stringResource(R.string.character_card_previous)) }
                            TextButton(enabled = !busy && index < card.greetings.lastIndex, onClick = {
                                actions.onCharacterImportChange(card.copy(greetingIndex = index + 1))
                            }) { Text(stringResource(R.string.character_card_next)) }
                        }
                        Text(card.greetings[index].take(4000), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (card.hasWorldBook) {
                    ImportChoice(card.importWorldBook, !busy,
                        stringResource(R.string.character_card_book, card.worldBookEntries)) {
                        actions.onCharacterImportChange(card.copy(importWorldBook = it))
                    }
                    if (card.importWorldBook) OutlinedTextField(card.worldBookName,
                        { actions.onCharacterImportChange(card.copy(worldBookName = it)) }, enabled = !busy,
                        label = { Text(stringResource(R.string.character_card_book_name)) }, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.character_card_draft_help),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.character_card_limits_help),
                    style = MaterialTheme.typography.bodySmall)
                card.notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirmCharacterImport, enabled = !busy && card.name.isNotBlank() && card.userName.isNotBlank(),
                modifier = Modifier.testTag("agents.character_import.confirm")) {
                Text(stringResource(R.string.character_card_draft))
            }
        },
        dismissButton = { TextButton(onClick = actions.onCancelCharacterImport, enabled = !busy,
            modifier = Modifier.testTag("agents.character_import.cancel")) { Text(stringResource(R.string.character_card_cancel)) } },
    )
}

@Composable
private fun ImportChoice(checked: Boolean, enabled: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CharacterCardEditorFields(editor: AgentEditorUi, actions: AgentsActions, zh: Boolean) {
    if (editor.characterCardLoading) {
        Text(stringResource(R.string.character_card_loading))
        return
    }
    val card = editor.characterCard ?: return
    Card(Modifier.fillMaxWidth().testTag("agents.editor.character_card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.character_card_format, card.document.format))
            OutlinedTextField(card.userName, { actions.onEditorChange(editor.copy(characterCard = card.copy(userName = it))) },
                label = { Text(stringResource(R.string.character_card_player)) }, modifier = Modifier.fillMaxWidth())
            ImportChoice(card.greeting != null, true, stringResource(R.string.character_card_use_greeting)) {
                actions.onEditorChange(editor.copy(characterCard = card.copy(greeting = if (it) card.document.firstMessage else null)))
            }
            card.greeting?.let { greeting ->
                OutlinedTextField(greeting, { actions.onEditorChange(editor.copy(characterCard = card.copy(greeting = it))) },
                    label = { Text(stringResource(R.string.character_card_greeting)) }, minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("agents.editor.character_greeting"))
            }
            editor.pendingCharacterWorldBook?.let { book ->
                Text(stringResource(R.string.character_card_pending_book, book.name, book.entries.size))
                TextButton(onClick = { actions.onEditorChange(editor.copy(pendingCharacterWorldBook = null)) }) {
                    Text(stringResource(R.string.character_card_exclude_book))
                }
            }
            Text(stringResource(R.string.character_card_snapshot_help),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
