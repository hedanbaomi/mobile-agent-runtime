// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package runtime.mobileagent.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import runtime.mobileagent.TrpgAtlasBasemap
import runtime.mobileagent.domain.trpg.TrpgAtlas
import runtime.mobileagent.domain.trpg.TrpgAtlasCommand
import runtime.mobileagent.domain.trpg.TrpgAtlasOperation
import runtime.mobileagent.domain.trpg.TrpgAtlasReducer
import runtime.mobileagent.domain.trpg.TrpgAtlasValidation
import runtime.mobileagent.domain.trpg.TrpgEdge
import runtime.mobileagent.domain.trpg.TrpgLocation
import runtime.mobileagent.domain.trpg.TrpgSession
import runtime.mobileagent.domain.trpg.TrpgViewRole

/**
 * Atlas surface: a real scrollable map with clickable pins plus accessible place/link lists. The
 * host owns persistence: every accepted edit goes through [onCommand] with the revision the draft
 * was built from, and a rejected command keeps the draft and shows an explicit reload action.
 */
@Composable
internal fun TrpgAtlasDialog(
    session: TrpgSession,
    atlas: TrpgAtlas,
    writable: Boolean,
    saving: Boolean,
    error: String?,
    chinese: Boolean,
    onCommand: suspend (Long, TrpgAtlasCommand) -> TrpgSession,
    onDismiss: () -> Unit,
) {
    val conversationId = session.conversationId
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var viewAtlas by remember(conversationId) { mutableStateOf(atlas) }
    var atlasAtBase by remember(conversationId) { mutableStateOf(atlas) }
    var baseRevision by remember(conversationId) { mutableLongStateOf(session.revision) }
    var externalNewer by remember(conversationId) { mutableStateOf(false) }
    var busy by remember(conversationId) { mutableStateOf(false) }
    var tab by remember(conversationId) { mutableIntStateOf(0) }
    var localError by remember(conversationId) { mutableStateOf<String?>(null) }
    var selectedId by remember(conversationId) { mutableStateOf<String?>(null) }
    var placing by remember(conversationId) { mutableStateOf(false) }
    var editingLocation by remember(conversationId) { mutableStateOf<TrpgLocation?>(null) }
    var editingEdge by remember(conversationId) { mutableStateOf<TrpgEdge?>(null) }
    var editorRevision by remember(conversationId) { mutableLongStateOf(session.revision) }
    var basemapUri by remember(conversationId) { mutableStateOf(TrpgAtlasBasemap.loadUri(context, conversationId)) }
    var basemapImage by remember(conversationId) { mutableStateOf<ImageBitmap?>(null) }
    var basemapError by remember(conversationId) { mutableStateOf<String?>(null) }
    var basemapNotice by remember(conversationId) { mutableStateOf<String?>(null) }

    LaunchedEffect(session.revision, atlas, busy) {
        if (busy) return@LaunchedEffect
        val editorOpen = editingLocation != null || editingEdge != null
        val sync = reconcileAtlas(atlasAtBase, baseRevision, atlas, session.revision, editorOpen)
        if (sync.conflict) {
            externalNewer = true
        } else if (sync.atlas != atlasAtBase || sync.baseRevision != baseRevision) {
            viewAtlas = sync.atlas
            atlasAtBase = sync.atlas
            baseRevision = sync.baseRevision
            externalNewer = false
            selectedId = selectedId?.takeIf { id -> sync.atlas.locations.any { it.id == id } }
        }
    }

    LaunchedEffect(basemapUri) {
        val uri = basemapUri
        if (uri == null) {
            basemapImage = null
            basemapError = null
        } else {
            val image = withContext(Dispatchers.IO) {
                runCatching { TrpgAtlasBasemap.decode(context.contentResolver, Uri.parse(uri)) }.getOrNull()
            }
            basemapImage = image?.asImageBitmap()
            basemapError = if (image == null) {
                if (chinese) "底图无法读取（可能已失效）；请重新选择，标记不受影响。" else
                    "The base map cannot be read (it may be stale); pick it again. Pins are unaffected."
            } else null
        }
    }

    val basemapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val persisted = TrpgAtlasBasemap.takeReadPermission(context, uri)
            basemapUri = uri.toString()
            TrpgAtlasBasemap.saveUri(context, conversationId, uri.toString())
            basemapNotice = if (persisted) null else if (chinese) {
                "系统未授予长期读取权限：本次仍可预览，重新打开后可能需要重新选择。"
            } else {
                "Persistent read access was not granted: the preview still works now, but you may need to pick it again later."
            }
        }
    }

    val blocked = busy || saving
    val editable = writable && !blocked
    val gameMasterView = session.state.campaign.preferences.viewRole == TrpgViewRole.GAME_MASTER
    val visibleAtlas = atlasForRole(viewAtlas, gameMasterView)

    fun reloadLatest() {
        if (session.revision >= baseRevision) {
            viewAtlas = atlas
            atlasAtBase = atlas
            baseRevision = session.revision
            externalNewer = false
            localError = null
            selectedId = null
        } else {
            localError = if (chinese) "宿主尚未提供更新的地图，请稍后重试。" else
                "The host has not published a newer atlas yet; retry shortly."
        }
        // An explicit reload discards editor drafts on purpose.
        editingLocation = null
        editingEdge = null
        placing = false
    }

    suspend fun submit(expectedRevision: Long, command: TrpgAtlasCommand): Boolean {
        if (!editable) return false
        if (externalNewer || session.revision > baseRevision) {
            externalNewer = true
            localError = atlasConflict(chinese)
            return false
        }
        if (expectedRevision != baseRevision) {
            externalNewer = true
            localError = atlasConflict(chinese)
            return false
        }
        val next = try {
            TrpgAtlasReducer.apply(viewAtlas, command)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: atlasFailure(chinese)
            return false
        }
        busy = true
        return try {
            val returned = onCommand(expectedRevision, command)
            viewAtlas = next
            atlasAtBase = next
            baseRevision = returned.revision
            externalNewer = false
            localError = null
            selectedId = selectedId?.takeIf { id -> next.locations.any { it.id == id } }
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            localError = failure.message ?: atlasFailure(chinese)
            false
        } finally {
            busy = false
        }
    }

    fun openLocation(location: TrpgLocation) {
        editorRevision = baseRevision
        editingLocation = location
        placing = false
    }

    AlertDialog(
        onDismissRequest = { if (!blocked) onDismiss() },
        modifier = Modifier.testTag("trpg.atlas.dialog"),
        title = { Text(if (chinese) "地点关系与地图" else "Atlas & map", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                val problem = error ?: localError
                if (problem != null) {
                    Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp).testTag("trpg.atlas.error"))
                }
                if (externalNewer) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (chinese) "地图已有更新 #${session.revision}；草稿未覆盖。"
                                else "The atlas changed to #${session.revision}; your draft is kept.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { reloadLatest() }, enabled = !blocked,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.reload")) {
                                Text(if (chinese) "载入最新" else "Reload")
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AtlasTab(0, tab, chinese, "地图", "Map") { tab = it }
                    AtlasTab(1, tab, chinese, "地点与连线", "Places & links") { tab = it }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (tab == 0) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (chinese) "地点" else "Places") +
                                    " ${visibleAtlas.locations.size}/${TrpgAtlasValidation.MAX_LOCATIONS} · " +
                                    (if (chinese) "连线" else "Links") + " ${visibleAtlas.edges.size}/${TrpgAtlasValidation.MAX_EDGES}",
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { placing = !placing }, enabled = editable,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.place")) {
                                Text(if (placing) (if (chinese) "取消放置" else "Cancel")
                                else (if (chinese) "放置标记" else "Place pin"))
                            }
                            TextButton(onClick = { openLocation(TrpgLocation(UUID.randomUUID().toString(), "", "", 500, 500)) },
                                enabled = editable,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.location.create")) {
                                Text(if (chinese) "新地点" else "New")
                            }
                        }
                        if (placing) {
                            Text(if (chinese) "点按地图空白处放置标记，坐标会预先填入编辑器。"
                                else "Tap an empty spot on the map to place a pin; the coordinates prefill the editor.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("trpg.atlas.place.hint"))
                        }
                        TrpgAtlasMap(
                            atlas = visibleAtlas,
                            basemap = basemapImage,
                            selectedId = selectedId,
                            onSelect = { id -> selectedId = id; placing = false },
                            onTap = { x, y ->
                                if (editable && placing) openLocation(TrpgLocation(UUID.randomUUID().toString(), "", "", x, y))
                            },
                        )
                        AtlasSelectionCard(
                            atlas = visibleAtlas,
                            selectedId = selectedId,
                            writable = editable,
                            chinese = chinese,
                            onSetCurrent = { location ->
                                scope.launch { submit(baseRevision, TrpgAtlasCommand(
                                    TrpgAtlasOperation.SET_CURRENT, locationId = location.id)) }
                            },
                            onEdit = { location -> openLocation(location) },
                            onRemove = { location ->
                                scope.launch { submit(baseRevision, TrpgAtlasCommand(
                                    TrpgAtlasOperation.REMOVE_LOCATION, id = location.id)) }
                            },
                            onClear = { selectedId = null },
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { basemapPicker.launch(arrayOf("image/*")) }, enabled = editable,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.basemap.pick")) {
                                Text(if (chinese) "选择本机底图" else "Pick local base map")
                            }
                            if (basemapUri != null) {
                                TextButton(onClick = {
                                    basemapUri = null
                                    basemapNotice = null
                                    TrpgAtlasBasemap.saveUri(context, conversationId, null)
                                }, enabled = editable,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.basemap.clear")) {
                                    Text(if (chinese) "移除底图" else "Remove")
                                }
                            }
                        }
                        Text(if (chinese) "本机底图不随备份转移，地图标记会备份。底图只在本机读取，不会自动发给模型。"
                            else "The local base map does not travel with backups; map pins do. It is read on this device only and never sent to the model automatically.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        basemapNotice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("trpg.atlas.basemap.notice"))
                        }
                        basemapError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("trpg.atlas.basemap.error"))
                        }
                    } else {
                        AtlasListTab(
                            atlas = visibleAtlas,
                            writable = editable,
                            chinese = chinese,
                            onCreateLocation = { openLocation(TrpgLocation(UUID.randomUUID().toString(), "", "", 500, 500)) },
                            onCreateEdge = {
                                editorRevision = baseRevision
                                editingEdge = TrpgEdge(UUID.randomUUID().toString(), "", "")
                            },
                            onEditLocation = { location -> openLocation(location) },
                            onRemoveLocation = { location ->
                                scope.launch { submit(baseRevision, TrpgAtlasCommand(
                                    TrpgAtlasOperation.REMOVE_LOCATION, id = location.id)) }
                            },
                            onSetCurrent = { location ->
                                scope.launch { submit(baseRevision, TrpgAtlasCommand(
                                    TrpgAtlasOperation.SET_CURRENT, locationId = location.id)) }
                            },
                            onEditEdge = { edge -> editorRevision = baseRevision; editingEdge = edge },
                            onRemoveEdge = { edge ->
                                scope.launch { submit(baseRevision, TrpgAtlasCommand(
                                    TrpgAtlasOperation.REMOVE_EDGE, id = edge.id)) }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, enabled = !blocked,
                modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.close")) {
                Text(if (chinese) "关闭" else "Close")
            }
        },
    )

    editingLocation?.let { initial ->
        AtlasLocationEditor(
            initial = initial, chinese = chinese, enabled = editable,
            expectedRevision = editorRevision, acceptedRevision = baseRevision, externalNewer = externalNewer,
            onReloadLatest = { reloadLatest() },
            onDismiss = { editingLocation = null },
            onSubmit = { command -> submit(editorRevision, command) },
        )
    }
    editingEdge?.let { initial ->
        AtlasEdgeEditor(
            initial = initial, locations = visibleAtlas.locations, chinese = chinese, enabled = editable,
            expectedRevision = editorRevision, acceptedRevision = baseRevision, externalNewer = externalNewer,
            onReloadLatest = { reloadLatest() },
            onDismiss = { editingEdge = null },
            onSubmit = { command -> submit(editorRevision, command) },
        )
    }
}

@Composable
private fun AtlasTab(index: Int, selected: Int, chinese: Boolean, zh: String, en: String, onSelect: (Int) -> Unit) {
    FilterChip(
        selected = selected == index,
        onClick = { onSelect(index) },
        label = { Text(if (chinese) zh else en) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("trpg.atlas.tab.$index"),
    )
}
