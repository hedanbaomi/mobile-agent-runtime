// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.worldbooks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Renders the generic field form the host supplies. Every kind gets a real editable
 * control; a value is never shown as read-only JSON. Changes are reported through
 * [onChange] with the field key and the new text, so the host stays the only writer.
 */
@Composable
internal fun WorldBookFieldForm(
    fields: List<WorldBookFieldUi>,
    onChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (fields.isEmpty()) {
            Text(
                stringResource(R.string.wb_no_fields),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            fields.forEach { field -> WorldBookFieldRow(field, onChange) }
        }
    }
}

@Composable
private fun WorldBookFieldRow(field: WorldBookFieldUi, onChange: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        when (field.kind) {
            WorldBookFieldKind.TEXT -> OutlinedTextField(
                value = field.value,
                onValueChange = { onChange(field.key, it) },
                label = { Text(field.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(WorldBookTestTags.field(field.key)),
            )

            WorldBookFieldKind.MULTILINE -> OutlinedTextField(
                value = field.value,
                onValueChange = { onChange(field.key, it) },
                label = { Text(field.label) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag(WorldBookTestTags.field(field.key)),
            )

            WorldBookFieldKind.NUMBER -> OutlinedTextField(
                value = field.value,
                onValueChange = { candidate -> if (candidate.isNumericDraft()) onChange(field.key, candidate) },
                label = { Text(field.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(WorldBookTestTags.field(field.key)),
            )

            WorldBookFieldKind.BOOLEAN -> BooleanFieldRow(field, onChange)

            WorldBookFieldKind.CHOICE -> ChoiceFieldRow(field, onChange)
        }
        if (field.hint.isNotBlank()) {
            Text(
                field.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun BooleanFieldRow(field: WorldBookFieldUi, onChange: (String, String) -> Unit) {
    val enabled = field.booleanValue()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(field.label, style = MaterialTheme.typography.bodyLarge)
            Text(worldBookToggleLabel(enabled), style = MaterialTheme.typography.labelSmall)
        }
        Switch(
            checked = enabled,
            onCheckedChange = { onChange(field.key, WorldBookFieldValues.of(it)) },
            modifier = Modifier.testTag(WorldBookTestTags.field(field.key)),
        )
    }
}

@Composable
private fun ChoiceFieldRow(field: WorldBookFieldUi, onChange: (String, String) -> Unit) {
    if (field.options.isEmpty()) {
        OutlinedTextField(
            value = field.value,
            onValueChange = { onChange(field.key, it) },
            label = { Text(field.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(WorldBookTestTags.field(field.key)),
        )
        return
    }
    var expanded by remember(field.key) { mutableStateOf(false) }
    val current = field.options.firstOrNull { it.first == field.value }?.second ?: field.value
    Column(Modifier.fillMaxWidth()) {
        Text(field.label, style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().testTag(WorldBookTestTags.field(field.key)),
            ) {
                Text(
                    current.ifBlank { stringResource(R.string.wb_select) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                field.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.second) },
                        onClick = {
                            expanded = false
                            onChange(field.key, option.first)
                        },
                        modifier = Modifier.testTag(WorldBookTestTags.field(field.key) + "." + option.first),
                    )
                }
            }
        }
    }
}

/** Accepts a partially typed number so editing never fights the user's keystrokes. */
internal fun String.isNumericDraft(): Boolean =
    all { it.isDigit() || it == '-' || it == '.' || it == ',' }
