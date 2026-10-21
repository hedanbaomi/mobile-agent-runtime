// SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
// SPDX-License-Identifier: AGPL-3.0-only

package runtime.mobileagent.ui

import kotlin.math.roundToInt
import runtime.mobileagent.domain.trpg.TrpgAtlas
import runtime.mobileagent.domain.trpg.TrpgAtlasProjection
import runtime.mobileagent.domain.trpg.TrpgAtlasValidation
import runtime.mobileagent.domain.trpg.TrpgVisibility

/** A plain geometry pair so the coordinate helpers stay free of Compose/Android types. */
internal data class AtlasPoint(val x: Float, val y: Float)

/** Player view is a filtered projection; the GM view is the full authoritative atlas. */
internal fun atlasForRole(atlas: TrpgAtlas, gameMasterView: Boolean): TrpgAtlas =
    if (gameMasterView) atlas else TrpgAtlasProjection.player(atlas)

/** Map units always live in `0..1000`; out-of-range values are display-safe, not user input. */
internal fun atlasFraction(unit: Int): Float =
    unit.coerceIn(0, TrpgAtlasValidation.MAP_UNITS).toFloat() / TrpgAtlasValidation.MAP_UNITS

/**
 * Inverse mapping for a tap on the map surface. Returns map units in `0..1000`; taps outside the
 * surface are clamped, and an unmeasured surface yields null instead of NaN coordinates.
 */
internal fun atlasUnitAt(tapX: Float, tapY: Float, width: Float, height: Float): Pair<Int, Int>? {
    if (width <= 0f || height <= 0f) return null
    fun unit(value: Float, span: Float): Int =
        (value / span * TrpgAtlasValidation.MAP_UNITS).roundToInt().coerceIn(0, TrpgAtlasValidation.MAP_UNITS)
    return unit(tapX, width) to unit(tapY, height)
}

/** Top-left of a pin box that stays fully inside the map surface, including at the edges. */
internal fun atlasPinTopLeft(unitX: Int, unitY: Int, width: Float, height: Float, pin: Float): AtlasPoint {
    fun topLeft(unit: Int, span: Float): Float {
        val center = atlasFraction(unit) * span
        return (center - pin / 2f).coerceIn(0f, (span - pin).coerceAtLeast(0f))
    }
    return AtlasPoint(topLeft(unitX, width), topLeft(unitY, height))
}

/** Center of a pin in pixels, used by the canvas to draw edges and the current-marker ring. */
internal fun atlasPinCenter(unitX: Int, unitY: Int, width: Float, height: Float): AtlasPoint =
    AtlasPoint(atlasFraction(unitX) * width, atlasFraction(unitY) * height)

internal fun atlasVisibilityLabel(visibility: TrpgVisibility, chinese: Boolean): String =
    if (visibility == TrpgVisibility.GAME_MASTER) (if (chinese) "主持" else "GM")
    else (if (chinese) "玩家" else "Player")
