// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import runtime.mobileagent.domain.trpg.TrpgAtlasCommand
import runtime.mobileagent.domain.trpg.TrpgAtlasOperation
import runtime.mobileagent.domain.trpg.TrpgAtlasValidation
import runtime.mobileagent.domain.trpg.TrpgEdge
import runtime.mobileagent.domain.trpg.TrpgLocation

/**
 * Location editor. All values stay raw strings while editing (empty or a leading minus are legal
 * drafts); validation runs only on submit and never clamps the typed coordinates.
 */
@Composable
internal fun AtlasLocationEditor(
    initial: TrpgLocation,
    chinese: Boolean,
    enabled: Boolean,
    expectedRevision: Long,
    acceptedRevision: Long,
    externalNewer: Boolean,
    onReloadLatest: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: suspend (TrpgAtlasCommand) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id) { mutableStateOf(false) }
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var description by remember(initial.id) { mutableStateOf(initial.description) }
    var x by remember(initial.id) { mutableStateOf(initial.x.toString()) }
    var y by remember(initial.id) { mutableStateOf(initial.y.toString()) }
    var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
    var localError by remember(initial.id) { mutableStateOf<String?>(null) }
    val conflict = externalNewer || atlasEditorSubmitRevision(expectedRevision, acceptedRevision) == null

    TrpgEditorFrame(
        tag = "trpg.atlas.location.editor",
        title = if (initial.title.isBlank()) (if (chinese) "新建地点" else "New location")
        else (if (chinese) "编辑地点" else "Edit location"),
        chinese = chinese, enabled = enabled, submitting = submitting, conflict = conflict,
        localError = localError, expectedRevision = expectedRevision, acceptedRevision = acceptedRevision,
        onReloadLatest = onReloadLatest, onDismiss = onDismiss, confirmLabel = if (chinese) "保存" else "Save",
        onConfirm = {
            val draft = AtlasLocationDraft(initial.id, title, description, x, y, visibility)
            val problem = if (conflict) atlasConflict(chinese) else validateAtlasLocationDraft(draft, chinese)
            if (problem != null) {
                localError = problem
            } else {
                val location = checkNotNull(buildAtlasLocation(draft))
                scope.launch {
                    submitting = true
                    try {
                        if (!onSubmit(TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_LOCATION, location = location))) {
                            // The rejection is shown in this editor, not only behind it in the dialog.
                            localError = atlasFailure(chinese)
                        } else {
                            onDismiss()
                        }
                    } finally { submitting = false }
                }
            }
        },
    ) {
        OutlinedTextField(title, { title = it.take(TrpgAtlasValidation.MAX_TITLE) },
            enabled = enabled && !submitting, singleLine = true,
            label = { Text(if (chinese) "地点标题 (≤128)" else "Location title (≤128)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.atlas.location.editor.title"))
        OutlinedTextField(description, { description = it.take(TrpgAtlasValidation.MAX_DESCRIPTION) },
            enabled = enabled && !submitting, minLines = 3, maxLines = 6,
            label = { Text(if (chinese) "说明 (≤2048)" else "Description (≤2048)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.atlas.location.editor.description"))
        OutlinedTextField(x, { x = it }, enabled = enabled && !submitting, singleLine = true,
            label = { Text(if (chinese) "X（0..1000）" else "X (0..1000)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.atlas.location.editor.x"))
        OutlinedTextField(y, { y = it }, enabled = enabled && !submitting, singleLine = true,
            label = { Text(if (chinese) "Y（0..1000）" else "Y (0..1000)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.atlas.location.editor.y"))
        Text(if (chinese) "坐标允许空或负号继续编辑；提交时才校验，不自动截断。也可在地图上点选放置。"
            else "Empty or negative coordinates stay editable; validation happens on submit without clamping. You can also tap the map to place the pin.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TrpgVisibilityChips(visibility, enabled && !submitting, chinese, tagPrefix = "trpg.atlas.visibility") { visibility = it }
    }
}

/** Edge editor: two explicit endpoint selectors over the currently visible locations. */
@Composable
internal fun AtlasEdgeEditor(
    initial: TrpgEdge,
    locations: List<TrpgLocation>,
    chinese: Boolean,
    enabled: Boolean,
    expectedRevision: Long,
    acceptedRevision: Long,
    externalNewer: Boolean,
    onReloadLatest: () -> Unit,
    onDismiss: () -> Unit,
    onSubmit: suspend (TrpgAtlasCommand) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var submitting by remember(initial.id) { mutableStateOf(false) }
    var fromId by remember(initial.id) { mutableStateOf(initial.fromId) }
    var toId by remember(initial.id) { mutableStateOf(initial.toId) }
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var visibility by remember(initial.id) { mutableStateOf(initial.visibility) }
    var localError by remember(initial.id) { mutableStateOf<String?>(null) }
    val conflict = externalNewer || atlasEditorSubmitRevision(expectedRevision, acceptedRevision) == null
    val ids = locations.mapTo(mutableSetOf()) { it.id }

    TrpgEditorFrame(
        tag = "trpg.atlas.edge.editor",
        title = if (initial.fromId.isBlank()) (if (chinese) "新建连线" else "New link")
        else (if (chinese) "编辑连线" else "Edit link"),
        chinese = chinese, enabled = enabled, submitting = submitting, conflict = conflict,
        localError = localError, expectedRevision = expectedRevision, acceptedRevision = acceptedRevision,
        onReloadLatest = onReloadLatest, onDismiss = onDismiss, confirmLabel = if (chinese) "保存" else "Save",
        onConfirm = {
            val draft = AtlasEdgeDraft(initial.id, fromId, toId, label, visibility)
            val problem = if (conflict) atlasConflict(chinese) else validateAtlasEdgeDraft(draft, ids, chinese)
            if (problem != null) {
                localError = problem
            } else {
                val edge = checkNotNull(buildAtlasEdge(draft, ids))
                scope.launch {
                    submitting = true
                    try {
                        if (!onSubmit(TrpgAtlasCommand(TrpgAtlasOperation.UPSERT_EDGE, edge = edge))) {
                            // The rejection is shown in this editor, not only behind it in the dialog.
                            localError = atlasFailure(chinese)
                        } else {
                            onDismiss()
                        }
                    } finally { submitting = false }
                }
            }
        },
    ) {
        Text(if (chinese) "起点" else "From", style = MaterialTheme.typography.labelMedium)
        AtlasEndpointChips(locations, fromId, enabled && !submitting, "trpg.atlas.edge.editor.from") { fromId = it }
        Text(if (chinese) "终点" else "To", style = MaterialTheme.typography.labelMedium)
        AtlasEndpointChips(locations, toId, enabled && !submitting, "trpg.atlas.edge.editor.to") { toId = it }
        OutlinedTextField(label, { label = it.take(TrpgAtlasValidation.MAX_LABEL) },
            enabled = enabled && !submitting, singleLine = true,
            label = { Text(if (chinese) "标签 (≤128)" else "Label (≤128)") },
            modifier = Modifier.fillMaxWidth().testTag("trpg.atlas.edge.editor.label"))
        TrpgVisibilityChips(visibility, enabled && !submitting, chinese, tagPrefix = "trpg.atlas.visibility") { visibility = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AtlasEndpointChips(
    locations: List<TrpgLocation>,
    selected: String,
    enabled: Boolean,
    tagPrefix: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        locations.forEach { location ->
            FilterChip(
                selected = selected == location.id,
                onClick = { onSelect(location.id) },
                enabled = enabled,
                label = { Text(location.title, maxLines = 1) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix.${location.id}"),
            )
        }
    }
}
