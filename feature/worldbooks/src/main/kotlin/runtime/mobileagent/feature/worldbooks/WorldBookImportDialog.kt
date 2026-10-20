// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.worldbook.WorldBookBatchAction
import runtime.mobileagent.domain.worldbook.WorldBookConflictPolicy
import runtime.mobileagent.domain.worldbook.WorldBookFileSkip

/**
 * Import preview for one file, several files, a folder or ZIP archives. The dialog is
 * read-only apart from per-file selection and the same-name policy: confirming is what asks
 * the host to write, dismissing leaves every staged file untouched.
 *
 * SillyTavern scripts, personas, character cards and vector extensions are reported as
 * data only. This screen never executes an imported expression or script.
 */
@Composable
internal fun WorldBookImportDialog(import: WorldBookImportUi, actions: WorldBooksActions) {
    val batch = import.isBatch()
    AlertDialog(
        onDismissRequest = actions.onCancelImport,
        modifier = Modifier.testTag(WorldBookTestTags.IMPORT_DIALOG),
        title = { Text(stringResource(R.string.wb_import_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item(key = "summary") { ImportSummary(import, batch) }
                if (import.conflicts > 0) item(key = "policy") { ImportPolicy(import, actions) }
                if (import.writeCount > 0) item(key = "storage") { ImportStorage(import) }
                if (batch) {
                    items(import.items, key = { "item:" + it.key }) { item -> ImportItemRow(item, actions) }
                    if (import.skipped.isNotEmpty()) item(key = "skipped") { ImportSkipped(import.skipped) }
                } else {
                    item(key = "notices") { SingleNotices(import) }
                }
                item(key = "compat") {
                    Text(
                        stringResource(R.string.wb_import_compat),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        stringResource(R.string.wb_import_requires_confirm),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = actions.onConfirmImport,
                enabled = import.canConfirm,
                modifier = Modifier.testTag(WorldBookTestTags.IMPORT_CONFIRM),
            ) {
                Text(if (batch) stringResource(R.string.wb_import_confirm_count, import.writeCount) else stringResource(R.string.wb_import))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelImport, modifier = Modifier.testTag(WorldBookTestTags.IMPORT_CANCEL)) {
                Text(stringResource(R.string.wb_cancel))
            }
        },
    )
}

@Composable
private fun ImportSummary(import: WorldBookImportUi, batch: Boolean) {
    Text(stringResource(R.string.wb_import_source, import.name), style = MaterialTheme.typography.bodyMedium)
    if (batch) {
        Text(
            stringResource(R.string.wb_import_batch_summary, import.items.size, import.writeCount, import.entryCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    } else {
        Text(
            stringResource(R.string.wb_import_entries, import.entryCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        import.items.singleOrNull()?.takeIf { it.action != WorldBookBatchAction.CREATE }?.let { item ->
            Text(
                importActionLabel(item),
                style = MaterialTheme.typography.bodyMedium,
                color = actionColor(item),
                modifier = Modifier.padding(top = 2.dp),
            )
            if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImportPolicy(import: WorldBookImportUi, actions: WorldBooksActions) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(stringResource(R.string.wb_import_conflicts, import.conflicts), style = MaterialTheme.typography.titleSmall)
        WorldBookConflictPolicy.entries.forEach { policy ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(import.policy == policy, role = Role.RadioButton) { actions.onImportPolicy(policy) }
                    .padding(vertical = 2.dp)
                    .testTag(WorldBookTestTags.importPolicy(policy)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = import.policy == policy, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(stringResource(policyLabel(policy)), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(policyHint(policy)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ImportStorage(import: WorldBookImportUi) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.wb_import_storage, worldBookMiB(import.bytesToWrite),
                worldBookMiB(import.storedBytes), worldBookMiB(import.maxStoredBytes)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (import.overStorage) {
            Text(stringResource(R.string.wb_import_over_storage), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (import.overBooks) {
            Text(stringResource(R.string.wb_import_over_books, import.maxBooks), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SingleNotices(import: WorldBookImportUi) {
    Column {
        Text(
            stringResource(R.string.wb_import_notices),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (import.hasNotices()) {
            import.notices.forEachIndexed { index, notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp).testTag(WorldBookTestTags.importNotice(index)),
                )
            }
        } else {
            Text(
                stringResource(R.string.wb_import_no_notices),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ImportItemRow(item: WorldBookImportItemUi, actions: WorldBooksActions) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).testTag(WorldBookTestTags.importItem(item.key))) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Checked means "will be written"; a selected row the policy skips reads as unchecked.
            Checkbox(
                checked = item.selected && item.selectable && item.action.writes,
                onCheckedChange = { actions.onToggleImportItem(item.key) },
                enabled = item.selectable,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.name.ifBlank { item.source },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(item.source, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val label = if (item.selectable && !item.selected) stringResource(R.string.wb_import_action_unselected)
                    else importActionLabel(item)
                Text(
                    if (item.entryCount > 0 || item.selectable) stringResource(R.string.wb_import_item_entries, item.entryCount) + " · " + label else label,
                    style = MaterialTheme.typography.bodySmall,
                    color = actionColor(item),
                )
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            }
        }
        if (item.notices.isNotEmpty()) {
            Text(
                if (expanded) stringResource(R.string.wb_collapse) else stringResource(R.string.wb_import_item_notices, item.notices.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 48.dp).clickable { expanded = !expanded },
            )
            if (expanded) {
                item.notices.forEach { notice ->
                    Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp, top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ImportSkipped(skipped: List<WorldBookImportSkipUi>) {
    var expanded by remember { mutableStateOf(skipped.size <= SKIPPED_PREVIEW) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag(WorldBookTestTags.IMPORT_SKIPPED)) {
        HorizontalDivider()
        Text(
            stringResource(R.string.wb_import_skipped, skipped.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 6.dp).clickable { expanded = !expanded },
        )
        if (expanded) {
            skipped.take(SKIPPED_LIMIT).forEach { skip ->
                Text(
                    skip.source + " · " + stringResource(skipLabel(skip.reason)),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (skipped.size > SKIPPED_LIMIT) {
                Text(stringResource(R.string.wb_import_skipped_more, skipped.size - SKIPPED_LIMIT), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(stringResource(R.string.wb_expand), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { expanded = true })
        }
    }
}

@Composable
private fun importActionLabel(item: WorldBookImportItemUi): String {
    val other = item.conflictWith.orEmpty()
    return when (item.action) {
        WorldBookBatchAction.CREATE -> stringResource(R.string.wb_import_action_create)
        WorldBookBatchAction.CREATE_RENAMED -> stringResource(R.string.wb_import_action_renamed, item.name)
        WorldBookBatchAction.REPLACE -> stringResource(R.string.wb_import_action_replace, other)
        WorldBookBatchAction.SKIP_EXISTING -> stringResource(R.string.wb_import_action_skip_existing, other)
        WorldBookBatchAction.SKIP_IDENTICAL -> stringResource(R.string.wb_import_action_identical, other)
        WorldBookBatchAction.SKIP_DUPLICATE_IN_BATCH -> stringResource(R.string.wb_import_action_duplicate)
        WorldBookBatchAction.AMBIGUOUS_TARGET -> stringResource(R.string.wb_import_action_ambiguous, other)
        WorldBookBatchAction.NOT_WORLD_BOOK -> stringResource(R.string.wb_import_action_not_book)
        WorldBookBatchAction.INVALID -> stringResource(R.string.wb_import_action_invalid)
        WorldBookBatchAction.TOO_LARGE -> stringResource(R.string.wb_import_action_too_large)
    }
}

@Composable
private fun actionColor(item: WorldBookImportItemUi) = when {
    item.action == WorldBookBatchAction.INVALID || item.action == WorldBookBatchAction.TOO_LARGE ||
        item.action == WorldBookBatchAction.AMBIGUOUS_TARGET -> MaterialTheme.colorScheme.error
    item.action.writes && item.selected -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun policyLabel(policy: WorldBookConflictPolicy) = when (policy) {
    WorldBookConflictPolicy.KEEP_BOTH -> R.string.wb_import_policy_keep
    WorldBookConflictPolicy.REPLACE -> R.string.wb_import_policy_replace
    WorldBookConflictPolicy.SKIP -> R.string.wb_import_policy_skip
}

private fun policyHint(policy: WorldBookConflictPolicy) = when (policy) {
    WorldBookConflictPolicy.KEEP_BOTH -> R.string.wb_import_policy_keep_hint
    WorldBookConflictPolicy.REPLACE -> R.string.wb_import_policy_replace_hint
    WorldBookConflictPolicy.SKIP -> R.string.wb_import_policy_skip_hint
}

private fun skipLabel(reason: WorldBookFileSkip) = when (reason) {
    WorldBookFileSkip.UNSUPPORTED_TYPE -> R.string.wb_import_skip_type
    WorldBookFileSkip.IMAGE -> R.string.wb_import_skip_image
    WorldBookFileSkip.NESTED_ARCHIVE -> R.string.wb_import_skip_nested
    WorldBookFileSkip.UNSUPPORTED_ARCHIVE -> R.string.wb_import_skip_archive
    WorldBookFileSkip.ENCRYPTED -> R.string.wb_import_skip_encrypted
    WorldBookFileSkip.UNSUPPORTED_COMPRESSION -> R.string.wb_import_skip_compression
    WorldBookFileSkip.LINK -> R.string.wb_import_skip_link
    WorldBookFileSkip.UNSAFE_PATH -> R.string.wb_import_skip_path
    WorldBookFileSkip.DUPLICATE_PATH -> R.string.wb_import_skip_duplicate_path
    WorldBookFileSkip.TOO_LARGE -> R.string.wb_import_skip_large
    WorldBookFileSkip.CORRUPT -> R.string.wb_import_skip_corrupt
    WorldBookFileSkip.UNREADABLE -> R.string.wb_import_skip_unreadable
}

private const val SKIPPED_PREVIEW = 5
private const val SKIPPED_LIMIT = 200
