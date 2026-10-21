// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import runtime.mobileagent.domain.trpg.TrpgAtlas

/**
 * Relationship graph and pin map over the fixed `0..1000` atlas space. Pins are real composables
 * (stable [testTag]s, 48dp touch targets) laid over the canvas, so selecting a pin is accessible and
 * testable; the canvas itself draws the optional base map, the grid and every edge.
 */
@Composable
internal fun TrpgAtlasMap(
    atlas: TrpgAtlas,
    basemap: ImageBitmap?,
    selectedId: String?,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onTap: (Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val pin = 48.dp
    val pinPx = with(density) { pin.toPx() }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val edgeColor = MaterialTheme.colorScheme.outline
    val markerColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(260.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("trpg.atlas.map"),
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        Canvas(
            Modifier.fillMaxSize().pointerInput(widthPx, heightPx) {
                detectTapGestures { offset ->
                    atlasUnitAt(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                        ?.let { (x, y) -> onTap(x, y) }
                }
            },
        ) {
            if (basemap != null) {
                val scale = min(size.width / basemap.width, size.height / basemap.height)
                val width = basemap.width * scale
                val height = basemap.height * scale
                drawImage(
                    image = basemap,
                    dstOffset = IntOffset(
                        ((size.width - width) / 2f).roundToInt(),
                        ((size.height - height) / 2f).roundToInt(),
                    ),
                    dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                )
            } else {
                repeat(11) { index ->
                    val x = size.width * index / 10f
                    val y = size.height * index / 10f
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
            }
            atlas.edges.forEach { edge ->
                val from = atlas.locations.firstOrNull { it.id == edge.fromId }
                val to = atlas.locations.firstOrNull { it.id == edge.toId }
                if (from != null && to != null) {
                    val start = atlasPinCenter(from.x, from.y, size.width, size.height)
                    val end = atlasPinCenter(to.x, to.y, size.width, size.height)
                    drawLine(edgeColor, Offset(start.x, start.y), Offset(end.x, end.y),
                        strokeWidth = 2f, cap = StrokeCap.Round)
                }
            }
            atlas.currentLocationId?.let { current ->
                atlas.locations.firstOrNull { it.id == current }?.let { location ->
                    val center = atlasPinCenter(location.x, location.y, size.width, size.height)
                    drawCircle(markerColor, radius = pinPx / 2f - 2f,
                        center = Offset(center.x, center.y), style = Stroke(width = 3f))
                }
            }
        }
        atlas.locations.forEach { location ->
            val selected = location.id == selectedId
            val current = location.id == atlas.currentLocationId
            val topLeft = atlasPinTopLeft(location.x, location.y, widthPx, heightPx, pinPx)
            Box(
                modifier = Modifier
                    .offset(x = (topLeft.x / density.density).dp, y = (topLeft.y / density.density).dp)
                    .size(pin)
                    .clip(CircleShape)
                    .clickable { onSelect(location.id) }
                    .semantics { contentDescription = location.title }
                    .testTag("trpg.atlas.pin.${location.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        current -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    border = BorderStroke(if (selected || current) 3.dp else 1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            location.title.take(1),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(1.dp),
                        )
                    }
                }
            }
        }
    }
}
