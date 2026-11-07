// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.trpg.TrpgVisibility

/* Shared draft-editor shell for the story and atlas editors. */

/**
 * Baseline conflict banner, explicit reload, field slot, validation error and the
 * save/cancel actions. [subTagPrefix] preserves each surface's existing
 * conflict/reload/error tags; the dialog and apply tag use [tag].
 */
@Composable
internal fun TrpgEditorFrame(
    tag: String,
    title: String,
    chinese: Boolean,
    enabled: Boolean,
    submitting: Boolean,
    conflict: Boolean,
    localError: String?,
    expectedRevision: Long,
    acceptedRevision: Long,
    onReloadLatest: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String,
    subTagPrefix: String = tag,
    fields: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (enabled && !submitting) onDismiss() },
        modifier = Modifier.testTag(tag),
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (conflict) {
                    Text(if (chinese) "基线 #$expectedRevision 已过期（最新 #$acceptedRevision）。草稿保留；请显式载入最新。"
                        else "Baseline #$expectedRevision is stale (latest #$acceptedRevision). The draft is kept; reload explicitly.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("$subTagPrefix.conflict"))
                    TextButton(onClick = onReloadLatest, enabled = enabled && !submitting,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("$subTagPrefix.reload")) {
                        Text(if (chinese) "载入最新（丢弃草稿）" else "Reload latest (discard draft)")
                    }
                }
                fields()
                localError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("$subTagPrefix.error"))
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = enabled && !submitting,
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tag.apply")) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled && !submitting,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (chinese) "取消" else "Cancel")
            }
        },
    )
}

/**
 * Player/GM visibility selector. [tagPrefix] null keeps the story editors' untagged
 * chips; the atlas editors pass their prefix so `player`/`gm` tags stay unchanged.
 */
@Composable
internal fun TrpgVisibilityChips(
    value: TrpgVisibility,
    enabled: Boolean,
    chinese: Boolean,
    tagPrefix: String? = null,
    onChange: (TrpgVisibility) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = value == TrpgVisibility.PLAYER, onClick = { onChange(TrpgVisibility.PLAYER) },
            enabled = enabled, label = { Text(if (chinese) "玩家可见" else "Player visible") },
            modifier = Modifier.heightIn(min = 48.dp).taggedChip(tagPrefix, "player"))
        FilterChip(selected = value == TrpgVisibility.GAME_MASTER, onClick = { onChange(TrpgVisibility.GAME_MASTER) },
            enabled = enabled, label = { Text(if (chinese) "主持秘密" else "GM secret") },
            modifier = Modifier.heightIn(min = 48.dp).taggedChip(tagPrefix, "gm"))
    }
}

private fun Modifier.taggedChip(prefix: String?, suffix: String): Modifier =
    if (prefix == null) this else testTag("$prefix.$suffix")
