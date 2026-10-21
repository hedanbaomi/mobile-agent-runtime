// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import runtime.mobileagent.domain.trpg.TrpgAtlas
import runtime.mobileagent.domain.trpg.TrpgAtlasValidation
import runtime.mobileagent.domain.trpg.TrpgEdge
import runtime.mobileagent.domain.trpg.TrpgLocation

/* --------------------------------- selection -------------------------------- */

/** Details of the pin selected on the map, including the explicit "set current" action. */
@Composable
internal fun AtlasSelectionCard(
    atlas: TrpgAtlas,
    selectedId: String?,
    writable: Boolean,
    chinese: Boolean,
    onSetCurrent: (TrpgLocation) -> Unit,
    onEdit: (TrpgLocation) -> Unit,
    onRemove: (TrpgLocation) -> Unit,
    onClear: () -> Unit,
) {
    val location = atlas.locations.firstOrNull { it.id == selectedId } ?: return
    val current = location.id == atlas.currentLocationId
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("trpg.atlas.selection"),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(location.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (current) Text(if (chinese) "当前位置" else "Current",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("x=${location.x} · y=${location.y} · ${atlasVisibilityLabel(location.visibility, chinese)}",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            if (location.description.isNotBlank()) {
                Text(location.description, style = MaterialTheme.typography.bodySmall,
                    maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!current) {
                    TextButton(onClick = { onSetCurrent(location) }, enabled = writable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.selection.setcurrent")) {
                        Text(if (chinese) "设为当前位置" else "Set current")
                    }
                }
                TextButton(onClick = { onEdit(location) }, enabled = writable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.selection.edit")) {
                    Text(if (chinese) "编辑" else "Edit")
                }
                TextButton(onClick = { onRemove(location) }, enabled = writable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.selection.remove")) {
                    Text(if (chinese) "删除" else "Delete")
                }
                TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.selection.clear")) {
                    Text(if (chinese) "取消选择" else "Clear")
                }
            }
        }
    }
}

/* ----------------------------------- lists ---------------------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AtlasListTab(
    atlas: TrpgAtlas,
    writable: Boolean,
    chinese: Boolean,
    onCreateLocation: () -> Unit,
    onCreateEdge: () -> Unit,
    onEditLocation: (TrpgLocation) -> Unit,
    onRemoveLocation: (TrpgLocation) -> Unit,
    onSetCurrent: (TrpgLocation) -> Unit,
    onEditEdge: (TrpgEdge) -> Unit,
    onRemoveEdge: (TrpgEdge) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (chinese) "地点" else "Locations") + " (${atlas.locations.size}/${TrpgAtlasValidation.MAX_LOCATIONS})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCreateLocation, enabled = writable,
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.location.create")) {
            Text(if (chinese) "添加地点" else "Add location")
        }
    }
    if (atlas.locations.isEmpty()) {
        Text(if (chinese) "还没有地点；先添加一个地点，再在地图上放置标记。"
            else "No locations yet; add one, then place its pin on the map.",
            style = MaterialTheme.typography.bodySmall)
    }
    atlas.locations.forEach { location ->
        AtlasLocationRow(atlas, location, writable, chinese, onEditLocation, onRemoveLocation, onSetCurrent)
    }

    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (chinese) "连线" else "Links") + " (${atlas.edges.size}/${TrpgAtlasValidation.MAX_EDGES})",
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCreateEdge, enabled = writable && atlas.locations.size >= 2,
            modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.edge.create")) {
            Text(if (chinese) "添加连线" else "Add link")
        }
    }
    if (atlas.locations.size < 2) {
        Text(if (chinese) "至少需要两个地点才能建立连线。" else "Two locations are required before adding a link.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (atlas.edges.isEmpty()) {
        Text(if (chinese) "暂无连线。" else "No links yet.", style = MaterialTheme.typography.bodySmall)
    }
    atlas.edges.forEach { edge ->
        AtlasEdgeRow(atlas, edge, writable, chinese, onEditEdge, onRemoveEdge)
    }
    Text(if (chinese) "地图标记会随备份转移；本机底图不会。" else
        "Map pins travel with backups; a local base map does not.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AtlasLocationRow(
    atlas: TrpgAtlas,
    location: TrpgLocation,
    writable: Boolean,
    chinese: Boolean,
    onEdit: (TrpgLocation) -> Unit,
    onRemove: (TrpgLocation) -> Unit,
    onSetCurrent: (TrpgLocation) -> Unit,
) {
    val current = location.id == atlas.currentLocationId
    AtlasCard(tag = "trpg.atlas.location.row.${location.id}") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(location.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (current) Text(if (chinese) "当前位置" else "Current",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text("x=${location.x} · y=${location.y} · ${atlasVisibilityLabel(location.visibility, chinese)}",
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        if (location.description.isNotBlank()) {
            Text(location.description, style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!current) {
                TextButton(onClick = { onSetCurrent(location) }, enabled = writable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.location.setcurrent.${location.id}")) {
                    Text(if (chinese) "设为当前位置" else "Set current")
                }
            }
            TextButton(onClick = { onEdit(location) }, enabled = writable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.location.edit.${location.id}")) {
                Text(if (chinese) "编辑" else "Edit")
            }
            TextButton(onClick = { onRemove(location) }, enabled = writable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.location.remove.${location.id}")) {
                Text(if (chinese) "删除" else "Delete")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AtlasEdgeRow(
    atlas: TrpgAtlas,
    edge: TrpgEdge,
    writable: Boolean,
    chinese: Boolean,
    onEdit: (TrpgEdge) -> Unit,
    onRemove: (TrpgEdge) -> Unit,
) {
    val from = atlas.locations.firstOrNull { it.id == edge.fromId }?.title ?: edge.fromId
    val to = atlas.locations.firstOrNull { it.id == edge.toId }?.title ?: edge.toId
    AtlasCard(tag = "trpg.atlas.edge.row.${edge.id}") {
        Text("$from → $to", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (edge.label.isNotBlank()) Text(edge.label, style = MaterialTheme.typography.bodySmall)
        Text(atlasVisibilityLabel(edge.visibility, chinese),
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onEdit(edge) }, enabled = writable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.edge.edit.${edge.id}")) {
                Text(if (chinese) "编辑" else "Edit")
            }
            TextButton(onClick = { onRemove(edge) }, enabled = writable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.edge.remove.${edge.id}")) {
                Text(if (chinese) "删除" else "Delete")
            }
        }
    }
}

@Composable
private fun AtlasCard(tag: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}
