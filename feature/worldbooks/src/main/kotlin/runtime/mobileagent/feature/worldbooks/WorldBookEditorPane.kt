// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
 * Book editor: identity fields, the entry list with add/enable/duplicate/delete, the
 * generic entry field form and the generic book settings form.
 *
 * The actions contract carries no per-entry identifier for duplicate and delete, so a
 * row reports its own selection first ([WorldBooksActions.onSelectEntry]) and then the
 * unit-valued action; the host applies the selection before the follow-up action. The
 * entry enable switch writes back through [WorldBookEntryKeys.ENABLED], because there is
 * no dedicated entry-toggle callback.
 *
 * Closing a dirty draft asks for confirmation, and the system back gesture is routed to
 * the same question instead of discarding silently.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorldBookEditorPane(
    editor: WorldBookEditorUi,
    actions: WorldBooksActions,
    status: String = "",
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    var confirmClose by remember { mutableStateOf(false) }
    var pendingBookDelete by remember { mutableStateOf(false) }
    var pendingEntryDelete by remember { mutableStateOf<WorldBookEntryUi?>(null) }
    var entryFieldsExpanded by remember(editor.selectedEntryUid) { mutableStateOf(true) }
    var settingsExpanded by remember { mutableStateOf(true) }

    val backOwner = LocalOnBackPressedDispatcherOwner.current
    if (backOwner != null) {
        BackHandler(enabled = editor.dirty) { confirmClose = true }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp).testTag(WorldBookTestTags.EDITOR),
    ) {
        item(key = "top-bar") {
            EditorTopBar(
                editor = editor,
                actions = actions,
                onCloseRequest = { if (editor.dirty) confirmClose = true else actions.onCloseEditor() },
                onDeleteRequest = { pendingBookDelete = true },
            )
        }
        item(key = "status") {
            WorldBooksStatusRow(status = status, error = error, loading = false, actions = actions)
        }
        item(key = "identity") { EditorIdentity(editor, actions) }
        item(key = "entries-header") { EntriesHeader(editor, actions) }
        if (editor.entries.isEmpty()) {
            item(key = "entries-empty") {
                Text(
                    stringResource(R.string.wb_editor_no_entries),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp).testTag(WorldBookTestTags.EDITOR_ENTRIES_EMPTY),
                )
            }
        }
        items(editor.entries, key = { it.uid }) { entry ->
            EditorEntryRow(
                entry = entry,
                selected = entry.uid == editor.selectedEntryUid,
                actions = actions,
                onDeleteRequest = { pendingEntryDelete = entry },
            )
        }
        item(key = "entry-fields") {
            EntryFieldsSection(
                editor = editor,
                expanded = entryFieldsExpanded,
                onToggle = { entryFieldsExpanded = !entryFieldsExpanded },
                actions = actions,
            )
        }
        item(key = "settings-fields") {
            SettingsFieldsSection(
                editor = editor,
                expanded = settingsExpanded,
                onToggle = { settingsExpanded = !settingsExpanded },
                actions = actions,
            )
        }
        item(key = "tail") { Spacer(Modifier.height(32.dp)) }
    }

    if (confirmClose) {
        WorldBookConfirmDialog(
            title = stringResource(R.string.wb_unsaved_title),
            body = stringResource(R.string.wb_unsaved_body),
            confirmLabel = stringResource(R.string.wb_unsaved_discard),
            cancelLabel = stringResource(R.string.wb_unsaved_keep),
            dialogTag = WorldBookTestTags.UNSAVED_DIALOG,
            confirmTag = WorldBookTestTags.UNSAVED_DISCARD,
            cancelTag = WorldBookTestTags.UNSAVED_KEEP,
            onConfirm = {
                confirmClose = false
                actions.onCloseEditor()
            },
            onDismiss = { confirmClose = false },
        )
    }
    if (pendingBookDelete) {
        WorldBookConfirmDialog(
            title = stringResource(R.string.wb_delete_book_title),
            body = stringResource(R.string.wb_delete_book_body, editor.name),
            confirmLabel = stringResource(R.string.wb_delete),
            cancelLabel = stringResource(R.string.wb_cancel),
            dialogTag = WorldBookTestTags.DELETE_DIALOG,
            confirmTag = WorldBookTestTags.DELETE_CONFIRM,
            cancelTag = WorldBookTestTags.DELETE_CANCEL,
            onConfirm = {
                pendingBookDelete = false
                actions.onDeleteBook()
            },
            onDismiss = { pendingBookDelete = false },
        )
    }
    pendingEntryDelete?.let { entry ->
        WorldBookConfirmDialog(
            title = stringResource(R.string.wb_delete_entry_title),
            body = stringResource(R.string.wb_delete_entry_body, entry.title),
            confirmLabel = stringResource(R.string.wb_delete),
            cancelLabel = stringResource(R.string.wb_cancel),
            dialogTag = WorldBookTestTags.DELETE_DIALOG,
            confirmTag = WorldBookTestTags.DELETE_CONFIRM,
            cancelTag = WorldBookTestTags.DELETE_CANCEL,
            onConfirm = {
                pendingEntryDelete = null
                actions.onDeleteEntry()
            },
            onDismiss = { pendingEntryDelete = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorTopBar(
    editor: WorldBookEditorUi,
    actions: WorldBooksActions,
    onCloseRequest: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            stringResource(if (editor.id == null) R.string.wb_editor_new else R.string.wb_editor_edit),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.wb_card_revision, editor.revision), style = MaterialTheme.typography.labelSmall)
        if (editor.dirty) {
            Text(stringResource(R.string.wb_unsaved_title), style = MaterialTheme.typography.labelSmall)
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = actions.onSave, modifier = Modifier.testTag(WorldBookTestTags.EDITOR_SAVE)) {
                Text(stringResource(R.string.wb_save))
            }
            if (editor.id != null) {
                OutlinedButton(
                    onClick = actions.onDuplicateBook,
                    modifier = Modifier.testTag(WorldBookTestTags.EDITOR_DUPLICATE),
                ) {
                    Text(stringResource(R.string.wb_duplicate))
                }
                OutlinedButton(
                    onClick = onDeleteRequest,
                    modifier = Modifier.testTag(WorldBookTestTags.EDITOR_DELETE_BOOK),
                ) {
                    Text(stringResource(R.string.wb_delete))
                }
            }
            TextButton(onClick = onCloseRequest, modifier = Modifier.testTag(WorldBookTestTags.EDITOR_CLOSE)) {
                Text(stringResource(R.string.wb_close))
            }
        }
    }
}

