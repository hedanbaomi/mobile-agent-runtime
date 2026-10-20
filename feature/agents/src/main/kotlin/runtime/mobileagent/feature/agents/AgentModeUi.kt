// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.feature.agents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.AgentMode

@Composable
internal fun AgentCardModeBadge(id: String, mode: AgentMode) {
    if (mode != AgentMode.TRPG) return
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(end = 6.dp).testTag("agents.card.mode.$id"),
    ) {
        Text(
            text = stringResource(R.string.trpg_agent_mode_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun AgentSummaryModeSection(editor: AgentEditorUi) {
    val label = agentModeLabel(editor.mode)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("agents.summary.mode"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = if (editor.mode == AgentMode.TRPG) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (editor.mode == AgentMode.TRPG) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                text = stringResource(R.string.trpg_agent_summary_mode, label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    if (editor.mode == AgentMode.TRPG) {
        Text(
            text = stringResource(R.string.trpg_agent_mode_trpg_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun AgentEditorModeSection(editor: AgentEditorUi, actions: AgentsActions) {
    val editable = editor.id == null
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (editor.mode == AgentMode.TRPG) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (editor.mode == AgentMode.TRPG) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth().testTag("agents.editor.mode"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.trpg_agent_mode_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = editor.mode == AgentMode.NORMAL,
                    onClick = { if (editable) actions.onEditorChange(editor.copy(mode = AgentMode.NORMAL)) },
                    enabled = editable,
                    label = { Text(stringResource(R.string.trpg_agent_mode_normal)) },
                    modifier = Modifier.testTag("agents.editor.mode.normal"),
                )
                FilterChip(
                    selected = editor.mode == AgentMode.TRPG,
                    onClick = { if (editable) actions.onEditorChange(editor.copy(mode = AgentMode.TRPG)) },
                    enabled = editable,
                    label = { Text(stringResource(R.string.trpg_agent_mode_trpg)) },
                    modifier = Modifier.testTag("agents.editor.mode.trpg"),
                )
            }
            Text(
                text = if (editor.mode == AgentMode.TRPG) {
                    stringResource(R.string.trpg_agent_mode_trpg_desc)
                } else {
                    stringResource(R.string.trpg_agent_mode_normal_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!editable) {
                Text(
                    text = stringResource(R.string.trpg_agent_mode_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("agents.editor.mode.readonly"),
                )
            }
        }
    }
}

@Composable
internal fun CharacterCardImportModeSelector(
    card: CharacterCardImportUi,
    busy: Boolean,
    actions: AgentsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.trpg_agent_mode_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = card.mode == AgentMode.NORMAL,
                onClick = { actions.onCharacterImportChange(card.copy(mode = AgentMode.NORMAL)) },
                enabled = !busy,
                label = { Text(stringResource(R.string.trpg_agent_mode_normal)) },
                modifier = Modifier.testTag("agents.character_import.mode.normal"),
            )
            FilterChip(
                selected = card.mode == AgentMode.TRPG,
                onClick = { actions.onCharacterImportChange(card.copy(mode = AgentMode.TRPG)) },
                enabled = !busy,
                label = { Text(stringResource(R.string.trpg_agent_mode_trpg)) },
                modifier = Modifier.testTag("agents.character_import.mode.trpg"),
            )
        }
    }
}

@Composable
private fun agentModeLabel(mode: AgentMode): String = when (mode) {
    AgentMode.NORMAL -> stringResource(R.string.trpg_agent_mode_normal)
    AgentMode.TRPG -> stringResource(R.string.trpg_agent_mode_trpg)
}