@Composable
private fun EditorIdentity(editor: WorldBookEditorUi, actions: WorldBooksActions) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = editor.name,
            onValueChange = actions.onBookName,
            label = { Text(stringResource(R.string.wb_editor_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(WorldBookTestTags.EDITOR_NAME),
        )
        OutlinedTextField(
            value = editor.description,
            onValueChange = actions.onBookDescription,
            label = { Text(stringResource(R.string.wb_editor_description)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(WorldBookTestTags.EDITOR_DESCRIPTION),
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.wb_editor_enabled), style = MaterialTheme.typography.bodyLarge)
                Text(worldBookToggleLabel(editor.enabled), style = MaterialTheme.typography.labelSmall)
            }
            Switch(
                checked = editor.enabled,
                onCheckedChange = actions.onBookEnabled,
                modifier = Modifier.testTag(WorldBookTestTags.EDITOR_ENABLED),
            )
        }
    }
}

@Composable
private fun EntriesHeader(editor: WorldBookEditorUi, actions: WorldBooksActions) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.wb_editor_entries, editor.entries.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).testTag(WorldBookTestTags.EDITOR_ENTRIES),
        )
        Button(onClick = actions.onAddEntry, modifier = Modifier.testTag(WorldBookTestTags.ENTRY_ADD)) {
            Text(stringResource(R.string.wb_editor_add_entry))
        }
    }
}

@Composable
private fun EditorEntryRow(
    entry: WorldBookEntryUi,
    selected: Boolean,
    actions: WorldBooksActions,
    onDeleteRequest: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(WorldBookTestTags.entry(entry.uid)),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(1f)
                        .clickable { actions.onSelectEntry(entry.uid) }
                        .testTag(WorldBookTestTags.entrySelect(entry.uid)),
                ) {
                    Text(
                        entry.title.ifBlank { entry.uid },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.keysSummary.isNotBlank()) {
                        Text(
                            stringResource(R.string.wb_entry_keys) + ": " + entry.keysSummary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (selected) {
                        Text(stringResource(R.string.wb_selected), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.wb_entry_enabled), style = MaterialTheme.typography.labelSmall)
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { requested ->
                            actions.onSelectEntry(entry.uid)
                            actions.onEntryFieldChange(WorldBookEntryKeys.ENABLED, WorldBookEntryKeys.enabledValue(requested))
                        },
                        modifier = Modifier.testTag(WorldBookTestTags.entryToggle(entry.uid)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(worldBookToggleLabel(entry.enabled), style = MaterialTheme.typography.labelSmall)
                if (entry.constant) {
                    Text(
                        stringResource(R.string.wb_entry_constant),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        actions.onSelectEntry(entry.uid)
                        actions.onDuplicateEntry()
                    },
                    modifier = Modifier.testTag(WorldBookTestTags.entryDuplicate(entry.uid)),
                ) {
                    Text(stringResource(R.string.wb_duplicate))
                }
                TextButton(
                    onClick = {
                        actions.onSelectEntry(entry.uid)
                        onDeleteRequest()
                    },
                    modifier = Modifier.testTag(WorldBookTestTags.entryDelete(entry.uid)),
                ) {
                    Text(stringResource(R.string.wb_delete))
                }
            }
        }
    }
}

@Composable
private fun EntryFieldsSection(
    editor: WorldBookEditorUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: WorldBooksActions,
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag(WorldBookTestTags.ENTRY_FIELDS)) {
        SectionHeader(
            title = stringResource(R.string.wb_entry_fields, editor.entryFields.size),
            hint = stringResource(R.string.wb_entry_fields_hint),
            expanded = expanded,
            onToggle = onToggle,
            toggleTag = WorldBookTestTags.ENTRY_FIELDS_TOGGLE,
        )
        if (expanded) {
            if (editor.selectedEntry() == null) {
                Text(
                    stringResource(R.string.wb_entry_fields_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                WorldBookFieldForm(fields = editor.entryFields, onChange = actions.onEntryFieldChange)
            }
        }
    }
}

@Composable
private fun SettingsFieldsSection(
    editor: WorldBookEditorUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: WorldBooksActions,
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag(WorldBookTestTags.SETTINGS_FIELDS)) {
        SectionHeader(
            title = stringResource(R.string.wb_settings_fields, editor.settingsFields.size),
            hint = stringResource(R.string.wb_entry_fields_hint),
            expanded = expanded,
            onToggle = onToggle,
            toggleTag = WorldBookTestTags.SETTINGS_FIELDS_TOGGLE,
        )
        if (expanded) {
            WorldBookFieldForm(fields = editor.settingsFields, onChange = actions.onSettingFieldChange)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    hint: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    toggleTag: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onToggle, modifier = Modifier.testTag(toggleTag)) {
            Text(stringResource(if (expanded) R.string.wb_collapse else R.string.wb_expand))
        }
    }
}
